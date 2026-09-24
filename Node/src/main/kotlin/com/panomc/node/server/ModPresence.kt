package com.panomc.node.server

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.zip.ZipFile

/**
 * Whether a mod is already installed in a `mods` directory, by the id it declares rather than by
 * its file name.
 *
 * File names are whatever the pack or the operator made of them (`fabric-api-0.146.1+26.1.2.jar`,
 * `FabricAPI.jar`, ...), so the jar is opened and its `fabric.mod.json` or `quilt.mod.json` read:
 * the mod's own `id` counts, and so does anything it `provides` -- Quilted Fabric API provides
 * `fabric-api`, and a server running it must not be handed a second copy. A `.disabled` jar is not
 * loaded by the server and so does not count.
 */
object ModPresence {
    private const val JAR_SUFFIX = ".jar"

    fun isInstalled(modsDirectory: File, modId: String): Boolean {
        val jars = modsDirectory.listFiles { file -> file.isFile && file.name.lowercase().endsWith(JAR_SUFFIX) }
            ?: return false

        return jars.any { jar -> modId in idsOf(jar) }
    }

    /** Every id [jar] declares or provides; empty for a jar that is not a readable mod. */
    fun idsOf(jar: File): Set<String> = try {
        ZipFile(jar).use { zip ->
            val ids = mutableSetOf<String>()

            zip.getEntry("fabric.mod.json")?.let { entry ->
                parse(zip.getInputStream(entry).use { it.readBytes().decodeToString() })?.let { root ->
                    root.string("id")?.let(ids::add)
                    ids += providedIds(root.get("provides"))
                }
            }

            zip.getEntry("quilt.mod.json")?.let { entry ->
                parse(zip.getInputStream(entry).use { it.readBytes().decodeToString() })
                    ?.get("quilt_loader")
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.let { loader ->
                        loader.string("id")?.let(ids::add)
                        ids += providedIds(loader.get("provides"))
                    }
            }

            ids
        }
    } catch (_: Exception) {
        emptySet()
    }

    /** `provides` is a list of ids in Fabric and a list of ids or `{ "id": ... }` objects in Quilt. */
    private fun providedIds(element: JsonElement?): List<String> {
        if (element == null || !element.isJsonArray) {
            return emptyList()
        }

        return element.asJsonArray.mapNotNull { entry ->
            when {
                entry.isJsonPrimitive -> entry.asString
                entry.isJsonObject -> entry.asJsonObject.string("id")
                else -> null
            }
        }
    }

    private fun parse(text: String): JsonObject? = try {
        JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
}
