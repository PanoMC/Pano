package com.panomc.platform.config

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.panomc.platform.api.config.ConfigComment
import com.panomc.platform.api.config.ConfigSection
import com.panomc.platform.api.config.PluginConfig
import com.typesafe.config.ConfigFactory
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [HoconWriter] using small annotated fixtures. We avoid the real [PanoConfig]
 * because its defaults touch `Main.STAGE`/manifest globals that aren't initialized in unit tests.
 */
class HoconWriterTest {

    private data class DbFixture(
        @ConfigComment("\"mariadb\" or \"portable\".")
        val type: String = "mariadb",
        val host: String = "",
        @ConfigComment("Table prefix (do not change after installation).")
        val prefix: String = "pano_"
    )

    private data class RootFixture(
        @ConfigComment("Configuration version used for migrations (DO NOT manually change).")
        @SerializedName("config-version") val version: Int = 1,

        @ConfigComment("Website display name.")
        @SerializedName("website-name") val websiteName: String = "",

        @SerializedName("keywords") val keywords: List<String> = emptyList(),

        @ConfigSection("Database")
        @ConfigComment("Warning: changing type or prefix after installation requires a reinstall.")
        val database: DbFixture = DbFixture()
    )

    private val gson = Gson()

    private fun toJson(value: Any): JsonObject = JsonObject(gson.toJson(value))

    @Test
    fun `emits comments above annotated fields`() {
        val json = toJson(RootFixture(version = 25, websiteName = "Antik Küp"))
        val rendered = HoconWriter.render(json, RootFixture::class.java)

        assertTrue(
            rendered.contains("# Configuration version used for migrations"),
            "scalar field comment must appear above the key"
        )
        assertTrue(
            rendered.contains("config-version = 25"),
            "scalar key/value pair must be emitted"
        )
        assertTrue(
            rendered.contains("website-name = \"Antik Küp\""),
            "string values must be quoted and unicode preserved"
        )
    }

    @Test
    fun `emits section banner before @ConfigSection field`() {
        val rendered = HoconWriter.render(toJson(RootFixture()), RootFixture::class.java)
        val expectedBanner =
            "# ============================================================\n" +
                "# Database\n" +
                "# ============================================================"
        assertTrue(
            rendered.contains(expectedBanner),
            "section banner not found in output:\n$rendered"
        )
    }

    @Test
    fun `output round-trips through ConfigFactory`() {
        val source = RootFixture(
            version = 25,
            websiteName = "Test",
            keywords = listOf("a", "b", "c"),
            database = DbFixture(type = "portable", host = "127.0.0.1:3306", prefix = "px_")
        )
        val rendered = HoconWriter.render(toJson(source), RootFixture::class.java)

        val parsed = ConfigFactory.parseString(rendered)
        assertEquals(25, parsed.getInt("config-version"))
        assertEquals("Test", parsed.getString("website-name"))
        assertEquals(listOf("a", "b", "c"), parsed.getStringList("keywords"))
        assertEquals("portable", parsed.getString("database.type"))
        assertEquals("127.0.0.1:3306", parsed.getString("database.host"))
        assertEquals("px_", parsed.getString("database.prefix"))
    }

    @Test
    fun `forward-compat keys not in schema are preserved`() {
        val json = toJson(RootFixture()).put("future-feature-flag", true)
        val rendered = HoconWriter.render(json, RootFixture::class.java)

        assertTrue(
            rendered.contains("future-feature-flag = true"),
            "unknown keys must not be dropped by the writer"
        )

        val parsed = ConfigFactory.parseString(rendered)
        assertEquals(true, parsed.getBoolean("future-feature-flag"))
    }

    @Test
    fun `bare class with no annotations produces comment-free HOCON`() {
        data class PluginCfgLocal(
            val version: Int = 1,
            val name: String = "",
            val enabled: Boolean = false
        )

        val json = JsonObject().put("version", 1).put("name", "demo").put("enabled", true)
        val rendered = HoconWriter.render(json, PluginCfgLocal::class.java)

        assertTrue(!rendered.contains("#"), "no annotations means no comments in output")
        assertTrue(rendered.contains("name = \"demo\""))
        val parsed = ConfigFactory.parseString(rendered)
        assertEquals("demo", parsed.getString("name"))
    }

    @Test
    fun `multiline strings escape control chars and round-trip cleanly`() {
        data class Wrapper(val text: String = "")
        val data = JsonObject().put("text", "line1\nline2\twith\ttab\rwindows")
        val rendered = HoconWriter.render(data, Wrapper::class.java)

        assertTrue(rendered.contains("\\n"), "newlines must be escaped")
        assertTrue(rendered.contains("\\t"), "tabs must be escaped")
        assertTrue(rendered.contains("\\r"), "carriage returns must be escaped")
        val parsed = ConfigFactory.parseString(rendered)
        assertEquals("line1\nline2\twith\ttab\rwindows", parsed.getString("text"))
    }

    private class SamplePluginConfig(
        @ConfigComment("Public URL the plugin reports to clients.")
        @SerializedName("public-url") val publicUrl: String = "",

        @ConfigSection("API Credentials")
        @ConfigComment("Issued by the dashboard.")
        @SerializedName("api-key") val apiKey: String = ""
    ) : PluginConfig()

    /**
     * Plugin configs inherit `version` from [PluginConfig], which carries a default @ConfigComment.
     * It must appear first in the rendered file (before subclass fields) and the comment must be
     * picked up via reflection on the parent class.
     */
    @Test
    fun `inherited PluginConfig version is rendered first with its default comment`() {
        val cfg = SamplePluginConfig(publicUrl = "https://example.test", apiKey = "secret")
        val json = JsonObject(gson.toJson(cfg)).put("version", 3)

        val rendered = HoconWriter.render(json, SamplePluginConfig::class.java)

        val versionIdx = rendered.indexOf("version = 3")
        val publicUrlIdx = rendered.indexOf("public-url")
        assertTrue(versionIdx >= 0, "version key must be emitted: $rendered")
        assertTrue(publicUrlIdx >= 0, "plugin field must still be emitted: $rendered")
        assertTrue(
            versionIdx < publicUrlIdx,
            "inherited version must appear before subclass fields:\n$rendered"
        )
        assertTrue(
            rendered.contains("# Plugin config version used for migrations"),
            "PluginConfig.version's default @ConfigComment must be applied: $rendered"
        )
        assertTrue(
            rendered.contains("# API Credentials"),
            "subclass @ConfigSection must still be emitted: $rendered"
        )

        val parsed = ConfigFactory.parseString(rendered)
        assertEquals(3, parsed.getInt("version"))
        assertEquals("https://example.test", parsed.getString("public-url"))
        assertEquals("secret", parsed.getString("api-key"))
    }

    @Test
    fun `non-empty list emits multi-line array, empty list inline`() {
        data class Wrapper(
            val empty: List<String> = emptyList(),
            val items: List<String> = emptyList()
        )
        val data = JsonObject().put("empty", emptyList<String>()).put("items", listOf("a", "b"))
        val rendered = HoconWriter.render(data, Wrapper::class.java)

        assertTrue(rendered.contains("empty = []"), "empty list should be inline []")
        assertTrue(rendered.contains("items = [\n"), "non-empty list should open multi-line bracket")
        val parsed = ConfigFactory.parseString(rendered)
        assertEquals(emptyList<String>(), parsed.getStringList("empty"))
        assertEquals(listOf("a", "b"), parsed.getStringList("items"))
    }
}
