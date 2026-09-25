package com.panomc.node.java

import com.panomc.node.host.JavaRuntime
import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.net.JavaInstallMessage
import com.panomc.node.net.JavaRemoveMessage
import com.panomc.node.task.TaskSink
import com.panomc.node.util.NodeLogger
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What a server start needs from the Java downloads, so [com.panomc.node.server.ServerProcess]
 * does not have to know about tasks, sockets or vendors.
 */
interface JavaDownloads {
    /** Whether a missing runtime may be downloaded at all (`node.java-auto-download`). */
    val enabled: Boolean

    /**
     * Installs Java [major] for a start of [serverUuid], reported as its own `JAVA_INSTALL` task.
     * Blocks until it is done; returns null on success and the reason otherwise. Never throws.
     */
    fun installForStart(serverUuid: String, major: Int): String?
}

/**
 * Everything the node does with Java runtimes on Pano's behalf (SM-63, §2.4.28).
 *
 * Four jobs share this class because they share one list: `JAVA_CATALOG` describes it,
 * `JAVA_INSTALL` and `JAVA_REMOVE` change it, and every change is followed by a
 * `NODE_JAVA_RUNTIMES` so Pano's stored copy -- what the panel shows while the node is offline --
 * never lags. The install and start paths come in through [ensure] and [installForStart], which
 * are the automatic side of the same installer.
 */
