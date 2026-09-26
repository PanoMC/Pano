package com.panomc.platform

import com.panomc.platform.hosted.ContainerMode
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component
import java.nio.file.Paths

@Component
class PlatformStateManager {
    var restartRequired: Boolean = false

    /** Container detection; replaceable in tests. */
    internal var containerMode: () -> ContainerMode = { ContainerMode.current }

    /** Stops this process with the given exit code; replaceable in tests. */
    internal var shutdown: suspend (exitCode: Int) -> Unit = { exitCode ->
        Main.applicationContext.getBean(Main::class.java).shutdown(exitCode = exitCode)
    }

    /**
     * Restarts Pano. In container mode (Pano Host or the pano-runtime image) the launcher owns the
     * process: exit [ContainerMode.EXIT_RESTART] and it relaunches in place (no detached JVM, `-bg`
     * ignored). Otherwise starts a new process with the same jar and startup args, then shuts this one
     * down. [background] adds `-bg` (detached respawn).
     */
    suspend fun restart(background: Boolean = false) {
        if (containerMode().restartInPlace { shutdown(it) }) {
            return
        }

        val targetJar = Paths.get(Main::class.java.protectionDomain.codeSource.location.toURI()).toAbsolutePath().toString()

        check(targetJar.endsWith(".jar")) { "Pano is not running from a jar; restart it by hand." }

        val javaBin = Paths.get(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        ).toString()

        // Start from the original startup args; -bg is independent of -nogui (the child's Main
        // self-respawns detached but still honours -nogui / GUI as a separate decision).
        val args = mutableListOf(javaBin, "-jar", targetJar)
        val baseArgs = Main.STARTUP_ARGS.toMutableList()

        if (background && !baseArgs.contains("-bg")) {
            baseArgs.add("-bg")
        }

        args.addAll(baseArgs)

        ProcessBuilder(args).inheritIO().start()

        delay(500)

        shutdown(0)
    }
}
