package com.panomc.platform.node

import com.panomc.platform.Main
import com.panomc.platform.config.ConfigManager
import io.vertx.core.Vertx
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
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile
import java.util.zip.ZipInputStream

/**
 * Keeps the `pano-node.jar` next to Pano the one this Pano was built with.
 *
 * That file exists for exactly one reason: `java -jar` cannot start a classpath entry, so the local
 * node needs the daemon as a file. Everything else -- what nodes and Pano Agents download, the
 * checksum, the "update available" -- is served from the bundled copy directly ([NodeJarProvider])
 * and never looks at this file.
 *
 * The daemon ships inside the Pano jar, as the `pano-node.zip` resource the build drops in the same
 * way it does `pano-updater.zip`. So once at boot, and again whenever the local node is started, the
 * bundled daemon is unpacked next to Pano when there is none there or the one there carries another
 * version in its manifest (the `VERSION` attribute the build writes, the same one [Main.VERSION]
 * comes from). Never over a jar an operator chose (`local-node.jar-path`, `-Dpano.node.jar`) or one
 * a checkout just built.
 *
 * A development build has no version to compare, so it unpacks every time: what is inside the jar
 * that is running is what runs as the node, with no stale copy from a previous build in the way.
 * That is cheap -- one file write -- and the replace is a rename, so a daemon already running from
 * the old file keeps its inode and only the next start reads the new one.
 *
 * Bundling replaced a download from the GitHub release, which left an install that could not reach
 * github.com with no daemon at all and one that updated itself serving the old jar to every node.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class NodeJarSync(
    private val vertx: Vertx,
    private val logger: Logger,
    private val configManager: ConfigManager,
    private val nodeJarProvider: NodeJarProvider
) {
    private val mutex = Mutex()

    /**
     * Starts [ensureCurrent] without waiting for it, and warms the served checksum while at it;
     * boot must not wait on a file write or a hash.
     */
    fun syncInBackground() {
        CoroutineScope(vertx.dispatcher()).launch {
            try {
                nodeJarProvider.describe()
            } catch (exception: Exception) {
                logger.warn("Could not read the bundled ${LocalNodeJarLocator.JAR_NAME}: ${exception.message}")
            }

            try {
                ensureCurrent()
            } catch (exception: Exception) {
                logger.warn("Could not unpack ${LocalNodeJarLocator.JAR_NAME} from Pano ${Main.VERSION}: ${exception.message}")
            }
        }
    }

    /** The daemon jar on disk, where [LocalNodeJarLocator] says to look, or null when there is none yet. */
    fun locate(): File? = LocalNodeJarLocator.locate(
        configuredPath = configManager.config.effectiveLocalNode.jarPath,
        systemProperty = System.getProperty(LocalNodeJarLocator.JAR_PROPERTY),
        workingDir = File("").absoluteFile,
        runningJarDir = runningJarDirectory()
    )

    /**
     * The daemon jar the local node should run, unpacked from the bundled copy first when the
     * install has none or one from another version.
     *
     * @throws IllegalStateException when the Pano jar carries no daemon to unpack.
     */
    suspend fun ensureCurrent(): File = mutex.withLock {
        val found = locate()
        val config = configManager.config.effectiveLocalNode
        val workingDir = File("").absoluteFile

        val chosenByOperator = found != null && NodeJarSyncPlan.isOperatorChoice(
            jar = found,
            configuredPath = config.jarPath,
            systemProperty = System.getProperty(LocalNodeJarLocator.JAR_PROPERTY),
            devJar = File(workingDir, LocalNodeJarLocator.DEV_RELATIVE_PATH).absoluteFile
        )

        val jarVersion = found?.let { withContext(Dispatchers.IO) { versionOf(it) } }

        if (found != null && !NodeJarSyncPlan.needsUnpack(Main.VERSION, chosenByOperator, jarVersion)) {
            return@withLock found
        }

        val target = found?.takeUnless { chosenByOperator } ?: File(workingDir, LocalNodeJarLocator.JAR_NAME).absoluteFile

        if (found != null && jarVersion != Main.VERSION) {
            logger.info("${found.absolutePath} is from ${jarVersion ?: "an unknown version"}; replacing it with the daemon bundled in Pano ${Main.VERSION}.")
        }

        withContext(Dispatchers.IO) { NodeJarBundle.unpack(target) }

        logger.info("Unpacked ${target.absolutePath} from Pano ${Main.VERSION}.")

        target
    }

    /** Where the running Pano jar lives, which is where a release install keeps the daemon. */
    private fun runningJarDirectory(): File? = try {
        val location = Main::class.java.protectionDomain?.codeSource?.location

        val file = location?.let { File(it.toURI()) }

        if (file != null && file.isFile) file.parentFile else null
    } catch (_: Exception) {
        null
    }

    /** The `VERSION` a daemon jar's manifest carries, or null when it cannot be read. */
    private fun versionOf(jar: File): String? = try {
        JarFile(jar).use { it.manifest?.mainAttributes?.getValue("VERSION") }
    } catch (_: Exception) {
        null
    }
}

