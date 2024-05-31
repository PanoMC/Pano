package com.panomc.platform.util

import org.apache.tika.Tika

object MimeTypeUtil {
    private val tika by lazy {
        Tika()
    }

    fun getMimeTypeFromFileName(fileName: String): String {
        val split = fileName.split(".")

        val extension = split[split.size - 1]

        if (extension == "mjs" || extension == "js") {
            return "text/javascript"
        }

        return tika.detect(fileName)
    }
}