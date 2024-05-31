package com.panomc.platform.util

import com.panomc.platform.api.PanoPlugin
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServerResponse
import java.io.InputStream

object FileResourceUtil {
    fun PanoPlugin.getResource(resourceName: String) = this.javaClass.classLoader.getResourceAsStream(resourceName)

    fun InputStream.writeToResponse(response: HttpServerResponse) {
        this.use {
            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (it.read(buffer).also { bytesRead = it } != -1) {
                response.write(Buffer.buffer(buffer.copyOfRange(0, bytesRead)))
            }
        }
    }
}