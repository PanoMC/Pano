package com.panomc.node.server

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * `<data>/external-servers.json`: the servers this node runs from where they already were.
 *
 * A server adopted in place (`IMPORT_SERVER` mode `IN_PLACE`) keeps its own directory, which is by
 * definition not under `<data>/servers`, so the scan the registry rebuilds itself from at boot would
 * never find it again. This file is the one extra thing it reads: `{"<uuid>": "/abs/path", …}`,
 * nothing else, written whole through a temporary file and a rename so a crash halfway through a
 * write leaves either the old index or the new one and never half of each.
 *
 * Losing an entry is worse than keeping a stale one. A stale entry is a server whose directory is
 * missing, which the registry skips with a warning; a lost entry is a server whose directory is
 * right there and which the node has quietly stopped supervising — and whose backups the next boot
 * would sweep as an orphan's. That is why a file that exists but cannot be read is reported as
 * unreadable ([read] returns null) rather than as empty: every caller has to decide what "I do not
 * know" means for it, and none of them may take it to mean "there are none".
 */
object ExternalServerIndex {
    const val FILE = "external-servers.json"

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun file(dataDir: File): File = File(dataDir, FILE)

    /**
     * Every recorded uuid and the absolute path it lives at; empty when there is no file yet, null
     * when there is one that cannot be read or is not a JSON object of strings.
     */
    fun read(dataDir: File): Map<String, String>? {
        val file = file(dataDir)

        if (!file.exists()) {
            return emptyMap()
        }

        return try {
            val element = JsonParser.parseString(file.readText())

            if (element == null || element.isJsonNull) {
                return emptyMap()
            }

            if (!element.isJsonObject) {
                return null
            }

            val result = LinkedHashMap<String, String>()

            element.asJsonObject.entrySet().forEach { (uuid, value) ->
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
                    return null
                }

                result[uuid] = value.asString
            }

            result
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Replaces the index with [entries], atomically where the filesystem allows it.
     *
     * An empty map removes the file rather than leaving `{}` behind: a data directory with no
     * adopted servers looks exactly like one from before this feature existed.
     */
    fun write(dataDir: File, entries: Map<String, String>) {
        val file = file(dataDir)

        if (entries.isEmpty()) {
            Files.deleteIfExists(file.toPath())

            return
        }

        dataDir.mkdirs()

        val json = JsonObject()

        entries.toSortedMap().forEach { (uuid, path) -> json.addProperty(uuid, path) }

        val temporary = File(dataDir, "$FILE.tmp")

        temporary.writeText(gson.toJson(json))

        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Points [uuid] at [directory] when the folder recorded for it is gone, and says whether it did.
     *
     * For a Pano Agent (SM-74), whose data lives inside its server's folder: an admin who moves the
     * folder moves the agent with it, and the next start finds the index still naming the old place.
     * Only a recorded folder that no longer exists is replaced -- one that is still there is a
     * different server, and nothing about it is guessed.
     */
    fun followMove(dataDir: File, uuid: String, directory: File): Boolean {
        val entries = read(dataDir) ?: return false
        val recorded = entries[uuid]?.let { File(it) } ?: return false

        if (recorded.absoluteFile.normalize() == directory.absoluteFile.normalize() || recorded.isDirectory) {
            return false
        }

        write(dataDir, entries + (uuid to directory.path))

        return true
    }
}
