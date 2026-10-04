package com.panomc.platform.node

import com.panomc.platform.server.plugins.dto.PluginVersionData
import com.panomc.platform.server.plugins.dto.PluginVersionFileData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ManagedPluginDependencyResolverTest {
    private fun version(
        id: String,
        gameVersions: List<String>,
        channel: String = "release",
        loaders: List<String> = listOf("fabric"),
        files: List<PluginVersionFileData> = listOf(file("$id.jar", primary = true))
    ) = PluginVersionData(
        id = id,
        name = id,
        versionNumber = id,
        gameVersions = gameVersions,
        loaders = loaders,
        publishedAt = null,
        channel = channel,
        compatible = true,
        files = files
    )

    private fun file(name: String, primary: Boolean = false, url: String? = "https://cdn.modrinth.com/$name") =
        PluginVersionFileData(url = url, filename = name, size = 1, sha512 = "sha512-$name", sha1 = "sha1-$name", primary = primary)

    private val fabric = listOf("fabric")

    @Test
    fun `takes the newest release built for exactly this Minecraft version`() {
        val versions = listOf(
            version("0.147.0+26.2", listOf("26.2")),
            version("0.146.2+26.1.2-beta", listOf("26.1.2"), channel = "beta"),
            version("0.146.1+26.1.2", listOf("26.1.2")),
            version("0.146.0+26.1.2", listOf("26.1.2")),
            version("0.145.0+26.1", listOf("26.1"))
        )

        val (picked, file) = ManagedPluginDependencyResolver.pick(versions, "26.1.2", fabric)!!

        assertEquals("0.146.1+26.1.2", picked.id)
        assertEquals("0.146.1+26.1.2.jar", file.filename)
    }

    @Test
    fun `falls back to a pre-release when there is no release for the version`() {
        val versions = listOf(version("0.150.0+26.3-beta", listOf("26.3"), channel = "beta"))

        assertEquals("0.150.0+26.3-beta", ManagedPluginDependencyResolver.pick(versions, "26.3", fabric)!!.first.id)
    }

    @Test
    fun `never takes a build for another release of the same line`() {
        val versions = listOf(version("0.145.0+26.1", listOf("26.1")), version("0.147.0+26.2", listOf("26.2")))

        assertNull(ManagedPluginDependencyResolver.pick(versions, "26.1.2", fabric))
    }

    @Test
    fun `wants a loader it runs on and a file it can download`() {
        assertNull(ManagedPluginDependencyResolver.pick(listOf(version("a", listOf("26.1.2"), loaders = listOf("forge"))), "26.1.2", fabric))
        assertNull(
            ManagedPluginDependencyResolver.pick(
                listOf(version("b", listOf("26.1.2"), files = listOf(file("b.jar", primary = true, url = null)))),
                "26.1.2",
                fabric
            )
        )
        assertEquals("c", ManagedPluginDependencyResolver.pick(listOf(version("c", listOf("26.1.2"))), "26.1.2", listOf("quilt", "fabric"))!!.first.id)
    }

    @Test
    fun `prefers the primary file over sources and javadoc jars`() {
        val versions = listOf(
            version("d", listOf("26.1.2"), files = listOf(file("d-sources.jar"), file("d.jar", primary = true)))
        )

        assertEquals("d.jar", ManagedPluginDependencyResolver.pick(versions, "26.1.2", fabric)!!.second.filename)
    }

    @Test
    fun `an unknown game version resolves nothing`() {
        assertNull(ManagedPluginDependencyResolver.pick(listOf(version("e", listOf("26.1.2"))), null, fabric))
        assertNull(ManagedPluginDependencyResolver.pick(listOf(version("e", listOf("26.1.2"))), " ", fabric))
    }
}
