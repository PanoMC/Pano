package com.panomc.node

import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.java.JavaPackage
import com.panomc.node.java.JavaPackageResolver
import com.panomc.node.java.JavaRuntimeInstaller
import com.panomc.node.java.JavaTarget
import com.panomc.node.java.JavaUnavailableException
import com.panomc.node.java.ManagedRuntimeMarker
import com.panomc.node.util.NodeLogger
import com.panomc.node.util.Sha256
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The managed Java installer (SM-63, §2.4.28), end to end but offline: the resolver is answered
 * with a Temurin-shaped fixture pointing at an archive built here, the download is a file copy,
 * and the `java -version` check is replaced, because `:Node:test` starts no processes.
 *
 * Major 42 throughout, so nothing on the machine running the tests can be mistaken for the
 * runtime under test.
 */
class JavaRuntimeInstallerTest {
    @TempDir
    lateinit var dataDir: File

    @TempDir
    lateinit var workDir: File

    private val logger = NodeLogger("test", PrintStream(ByteArrayOutputStream()))

    private val target = JavaTarget("linux", "x64")

    /** A JRE-shaped tarball: one top-level directory, `bin/java`, `lib/jspawnhelper`, `release`. */
    private fun jreTarGz(version: String = "42.0.1", name: String = "runtime-$version.tar.gz"): File =
        TarBuilder()
            .directory("jdk-$version-jre/")
            .file("jdk-$version-jre/bin/java", "#!/bin/sh\necho fake\n", mode = 0b111_101_101)
            .file("jdk-$version-jre/lib/jspawnhelper", "helper", mode = 0b110_100_100)
            .file("jdk-$version-jre/release", "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"$version\"\n")
            .symlink("jdk-$version-jre/lib/java", "../bin/java")
            .writeTarGz(File(workDir, name))

    private fun temurinJson(archive: File, version: String, sha256: String = Sha256.of(archive)) = """
        [{"binary":{"package":{"link":"https://example.invalid/${archive.name}","checksum":"$sha256",
        "name":"${archive.name}","size":${archive.length()}}},
        "version":{"openjdk_version":"$version-LTS"}}]
    """.trimIndent()

    private fun resolverServing(json: () -> String) = JavaPackageResolver(target, { url ->
        if (url.contains("adoptium")) {
            JavaPackageResolver.Http.Response(200, json())
        } else {
            JavaPackageResolver.Http.Response(200, "[]")
        }
    })

    private fun installer(
        resolver: JavaPackageResolver,
        source: (JavaPackage) -> File,
        fetches: AtomicInteger = AtomicInteger(),
        beforeCopy: () -> Unit = {}
    ) = JavaRuntimeInstaller(
        dataDir,
        resolver,
        JavaRuntimeLocator(dataDir),
        logger,
        fetch = { pkg, file, onFraction ->
            fetches.incrementAndGet()
            beforeCopy()
            source(pkg).copyTo(file, overwrite = true)
            onFraction(1.0)
        },
        sanityCheck = {}
    )

    @Test
    fun `installs a runtime the locator then finds as managed`() {
        val archive = jreTarGz()
        val installer = installer(resolverServing { temurinJson(archive, "42.0.1+3") }, { archive })

        val progress = mutableListOf<String>()

        val result = installer.install(42) { _, message -> progress.add(message) }

        assertFalse(result.alreadyCurrent)
        assertEquals(42, result.runtime.major)
        assertEquals("42.0.1+3", result.runtime.version)
        assertTrue(result.runtime.managed)

        val home = File(dataDir, "java/temurin-42.0.1+3")

        assertEquals(home.canonicalPath, result.runtime.path)
        assertTrue(File(home, "bin/java").isFile)
        assertTrue(File(home, "bin/java").canExecute())
        assertTrue(File(home, "lib/jspawnhelper").canExecute(), "jspawnhelper must be made executable")

        val marker = File(home, ManagedRuntimeMarker.FILE).readText()

        assertTrue(marker.contains("\"vendor\": \"temurin\""))
        assertTrue(marker.contains("\"sha256\": \"${Sha256.of(archive)}\""))

        val found = JavaRuntimeLocator(dataDir).discover().single { it.major == 42 }

        assertTrue(found.managed)
        assertEquals("42.0.1+3", found.version)

        // Nothing left behind: no archive in the cache, no temporary directory.
        assertTrue(File(dataDir, "cache/java").listFiles().orEmpty().isEmpty())
        assertTrue(File(dataDir, "java").listFiles().orEmpty().none { it.name.startsWith(".") })

        assertTrue(progress.any { it.startsWith("Downloading Java 42 (Temurin 42.0.1+3)") })
    }

