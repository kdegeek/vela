package app.vela.tv.profile

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Kept separate from the pure JVM store. Application code should use [AndroidHouseholds.open]. */
internal class AndroidHouseholdStorage(private val base: File) : HouseholdStorage {
    private val atomic = AtomicFile(base)

    override fun read(): ByteArray? {
        val stream = try {
            atomic.openRead()
        } catch (error: FileNotFoundException) {
            // Do not turn unreadable files or an existing recovery backup into an empty household.
            if (!base.exists() && !File(base.path + ".bak").exists()) {
                val parent = base.parentFile
                if (parent != null && parent.isDirectory && parent.canRead()) return null
            }
            throw error
        }
        return stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > HouseholdLimits.MAX_DOCUMENT_BYTES) {
                    throw HouseholdException(HouseholdError.CORRUPT_STORAGE, "Household storage is too large.")
                }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }

    override fun writeAtomically(bytes: ByteArray) {
        householdRequire(bytes.size <= HouseholdLimits.MAX_DOCUMENT_BYTES, "Household storage is too large.", HouseholdError.LIMIT_EXCEEDED)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            // Surface sync errors; AtomicFile.finishWrite otherwise logs some IO errors internally.
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
        // AtomicFile's commit API is void. Detect failed replacement rather than publishing success.
        if (read()?.contentEquals(bytes) != true) throw IOException("Household commit could not be verified.")
    }
}

/** One instance per app-private path prevents multiple TV/phone/agent stores from losing each other's writes. */
object AndroidHouseholds {
    private val mutex = Mutex()
    private val stores = mutableMapOf<String, HouseholdStore>()

    suspend fun open(context: Context): HouseholdStore = mutex.withLock {
        val file = File(context.applicationContext.noBackupFilesDir, "household.json")
        stores[file.absolutePath] ?: HouseholdStore.open(AndroidHouseholdStorage(file)).also { stores[file.absolutePath] = it }
    }
}
