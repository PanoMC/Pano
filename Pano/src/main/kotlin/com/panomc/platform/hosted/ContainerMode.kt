package com.panomc.platform.hosted

import com.panomc.platform.Main
import java.io.File
import java.nio.file.Path

/**
 * Pano runs inside the pano-runtime container (Pano Host or a self-run image) when `PANO_HOSTED` is
 * set, `PANO_CONTAINER=1`, or `.pano-jar` next to the running jar names it. Then the launcher owns the
 * process: restart and self-update exit with [EXIT_RESTART] instead of spawning a detached JVM, and the
 * new jar is installed through [JarPointer].
 */
class ContainerMode(
    private val env: Map<String, String> = System.getenv(),
    private val runningJar: File? = detectRunningJar(),
    private val defaultDataDir: File = File(DEFAULT_DATA_DIR)
) {
    companion object {
        /** The launcher relaunches in place (re-reading `.pano-jar`) on this exit code. */
        const val EXIT_RESTART = 75

        const val DEFAULT_DATA_DIR = "/data"

        val current by lazy { ContainerMode() }

        fun detectRunningJar(): File? = runCatching {
            Path.of(Main::class.java.protectionDomain.codeSource.location.toURI()).toFile()
                .takeIf { it.isFile && it.name.endsWith(".jar") }
        }.getOrNull()
    }

    /** `PANO_HOSTED` value (`pano-host` on Pano Host), null when not hosted. */
    val hosted: String? = env["PANO_HOSTED"]?.trim()?.takeIf { it.isNotEmpty() }

    val isHosted get() = hosted != null

    /** Where `.pano-jar` and the `Pano-*.jar` files live: the running jar's folder when it has a pointer, else `/data`. */
    val dataDir: File = runningJar?.absoluteFile?.parentFile?.takeIf { File(it, JarPointer.POINTER).isFile }
        ?: defaultDataDir

    val jarPointer get() = JarPointer(dataDir)

    val active: Boolean = isHosted ||
            env["PANO_CONTAINER"]?.trim() == "1" ||
            (runningJar != null && JarPointer(dataDir).current() == runningJar.name &&
                    runningJar.absoluteFile.parentFile == dataDir.absoluteFile)

    /**
     * Restarts through the launcher: runs [shutdown] with [EXIT_RESTART] and returns true, or returns
     * false (nothing done) outside container mode.
     */
    suspend fun restartInPlace(shutdown: suspend (exitCode: Int) -> Unit): Boolean {
        if (!active) return false
        shutdown(EXIT_RESTART)
        return true
    }

    /** Installs [stagedJar] via the pointer, then restarts in place. False outside container mode. */
    suspend fun installAndRestart(stagedJar: File, version: String, shutdown: suspend (exitCode: Int) -> Unit): Boolean {
        if (!active) return false
        jarPointer.stage(stagedJar, version)
        shutdown(EXIT_RESTART)
        return true
    }
}
