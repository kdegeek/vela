package app.vela.tv.runtime

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Small private sidecars. Writes are verified; an ambiguous write must not publish an in-memory value. */
class PrivateRuntimeFile(file: File) {
    private val file = AtomicFile(file)
    fun read(maxBytes: Int): ByteArray? {
        val input = try { file.openRead() } catch (e: FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw e
            return null
        }
        return input.use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                check(output.size() + count <= maxBytes)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    fun write(bytes: ByteArray) {
        val out = file.startWrite()
        try { out.write(bytes); out.fd.sync(); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
        if (read(bytes.size)?.contentEquals(bytes) != true) throw IOException("Private state could not be saved.")
    }
}
