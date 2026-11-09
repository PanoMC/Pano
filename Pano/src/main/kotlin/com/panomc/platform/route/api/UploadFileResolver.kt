package com.panomc.platform.route.api

import java.io.File

/**
 * Resolves a file name taken from a request (a path parameter such as `:filename`) inside an uploads folder.
 *
 * Vert.x decodes path parameters, so `..%2F..%2Fconfig.conf` reaches a handler as `../../config.conf`. Only a single,
 * plain file name is accepted: no separator, no `..`, no drive or NUL character. The canonical result must also stay
 * inside [folder]. Anything else answers null, which the routes turn into a 404.
 */
object UploadFileResolver {
    private val SAFE_NAME = Regex("^[A-Za-z0-9_-][A-Za-z0-9._-]{0,254}$")

    fun isSafeName(name: String): Boolean = SAFE_NAME.matches(name) && !name.contains("..")

    /** The existing regular file [name] directly inside [folder], or null when the name is unsafe or the file is missing. */
    fun resolve(folder: String, name: String): File? {
        if (!isSafeName(name)) {
            return null
        }

        return try {
            val base = File(folder).canonicalFile
            val file = File(base, name).canonicalFile

            if (file.parentFile != base || !file.isFile) null else file
        } catch (_: Exception) {
            null
        }
    }
}