    @Test
    fun `a checksum mismatch deletes the download and installs nothing`() {
        val archive = jreTarGz()
        val installer = installer(resolverServing { temurinJson(archive, "42.0.1+3", sha256 = "0".repeat(64)) }, { archive })

        val failure = assertThrows(IllegalStateException::class.java) { installer.install(42) }

        assertTrue(failure.message!!.contains("SHA-256"))
        assertTrue(File(dataDir, "cache/java").listFiles().orEmpty().isEmpty())
        assertNull(JavaRuntimeLocator(dataDir).discover().firstOrNull { it.major == 42 })
        assertTrue(File(dataDir, "java").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a failed sanity check installs nothing`() {
        val archive = jreTarGz()

        val installer = JavaRuntimeInstaller(
            dataDir,
            resolverServing { temurinJson(archive, "42.0.1+3") },
            JavaRuntimeLocator(dataDir),
            logger,
            fetch = { _, file, _ -> archive.copyTo(file, overwrite = true) },
            sanityCheck = { throw IllegalStateException("exec format error") }
        )

        assertThrows(IllegalStateException::class.java) { installer.install(42) }

        assertNull(JavaRuntimeLocator(dataDir).discover().firstOrNull { it.major == 42 })
        assertTrue(File(dataDir, "java").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a major no source builds is reported as unavailable`() {
        val installer = installer(resolverServing { "[]" }, { error("never fetched") })

        assertThrows(JavaUnavailableException::class.java) { installer.install(42) }
    }

    @Test
    fun `installing a current major again downloads nothing`() {
        val archive = jreTarGz()
        val fetches = AtomicInteger()
        val installer = installer(resolverServing { temurinJson(archive, "42.0.1+3") }, { archive }, fetches)

        installer.install(42)

        val again = installer.install(42)

        assertTrue(again.alreadyCurrent)
        assertEquals(1, fetches.get())
    }

    @Test
    fun `an update lands next to the old version and the old one is superseded`() {
        val old = jreTarGz("42.0.1")
        val new = jreTarGz("42.0.2")
        var serving = old to "42.0.1+3"

        val installer = installer(resolverServing { temurinJson(serving.first, serving.second) }, { serving.first })

        installer.install(42)

        serving = new to "42.0.2+1"

        val resolverCacheBust = installer(resolverServing { temurinJson(serving.first, serving.second) }, { serving.first })

        val updated = resolverCacheBust.install(42)

        assertFalse(updated.alreadyCurrent)
        assertEquals("42.0.2+1", updated.runtime.version)

        val locator = JavaRuntimeLocator(dataDir)

        assertEquals(2, locator.discover().count { it.major == 42 })
        assertEquals("42.0.2+1", locator.exact(42)!!.version, "the newest version wins")
        assertEquals(listOf("42.0.1+3"), resolverCacheBust.superseded().map { it.version })

        // In use: kept. Not in use: gone.
        resolverCacheBust.cleanup { path -> path.contains("42.0.1") }
        assertEquals(2, locator.discover().count { it.major == 42 })

        assertTrue(resolverCacheBust.cleanup { false })
        assertEquals(listOf("42.0.2+1"), locator.discover().filter { it.major == 42 }.map { it.version })
    }

    @Test
    fun `a second request for the same major joins the first`() {
        val archive = jreTarGz()
        val fetches = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        val installer = installer(resolverServing { temurinJson(archive, "42.0.1+3") }, { archive }, fetches) {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }

        val pool = Executors.newFixedThreadPool(2)

        val first = pool.submit<JavaRuntimeInstaller.Installed> { installer.install(42) }

        assertTrue(entered.await(10, TimeUnit.SECONDS))
        assertTrue(installer.isInstalling(42))

        val joinedProgress = mutableListOf<Int>()
        val second = pool.submit<JavaRuntimeInstaller.Installed> { installer.install(42) { percent, _ -> joinedProgress.add(percent) } }

        // Give the second caller time to find the running install before letting it finish.
        Thread.sleep(200)
        release.countDown()

        val a = first.get(10, TimeUnit.SECONDS)
        val b = second.get(10, TimeUnit.SECONDS)

        pool.shutdown()

        assertEquals(1, fetches.get(), "one download for both callers")
        assertEquals(a.runtime.path, b.runtime.path)
        assertTrue(joinedProgress.isNotEmpty(), "the joined caller hears the progress too")
        assertFalse(installer.isInstalling(42))
    }

    @Test
    fun `installs from a zip, making bin executable`() {
        val zip = File(workDir, "runtime.zip")

        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("zulu42-jre/"))
            out.closeEntry()
            out.putNextEntry(ZipEntry("zulu42-jre/bin/java"))
            out.write("#!/bin/sh\n".toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("zulu42-jre/release"))
            out.write("JAVA_VERSION=\"42.0.1\"\n".toByteArray())
            out.closeEntry()
        }

        val sha = Sha256.of(zip)

        val resolver = JavaPackageResolver(target, { url ->
            when {
                url.contains("adoptium") -> JavaPackageResolver.Http.Response(200, "[]")
                url.contains("/packages/?") -> JavaPackageResolver.Http.Response(
                    200,
                    """[{"package_uuid":"u-1","download_url":"https://example.invalid/runtime.zip","name":"runtime.zip","java_version":[42,0,1],"openjdk_build_number":9}]"""
                )

                else -> JavaPackageResolver.Http.Response(200, """{"sha256_hash":"$sha","size":${zip.length()}}""")
            }
        })

        val result = installer(resolver, { zip }).install(42)

        assertEquals("42.0.1+9", result.runtime.version)
        assertEquals(File(dataDir, "java/zulu-42.0.1+9").canonicalPath, result.runtime.path)
        assertTrue(File(result.runtime.path, "bin/java").canExecute())
    }

