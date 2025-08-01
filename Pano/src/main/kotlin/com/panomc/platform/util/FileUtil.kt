package com.panomc.platform.util

import java.io.File

object FileUtil {
    fun File.getSize(): Long {
        if (this.isFile) {
            return this.length()
        }
        return this.listFiles()?.sumOf { it.getSize() } ?: 0L
    }
}