package com.panomc.node

import com.panomc.node.java.JavaPackageResolver
import com.panomc.node.java.JavaTarget
import com.panomc.node.java.JavaVersionOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The Java download lookup (SM-63), against fixtures captured from the real APIs on 2026-09-23.
 *
 * Nothing here reaches the network: the resolver's one HTTP call is a seam, and each test answers
 * it with the fixture a real Adoptium or Azul would have sent for that URL.
 */
class JavaPackageResolverTest {
    private fun fixture(name: String): String =
        javaClass.getResource("/java/$name")!!.readText()

    /** Serves fixtures by URL prefix and records every URL asked for. */
    private class FakeHttp(private val routes: List<Pair<(String) -> Boolean, () -> JavaPackageResolver.Http.Response>>) :
        JavaPackageResolver.Http {
        val asked = mutableListOf<String>()

        override fun get(url: String): JavaPackageResolver.Http.Response {
            asked.add(url)

            val route = routes.firstOrNull { it.first(url) }
                ?: return JavaPackageResolver.Http.Response(404, "")

            return route.second()
        }
    }

    private fun ok(body: String) = { JavaPackageResolver.Http.Response(200, body) }

    private val linuxX64 = JavaTarget("linux", "x64")

    @Test
    fun `parses a Temurin answer into a package`() {
        val pkg = JavaPackageResolver.parseTemurin(fixture("temurin-21-linux-x64.json"), 21)

        assertNotNull(pkg)
        assertEquals("temurin", pkg!!.vendor)
        assertEquals(21, pkg.major)
        assertEquals("21.0.12.1+1", pkg.version)
        assertEquals("2413149700df0f7d440500a84a8f764c535f21e5a5e87d38328b64eec2c5b500", pkg.sha256)
        assertEquals(52059408L, pkg.size)
        assertEquals("tar.gz", pkg.archive)
        assertEquals("OpenJDK21U-jre_x64_linux_hotspot_21.0.12.1_1.tar.gz", pkg.fileName)
        assertTrue(pkg.url.startsWith("https://github.com/adoptium/"))
    }

    @Test
    fun `keeps Java 8's own version shape`() {
        val pkg = JavaPackageResolver.parseTemurin(fixture("temurin-8-linux-x64.json"), 8)

        assertEquals("1.8.0_504-b01", pkg!!.version)
    }

    @Test
    fun `an empty Temurin list is no package`() {
        assertNull(JavaPackageResolver.parseTemurin(fixture("temurin-empty.json"), 16))
    }

    @Test
    fun `parses a Zulu listing and its details`() {
        val listing = JavaPackageResolver.parseZuluList(fixture("zulu-16-list.json"), 16)

        assertNotNull(listing)
        assertEquals("f8268574-977d-4b40-94f8-5a8f5ba0d777", listing!!.uuid)
        assertEquals("16.0.2+7", listing.version)

        val pkg = JavaPackageResolver.parseZuluDetails(fixture("zulu-16-details.json"), listing)

        assertEquals("zulu", pkg!!.vendor)
        assertEquals(16, pkg.major)
        assertEquals("a256bcf7383957e022433d5421affceea98a032dc11763249f6944f836eadb73", pkg.sha256)
        assertEquals(49665100L, pkg.size)
        assertEquals("https://cdn.azul.com/zulu/bin/zulu16.32.15-ca-jre16.0.2-linux_x64.tar.gz", pkg.url)
    }

    @Test
    fun `Temurin wins when it has the build`() {
        val http = FakeHttp(listOf({ url: String -> url.contains("adoptium") } to ok(fixture("temurin-21-linux-x64.json"))))

        val pkg = JavaPackageResolver(linuxX64, http).resolve(21)

        assertEquals("temurin", pkg!!.vendor)
        assertTrue(http.asked.none { it.contains("azul") })
        assertTrue(http.asked.single().contains("os=linux&architecture=x64&image_type=jre&vendor=eclipse"))
    }

    @Test
    fun `a Temurin gap falls back to Zulu`() {
        val http = FakeHttp(
            listOf(
                { url: String -> url.contains("adoptium") } to ok(fixture("temurin-empty.json")),
                { url: String -> url.contains("/packages/?") } to ok(fixture("zulu-16-list.json")),
                { url: String -> url.contains("/packages/f8268574") } to ok(fixture("zulu-16-details.json"))
            )
        )

        val pkg = JavaPackageResolver(linuxX64, http).resolve(16)

        assertEquals("zulu", pkg!!.vendor)
        assertEquals("16.0.2+7", pkg.version)
        assertTrue(http.asked.any { it.contains("java_version=16&os=linux&arch=x64&java_package_type=jre&archive_type=tar.gz&javafx_bundled=false") })
    }

    @Test
    fun `a Temurin 404 is a gap, not an error`() {
        val http = FakeHttp(
            listOf(
                { url: String -> url.contains("adoptium") } to { JavaPackageResolver.Http.Response(404, "not found") },
                { url: String -> url.contains("/packages/?") } to ok("[]")
            )
        )

        assertNull(JavaPackageResolver(linuxX64, http).resolve(16))
    }

    @Test
    fun `neither source having a build is null, not an error`() {
        val http = FakeHttp(
            listOf(
                { url: String -> url.contains("adoptium") } to ok("[]"),
                { url: String -> url.contains("azul") } to ok("[]")
            )
        )

        val resolver = JavaPackageResolver(JavaTarget("windows", "arm64"), http)

        assertNull(resolver.resolve(8))
        assertTrue(http.asked.any { it.contains("os=windows&architecture=aarch64") })
        assertTrue(http.asked.any { it.contains("os=windows&arch=aarch64") && it.contains("archive_type=zip") })
    }