    @Test
    fun `a zip entry climbing out is refused`() {
        val zip = File(workDir, "evil.zip")

        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("../../evil.txt"))
            out.write("x".toByteArray())
            out.closeEntry()
        }

        val sha = Sha256.of(zip)

        val resolver = JavaPackageResolver(target, { url ->
            when {
                url.contains("adoptium") -> JavaPackageResolver.Http.Response(
                    200,
                    """[{"binary":{"package":{"link":"https://example.invalid/evil.zip","checksum":"$sha","name":"evil.zip"}},"version":{"openjdk_version":"42.0.1+1"}}]"""
                )

                else -> JavaPackageResolver.Http.Response(200, "[]")
            }
        })

        assertThrows(IllegalStateException::class.java) { installer(resolver, { zip }).install(42) }

        assertFalse(File(dataDir, "evil.txt").exists())
        assertFalse(File(dataDir.parentFile, "evil.txt").exists())
    }

    @Test
    fun `boot cleanup removes leftovers of an interrupted install`() {
        val javaRoot = File(dataDir, "java")

        File(javaRoot, ".tmp-abc123/jdk/bin").mkdirs()
        File(javaRoot, ".trash-def456/jdk").mkdirs()
        File(dataDir, "cache/java").mkdirs()
        File(dataDir, "cache/java/half.tar.gz").writeText("partial")

        val installer = installer(resolverServing { "[]" }, { error("never") })

        installer.cleanup()

        assertFalse(File(javaRoot, ".tmp-abc123").exists())
        assertFalse(File(javaRoot, ".trash-def456").exists())
        assertFalse(File(dataDir, "cache/java/half.tar.gz").exists())
    }

    @Test
    fun `the locator ignores a runtime still being extracted`() {
        val home = File(dataDir, "java/.tmp-inprogress")

        File(home, "bin").mkdirs()
        File(home, "bin/java").writeText("")
        File(home, "release").writeText("JAVA_VERSION=\"42.0.1\"\n")

        assertNull(JavaRuntimeLocator(dataDir).discover().firstOrNull { it.major == 42 })
    }

    @Test
    fun `a runtime dropped in by hand is found but not managed`() {
        val home = File(dataDir, "java/my-own-jdk")

        File(home, "bin").mkdirs()
        File(home, "bin/java").writeText("")
        File(home, "release").writeText("JAVA_VERSION=\"42.0.7\"\n")

        val found = JavaRuntimeLocator(dataDir).discover().single { it.major == 42 }

        assertFalse(found.managed)
        assertEquals("42.0.7", found.version)
    }

    @Test
    fun `directory names are sanitised`() {
        assertEquals("temurin-1.8.0_504-b01", JavaRuntimeInstaller.sanitise("temurin-1.8.0_504-b01"))
        assertEquals("zulu-21_0_8_x", JavaRuntimeInstaller.sanitise("zulu-21/0\\8 x"))
        assertNotNull(JavaRuntimeInstaller.sanitise("..."))
        assertFalse(JavaRuntimeInstaller.sanitise("../evil").contains(".."))
    }
}
