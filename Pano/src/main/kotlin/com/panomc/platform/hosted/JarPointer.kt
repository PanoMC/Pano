package com.panomc.platform.hosted

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The jar pointer protocol shared with the Portal agent (`WorkloadHelper.INSTALL` / `ROLLBACK`):
 * `<data>/.pano-jar` holds the file name (no path, no newline) of the jar the launcher runs,
 * `<data>/.pano-jar.previous` the one before it. Pointer files are written through a tmp file and an
 * atomic rename, and every `Pano-*.jar` other than the new and the previous one is pruned.
 */
class JarPointer(val dataDir: File) {
    companion object {
        const val POINTER = ".pano-jar"
        const val PREVIOUS = ".pano-jar.previous"

        private val VERSION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")

        /** `Pano-<version>.jar`, a leading `v` of a release tag dropped. */
        fun jarName(version: String): String {
            val clean = version.trim().removePrefix("v")
            require(VERSION_PATTERN.matches(clean) && !clean.contains("..")) { "Invalid Pano version: $version" }
            return "Pano-$clean.jar"
        }

        private fun isValidName(name: String) = name.isNotEmpty() && !name.contains('/') && name != "." && name != ".."
    }

    fun current(): String? = read(POINTER)

    fun previous(): String? = read(PREVIOUS)

    /**
     * Installs [stagedJar] as `Pano-<version>.jar`, points `.pano-jar` at it (the old target goes to
     * `.pano-jar.previous`) and prunes older jars. Returns the new jar name. The running JVM keeps its
     * open jar even when it is replaced or pruned, the launcher picks the new one on the next start.
     */
    fun stage(stagedJar: File, version: String): String {
        require(stagedJar.isFile) { "Staged jar does not exist: $stagedJar" }
        val name = jarName(version)

        dataDir.mkdirs()
        val target = File(dataDir, name)
        val tmp = File(dataDir, ".$name.tmp")
        try {
            Files.copy(stagedJar.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }

        val cur = current()
        if (cur != null && cur != name) {
            writeAtomic(PREVIOUS, cur)
        }
        writeAtomic(POINTER, name)

        prune(keep = setOfNotNull(name, previous()))

        return name
    }

    private fun prune(keep: Set<String>) {
        dataDir.listFiles()
            ?.filter { it.name.startsWith("Pano-") && it.name.endsWith(".jar") && it.name !in keep }
            ?.forEach { it.delete() }
    }

    private fun read(file: String): String? {
        val f = File(dataDir, file)
        if (!f.isFile) return null
        return runCatching { f.readText().trim() }.getOrNull()?.takeIf { isValidName(it) }
    }

    private fun writeAtomic(file: String, value: String) {
        val tmp = File(dataDir, "$file.tmp")
        tmp.writeText(value)
        Files.move(
            tmp.toPath(),
            File(dataDir, file).toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    }
}
