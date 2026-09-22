package com.panomc.node

import com.panomc.node.net.PlatformUrls
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlatformUrlsTest {
    @Test
    fun `joins a path Pano serves onto the address this node reaches it on`() {
        assertEquals(
            "http://127.0.0.1:18088/api/node/plugin-jars/spigot",
            PlatformUrls.resolve("/api/node/plugin-jars/spigot", "http://127.0.0.1:18088")
        )
    }

    @Test
    fun `keeps a non-default port, which is the whole point of a tunnelled Pano`() {
        assertEquals(
            "http://127.0.0.1:18088/api/node/pano-node.jar",
            PlatformUrls.resolve("/api/node/pano-node.jar", "http://127.0.0.1:18088/")
        )
    }

    @Test
    fun `leaves a third-party download exactly as it arrived`() {
        val paper = "https://api.papermc.io/v2/projects/paper/versions/1.21.8/builds/12/downloads/paper.jar"

        assertEquals(paper, PlatformUrls.resolve(paper, "https://panomc.com"))
        assertEquals(
            "http://cdn.modrinth.com/data/abc/versions/1/mod.jar",
            PlatformUrls.resolve("http://cdn.modrinth.com/data/abc/versions/1/mod.jar", "https://panomc.com")
        )
    }

    @Test
    fun `does not mistake a protocol-relative url for a path of ours`() {
        assertEquals("//evil.example/x.jar", PlatformUrls.resolve("//evil.example/x.jar", "https://panomc.com"))
    }

    @Test
    fun `gives a scheme to a platform url that was written without one`() {
        assertEquals(
            "http://10.0.0.5:8080/api/node/plugin-jars/velocity",
            PlatformUrls.resolve("/api/node/plugin-jars/velocity", "10.0.0.5:8080")
        )
    }

    @Test
    fun `drops the website's trailing slash rather than doubling it`() {
        assertEquals(
            "https://panomc.com/api/node/install.sh",
            PlatformUrls.resolve("/api/node/install.sh", "https://panomc.com/")
        )
    }

    @Test
    fun `nothing in, nothing out`() {
        assertNull(PlatformUrls.resolve(null, "https://panomc.com"))
        assertNull(PlatformUrls.resolve("   ", "https://panomc.com"))
    }

    @Test
    fun `a path with no platform url to join it to is left alone rather than mangled`() {
        assertEquals("/api/node/pano-node.jar", PlatformUrls.resolve("/api/node/pano-node.jar", ""))
    }
}