    @Test
    fun `a Temurin outage still answers from Zulu`() {
        val http = FakeHttp(
            listOf(
                { url: String -> url.contains("adoptium") } to { throw JavaPackageResolver.LookupFailedException("timed out") },
                { url: String -> url.contains("/packages/?") } to ok(fixture("zulu-16-list.json")),
                { url: String -> url.contains("/packages/f8268574") } to ok(fixture("zulu-16-details.json"))
            )
        )

        assertEquals("zulu", JavaPackageResolver(linuxX64, http).resolve(16)!!.vendor)
    }

    @Test
    fun `an outage with no answer throws instead of claiming there is no build`() {
        val http = FakeHttp(
            listOf(
                { url: String -> url.contains("adoptium") } to { throw JavaPackageResolver.LookupFailedException("offline") },
                { url: String -> url.contains("azul") } to ok("[]")
            )
        )

        assertThrows(JavaPackageResolver.LookupFailedException::class.java) {
            JavaPackageResolver(linuxX64, http).resolve(21)
        }
    }

    @Test
    fun `a server error is an error`() {
        val http = FakeHttp(listOf({ _: String -> true } to { JavaPackageResolver.Http.Response(503, "") }))

        assertThrows(JavaPackageResolver.LookupFailedException::class.java) {
            JavaPackageResolver(linuxX64, http).resolve(21)
        }
    }

    @Test
    fun `answers are cached for an hour and failures for a minute`() {
        var now = 0L
        var fail = true

        val http = FakeHttp(
            listOf(
                { url: String -> url.contains("adoptium") } to {
                    if (fail) throw JavaPackageResolver.LookupFailedException("offline")

                    JavaPackageResolver.Http.Response(200, fixture("temurin-21-linux-x64.json"))
                },
                { url: String -> url.contains("azul") } to { throw JavaPackageResolver.LookupFailedException("offline") }
            )
        )

        val resolver = JavaPackageResolver(linuxX64, http) { now }

        assertThrows(JavaPackageResolver.LookupFailedException::class.java) { resolver.resolve(21) }

        fail = false

        // Still inside the failure window: the cached failure, no new request.
        val askedBefore = http.asked.size

        assertThrows(JavaPackageResolver.LookupFailedException::class.java) { resolver.resolve(21) }
        assertEquals(askedBefore, http.asked.size)

        now += JavaPackageResolver.FAILURE_CACHE_MILLIS + 1

        assertNotNull(resolver.resolve(21))

        val askedAfter = http.asked.size

        now += JavaPackageResolver.CACHE_MILLIS - 10

        assertNotNull(resolver.resolve(21))
        assertEquals(askedAfter, http.asked.size)
    }

    @Test
    fun `musl hosts ask for the musl builds`() {
        val http = FakeHttp(listOf({ _: String -> true } to ok("[]")))

        JavaPackageResolver(JavaTarget("linux", "x64", musl = true), http).resolve(21)

        assertTrue(http.asked.any { it.contains("os=alpine-linux") })
        assertTrue(http.asked.any { it.contains("os=linux_musl") })
    }

    @Test
    fun `maps every platform to both vendors' spellings`() {
        val mac = JavaTarget("macos", "arm64")

        assertEquals("mac", mac.temurinOs)
        assertEquals("aarch64", mac.temurinArch)
        assertEquals("macos", mac.zuluOs)
        assertEquals("aarch64", mac.zuluArch)
        assertEquals("tar.gz", mac.archive)
        assertNull(mac.libc)

        val windows = JavaTarget("windows", "x86")

        assertEquals("windows", windows.temurinOs)
        assertEquals("x32", windows.temurinArch)
        assertEquals("i686", windows.zuluArch)
        assertEquals("zip", windows.archive)

        val arm = JavaTarget("linux", "arm32")

        assertEquals("arm", arm.temurinArch)
        assertEquals("arm", arm.zuluArch)
        assertEquals("glibc", arm.libc)

        val alpine = JavaTarget("linux", "x64", musl = true)

        assertEquals("alpine-linux", alpine.temurinOs)
        assertEquals("linux_musl", alpine.zuluOs)
        assertEquals("musl", alpine.libc)

        assertNull(JavaTarget("freebsd", "x64").temurinOs)
        assertNull(JavaTarget("linux", "riscv64").zuluArch)
    }

    @Test
    fun `detects musl from the loader or the Alpine release file`(@TempDir dir: File) {
        val lib = File(dir, "lib").apply { mkdirs() }

        assertFalse(JavaTarget.detectMusl(File(dir, "alpine-release"), listOf(lib)))

        File(lib, "ld-musl-x86_64.so.1").writeText("")

        assertTrue(JavaTarget.detectMusl(File(dir, "alpine-release"), listOf(lib)))

        File(lib, "ld-musl-x86_64.so.1").delete()
        File(dir, "alpine-release").writeText("3.20.0")

        assertTrue(JavaTarget.detectMusl(File(dir, "alpine-release"), listOf(lib)))
    }

    @Test
    fun `orders versions across vendors and eras`() {
        assertTrue(JavaVersionOrder.isNewer("21.0.12.1+1", "21.0.12+1"))
        assertTrue(JavaVersionOrder.isNewer("21.0.13+2", "21.0.12.1+1"))
        assertTrue(JavaVersionOrder.isNewer("1.8.0_504-b01", "8.0.462+8"))
        assertFalse(JavaVersionOrder.isNewer("21.0.12.1+1", "21.0.12.1+1"))
        assertTrue(JavaVersionOrder.isNewer("17.0.1", null))
        assertFalse(JavaVersionOrder.isNewer(null, "17.0.1"))
    }
}