class JavaRuntimeService(
    private val locator: JavaRuntimeLocator,
    private val installer: JavaRuntimeInstaller,
    private val resolver: JavaPackageResolver,
    private val tasks: TaskSink,
    /** Sends `NODE_JAVA_RUNTIMES`; a no-op while the node is not connected. */
    private val announce: (JsonObject) -> Unit,
    /** Every managed server, as the removal check and `usedBy` see it. */
    private val users: () -> List<JavaUser>,
    private val autoDownload: () -> Boolean,
    private val logger: NodeLogger
) : JavaDownloads {
    override val enabled: Boolean get() = autoDownload()

    /** How an [ensure] call ended. */
    sealed class Outcome {
        /** That major was already installed. */
        data class Present(val runtime: JavaRuntime) : Outcome()

        /** It was missing and has just been installed. */
        data class Installed(val runtime: JavaRuntime, val label: String?) : Outcome()

        /** It is missing and downloads are switched off. */
        object Disabled : Outcome()

        /** It is missing and could not be installed; [unavailable] when no source builds it here. */
        data class Failed(val message: String, val unavailable: Boolean) : Outcome()
    }

    /**
     * Makes sure exactly Java [major] is installed, downloading it when allowed.
     *
     * [onProgress] gets 0..100 of the Java install alone; the caller scales it into its own task.
     */
    fun ensure(major: Int, onProgress: (Int, String) -> Unit = { _, _ -> }): Outcome {
        locator.exact(major)?.let { return Outcome.Present(it) }

        if (!enabled) {
            return Outcome.Disabled
        }

        return try {
            val result = installer.install(major, onProgress)

            announceRuntimes()

            Outcome.Installed(result.runtime, result.pkg?.let { JavaRuntimeInstaller.describe(it) })
        } catch (exception: JavaUnavailableException) {
            Outcome.Failed(exception.message ?: "Java $major is not available for this host", unavailable = true)
        } catch (exception: Exception) {
            logger.warn("Could not install Java $major: ${exception.message}")

            Outcome.Failed(exception.message ?: exception.javaClass.simpleName, unavailable = false)
        }
    }

    override fun installForStart(serverUuid: String, major: Int): String? {
        val taskId = UUID.randomUUID().toString()

        tasks.running(taskId, serverUuid, KIND_INSTALL, 0, "Looking up Java $major")

        val outcome = tasks.watchingDownloads(taskId, serverUuid, KIND_INSTALL) {
            ensure(major) { percent, message -> tasks.running(taskId, serverUuid, KIND_INSTALL, percent, message) }
        }

        return when (outcome) {
            is Outcome.Present -> {
                tasks.done(taskId, serverUuid, KIND_INSTALL, "Java $major is already installed", runtimeExtra(outcome.runtime))

                null
            }

            is Outcome.Installed -> {
                tasks.done(
                    taskId,
                    serverUuid,
                    KIND_INSTALL,
                    "Installed Java $major" + (outcome.label?.let { " ($it)" } ?: ""),
                    runtimeExtra(outcome.runtime)
                )

                null
            }

            Outcome.Disabled -> {
                tasks.failed(taskId, serverUuid, KIND_INSTALL, "Automatic Java download is disabled")

                "automatic Java download is disabled"
            }

            is Outcome.Failed -> {
                tasks.failed(taskId, serverUuid, KIND_INSTALL, outcome.message, JsonObject().put("major", major))

                outcome.message
            }
        }
    }

    /**
     * `JAVA_INSTALL`: install or update Java [JavaInstallMessage.major] on Pano's request.
     *
     * An update lands next to the version it replaces; the old one is removed straight after when
     * nothing runs from it, and otherwise at the next boot ([JavaRuntimeInstaller.cleanup]).
     */
    fun install(message: JavaInstallMessage) {
        val taskId = message.taskId?.takeIf { it.isNotBlank() } ?: run {
            logger.warn("Ignoring a JAVA_INSTALL with no task id.")

            return
        }

        val major = message.major

        if (major == null || major !in 1..MAX_MAJOR) {
            tasks.failed(taskId, null, KIND_INSTALL, "INVALID_MAJOR")

            return
        }

        try {
            val result = installer.install(major) { percent, text ->
                tasks.running(taskId, null, KIND_INSTALL, percent, text)
            }

            val label = result.pkg?.let { JavaRuntimeInstaller.describe(it) }

            if (result.alreadyCurrent) {
                tasks.done(
                    taskId,
                    null,
                    KIND_INSTALL,
                    "Java $major is already up to date" + (result.runtime.version?.let { " ($it)" } ?: ""),
                    runtimeExtra(result.runtime).put("alreadyCurrent", true)
                )

                return
            }

            removeSuperseded(major)

            announceRuntimes()

            tasks.done(
                taskId,
                null,
                KIND_INSTALL,
                "Installed Java $major" + (label?.let { " ($it)" } ?: ""),
                runtimeExtra(result.runtime).put("alreadyCurrent", false)
            )
        } catch (exception: JavaUnavailableException) {
            tasks.failed(taskId, null, KIND_INSTALL, exception.message ?: "JAVA_UNAVAILABLE", JsonObject().put("major", major))
        } catch (exception: Exception) {
            logger.warn("Installing Java $major failed: ${exception.message}")

            tasks.failed(
                taskId,
                null,
                KIND_INSTALL,
                exception.message ?: exception.javaClass.simpleName,
                JsonObject().put("major", major)
            )
        }
    }

    /**
     * `JAVA_REMOVE`: delete the managed runtime(s) of a major, or one version of it.
     *
     * FAILED with `NOT_FOUND` when nothing of that major is installed, `NOT_MANAGED` when only
     * runtimes the node did not install are, and `JAVA_IN_USE` (plus `serverUuids`) when
     * [JavaRemoval.blockers] says a server depends on it.
     */
    fun remove(message: JavaRemoveMessage) {
        val taskId = message.taskId?.takeIf { it.isNotBlank() } ?: run {
            logger.warn("Ignoring a JAVA_REMOVE with no task id.")

            return
        }

        val major = message.major

        if (major == null || major !in 1..MAX_MAJOR) {
            tasks.failed(taskId, null, KIND_REMOVE, "INVALID_MAJOR")

            return
        }

        if (installer.isInstalling(major)) {
            tasks.failed(taskId, null, KIND_REMOVE, "JAVA_INSTALLING", JsonObject().put("major", major))

            return
        }

        val installed = locator.discover()

        val ofMajor = installed.filter { it.major == major }
            .filter { message.version.isNullOrBlank() || it.version == message.version }

        if (ofMajor.isEmpty()) {
            tasks.failed(taskId, null, KIND_REMOVE, ERROR_NOT_FOUND, JsonObject().put("major", major))

            return
        }

        val targets = ofMajor.filter { it.managed }

        if (targets.isEmpty()) {
            tasks.failed(taskId, null, KIND_REMOVE, ERROR_NOT_MANAGED, JsonObject().put("major", major))

            return
        }

        val blockers = JavaRemoval.blockers(targets, installed, users())

        if (blockers.isNotEmpty()) {
            tasks.failed(
                taskId,
                null,
                KIND_REMOVE,
                ERROR_IN_USE,
                JsonObject().put("major", major).put("serverUuids", JsonArray(blockers))
            )

            return
        }

        tasks.running(taskId, null, KIND_REMOVE, 10, "Removing Java $major")

        try {
            targets.forEach { runtime ->
                installer.remove(installer.runtimeDirectoryOf(runtime))

                logger.info("Removed Java ${runtime.version} (${runtime.path}).")
            }
        } catch (exception: Exception) {
            announceRuntimes()

            tasks.failed(taskId, null, KIND_REMOVE, exception.message ?: exception.javaClass.simpleName)

            return
        }

        announceRuntimes()

        val removed = targets.mapNotNull { it.version }.joinToString(", ")

        tasks.done(
            taskId,
            null,
            KIND_REMOVE,
            "Removed Java $major" + (removed.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""),
            JsonObject().put("major", major)
        )
    }

    /**
     * `JAVA_CATALOG`: what is installed, what can be downloaded, and what this host is.
     *
     * The lookups run in parallel under one deadline so the reply beats Pano's ten-second timeout
     * even when both vendors are slow; a failed lookup never fails the reply, it only empties
     * `downloadable` and says why in `catalogError`.
     */
    fun catalog(): JsonObject {
        val installed = locator.discover()
        val currentUsers = users()
        val target = resolver.host

        val reply = JsonObject()
            .put("ok", true)
            .put("os", target.os)
            .put("arch", target.arch)
            .put("libc", target.libc)
            .put("autoDownload", enabled)

        val runtimes = JsonArray()

        installed.forEach { runtime ->
            runtimes.add(
                runtimeJson(runtime).put("usedBy", JsonArray(JavaRemoval.usedBy(runtime, installed, currentUsers)))
            )
        }

        reply.put("runtimes", runtimes)

        val lookups = JavaPackageResolver.OFFERED_MAJORS.associateWith { major ->
            CompletableFuture.supplyAsync({ runCatching { resolver.resolve(major) } }, lookupPool)
        }

        val deadline = System.currentTimeMillis() + CATALOG_BUDGET_MILLIS

        val downloadable = JsonArray()
        val errors = ArrayList<String>()

        lookups.forEach { (major, future) ->
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1)

            val result = try {
                future.get(remaining, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                Result.failure(IllegalStateException("Java $major lookup timed out"))
            }

            result.onFailure { errors.add(it.message ?: "Java $major lookup failed") }

            result.onSuccess { pkg -> downloadable.add(downloadableJson(major, pkg, installed)) }
        }

        if (errors.isNotEmpty()) {
            reply.put("catalogError", errors.distinct().joinToString("; "))

            // All or nothing only when nothing answered at all; a partial answer is still true.
            if (downloadable.isEmpty) {
                reply.put("downloadable", JsonArray())

                return reply
            }
        }

        reply.put("downloadable", downloadable)

        return reply
    }

    /** The runtime list `NODE_HELLO` and `NODE_JAVA_RUNTIMES` both carry. */
    fun runtimesJson(): JsonArray {
        val array = JsonArray()

        locator.discover().forEach { array.add(runtimeJson(it)) }

        return array
    }

    /** Sends `NODE_JAVA_RUNTIMES` with the current list. */
    fun announceRuntimes() {
        try {
            announce(JsonObject().put("javaRuntimes", runtimesJson()))
        } catch (exception: Exception) {
            logger.warn("Could not announce the Java runtimes: ${exception.message}")
        }
    }

    /** The boot sweep, announced when it removed anything. Runs after the servers are loaded. */
    fun bootCleanup() {
        val inUse = users().filter { it.alive }.mapNotNull { it.javaHome }.map { canonical(it) }.toSet()

        try {
            if (installer.cleanup { path -> canonical(path) in inUse }) {
                announceRuntimes()
            }
        } catch (exception: Exception) {
            logger.warn("Cleaning up Java runtimes failed: ${exception.message}")
        }
    }

    private fun removeSuperseded(major: Int) {
        val inUse = users().filter { it.alive }.mapNotNull { it.javaHome }.map { canonical(it) }.toSet()

        installer.superseded()
            .filter { it.major == major && canonical(it.path) !in inUse }
            .forEach { old ->
                try {
                    installer.remove(installer.runtimeDirectoryOf(old))

                    logger.info("Removed Java ${old.version}; Java ${old.major} was updated.")
                } catch (exception: Exception) {
                    logger.warn("Could not remove the superseded Java ${old.version}: ${exception.message}")
                }
            }
    }

    private fun downloadableJson(major: Int, pkg: JavaPackage?, installed: List<JavaRuntime>): JsonObject {
        val ofMajor = installed.filter { it.major == major }
        val newestInstalled = ofMajor.maxWithOrNull(compareBy(JavaVersionOrder) { it.version })
        val newestManaged = ofMajor.filter { it.managed }.maxWithOrNull(compareBy(JavaVersionOrder) { it.version })

        val entry = JsonObject()
            .put("major", major)
            .put("available", pkg != null)

        if (pkg != null) {
            entry.put("vendor", pkg.vendor)
                .put("version", pkg.version)
                .put("size", pkg.size)
        }

        entry.put("installedVersion", (newestManaged ?: newestInstalled)?.version)
            .put(
                "updateAvailable",
                pkg != null && newestManaged != null && JavaVersionOrder.isNewer(pkg.version, newestManaged.version)
            )

        return entry
    }

    private fun runtimeJson(runtime: JavaRuntime): JsonObject = JsonObject()
        .put("major", runtime.major)
        .put("version", runtime.version)
        .put("vendor", runtime.vendor)
        .put("path", runtime.path)
        .put("managed", runtime.managed)

    private fun runtimeExtra(runtime: JavaRuntime): JsonObject = JsonObject()
        .put("major", runtime.major)
        .put("version", runtime.version)
        .put("vendor", runtime.vendor)
        .put("path", runtime.path)

    private fun canonical(path: String): String = try {
        java.io.File(path).canonicalPath
    } catch (_: Exception) {
        path
    }

    companion object {
        const val KIND_INSTALL = "JAVA_INSTALL"
        const val KIND_REMOVE = "JAVA_REMOVE"

        const val ERROR_IN_USE = "JAVA_IN_USE"
        const val ERROR_NOT_MANAGED = "NOT_MANAGED"
        const val ERROR_NOT_FOUND = "NOT_FOUND"

        private const val MAX_MAJOR = 99

        /** Under Pano's ten-second catalog timeout, with room for the reply to travel. */
        private const val CATALOG_BUDGET_MILLIS = 8_000L

        private val lookupPool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "pano-node-java-lookup").apply { isDaemon = true }
        }
    }
}
