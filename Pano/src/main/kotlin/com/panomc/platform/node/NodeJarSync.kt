package com.panomc.platform.node

import com.panomc.platform.AppConstants
import com.panomc.platform.Main
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.util.HashUtil
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile

/**
 * Keeps the `pano-node.jar` a release install serves and runs matching the Pano it belongs to.
 *
 * The daemon and the platform ship from one release and speak a versioned protocol, and Pano hands
 * its own copy to every node and Pano Agent (`GET /api/node/pano-node.jar`, the self-update of both).
 * A release install gets that copy from the GitHub release on first use, but nothing replaced it when
 * Pano updated itself: the old jar stayed next to the new Pano, was served as "the current daemon",
 * matched every node's checksum, and so no node or agent ever updated again. An install that never
 * set up a local node had no jar at all, which made the same routes answer 404 and left the Update
 * buttons with nothing to hand out.
 *
 * So once at boot, and again whenever the local node is started, the jar is checked against
 * [Main.VERSION] (the `VERSION` attribute the build writes into the daemon's manifest) and the
 * release's copy is downloaded over it when it is missing or from another version. Never over a jar
 * an operator chose (`local-node.jar-path`, `-Dpano.node.jar`) or one a checkout just built, and
 * never on a development build, which has no release to download.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class NodeJarSync(
    private val vertx: Vertx,
    private val logger: Logger,
    private val configManager: ConfigManager,
    private val nodeJarProvider: NodeJarProvider,
    private val webClient: WebClient
) {
    private val mutex = Mutex()

    /** Starts [ensureCurrent] without waiting for it; boot must not hang on GitHub. */
    fun syncInBackground() {
        if (!NodeJarSyncPlan.isReleaseBuild(Main.VERSION)) {
            return
        }

        CoroutineScope(vertx.dispatcher()).launch {
            try {
                ensureCurrent()
            } catch (exception: Exception) {
                logger.warn("Could not bring ${LocalNodeJarLocator.JAR_NAME} up to Pano ${Main.VERSION}: ${exception.message}")
            }
        }
    }

    /**
     * The daemon jar this Pano should serve and run, downloading the matching release's copy first
     * when a release install has none or one from another version.
     *
     * @throws IllegalStateException when there is no jar and none could be downloaded.
     */
    suspend fun ensureCurrent(): File = mutex.withLock {
        val found = nodeJarProvider.locate()
        val config = configManager.config.effectiveLocalNode
        val workingDir = File("").absoluteFile

        val chosenByOperator = found != null && NodeJarSyncPlan.isOperatorChoice(
            jar = found,
            configuredPath = config.jarPath,
            systemProperty = System.getProperty(LocalNodeJarLocator.JAR_PROPERTY),
            devJar = File(workingDir, LocalNodeJarLocator.DEV_RELATIVE_PATH).absoluteFile
        )

        val jarVersion = found?.let { withContext(Dispatchers.IO) { versionOf(it) } }

        if (found != null && !NodeJarSyncPlan.needsDownload(Main.VERSION, chosenByOperator, jarVersion)) {
            return@withLock found
        }

        val target = found?.takeUnless { chosenByOperator } ?: File(workingDir, LocalNodeJarLocator.JAR_NAME).absoluteFile

        if (found != null) {
            logger.info("${found.absolutePath} is from ${jarVersion ?: "an unknown version"}; fetching the one for Pano ${Main.VERSION}.")
        }

        download(target)
    }

    /**
     * Fetches `pano-node.jar` from the GitHub release this Pano was built from into [target].
     *
     * Written next to it first and moved over it only once the published checksum matches, so a
     * failed or tampered download never replaces a working jar. A release without a checksum is
     * installed with a warning rather than refused; there is no other signature to check.
     */
    private suspend fun download(target: File): File {
        val release = fetchRelease() ?: throw IllegalStateException(
            "No release found for Pano ${Main.VERSION}, so ${LocalNodeJarLocator.JAR_NAME} could not be downloaded. " +
                "Build it with \"./gradlew :Node:build\" or set local-node.jar-path in config.conf."
        )

        val assets = release.getJsonArray("assets")?.map { it as JsonObject } ?: emptyList()

        val asset = assets.firstOrNull { it.getString("name") == LocalNodeJarLocator.JAR_NAME }
            ?: throw IllegalStateException("Release ${release.getString("tag_name")} has no ${LocalNodeJarLocator.JAR_NAME}.")

        val part = File(target.parentFile, "${target.name}.part")

        val response = webClient
            .getAbs(asset.getString("browser_download_url"))
            .followRedirects(true)
            .timeout(LocalNodeManager.DOWNLOAD_TIMEOUT_MILLIS)
            .send()
            .coAwait()

        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Downloading ${LocalNodeJarLocator.JAR_NAME} failed with HTTP ${response.statusCode()}.")
        }

        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            part.writeBytes(response.body().bytes)
        }

        val checksum = assets.firstOrNull { it.getString("name") == "${LocalNodeJarLocator.JAR_NAME}.sha256" }

        if (checksum == null) {
            logger.warn(
                "Release ${release.getString("tag_name")} publishes no ${LocalNodeJarLocator.JAR_NAME}.sha256; " +
                    "the downloaded daemon could not be verified."
            )
        } else {
            val expected = webClient
                .getAbs(checksum.getString("browser_download_url"))
                .followRedirects(true)
                .timeout(LocalNodeManager.DOWNLOAD_TIMEOUT_MILLIS)
                .send()
                .coAwait()
                .bodyAsString()
                ?.trim()
                ?.substringBefore(' ')

            if (expected.isNullOrBlank() || !HashUtil.verifyFileHash(part, expected)) {
                part.delete()

                throw IllegalStateException("The downloaded ${LocalNodeJarLocator.JAR_NAME} does not match its published checksum.")
            }
        }

        // A rename, so a daemon running from the old file keeps its inode and the next start
        // reads the new one; where that is not possible, a plain replace.
        withContext(Dispatchers.IO) {
            try {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        logger.info("Downloaded and verified ${target.absolutePath} for Pano ${Main.VERSION}.")

        return target
    }

    private suspend fun fetchRelease(): JsonObject? {
        val version = Main.VERSION

        listOf("v$version", version).forEach { tag ->
            val response = webClient
                .getAbs("https://api.github.com/repos/${AppConstants.REPO}/releases/tags/$tag")
                .timeout(LocalNodeManager.DOWNLOAD_TIMEOUT_MILLIS)
                .send()
                .coAwait()

            if (response.statusCode() == 200) {
                return response.bodyAsJsonObject()
            }
        }

        return null
    }

    /** The `VERSION` a daemon jar's manifest carries, or null when it cannot be read. */
    private fun versionOf(jar: File): String? = try {
        JarFile(jar).use { it.manifest?.mainAttributes?.getValue("VERSION") }
    } catch (_: Exception) {
        null
    }
}

