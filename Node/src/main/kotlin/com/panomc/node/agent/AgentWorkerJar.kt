package com.panomc.node.agent

import com.panomc.node.util.Sha256
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The jar a Pano Agent's worker runs from: `.pano-agent/worker.jar`, a copy of the launcher's own
 * `pano-agent.jar` (SM-76).
 *
 * Why a copy: Windows locks the jar a process runs from, and when the worker ran from the very jar
 * the launcher runs from, a self-update could not replace it -- neither the worker on its way out
 * (it still runs from it) nor on its next start (the launcher still does) -- so the agent restarted
 * on its old version and Pano offered the update again every thirty minutes. The launcher never
 * runs from `worker.jar`, so between two workers nobody holds it, and [prepare] swaps a staged
 * update into it right there, on every OS.
 *
 * What [prepare] does before every worker start, in order:
 *
 * 1. A staged update (`updates/pending.json`, written by the worker's `SelfUpdateService`) is
 *    checked against its recorded SHA-256 and moved over `worker.jar`; on a mismatch the staged jar
 *    and its record are deleted. The worker's own attempt on its way out already did this on
 *    Linux and macOS; this is what finishes it on Windows.
 * 2. `worker.jar` is copied again from the launcher's jar when it is missing, or when the launcher's
 *    jar is not the one the copy was made from ([SOURCE_SHA_FILE]) -- an admin who drops a newer
 *    `pano-agent.jar` into the folder by hand gets it.
 * 3. Otherwise, when `worker.jar` differs from the launcher's jar it was updated, and on Linux and
 *    macOS the launcher's jar is replaced with the same bytes (a temporary file and an atomic
 *    rename, which the running launcher survives), so the next full start runs the new launcher
 *    too. Windows keeps the old launcher jar, which is locked, and runs the new worker all the same.
 *
 * Plain JDK: the launcher calls it.
 */
