package com.wisp.app.repo

import android.util.AtomicFile
import java.io.File
import java.io.IOException

/** Atomic file operations, separated so storage policy can be tested on the JVM. */
interface PublicationFileIO {
    fun read(path: File): String
    fun write(path: File, content: String)
    fun delete(path: File)
}

class AndroidPublicationFileIO : PublicationFileIO {
    override fun read(path: File): String =
        AtomicFile(path).openRead().bufferedReader().use { it.readText() }

    override fun write(path: File, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val file = AtomicFile(path)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
            if (!path.isFile || File(path.parentFile, "${path.name}.new").exists() || File(path.parentFile, "${path.name}.bak").exists()) {
                throw IOException("Could not commit publication file")
            }
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    override fun delete(path: File) {
        AtomicFile(path).delete()
    }
}
