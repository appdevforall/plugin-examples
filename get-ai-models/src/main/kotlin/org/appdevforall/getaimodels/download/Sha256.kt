package org.appdevforall.getaimodels.download

import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

object Sha256 {

    private const val BUFFER_BYTES = 1 shl 20 // 1 MiB: model files run to several GB

    /**
     * Streams [file] and returns its lower-case hex SHA-256. Never reads the whole file into
     * memory, and checks for cancellation between chunks so tearing the plugin down mid-verify
     * does not leave the CPU hashing gigabytes.
     */
    suspend fun of(file: File): String = file.inputStream().use { of(it) }

    /**
     * The same digest over any stream, for bytes with no filesystem path - a finished download that
     * DownloadManager exposes only through the downloads provider. Closing [input] stays the
     * caller's job, as everywhere else an [InputStream] is passed in.
     */
    suspend fun of(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