/** The `pano-node.zip` resource the build bundles into the Pano jar, and how it is read. */
object NodeJarBundle {
    /** The resource's name on the classpath; `:Node:copyNodeZip` puts it there. */
    const val RESOURCE = "pano-node.zip"

    /**
     * Whether this Pano carries a daemon at all. Every build task bundles one, so this is only
     * false for a jar somebody assembled by hand; answered once, the classpath does not change.
     */
    val isBundled: Boolean by lazy { NodeJarBundle::class.java.classLoader.getResource(RESOURCE) != null }

    /** The bundled zip, or null on a build that has none (which no build task produces). */
    fun open(): InputStream? =
        Thread.currentThread().contextClassLoader?.getResourceAsStream(RESOURCE)
            ?: NodeJarBundle::class.java.classLoader.getResourceAsStream(RESOURCE)

    /**
     * The jar inside [zip], as a stream that yields exactly its bytes and ends where it ends, so
     * it can be hashed, unpacked or written to a response without ever landing in memory whole.
     * Null when there is no zip or no `pano-node.jar` in it. Closing it closes the zip.
     */
    fun openJar(zip: InputStream? = open()): InputStream? {
        val entries = ZipInputStream(BufferedInputStream(zip ?: return null))

        var entry = entries.nextEntry

        while (entry != null) {
            if (!entry.isDirectory && entry.name == LocalNodeJarLocator.JAR_NAME) {
                return entries
            }

            entries.closeEntry()
            entry = entries.nextEntry
        }

        entries.close()

        return null
    }

    /**
     * Writes the bundled jar to [target].
     *
     * Written next to it first and moved over it only once it is complete, so a crash halfway never
     * leaves a truncated jar where a working one was. The move is a rename, so a daemon running from
     * the old file keeps its inode and the next start reads the new one; where that is not possible,
     * a plain replace.
     *
     * @throws IllegalStateException when there is no zip or it has no `pano-node.jar` in it.
     */
    fun unpack(target: File, zip: InputStream? = open()): File {
        if (zip == null) {
            throw IllegalStateException(
                "This Pano jar bundles no $RESOURCE, so ${LocalNodeJarLocator.JAR_NAME} could not be unpacked. " +
                    "Build it with \"./gradlew :Node:build\" or set local-node.jar-path in config.conf."
            )
        }

        val jar = openJar(zip) ?: throw IllegalStateException("${LocalNodeJarLocator.JAR_NAME} not found inside $RESOURCE")

        val part = File(target.absoluteFile.parentFile, "${target.name}.part")

        target.absoluteFile.parentFile?.mkdirs()

        try {
            jar.use { stream -> part.outputStream().buffered().use { stream.copyTo(it) } }
        } catch (exception: Exception) {
            part.delete()

            throw exception
        }

        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        return target
    }
}

/** The decisions [NodeJarSync] makes, pure so they can be tested without a jar to unpack. */
object NodeJarSyncPlan {
    /** What a build made without `-Pversion` calls itself; its jars cannot be told apart by version. */
    private const val DEV_VERSION = "local-build"

    /** Whether [platformVersion] names a release, whose jars carry a version worth comparing. */
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
     * Whether the jar that was found has to be replaced by the bundled copy: only for a jar nobody
     * chose, and then when its version is not this Pano's -- or when this Pano is a development
     * build, whose jars all say `local-build` and so can only be kept current by unpacking again.
     */
    fun needsUnpack(platformVersion: String, chosenByOperator: Boolean, jarVersion: String?): Boolean =
        !chosenByOperator && (!isReleaseBuild(platformVersion) || jarVersion != platformVersion)
}
