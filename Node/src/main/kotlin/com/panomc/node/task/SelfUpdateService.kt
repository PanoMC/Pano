package com.panomc.node.task

import com.panomc.node.NodeVersion
import com.panomc.node.agent.AgentFiles
import com.panomc.node.net.PlatformUrls
import com.panomc.node.net.SelfUpdateMessage
import com.panomc.node.util.Downloader
import com.panomc.node.util.NodeLogger
import com.panomc.node.util.Sha256
import io.vertx.core.json.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replaces the daemon's own jar when Pano ships a newer one.
 *
 * A running JVM cannot rewrite the jar it is executing and then carry on, so the update is done in
 * two halves: the new jar is downloaded and verified while the node is up, and the swap happens on
 * the way out, after which the node exits with [NodeVersion.SELF_UPDATE_EXIT_CODE] so whatever
 * supervises it starts the new one. The staged file and a record of it survive in
 * `<data>/updates`, so an update that could not be swapped is retried on the next boot instead of
 * being downloaded again.
 *
 * The hash is not optional. This downloads an executable and then runs it as the same user, which
 * is the one place in the whole daemon where a man in the middle would get everything.
 */
class SelfUpdateService(
    private val dataDir: File,
    private val reporter: TaskReporter,
    private val platformUrls: PlatformUrls,
    private val logger: NodeLogger,
    private val onUpdateStaged: () -> Unit
) {
    private val updatesDir = File(dataDir, "updates")

    fun handle(message: SelfUpdateMessage) {
        val version = message.version
        // Pano serves the daemon from `/api/node/pano-node.jar`, which is a path rather than a URL
        // for the usual reason: the address this node reaches Pano on is a fact only this node has.
        val url = platformUrls.resolve(message.url)
        val sha256 = message.sha256

        // Pano addresses this task by version: there is no server and no task row behind a self
        // update, so the version is what the progress frames are keyed on.
        val taskId = version ?: "self-update"

        if (version.isNullOrBlank() || url.isNullOrBlank() || sha256.isNullOrBlank()) {
            logger.warn("Ignoring a SELF_UPDATE without a version, url or sha256.")

            return
        }

        // Dev builds all carry the same version string, so the version alone cannot tell a newer
        // jar from the running one: the checksum decides whenever it is known.
        val sameJar = NodeVersion.jarSha256?.equals(sha256, ignoreCase = true) ?: (version == NodeVersion.VERSION)

        if (version == NodeVersion.VERSION && sameJar) {
            logger.info("Pano offered $version, which is already running.")

            reporter.done(taskId, null, KIND, "Already up to date")

            return
        }

        try {
            updatesDir.mkdirs()

            val staged = File(updatesDir, "pano-node-$version.jar")

            reporter.running(taskId, null, KIND, 5, "Downloading $version")

            Downloader.download(url, staged) { fraction ->
                reporter.running(taskId, null, KIND, 5 + (fraction * 80).toInt(), "Downloading $version")
            }

            val actual = Sha256.of(staged)

            if (!actual.equals(sha256.trim(), ignoreCase = true)) {
                staged.delete()

                reporter.failed(taskId, null, KIND, "The downloaded jar does not match the published checksum.")

                return
            }

            if (!Downloader.isZip(staged)) {
                staged.delete()

                reporter.failed(taskId, null, KIND, "The downloaded file is not a jar.")

                return
            }

            val target = NodeVersion.jarPath()

            if (target == null) {
                reporter.failed(taskId, null, KIND, "This node was not started from a jar, so it cannot update itself.")

                return
            }

            pendingFile().writeText(
                JsonObject()
                    .put("version", version)
                    .put("file", staged.absolutePath)
                    .put("sha256", actual)
                    .put("target", target)
                    .encode()
            )

            logger.notice("Downloaded update $version; restarting to apply it.")

            reporter.done(taskId, null, KIND, "Staged $version")

            onUpdateStaged()
        } catch (exception: Exception) {
            logger.error("Self update failed: ${exception.message}", exception)

            reporter.failed(taskId, null, KIND, exception.message ?: exception.javaClass.simpleName)
        }
    }

    /**
     * Moves a staged jar over the running one, if there is one waiting.
     *
     * Called on the way out and again on the way in: on Linux and macOS the move succeeds while
     * the old JVM is still alive, and the second attempt is simply a no-op. Windows locks the jar
     * a process is running from, so there the move fails on both attempts and the operator is told
     * where the new jar is -- the alternative is a helper process, which SM-40's installer brings.
     * A Pano Agent's worker is the exception (SM-76): it runs from `.pano-agent/worker.jar`, which
     * its launcher replaces from this same record between two workers (`AgentWorkerJar`).
     */
    fun applyPending(): Boolean {
        val record = pendingFile()

        if (!record.isFile) {
            return false
        }

        val pending = try {
            JsonObject(record.readText())
        } catch (exception: Exception) {
            logger.warn("Could not read the staged update: ${exception.message}")

            record.delete()

            return false
        }

        val staged = File(pending.getString("file") ?: return false)
        val target = File(pending.getString("target") ?: return false)

        if (!staged.isFile) {
            record.delete()

            return false
        }

        if (target.isFile && Sha256.of(target).equals(pending.getString("sha256"), ignoreCase = true)) {
            record.delete()
            staged.delete()

            logger.info("Node update ${pending.getString("version")} is in place.")

            return false
        }

        return try {
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)

            record.delete()

            logger.info("Applied node update ${pending.getString("version")}.")

            true
        } catch (exception: Exception) {
            if (AgentFiles.launcherJar != null) {
                // A Pano Agent's worker runs from its own copy, which its launcher replaces from the
                // same record once this process has exited (SM-76): nothing for the admin to do.
                logger.info("Could not replace ${target.absolutePath} while it runs (${exception.message}); the agent's launcher applies it.")
            } else {
                logger.warn(
                    "Could not replace ${target.absolutePath}: ${exception.message}. " +
                        "The new jar is at ${staged.absolutePath}; copy it over manually or restart the service."
                )
            }

            false
        }
    }

    private fun pendingFile() = File(updatesDir, "pending.json")

    companion object {
        private const val KIND = "SELF_UPDATE"
    }
}
