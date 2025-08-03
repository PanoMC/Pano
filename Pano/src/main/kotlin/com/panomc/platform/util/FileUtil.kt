package com.panomc.platform.util

import java.io.File

object FileUtil {
    fun File.getSize(): Long {
        if (this.isFile) {
            return this.length()
        }
        return this.listFiles()?.sumOf { it.getSize() } ?: 0L
    }

    fun getAvailableFilePath(path: String): String {
        val file = File(path)
        if (!file.exists()) return path

        val parent = file.parent ?: ""
        val name = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isNotEmpty()) ".$it" else "" }

        var index = 1
        while (true) {
            val newFile = File(parent, "$name ($index)$ext")
            if (!newFile.exists()) {
                return newFile.path
            }
            index++
        }
    }
}