package com.panomc.node

import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.java.JavaPackageResolver
import com.panomc.node.java.JavaRuntimeInstaller
import com.panomc.node.java.JavaTarget
import com.panomc.node.util.NodeLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The whole managed-Java pipeline against the real Adoptium and Azul APIs (SM-63).
 *
 * Opt-in, because it downloads two JREs (~95 MB) and runs `java -version` on them, which
 * `:Node:test` otherwise never does: `PANO_NODE_LIVE_JAVA=1 ./gradlew :Node:test --tests '*JavaLiveDownloadTest*'`.
 * Java 21 comes from Temurin; Java 16, which Temurin has never built, has to come from Zulu.
 */
@EnabledIfEnvironmentVariable(named = "PANO_NODE_LIVE_JAVA", matches = "1")
class JavaLiveDownloadTest {
    @TempDir
    lateinit var dataDir: File

    private val logger = NodeLogger("live-java")

    private fun installAndCheck(major: Int, vendor: String) {
        val resolver = JavaPackageResolver()
        val locator = JavaRuntimeLocator(dataDir)
        val installer = JavaRuntimeInstaller(dataDir, resolver, locator, logger)

        val pkg = resolver.resolve(major)

        assertNotNull(pkg, "no Java $major for ${JavaTarget.current}")
        assertEquals(vendor, pkg!!.vendor)

        var lastPercent = -1

        val result = installer.install(major) { percent, message ->
            if (percent / 10 != lastPercent / 10) {
                logger.info("Java $major: $percent% $message")
            }

            lastPercent = percent
        }

        logger.info("Installed ${result.runtime}")

        val found = locator.discover().single { it.managed && it.major == major }

        assertEquals(pkg.version, found.version)
        assertTrue(found.path.startsWith(File(dataDir, "java").canonicalPath))
        assertTrue(File(found.path, "bin/java").canExecute())

        // A second request is answered from what is installed.
        assertTrue(installer.install(major).alreadyCurrent)
    }

    @Test
    fun `downloads Temurin 21 and Zulu 16 and runs them`() {
        installAndCheck(21, JavaPackageResolver.VENDOR_TEMURIN)
        installAndCheck(16, JavaPackageResolver.VENDOR_ZULU)
    }
}
