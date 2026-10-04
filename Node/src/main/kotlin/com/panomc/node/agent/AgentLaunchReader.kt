package com.panomc.node.agent

import com.google.gson.JsonParser
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.io.File

/**
 * The worker's side of [AgentLaunch] (SM-76): reads `launch.json`, keeping only what could be
 * meant -- a jar that is a plain file name, a memory within the bounds Pano accepts, and at most
 * [MAX_JVM_ARGS] arguments of at most [MAX_JVM_ARG_LENGTH] characters. A file that does not read
 * is no file at all: the adoption then decides everything itself, as it did before SM-76.
 *
 * Kept apart from [AgentLaunch] so the launcher, which writes the file, never loads a JSON library.
 */
object AgentLaunchReader {
    /** Pano's limits for a server's Java arguments (its startup settings API). */
    const val MAX_JVM_ARGS = 64
    const val MAX_JVM_ARG_LENGTH = 256

    fun read(dataDir: File): AgentLaunch? {
        val file = AgentLaunch.file(dataDir)

        if (!file.isFile || file.length() > MAX_FILE_BYTES) {
            return null
        }

        return try {
            parse(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    fun parse(text: String): AgentLaunch? {
        val root = JsonParser.parseString(text)

        if (!root.isJsonObject) {
            return null
        }

        val json = root.asJsonObject

        val jar = json.get("jar")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            ?.takeIf { it.isNotEmpty() && !it.contains('/') && !it.contains('\\') && it != ".." && it.endsWith(".jar", ignoreCase = true) }

        val memoryMb = json.get("memoryMb")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            ?.coerceIn(JvmArgs.MIN_MEMORY_MB, JvmArgs.MAX_MEMORY_MB)
            ?: JvmArgs.DEFAULT_MEMORY_MB

        val jvmArgs = json.get("jvmArgs")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() } }
            ?.take(MAX_JVM_ARGS)
            ?.map { it.take(MAX_JVM_ARG_LENGTH) }
            .orEmpty()

        return AgentLaunch(jar, memoryMb, jvmArgs)
    }

    /** [launch] as the hello's `agentLaunch`. */
    fun toHello(launch: AgentLaunch): JsonObject = JsonObject()
        .put("jar", launch.jar)
        .put("memoryMb", launch.memoryMb)
        .put("jvmArgs", JsonArray(launch.jvmArgs))

    private const val MAX_FILE_BYTES = 64 * 1024L
}