/** The decisions [NodeJarSync] makes, pure so they can be tested without a release to download. */
object NodeJarSyncPlan {
    /** What a build made without `-Pversion` calls itself; there is no release behind it. */
    private const val DEV_VERSION = "local-build"

    /** Whether [platformVersion] names a release that has a daemon jar to download. */
    fun isReleaseBuild(platformVersion: String): Boolean =
        platformVersion.isNotBlank() && platformVersion != DEV_VERSION

    /**
     * Whether [jar] is one somebody picked on purpose — `local-node.jar-path`, `-Dpano.node.jar`, or
     * the jar a checkout's Gradle build just made — and so is never replaced.
     */
    fun isOperatorChoice(jar: File, configuredPath: String?, systemProperty: String?, devJar: File): Boolean {
        val path = jar.absoluteFile.normalize()

        return listOfNotNull(
            configuredPath?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) },
            systemProperty?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) },
            devJar
        ).any { it.absoluteFile.normalize() == path }
    }

    /**
     * Whether the jar that was found has to be replaced by the release's copy: only on a release
     * build, only for a jar nobody chose, and only when its version is not this Pano's.
     */
    fun needsDownload(platformVersion: String, chosenByOperator: Boolean, jarVersion: String?): Boolean =
        isReleaseBuild(platformVersion) && !chosenByOperator && jarVersion != platformVersion
}
