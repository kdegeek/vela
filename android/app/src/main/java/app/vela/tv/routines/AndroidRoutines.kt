package app.vela.tv.routines

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/** The engine serializes access. Do not share this file with another engine or Android process. */
class AtomicRoutineStorage(context: Context) : RoutineStorage {
    private val file = AtomicFile(File(context.noBackupFilesDir, "routines-v1.json"))

    override fun read(): RoutineSnapshot {
        val input = try { file.openRead() } catch (e: FileNotFoundException) {
            // A truly absent file is empty; unreadable/corrupt existing data must fail closed.
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw e
            return RoutineSnapshot()
        }
        return input.use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(output.size() + count <= RoutineDocuments.MAX_DOCUMENT_BYTES) { "Routine storage is too large." }
                output.write(buffer, 0, count)
            }
            RoutineDocuments.decode(output.toString(Charsets.UTF_8.name()))
        }
    }

    override fun write(snapshot: RoutineSnapshot) {
        val bytes = RoutineDocuments.encode(snapshot).toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }
}

/**
 * Native durable polling, not an exact alarm. Persisted periodic jobs survive ordinary process death
 * and reboot, subject to Android's background quotas, Doze, force-stop, and OEM restrictions.
 */
class AndroidRoutineScheduler(context: Context) {
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val component = ComponentName(context, RoutineJobService::class.java)

    /** Call at app initialization BEFORE accepting routine writes. Never resubmit on every tick. */
    fun ensureScheduled() {
        val existing = scheduler.getPendingJob(JOB_ID)
        if (existing != null) {
            check(existing.service == component && existing.isPeriodic && existing.isPersisted) {
                "Routine job ID is occupied by an incompatible job."
            }
            return
        }
        val info = JobInfo.Builder(JOB_ID, component)
            .setPersisted(true)
            .setPeriodic(POLL_INTERVAL_MILLIS)
            .build()
        check(scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS) { "Android could not schedule routine polling." }
    }

    companion object {
        const val JOB_ID = 0x56454C41
        const val POLL_INTERVAL_MILLIS = 15 * 60 * 1000L
    }
}

/** Implement on Application. Return the SAME engine from all callers, initialized on IO, once. */
interface RoutineRuntimeOwner {
    suspend fun routineEngine(): RoutineEngine
}

/** Declare with BIND_JOB_SERVICE and RECEIVE_BOOT_COMPLETED; see routines-contract.md. */
class RoutineJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var parameters: JobParameters? = null
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (work?.isActive == true) return false
        val owner = application as? RoutineRuntimeOwner
        if (owner == null) {
            Log.e("VelaRoutines", "Application must implement RoutineRuntimeOwner.")
            return false
        }
        parameters = params
        work = scope.launch {
            var retry = false
            try {
                withContext(Dispatchers.IO) { owner.routineEngine().onWake() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Never log a saved owner task or provider error/credential.
                Log.e("VelaRoutines", "Routine wake failed; persisted state was retained.")
                retry = true
            } finally {
                if (parameters === params) {
                    parameters = null
                    jobFinished(params, retry)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // Stop parameters may be a fresh Binder-parceled object, unlike the captured start object.
        if (parameters?.jobId == params.jobId) {
            parameters = null
            work?.cancel()
        }
        // The engine retains queued work, but interrupted executions are never blindly replayed.
        return true
    }

    override fun onDestroy() {
        parameters = null
        scope.cancel()
        super.onDestroy()
    }
}
