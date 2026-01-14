package com.panomc.platform.util

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream
import org.slf4j.LoggerFactory

object ZipUtil {
    private val logger = LoggerFactory.getLogger(ZipUtil::class.java)

    fun zipFoldersToBytes(folders: Map<String, File>): ByteArray {
        val baos = ByteArrayOutputStream()
        var fileCount = 0
        ZipOutputStream(baos).use { zos ->
            folders.forEach { (pathInZip, folder) ->
                if (folder.exists() && folder.isDirectory) {
                    logger.debug("Starting recursive zip of folder: ${folder.absolutePath} with prefix: '$pathInZip'")
                    folder.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            val relativePath = file.relativeTo(folder).path.replace("\\", "/")
                            val zipPath = if (pathInZip.isEmpty()) relativePath else "$pathInZip/$relativePath"
                            
                            val zipEntry = ZipEntry(zipPath)
                            zos.putNextEntry(zipEntry)
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                            fileCount++
                            logger.debug("Zipping file: $zipPath")
                        }
                    }
                }
            }
        }
        logger.info("Successfully zipped $fileCount files into memory.")
        return baos.toByteArray()
    }
}