class AgentWorkerJar(
    private val dataDir: File,
    /** The jar the launcher runs from: `pano-agent.jar`, or whatever a hosting panel made it. */
    private val launcherJar: File,
    private val windows: Boolean,
    /** One line for the terminal. */
    private val say: (String) -> Unit
) {
    val workerJar = File(dataDir, WORKER_JAR)

    private val sourceShaFile = File(dataDir, SOURCE_SHA_FILE)

    private val pendingFile = File(File(dataDir, UPDATES_DIRECTORY), PENDING_FILE)

    /** Readies [workerJar] and returns the jar to start the worker from. */
    fun prepare(): File {
        try {
            applyPending()
        } catch (exception: Exception) {
            say("Could not apply the staged update: ${exception.message ?: exception.javaClass.simpleName}")
        }

        return try {
            sync()

            workerJar
        } catch (exception: Exception) {
            // Running the worker from the launcher's jar is how every agent ran before SM-76; it
            // only loses the Windows update path, never the server.
            say("Could not copy ${launcherJar.name} to ${workerJar.path} (${exception.message}); starting the agent from ${launcherJar.name}.")

            launcherJar
        }
    }

    /**
     * Moves a staged update over [workerJar], if one is waiting. Returns whether it did. Never
     * moves anything from outside the data directory: the record names the staged file by path.
     */
    fun applyPending(): Boolean {
        if (!pendingFile.isFile) {
            return false
        }

        val record = pendingFile.readText(Charsets.UTF_8)
        val version = jsonField(record, "version") ?: "?"
        val staged = jsonField(record, "file")?.let { File(it) }
        val expected = jsonField(record, "sha256")?.trim()

        if (staged == null || expected.isNullOrEmpty() || !staged.isFile || !isInside(staged, dataDir)) {
            // Nothing to apply: the worker's own attempt already moved it, or the record is not one.
            pendingFile.delete()

            return false
        }

        if (!Sha256.of(staged).equals(expected, ignoreCase = true)) {
            staged.delete()
            pendingFile.delete()

            say("The staged update $version did not match its checksum; deleted it. Pano offers it again.")

            return false
        }

        moveWithRetry(staged, workerJar)

        pendingFile.delete()

        say("Applied the agent update $version.")

        return true
    }

    /** Steps 2 and 3 of [prepare]. */
    fun sync() {
        val launcherSha = Sha256.of(launcherJar)
        val recorded = readRecorded()

        if (!workerJar.isFile || !launcherSha.equals(recorded, ignoreCase = true)) {
            dataDir.mkdirs()

            replace(workerJar, launcherJar, File(dataDir, "$WORKER_JAR.tmp"))

            record(launcherSha)

            return
        }

        if (windows) {
            return
        }

        val workerSha = Sha256.of(workerJar)

        if (workerSha.equals(launcherSha, ignoreCase = true)) {
            return
        }

        try {
            replace(launcherJar, workerJar, File(launcherJar.absoluteFile.parentFile, ".${launcherJar.name}.tmp"))

            record(workerSha)

            say("Updated ${launcherJar.name} to the agent's new version.")
        } catch (exception: Exception) {
            // The recorded source stays the old launcher jar, so the updated worker is not
            // replaced by it on the next start; only the launcher stays behind.
            say("Could not update ${launcherJar.path} (${exception.message}); the agent itself is up to date.")
        }
    }

    /** Removes the copy and its record, once the agent was removed from Pano. */
    fun clear() {
        workerJar.delete()
        sourceShaFile.delete()
    }

    private fun readRecorded(): String? = try {
        sourceShaFile.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun record(sha: String) {
        sourceShaFile.writeText("$sha\n", Charsets.UTF_8)
    }

    /** [target] becomes a copy of [source], through [temporary] next to it and one rename. */
    private fun replace(target: File, source: File, temporary: File) {
        try {
            Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)

            moveWithRetry(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    /**
     * An atomic rename where the filesystem has one. Retried for a moment on Windows, where a worker
     * that has just exited, or a virus scanner looking at the new jar, can hold the file for a few
     * hundred milliseconds.
     */
    private fun moveWithRetry(source: File, target: File) {
        var attempt = 0

        while (true) {
            try {
                try {
                    Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }

                return
            } catch (exception: Exception) {
                if (!windows || ++attempt >= MOVE_ATTEMPTS) {
                    throw exception
                }

                try {
                    Thread.sleep(MOVE_RETRY_MILLIS)
                } catch (_: InterruptedException) {
                    throw exception
                }
            }
        }
    }

    companion object {
        /** The worker's copy of the agent, in the agent's data directory. */
        const val WORKER_JAR = "worker.jar"

        /** SHA-256 of the launcher jar [WORKER_JAR] was copied from. */
        const val SOURCE_SHA_FILE = "worker.source-sha256"

        /** Where `SelfUpdateService` stages an update, and its record. */
        const val UPDATES_DIRECTORY = "updates"
        const val PENDING_FILE = "pending.json"

        private const val MOVE_ATTEMPTS = 10
        private const val MOVE_RETRY_MILLIS = 300L

        private val FORM_FEED = Char(12)

        /**
         * One string field of a small flat JSON object, unescaped; null when it is not there. The
         * update record is written by the worker and read here without a JSON library.
         */
        fun jsonField(json: String, name: String): String? {
            val match = Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json) ?: return null

            return unescape(match.groupValues[1])
        }

        private fun unescape(value: String): String {
            val result = StringBuilder()
            var index = 0

            while (index < value.length) {
                val char = value[index]

                if (char != '\\' || index + 1 >= value.length) {
                    result.append(char)
                    index++

                    continue
                }

                when (val next = value[index + 1]) {
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'b' -> result.append('\b')
                    'f' -> result.append(FORM_FEED)
                    'u' -> {
                        val hex = value.substring(index + 2, minOf(index + 6, value.length))

                        hex.toIntOrNull(16)?.let { result.append(it.toChar()) }

                        index += 4
                    }

                    else -> result.append(next)
                }

                index += 2
            }

            return result.toString()
        }

        private fun isInside(file: File, directory: File): Boolean = try {
            file.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())
        } catch (_: Exception) {
            false
        }
    }
}
