package com.panomc.platform.util

import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.*

/*
* Utilities to (re)launch the current executable JAR inside a visible console/terminal
* when the process is started without an attached TTY (e.g., double-click on GUI).
*
* The entry point is [spawnWithConsole]. PANO_SPAWNED=1 prevents infinite relaunch loops.
*/
object LauncherUtil {

    /**
     * Returns true if a TTY is attached to the current process.
     * Uses System.console() and a portable "tty" probe for Linux/macOS.
     */
    fun hasAttachedTty(): Boolean {
        if (System.console() != null) return true
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        if (os.contains("win")) {
            // On Windows, System.console() is the best available signal.
            return false
        }
        return try {
            val p = ProcessBuilder("sh", "-lc", "tty >/dev/null 2>&1").start()
            p.waitFor()
            p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * If no console is attached, relaunch the current JAR inside a terminal and exit this process.
     *
     * @return true if a new terminal was launched (this process will exit immediately).
     */
    fun spawnWithConsole(): Boolean {
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val javaExe = resolveJavaExe()
        val jarPath = resolveJarPath() ?: return false
        val workDir = File(jarPath).parentFile
        val baseCmd = "\"$javaExe\" -jar \"${File(jarPath).absolutePath}\""

        val launched = try {
            when {
                os.contains("win") -> spawnWindows(workDir, baseCmd)
                os.contains("mac") -> spawnMac(workDir, baseCmd)
                else -> spawnLinux(workDir, baseCmd)
            }
        } catch (_: Exception) {
            false
        }

        if (launched) System.exit(0)
        return launched
    }

    // ------------------ Windows ------------------

    /**
     * Windows: open a new Command Prompt window and execute the base command, keeping it open.
     */
    private fun spawnWindows(workDir: File, baseCmd: String): Boolean {
        val cmd = listOf(
            "cmd.exe", "/c", "start", "Pano",
            "cmd.exe", "/k", baseCmd
        )
        ProcessBuilder(cmd)
            .directory(workDir)
            .apply { environment()["PANO_SPAWNED"] = "1" }
            .start()
        return true
    }

    // ------------------ macOS ------------------

    /**
     * macOS: try iTerm2 first (if installed), otherwise fall back to Terminal.app.
     */
    private fun spawnMac(workDir: File, baseCmd: String): Boolean {
        return spawnMacITerm(workDir, baseCmd) || spawnMacTerminal(workDir, baseCmd)
    }

    /**
     * Spawn iTerm2 and run the base command.
     */
    fun spawnMacITerm(workDir: File, baseCmd: String): Boolean {
        val iTermApp1 = File("/Applications/iTerm.app")
        val iTermApp2 = File(System.getProperty("user.home") + "/Applications/iTerm.app")
        if (!iTermApp1.exists() && !iTermApp2.exists()) return false

        val script = """
            tell application "iTerm"
              activate
              if (count of windows) = 0 then
                create window with default profile
              else
                create tab with default profile
              end if
              tell current session of current window
                write text "cd ${escapeForShell(workDir.absolutePath)}; $baseCmd; echo; echo 'Press Cmd+W to close...'"
              end tell
            end tell
        """.trimIndent()

        return try {
            ProcessBuilder(listOf("osascript", "-e", script))
                .apply { environment()["PANO_SPAWNED"] = "1" }
                .start()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Spawn Terminal.app and run the base command.
     */
    fun spawnMacTerminal(workDir: File, baseCmd: String): Boolean {
        val script = """
            tell application "Terminal"
              activate
              do script "cd ${escapeForShell(workDir.absolutePath)}; $baseCmd; echo; echo 'Press Ctrl+D to close...'"
            end tell
        """.trimIndent()
        return try {
            ProcessBuilder(listOf("osascript", "-e", script))
                .apply { environment()["PANO_SPAWNED"] = "1" }
                .start()
            true
        } catch (_: Exception) {
            false
        }
    }

    // ------------------ Linux ------------------

    /**
     * Linux: try a wide set of terminal emulators; run the base command via bash -lc when possible.
     */
    fun spawnLinux(workDir: File, baseCmd: String): Boolean {
        val commands: List<List<String>> = listOf(
            listOf(
                "x-terminal-emulator",
                "-e",
                "bash",
                "-lc",
                "$baseCmd; echo; read -n 1 -s -r -p 'Press any key to close...'"
            ),
            listOf(
                "gnome-terminal",
                "--",
                "bash",
                "-lc",
                "$baseCmd; echo; read -n 1 -s -r -p 'Press any key to close...'"
            ),
            listOf("konsole", "-e", "bash", "-lc", "$baseCmd; echo; read -n 1 -s -r -p 'Press any key to close...'"),
            listOf(
                "xfce4-terminal",
                "--command",
                "bash -lc \"$baseCmd; echo; read -n 1 -s -r -p 'Press any key to close...'\""
            ),
            listOf(
                "mate-terminal",
                "-e",
                "bash",
                "-lc",
                "$baseCmd; echo; read -n 1 -s -r -p 'Press any key to close...'"
            ),
            listOf("lxterminal", "-e", "bash", "-lc", "$baseCmd; echo; read -n 1 -s -r -p 'Press any key to close...'"),
            listOf("tilix", "-e", "bash -lc \"$baseCmd; echo; read -n 1 -s -r -p 'Press any key to close...'\""),
            listOf("alacritty", "-e", "bash", "-lc", "$baseCmd; echo; read -n 1 -s -r -p 'Press any key to close...'"),
            listOf("kitty", "bash", "-lc", "$baseCmd; echo; read -n 1 -s -r -p 'Press any key to close...'"),
            listOf("terminator", "-x", "bash", "-lc", "$baseCmd; echo; read -n 1 -s -r -p 'Press any key to close...'"),
            listOf(
                "wezterm",
                "start",
                "--",
                "bash",
                "-lc",
                "$baseCmd; echo; read -n 1 -s -r -p 'Press any key to close...'"
            ),
            listOf("qterminal", "-e", "bash -lc \"$baseCmd; echo; read -n 1 -s -r -p 'Press any key to close...'\""),
            listOf("sakura", "-e", "bash -lc \"$baseCmd; echo; read -n 1 -s -r -p 'Press any key to close...'\""),
            listOf("urxvt", "-e", "bash", "-lc", "$baseCmd; echo; read -n 1 -s -r -p 'Press any key to close...'"),
            listOf("xterm", "-e", "bash", "-lc", "$baseCmd; echo; read -n 1 -s -r -p 'Press any key to close...'")
        )

        val filtered = commands.filter { isOnPath(it.first()) }
        val listToTry = if (filtered.isNotEmpty()) filtered else commands

        for (cmd in listToTry) {
            try {
                ProcessBuilder(cmd)
                    .directory(workDir)
                    .apply { environment()["PANO_SPAWNED"] = "1" }
                    .start()
                return true
            } catch (_: Exception) {
                // try next
            }
        }
        return false
    }

    // -------------- helpers --------------

    /**
     * Locates the Java executable of the current JVM (`java` on Unix, `java.exe` on Windows).
     */
    fun resolveJavaExe(): String {
        val javaHome = System.getProperty("java.home")
        val bin = File(javaHome, "bin")
        val isWindows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
        val name = if (isWindows) "java.exe" else "java"
        return File(bin, name).absolutePath
    }

    /**
     * Resolves the absolute path of the currently running JAR; null if not a JAR launch.
     */
    fun resolveJarPath(): String? {
        return try {
            val url = LauncherUtil::class.java.protectionDomain.codeSource.location
            val path = URLDecoder.decode(url.path, StandardCharsets.UTF_8)
            val file = File(path)
            if (file.isFile && path.endsWith(".jar", ignoreCase = true)) file.absolutePath else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Minimal escaping for embedded shell strings (e.g., AppleScript `do script` lines).
     */
    fun escapeForShell(s: String): String = s.replace("\"", "\\\"")

    /**
     * Returns true if a command exists on PATH (via `which`/`where`).
     */
    fun isOnPath(cmd: String): Boolean {
        val isWindows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
        val whichCmd = if (isWindows) listOf("where", cmd) else listOf("which", cmd)
        return try {
            val p = ProcessBuilder(whichCmd).redirectErrorStream(true).start()
            p.waitFor()
            p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }
}