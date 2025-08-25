package com.panomc.updater

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Duration
import java.util.*
import kotlin.system.exitProcess

class Main {
    companion object {

        @JvmStatic
        fun main(args: Array<String>) {
            val argMap = parseArgs(args)

            val host = argMap["--host"] ?: fail("Missing --host")
            val port = (argMap["--port"] ?: fail("Missing --port")).toIntOrNull() ?: fail("--port must be an integer")
            val targetJar = Paths.get(argMap["--target"] ?: fail("Missing --target")).toAbsolutePath().normalize()
            val updateJar = Paths.get(argMap["--update"] ?: fail("Missing --update")).toAbsolutePath().normalize()
            val javaBin =
                (argMap["--java"] ?: currentJavaBin()).let { Paths.get(it).toAbsolutePath().normalize().toString() }
            val launchArgs = splitArgsPreservingQuotes(argMap["--launch-args"])

            // Check for --nogui
            val noGui = argMap["--nogui"]?.toBoolean() == true

            val childArgs = buildList {
                addAll(launchArgs)
                if (noGui) add("--nogui")
            }

            println("[Pano Updater] PID=${ProcessHandle.current().pid()} starting…")
            println("[Pano Updater] host=$host port=$port")
            println("[Pano Updater] target=$targetJar")
            println("[Pano Updater] javaBin=$javaBin")
            println("[Pano Updater] update=$updateJar")
            println("[Pano Updater] pass --nogui: $noGui")
            if (childArgs.isNotEmpty()) println("[Pano Updater] child args: $childArgs")

            waitForPortToClose(host, port, timeout = Duration.ofMinutes(5), poll = Duration.ofMillis(300))
            replaceJarWithRetries(updateJar, targetJar, attempts = 20, sleepMs = 300)

            val child = startChild(javaBin, targetJar, childArgs)
            println("[Pano Updater] Child PID=${child.pid()} launched.")

            println("[Pano Updater] Updater finished! Exiting...")
            exitProcess(0)
        }

        // --- Networking ---
        private fun canConnect(host: String, port: Int, timeoutMs: Int = 300): Boolean =
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
            } catch (_: IOException) {
                false
            }

        private fun waitForPortToClose(host: String, port: Int, timeout: Duration, poll: Duration) {
            val deadline = System.nanoTime() + timeout.toNanos()
            while (System.nanoTime() < deadline) {
                if (!canConnect(host, port)) {
                    println("[Pano Updater] $host:$port closed. Proceeding.")
                    return
                }
                Thread.sleep(poll.toMillis())
            }
            if (canConnect(host, port)) fail("Timeout waiting for $host:$port to close.")
        }

        // --- Files ---
        private fun replaceJarWithRetries(updateJar: Path, targetJar: Path, attempts: Int, sleepMs: Long) {
            require(Files.exists(updateJar)) { "Update jar not found: $updateJar" }
            require(updateJar != targetJar) { "--update and --target must be different" }
            val dir = targetJar.parent ?: Paths.get(".").toAbsolutePath().normalize()
            Files.createDirectories(dir)
            val backup = dir.resolve(targetJar.fileName.toString() + ".bak")

            repeat(attempts) { i ->
                try {
                    try {
                        if (Files.exists(backup)) Files.delete(backup)
                    } catch (_: Exception) { /* ignore */
                    }

                    if (Files.exists(targetJar)) {
                        try {
                            Files.move(targetJar, backup, ATOMIC_MOVE, REPLACE_EXISTING)
                        } catch (_: AtomicMoveNotSupportedException) {
                            Files.move(targetJar, backup, REPLACE_EXISTING)
                        }
                    }

                    try {
                        Files.move(updateJar, targetJar, ATOMIC_MOVE, REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(updateJar, targetJar, REPLACE_EXISTING)
                    }

                    try {
                        if (Files.exists(backup)) Files.delete(backup)
                    } catch (_: Exception) { /* ignore */
                    }

                    println("[Pano Updater] Replaced $targetJar successfully.")
                    return
                } catch (e: Exception) {
                    val left = attempts - i - 1
                    println("[Pano Updater] Replace failed (${e.message}). Retries left: $left")
                    if (left <= 0) {
                        try {
                            if (Files.exists(backup)) {
                                if (Files.exists(targetJar)) Files.delete(targetJar)
                                Files.move(backup, targetJar, REPLACE_EXISTING)
                            }
                        } catch (_: Exception) { /* ignore */
                        }
                        fail("Failed to replace jar after $attempts attempts.")
                    }
                    Thread.sleep(sleepMs)
                }
            }
        }

        private fun startChild(javaBin: String, targetJar: Path, args: List<String>): Process {
            val cmd = mutableListOf(javaBin, "-jar", targetJar.toString()).apply { addAll(args) }
            return ProcessBuilder(cmd)
                .inheritIO()
                .start()
        }

        // --- Utils ---
        private fun parseArgs(args: Array<String>): Map<String, String> {
            val map = mutableMapOf<String, String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                if (a.startsWith("--")) {
                    val eq = a.indexOf('=')
                    if (eq > 0) {
                        // --key=value
                        map[a.substring(0, eq)] = a.substring(eq + 1)
                    } else {
                        // --flag [value?]
                        val next = args.getOrNull(i + 1)
                        if (next != null && !next.startsWith("--")) {
                            map[a] = next
                            i++
                        } else {
                            // unnecessary flag -> "true"
                            map[a] = "true"
                        }
                    }
                }
                i++
            }
            return map
        }

        private fun splitArgsPreservingQuotes(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            var quote: Char? = null
            for (c in raw) {
                when {
                    quote == null && (c == '"' || c == '\'') -> quote = c
                    quote != null && c == quote -> quote = null
                    quote == null && c.isWhitespace() -> {
                        if (sb.isNotEmpty()) {
                            out += sb.toString()
                            sb.setLength(0)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            if (sb.isNotEmpty()) out += sb.toString()
            return out
        }

        private fun currentJavaBin(): String {
            val home = System.getProperty("java.home")
            val exe = if (isWindows()) "java.exe" else "java"
            return Paths.get(home, "bin", exe).toString()
        }

        private fun isWindows(): Boolean =
            System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")

        private fun fail(msg: String): Nothing {
            System.err.println("[Pano Updater] ERROR: $msg")
            exitProcess(1)
        }
    }
}
