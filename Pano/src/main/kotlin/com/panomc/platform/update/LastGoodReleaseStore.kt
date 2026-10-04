package com.panomc.platform.update

import io.vertx.core.json.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The last good release lookup per product and channel, kept on disk next to config.conf
 * (`release-info.json`) so it survives restarts and a website / GitHub outage never blanks the
 * update info.
 *
 * Shape: `{"pano:alpha": <ReleaseList>, "pano-mc-plugin:all": <ReleaseList>, …}`. A missing or
 * unreadable file is simply an empty store.
 */
class LastGoodReleaseStore(private val file: File) {
    private val mutex = Mutex()

    @Volatile
    private var entries: Map<String, ReleaseList>? = null

    suspend fun get(key: String): ReleaseList? = loaded()[key]

    suspend fun put(key: String, list: ReleaseList) {
        val trimmed = list.copy(releases = list.releases.take(ReleaseLookup.STORED_RELEASES))

        mutex.withLock {
            val updated = loaded() + (key to trimmed)
            entries = updated

            withContext(Dispatchers.IO) {
                try {
                    val json = JsonObject()
                    updated.forEach { (k, v) -> json.put(k, v.toJson()) }

                    file.absoluteFile.parentFile?.mkdirs()
                    val temp = File(file.absoluteFile.parentFile, file.name + ".tmp")
                    temp.writeText(json.encodePrettily())
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: Exception) {
                    // Best effort: the in-memory copy still answers until the next restart.
                }
            }
        }
    }

    private suspend fun loaded(): Map<String, ReleaseList> {
        entries?.let { return it }

        val read = withContext(Dispatchers.IO) {
            try {
                if (!file.isFile) return@withContext emptyMap()

                val json = JsonObject(file.readText())
                json.fieldNames().mapNotNull { key ->
                    val value = json.getValue(key) as? JsonObject ?: return@mapNotNull null
                    ReleaseList.fromJson(value)?.let { key to it }
                }.toMap()
            } catch (_: Exception) {
                emptyMap()
            }
        }

        return entries ?: read.also { entries = it }
    }

    companion object {
        const val FILE_NAME = "release-info.json"

        /** `release-info.json` in the directory config.conf lives in (`-Dpano.configFile` respected). */
        fun defaultFile(): File {
            val config = File(System.getProperty("pano.configFile", "config.conf")).absoluteFile

            return File(config.parentFile ?: File("."), FILE_NAME)
        }
    }
}
