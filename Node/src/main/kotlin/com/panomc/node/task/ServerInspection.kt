package com.panomc.node.task

import com.panomc.node.agent.AgentFiles
import com.panomc.node.server.JarJavaRequirement
import com.panomc.node.server.MinecraftJavaVersions
import io.vertx.core.json.JsonObject
import java.io.File
import java.util.Properties
import java.util.jar.JarFile

/**
 * Works out what somebody's server directory actually is.
 *
 * An import starts from a folder, a zip or a modpack, and in none of those cases does Pano know
 * what it is looking at: the wizard's software and version fields are optional for imports
 * precisely because guessing is this side's job. Everything here is a guess with a reason, and
 * every guess is allowed to come back null — an import whose software could not be identified is
 * still a working server as long as there is a jar to launch, and Pano shows "unknown" rather than
 * a wrong answer.
 *
 * The order of evidence matters. A jar's manifest is what the software itself says about itself
 * and is trusted first; the file name is a convention people follow until they rename something;
 * `version.json` inside the jar is Mojang's own record of the Minecraft version and beats
 * `server.properties`, which only has one by accident.
 */
object ServerInspection {
    /** What an inspection concluded. Every field except [jar] may be unknown. */
    data class Result(
        /** The jar to launch, relative to the server directory. */
        val jar: String?,
        val software: String?,
        val version: String?,
        val port: Int?,
        val javaMajor: Int?,
        /** Whether the directory already carries an accepted `eula.txt`. */
        val eulaAccepted: Boolean
    )

    /** Names that give the software away, longest-matching first so `neoforge` beats `forge`. */
    private val NAME_HINTS = listOf(
        "neoforge" to "neoforge",
        "waterfall" to "waterfall",
        "bungeecord" to "bungeecord",
        "velocity" to "velocity",
        "purpur" to "purpur",
        "folia" to "folia",
        "paper" to "paper",
        "spigot" to "spigot",
        "craftbukkit" to "bukkit",
        "fabric" to "fabric",
        "quilt" to "quilt",
        "forge" to "forge",
        "minecraft_server" to "vanilla",
        "server" to null
    )

    /**
     * Inspects [directory] and reports what can be launched from it.
     *
     * [packVersion] and [packLoader] are what a modpack manifest already stated; they are used
     * only where the directory itself is silent, because a pack that says it is Fabric 1.20.1 is
     * better evidence than a jar called `server.jar`.
     */
    fun inspect(
        directory: File,
        packVersion: String? = null,
        packLoader: String? = null,
        /**
         * The jar somebody chose (a Pano Agent's `launch.json`, SM-76): used when it is a file in
         * [directory], and the detection below decides otherwise.
         */
        chosenJar: String? = null
    ): Result {
        val jar = chosenJar?.takeIf { isTopLevelFile(directory, it) } ?: findServerJar(directory)

        val manifestSoftware = jar?.let { softwareFromManifest(File(directory, it)) }
        val nameSoftware = jar?.let { softwareFromName(it) }

        val properties = readProperties(File(directory, "server.properties"))

        return Result(
            jar = jar,
            software = manifestSoftware ?: nameSoftware ?: loaderSoftware(packLoader),
            version = jar?.let { versionFromJar(File(directory, it)) }
                ?: packVersion
                ?: properties["version"],
            port = properties["server-port"]?.toIntOrNull()?.takeIf { it in 1..65535 },
            javaMajor = null,
            eulaAccepted = isEulaAccepted(File(directory, "eula.txt"))
        ).let { result ->
            result.copy(javaMajor = javaMajorFor(result.version, jar?.let { File(directory, it) }))
        }
    }

    /**
     * The jar most likely to be the server (see [ServerJars.pick]).
     *
     * A Pano Agent's own jar is never the server (SM-74): not as `pano-agent.jar`, and not when a
     * hosting panel made the admin rename it to `server.jar` -- [agentJar] is the agent's jar in the
     * folder, and it is skipped whatever it is called.
     */
    fun findServerJar(directory: File, agentJar: File? = AgentFiles.agentJar): String? = ServerJars.pick(directory, agentJar)

    /** Whether [name] is a plain file name (no path) of a file directly in [directory]. */
    fun isTopLevelFile(directory: File, name: String): Boolean =
        name.isNotBlank() && !name.contains('/') && !name.contains('\\') && name != ".." && File(directory, name).isFile

    /** The software a jar's own manifest claims to be, or null when it claims nothing useful. */
    fun softwareFromManifest(jar: File): String? {
        if (!jar.isFile) {
            return null
        }

        val attributes = try {
            JarFile(jar).use { it.manifest?.mainAttributes }
        } catch (_: Exception) {
            null
        } ?: return null

        val implementation = attributes.getValue("Implementation-Title")
            ?: attributes.getValue("Specification-Title")
            ?: attributes.getValue("Main-Class")

        return softwareFromName(implementation ?: return null)
    }

    /** The software a file or class name gives away, or null when the name says nothing. */
    fun softwareFromName(name: String?): String? {
        val lowered = name?.lowercase() ?: return null

        return NAME_HINTS.firstOrNull { (needle, _) -> lowered.contains(needle) }?.second
    }

    /**
     * The Minecraft version recorded inside [jar].
     *
     * `version.json` is written by Mojang into the server jar and carries the exact version, which
     * is the only place it exists that nobody can rename.
     */
    fun versionFromJar(jar: File): String? {
        if (!jar.isFile) {
            return null
        }

        return try {
            JarFile(jar).use { file ->
                val entry = file.getEntry("version.json") ?: return null

                val body = file.getInputStream(entry).bufferedReader().use { it.readText() }

                val parsed = JsonObject(body)

                parsed.getString("id") ?: parsed.getString("name")
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Whether Mojang's EULA has already been accepted in this directory. */
    fun isEulaAccepted(eula: File): Boolean {
        if (!eula.isFile) {
            return false
        }

        return try {
            eula.readLines().any { it.trim().replace(" ", "").equals("eula=true", ignoreCase = true) }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The Java major this server needs, or null when nothing here has an opinion.
     *
     * The minimum rather than the newest, for the reason the fresh-install path already learned
     * the hard way: an automatic pick of Java 26 for a 1.21 server crashed it on shutdown. Null
     * means "leave it automatic", which is the honest answer when neither source says anything.
     *
     * Both sources are consulted and the higher wins. The Minecraft version knows what Mojang's
     * ladder asks for; only [jar] knows what its own class files were compiled for, and for an
     * imported proxy -- whose version is not a Minecraft version at all -- it is the only source
     * there is.
     */
    fun javaMajorFor(version: String?, jar: File? = null): Int? {
        val fromVersion = version?.trim()?.takeIf { it.isNotEmpty() }?.let { MinecraftJavaVersions.minimumFor(it) }
        val fromJar = jar?.let { JarJavaRequirement.of(it) }

        if (fromVersion == null) {
            return fromJar
        }

        return maxOf(fromVersion, fromJar ?: 0)
    }

    /** `fabric-loader` and friends, reduced to the software name Pano uses. */
    private fun loaderSoftware(loader: String?): String? = when (loader) {
        "fabric-loader" -> "fabric"
        "quilt-loader" -> "quilt"
        "forge" -> "forge"
        "neoforge" -> "neoforge"
        else -> null
    }

    private fun readProperties(file: File): Map<String, String> {
        if (!file.isFile) {
            return emptyMap()
        }

        return try {
            val properties = Properties()

            file.inputStream().use { properties.load(it) }

            properties.entries.associate { (key, value) -> key.toString() to value.toString() }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
