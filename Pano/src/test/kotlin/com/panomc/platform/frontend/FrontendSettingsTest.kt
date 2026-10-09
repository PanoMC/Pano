package com.panomc.platform.frontend

import com.panomc.platform.UIManager
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Error
import com.panomc.platform.ui.DescriptorHostNotAllowed
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpServer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.objenesis.ObjenesisStd
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Schema-driven front-end settings (open front-end plan, doc 05 sections 8.2 and 9): the schema rules, the check of
 * every field type, a write-and-read round trip per type through the real storage class, the file rules the
 * theme settings endpoint shares, a theme whose `core-meta.json` has `fields`, and the descriptor (host rule,
 * parse, fetch against a stub server).
 */
class FrontendSettingsTest {
    @TempDir
    lateinit var dir: File

    /** The eight types of doc 05 section 9, with tabs. */
    private val everything = """
        {
          "tabs": { "general": ["title", "about", "dark"], "look": ["columns", "layout", "accent", "link", "logo"] },
          "defaultTab": "look",
          "fields": {
            "title":   { "type": "text", "label": "Title", "default": "Pano", "min": 2, "max": 20 },
            "about":   { "type": "textarea", "label": "settings.about", "help": "Shown in the footer" },
            "dark":    { "type": "boolean", "label": "Dark mode", "default": false },
            "columns": { "type": "number", "label": "Columns", "default": 3, "min": 1, "max": 6 },
            "layout":  { "type": "select", "label": "Layout", "default": "wide",
                         "options": [ { "value": "wide", "label": "Wide" }, { "value": "narrow", "label": "Narrow" }, { "value": 2, "label": "Two" } ] },
            "accent":  { "type": "color", "label": "Accent", "default": "#ff8800" },
            "link":    { "type": "url", "label": "Link" },
            "logo":    { "type": "image", "label": "Logo" }
          }
        }
    """.trimIndent()

    private fun schema(json: String = everything) = FrontendSettingsSchema.parse(JsonObject(json))!!

    private class MemoryAccess(var text: String? = null) : PropertyAccess {
        override suspend fun read() = text

        override suspend fun write(value: String) {
            text = value
        }
    }

    private fun detail(error: Error, name: String) =
        JsonObject(error.encode(emptyMap())).getJsonObject("error").getJsonObject("details").getString(name)

    private fun code(error: Error) = JsonObject(error.encode(emptyMap())).getJsonObject("error").getString("code")

    private fun schemaProblem(json: String): FrontendSettingsSchemaInvalid =
        assertThrows { FrontendSettingsSchema.parse(JsonObject(json)) }

    private fun settingProblem(schema: FrontendSettingsSchema, request: String, uploads: List<SettingsUpload> = emptyList()): FrontendSettingInvalid =
        assertThrows { schema.prepare(null, JsonObject(request), uploads) }

    private lateinit var uploadsFolder: File

    @BeforeEach
    fun folders() {
        uploadsFolder = File(dir, "theme-settings")
    }

    // --- the schema --------------------------------------------------------------

    @Test
    fun `a schema with the eight types, tabs and a default tab parses and describes itself`() {
        val parsed = schema()

        assertEquals(listOf("title", "about", "dark", "columns", "layout", "accent", "link", "logo"), parsed.fields.keys.toList())
        assertEquals(SettingType.entries.toSet(), parsed.fields.values.map { it.type }.toSet())
        assertEquals("look", parsed.defaultTab)
        assertEquals(listOf("columns", "layout", "accent", "link", "logo"), parsed.tabs!!["look"])

        val json = parsed.toJson()

        assertEquals("look", json.getString("defaultTab"))
        assertEquals("settings.about", json.getJsonObject("fields").getJsonObject("about").getString("label"))
        assertEquals(3, json.getJsonObject("fields").getJsonObject("layout").getJsonArray("options").size())
        assertEquals(1, json.getJsonObject("fields").getJsonObject("columns").getInteger("min"))

        // what it describes parses to the same thing
        assertEquals(json, FrontendSettingsSchema.parse(json)!!.toJson())
    }

    @Test
    fun `tabs are optional when there are fields`() {
        val parsed = schema("""{ "fields": { "a": { "type": "text", "label": "A" } } }""")

        assertNull(parsed.tabs)
        assertNull(parsed.defaultTab)
    }

    @Test
    fun `a schema without fields is not an error, it is no schema`() {
        assertNull(FrontendSettingsSchema.parse(null))
        assertNull(FrontendSettingsSchema.parse(JsonArray().add("legacy")))
        assertNull(FrontendSettingsSchema.parse(JsonObject("""{ "tabs": { "general": ["a"] } }""")))
        assertNull(FrontendSettingsSchema.parse(JsonObject("""{ "tabs": {}, "fields": {} }""")))
    }

    @Test
    fun `every tab key needs a field`() {
        val problem = schemaProblem(
            """{ "tabs": { "general": ["a", "ghost"] }, "fields": { "a": { "type": "text", "label": "A" } } }"""
        )

        assertEquals("FRONTEND_SETTINGS_SCHEMA_INVALID", problem.code)
        assertEquals("ghost", detail(problem, "key"))
        assertEquals("TAB_KEY_WITHOUT_FIELD", detail(problem, "reason"))
    }

    @Test
    fun `every field needs a tab when tabs are given`() {
        val problem = schemaProblem(
            """{ "tabs": { "general": ["a"] }, "fields": { "a": { "type": "text", "label": "A" }, "b": { "type": "text", "label": "B" } } }"""
        )

        assertEquals("b", detail(problem, "key"))
        assertEquals("FIELD_WITHOUT_TAB", detail(problem, "reason"))
    }

    @Test
    fun `a key cannot sit in two tabs and the default tab must exist`() {
        val twice = schemaProblem(
            """{ "tabs": { "x": ["a"], "y": ["a"] }, "fields": { "a": { "type": "text", "label": "A" } } }"""
        )

        assertEquals("KEY_IN_TWO_TABS", detail(twice, "reason"))

        val unknown = schemaProblem(
            """{ "tabs": { "x": ["a"] }, "defaultTab": "z", "fields": { "a": { "type": "text", "label": "A" } } }"""
        )

        assertEquals("defaultTab", detail(unknown, "key"))
        assertEquals("UNKNOWN_TAB", detail(unknown, "reason"))
    }

    @Test
    fun `a broken field names its key`() {
        fun reason(field: String) = detail(schemaProblem("""{ "fields": { "f": $field } }"""), "reason")

        assertEquals("UNKNOWN_TYPE", reason("""{ "type": "slider", "label": "F" }"""))
        assertEquals("LABEL_REQUIRED", reason("""{ "type": "text" }"""))
        assertEquals("NOT_AN_OBJECT", reason("""["text"]"""))
        assertEquals("OPTIONS_REQUIRED", reason("""{ "type": "select", "label": "F" }"""))
        assertEquals("OPTION_DUPLICATE", reason("""{ "type": "select", "label": "F", "options": [ { "value": "a", "label": "A" }, { "value": "a", "label": "B" } ] }"""))
        assertEquals("OPTION_LABEL_REQUIRED", reason("""{ "type": "select", "label": "F", "options": [ { "value": "a" } ] }"""))
        assertEquals("MIN_ABOVE_MAX", reason("""{ "type": "number", "label": "F", "min": 5, "max": 1 }"""))
        assertEquals("MIN_NOT_A_NUMBER", reason("""{ "type": "number", "label": "F", "min": "5" }"""))
        assertEquals("IMAGE_HAS_NO_DEFAULT", reason("""{ "type": "image", "label": "F", "default": "x.png" }"""))
        assertEquals("REQUIRED_NOT_A_BOOLEAN", reason("""{ "type": "text", "label": "F", "required": "yes" }"""))
    }

    @Test
    fun `a default must itself be a valid value`() {
        fun reason(field: String) = detail(schemaProblem("""{ "fields": { "f": $field } }"""), "reason")

        assertEquals("DEFAULT_WRONG_TYPE", reason("""{ "type": "boolean", "label": "F", "default": "yes" }"""))
        assertEquals("DEFAULT_ABOVE_MAX", reason("""{ "type": "number", "label": "F", "default": 9, "max": 3 }"""))
        assertEquals("DEFAULT_NOT_AN_OPTION", reason("""{ "type": "select", "label": "F", "default": "c", "options": [ { "value": "a", "label": "A" } ] }"""))
        assertEquals("DEFAULT_INVALID_COLOR", reason("""{ "type": "color", "label": "F", "default": "red" }"""))
    }

    @Test
    fun `the file bookkeeping words are not field keys`() {
        assertEquals("INVALID_KEY", detail(schemaProblem("""{ "fields": { "files": { "type": "text", "label": "F" } } }"""), "reason"))
        assertEquals("INVALID_KEY", detail(schemaProblem("""{ "fields": { "remove-files": { "type": "text", "label": "F" } } }"""), "reason"))
        assertEquals("INVALID_KEY", detail(schemaProblem("""{ "fields": { "a b": { "type": "text", "label": "F" } } }"""), "reason"))
    }

    // --- values --------------------------------------------------------------------

    @Test
    fun `text and textarea check the length and the type`() {
        val parsed = schema()

        assertEquals("TOO_SHORT", settingProblem(parsed, """{ "title": "x" }""").reason)
        assertEquals("TOO_LONG", settingProblem(parsed, """{ "title": "${"x".repeat(21)}" }""").reason)
        assertEquals("WRONG_TYPE", settingProblem(parsed, """{ "title": 5 }""").reason)
        assertEquals("title", detail(settingProblem(parsed, """{ "title": 5 }"""), "key"))
        assertEquals("FRONTEND_SETTING_INVALID", code(settingProblem(parsed, """{ "about": false }""")))
        assertEquals("TOO_LONG", settingProblem(parsed, """{ "about": "${"x".repeat(FALLBACK_TEXTAREA_CAP + 1)}" }""").reason)
    }

    @Test
    fun `number, boolean, select, color and url are checked`() {
        val parsed = schema()

        assertEquals("BELOW_MIN", settingProblem(parsed, """{ "columns": 0 }""").reason)
        assertEquals("ABOVE_MAX", settingProblem(parsed, """{ "columns": 7 }""").reason)
        assertEquals("WRONG_TYPE", settingProblem(parsed, """{ "columns": "3" }""").reason)
        assertEquals("WRONG_TYPE", settingProblem(parsed, """{ "dark": "true" }""").reason)
        assertEquals("NOT_AN_OPTION", settingProblem(parsed, """{ "layout": "tiny" }""").reason)
        assertEquals("INVALID_COLOR", settingProblem(parsed, """{ "accent": "orange" }""").reason)
        assertEquals("INVALID_COLOR", settingProblem(parsed, """{ "accent": "#12345" }""").reason)
        assertEquals("INVALID_URL", settingProblem(parsed, """{ "link": "javascript:alert(1)" }""").reason)
        assertEquals("INVALID_URL", settingProblem(parsed, """{ "link": "//evil.example/x" }""").reason)
        assertEquals("INVALID_URL", settingProblem(parsed, """{ "link": "https://a b.example" }""").reason)
    }

    @Test
    fun `an unknown key is dropped, not an error`() {
        val plan = schema().prepare(null, JsonObject("""{ "title": "Hello", "nonsense": 1, "files": { "ghost": ["a.png"] } }"""))

        assertEquals(JsonObject().put("title", "Hello"), plan.settings)
    }

    @Test
    fun `required means a value, a default or a file`() {
        val required = schema(
            """{ "fields": {
                "name":  { "type": "text", "label": "N", "required": true },
                "color": { "type": "color", "label": "C", "required": true, "default": "#000" },
                "logo":  { "type": "image", "label": "L", "required": true } } }"""
        )

        assertEquals("REQUIRED", settingProblem(required, "{}").reason)
        assertEquals("name", detail(settingProblem(required, "{}"), "key"))
        assertEquals("REQUIRED", settingProblem(required, """{ "name": "" }""").reason)

        // the colour has a default, so only the name and the logo are missing
        assertEquals("logo", detail(settingProblem(required, """{ "name": "x" }"""), "key"))

        val png = File(dir, "u1").apply { writeText("png") }
        val plan = required.prepare(null, JsonObject("""{ "name": "x" }"""), listOf(SettingsUpload("logo", png.path, "logo.png", "image/png")), { "fixed.png" })

        assertEquals(JsonArray().add("fixed.png"), plan.settings.getJsonObject("files").getJsonArray("logo"))
    }

    // --- round trip per type -------------------------------------------------------------

    private fun roundTrip(schema: FrontendSettingsSchema, request: String, frontendId: String = "my-site"): SettingsReading = runBlocking {
        val access = MemoryAccess()
        val storage = FrontendSettingsStorage(uploadsFolder)

        storage.commit(access, frontendId, schema.prepare(storage.load(access, frontendId), JsonObject(request)))

        schema.read(storage.load(access, frontendId))
    }

    @Test
    fun `text survives a write and a read`() = assertEquals("Hi", roundTrip(schema(), """{ "title": "Hi" }""").settings.getString("title"))

    @Test
    fun `textarea survives a write and a read`() =
        assertEquals("line 1\nline 2", roundTrip(schema(), """{ "about": "line 1\nline 2" }""").settings.getString("about"))

    @Test
    fun `boolean survives a write and a read`() {
        assertEquals(true, roundTrip(schema(), """{ "dark": true }""").settings.getBoolean("dark"))
        assertEquals(false, roundTrip(schema(), """{ "dark": false }""").settings.getBoolean("dark"))
    }

    @Test
    fun `number survives a write and a read`() {
        assertEquals(5, roundTrip(schema(), """{ "columns": 5 }""").settings.getInteger("columns"))
        assertEquals(2.5, roundTrip(schema("""{ "fields": { "n": { "type": "number", "label": "N" } } }"""), """{ "n": 2.5 }""").settings.getDouble("n"))
    }

    @Test
    fun `select survives a write and a read, also with a number option`() {
        assertEquals("narrow", roundTrip(schema(), """{ "layout": "narrow" }""").settings.getString("layout"))
        assertEquals(2, roundTrip(schema(), """{ "layout": 2 }""").settings.getInteger("layout"))
    }

    @Test
    fun `color survives a write and a read`() {
        assertEquals("#00ff00", roundTrip(schema(), """{ "accent": "#00ff00" }""").settings.getString("accent"))
        assertEquals("#abcd", roundTrip(schema(), """{ "accent": "#abcd" }""").settings.getString("accent"))
    }

    @Test
    fun `url survives a write and a read`() {
        assertEquals("https://example.com/a?b=1", roundTrip(schema(), """{ "link": "https://example.com/a?b=1" }""").settings.getString("link"))
        assertEquals("/store", roundTrip(schema(), """{ "link": "/store" }""").settings.getString("link"))
    }

    @Test
    fun `image survives a write and a read and a removal`() = runBlocking {
        val schema = schema()
        val access = MemoryAccess()
        val storage = FrontendSettingsStorage(uploadsFolder)

        val upload = File(dir, "spooled-1").apply { writeText("PNG-1") }

        storage.commit(
            access, "my-site",
            schema.prepare(null, JsonObject("""{ "title": "Hi" }"""), listOf(SettingsUpload("logo", upload.path, "logo.png", "image/png")), { "one.png" })
        )

        assertFalse(upload.exists(), "the spooled file moved")
        assertEquals("PNG-1", File(uploadsFolder, "one.png").readText())
        assertEquals(JsonArray().add("one.png"), schema.read(storage.load(access, "my-site")).files.getJsonArray("logo"))

        // saving the form again without touching the logo keeps it
        storage.commit(access, "my-site", schema.prepare(storage.load(access, "my-site"), JsonObject("""{ "title": "Ho" }""")))

        assertTrue(File(uploadsFolder, "one.png").exists())
        assertEquals("Ho", schema.read(storage.load(access, "my-site")).settings.getString("title"))
        assertEquals(JsonArray().add("one.png"), schema.read(storage.load(access, "my-site")).files.getJsonArray("logo"))

        // replacing it deletes the old file
        val second = File(dir, "spooled-2").apply { writeText("PNG-2") }

        storage.commit(
            access, "my-site",
            schema.prepare(
                storage.load(access, "my-site"),
                JsonObject("""{ "files": { "logo": [] } }"""),
                listOf(SettingsUpload("logo", second.path, "b.png", "image/png")),
                { "two.png" }
            )
        )

        assertFalse(File(uploadsFolder, "one.png").exists())
        assertTrue(File(uploadsFolder, "two.png").exists())

        // an explicit removal
        storage.commit(access, "my-site", schema.prepare(storage.load(access, "my-site"), JsonObject("""{ "remove-files": ["two.png"] }""")))

        assertFalse(File(uploadsFolder, "two.png").exists())
        assertTrue(schema.read(storage.load(access, "my-site")).files.isEmpty)
    }

    @Test
    fun `a file that is not an image is refused and a field that is no image field is ignored`() {
        val parsed = schema()
        val text = File(dir, "spooled-3").apply { writeText("x") }

        val problem = settingProblem(parsed, "{}", listOf(SettingsUpload("logo", text.path, "notes.txt", "text/plain")))

        assertEquals("logo", detail(problem, "key"))
        assertEquals("NOT_AN_IMAGE", problem.reason)

        // an octet-stream upload counts as an image by its extension
        val plan = parsed.prepare(null, JsonObject("{}"), listOf(SettingsUpload("logo", text.path, "a.webp", "application/octet-stream")), { "n.webp" })

        assertEquals(1, plan.copies.size)

        // an upload under the name of a text field is not stored
        val dropped = parsed.prepare(null, JsonObject("{}"), listOf(SettingsUpload("title", text.path, "a.png", "image/png")), { "x.png" })

        assertTrue(dropped.copies.isEmpty())
    }

    @Test
    fun `a client cannot claim a file it was not given or a path outside the folder`() {
        val parsed = schema()
        val current = JsonObject("""{ "files": { "logo": ["mine.png"] } }""")

        val plan = parsed.prepare(
            current,
            JsonObject("""{ "files": { "logo": ["mine.png", "other.png", "../../etc/passwd"] }, "remove-files": ["../../x", "sub/dir.png"] }""")
        )

        assertEquals(JsonArray().add("mine.png"), plan.settings.getJsonObject("files").getJsonArray("logo"))
        assertTrue(plan.deletes.isEmpty(), "nothing outside the folder is ever deleted: ${plan.deletes}")
    }

    @Test
    fun `reading fills in the defaults and leaves out fields with neither a value nor a default`() {
        val read = schema().read(null)

        assertEquals("Pano", read.settings.getString("title"))
        assertEquals(false, read.settings.getBoolean("dark"))
        assertEquals(3, read.settings.getInteger("columns"))
        assertEquals("wide", read.settings.getString("layout"))
        assertEquals("#ff8800", read.settings.getString("accent"))
        assertFalse(read.settings.containsKey("about"))
        assertFalse(read.settings.containsKey("link"))
        assertFalse(read.settings.containsKey("logo"))
        assertTrue(read.files.isEmpty)
    }

    @Test
    fun `a stored value the schema no longer accepts reads as the default`() {
        val read = schema().read(JsonObject("""{ "columns": 99, "layout": "gone", "title": "Kept" }"""))

        assertEquals(3, read.settings.getInteger("columns"))
        assertEquals("wide", read.settings.getString("layout"))
        assertEquals("Kept", read.settings.getString("title"))
    }

    @Test
    fun `settings of two front-ends live side by side in the one property`() = runBlocking {
        val access = MemoryAccess()
        val storage = FrontendSettingsStorage(uploadsFolder)
        val parsed = schema()

        storage.commit(access, "vanilla-theme", parsed.prepare(null, JsonObject("""{ "title": "Alpha" }""")))
        storage.commit(access, "my-site", parsed.prepare(null, JsonObject("""{ "title": "Beta" }""")))

        val all = JsonObject(access.text!!)

        assertEquals(setOf("vanilla-theme", "my-site"), all.fieldNames())
        assertEquals("Alpha", all.getJsonObject("vanilla-theme").getString("title"))
        assertEquals("Beta", all.getJsonObject("my-site").getString("title"))
        assertEquals("theme_settings", FrontendSettingsStorage.PROPERTY)
    }

    // --- the storage rules shared with the theme settings endpoint --------------------------

    @Test
    fun `a first theme write stores what was sent`() {
        val plan = FrontendSettingsStorage.plan(null, JsonObject("""{ "color": "red", "files": { "logo": ["a.png"] } }"""))

        assertEquals(JsonObject("""{ "color": "red", "files": { "logo": ["a.png"] } }"""), plan.settings)
        assertTrue(plan.copies.isEmpty())
        assertTrue(plan.deletes.isEmpty())
    }

    @Test
    fun `a theme write keeps the files of keys it does not mention and deletes the ones it drops`() {
        val current = JsonObject("""{ "files": { "logo": ["a.png"], "banner": ["b.png"], "icon": ["c.png"] } }""")

        val plan = FrontendSettingsStorage.plan(current, JsonObject("""{ "color": "blue", "files": { "icon": [] } }"""))

        assertEquals(JsonObject("""{ "color": "blue", "files": { "logo": ["a.png"], "banner": ["b.png"] } }"""), plan.settings)
        assertEquals(listOf("c.png"), plan.deletes)
    }

    @Test
    fun `an empty theme write resets everything including the files`() {
        val plan = FrontendSettingsStorage.plan(JsonObject("""{ "files": { "logo": ["a.png"] } }"""), JsonObject())

        assertTrue(plan.settings.isEmpty)
        assertEquals(listOf("a.png"), plan.deletes)
    }

    @Test
    fun `uploads are appended under their field and remove-files wins`() {
        val one = File(dir, "s1").apply { writeText("1") }
        val two = File(dir, "s2").apply { writeText("2") }
        var counter = 0

        val plan = FrontendSettingsStorage.plan(
            JsonObject("""{ "files": { "gallery": ["old.png"] } }"""),
            JsonObject("""{ "files": { "gallery": ["old.png", "stale.png"] }, "remove-files": ["stale.png"] }"""),
            listOf(SettingsUpload("gallery", one.path, null, null), SettingsUpload("gallery", two.path, null, null)),
            { "n${++counter}.png" }
        )

        assertEquals(JsonArray().add("old.png").add("n1.png").add("n2.png"), plan.settings.getJsonObject("files").getJsonArray("gallery"))
        assertFalse(plan.settings.containsKey("remove-files"))
        assertEquals(listOf("stale.png"), plan.deletes)
        assertEquals(listOf("n1.png", "n2.png"), plan.copies.map { it.second })
    }

    @Test
    fun `a generated name is a uuid and keeps only a sane extension`() {
        assertTrue(FrontendSettingsStorage.generatedName(SettingsUpload("a", "/x/y/abc.png", null, null)).endsWith(".png"))
        assertFalse(FrontendSettingsStorage.generatedName(SettingsUpload("a", "/x.y/abc", null, null)).contains("/"))
        assertFalse(FrontendSettingsStorage.generatedName(SettingsUpload("a", "/x/y/abc", null, null)).contains("."))
    }

    @Test
    fun `without a schema the stored object is read as it is, minus the file bookkeeping`() {
        val read = FrontendSettingsSchema.readRaw(JsonObject("""{ "color": "red", "files": { "logo": ["a.png"] } }"""))

        assertEquals(JsonObject("""{ "color": "red" }"""), read.settings)
        assertEquals(JsonObject("""{ "logo": ["a.png"] }"""), read.files)
        assertTrue(FrontendSettingsSchema.readRaw(null).settings.isEmpty)
    }

    // --- a theme whose schema has fields -------------------------------------------------------

    private fun themeFolder(coreMeta: String?): File {
        val theme = File(dir, "themes/blaze-theme").apply { mkdirs() }

        if (coreMeta != null) File(theme, "core-meta.json").writeText(coreMeta)

        return File(theme, "core-meta.json")
    }

    @Test
    fun `a theme whose core-meta has fields gets the schema form`() {
        val file = themeFolder(
            """{ "engine": { "Navbar": 1 }, "overrides": {}, "settingsSchema": $everything }"""
        )

        val parsed = FrontendSettingsSchema.fromFile(file)!!

        assertEquals(8, parsed.fields.size)

        // and the whole write and read works under the theme's id
        val read = runBlocking {
            val access = MemoryAccess()
            val storage = FrontendSettingsStorage(uploadsFolder)

            storage.commit(access, "blaze-theme", parsed.prepare(null, JsonObject("""{ "accent": "#112233", "columns": 4 }""")))

            parsed.read(storage.load(access, "blaze-theme"))
        }

        assertEquals("#112233", read.settings.getString("accent"))
        assertEquals(4, read.settings.getInteger("columns"))
        assertEquals("Pano", read.settings.getString("title"))
    }

    @Test
    fun `a theme without fields, without core-meta or with a broken schema has no schema form`() {
        assertNull(FrontendSettingsSchema.fromFile(themeFolder("""{ "overrides": {} }""")))
        assertNull(FrontendSettingsSchema.fromFile(themeFolder("""{ "settingsSchema": ["legacy"] }""")))
        assertNull(FrontendSettingsSchema.fromFile(themeFolder("""{ "settingsSchema": { "tabs": { "general": ["a"] } } }""")))
        assertNull(FrontendSettingsSchema.fromFile(themeFolder("not json")))
        assertNull(FrontendSettingsSchema.fromFile(themeFolder("""{ "settingsSchema": { "fields": { "a": { "type": "nope", "label": "A" } } } }""")))
        assertNull(FrontendSettingsSchema.fromFile(File(dir, "missing/core-meta.json")))
        assertNull(FrontendSettingsSchema.fromFile(null))
    }

    @Test
    fun `a custom app manifest carries the same keys`() {
        val manifest = File(dir, "manifest.json").apply {
            writeText("""{ "id": "my-site", "type": "custom-app", "title": "My site", "version": "1.0.0", "author": "me", "settingsSchema": $everything }""")
        }

        assertEquals(8, FrontendSettingsSchema.fromFile(manifest)!!.fields.size)
    }

    // --- the descriptor ---------------------------------------------------------------------------

    @Test
    fun `the descriptor of doc 05 parses`() {
        val document = FrontendDescriptor.parse(
            """{ "id": "my-site", "title": "My site",
                 "urls": { "auth.activate": "/welcome/confirm?token={token}", "market.order": "/shop/o/{id}" },
                 "settingsSchema": { "tabs": { "general": ["heroTitle"] },
                                     "fields": { "heroTitle": { "type": "text", "label": "Hero title" } } } }"""
        )

        assertEquals("my-site", document.id)
        assertEquals("My site", document.title)
        assertEquals("/shop/o/{id}", document.json.getJsonObject("urls").getString("market.order"))
        assertEquals(listOf("heroTitle"), document.schema!!.fields.keys.toList())
    }

    @Test
    fun `a descriptor needs an id and a sane shape`() {
        fun invalid(text: String) = assertThrows<FrontendDescriptorInvalid> { FrontendDescriptor.parse(text) }

        assertEquals("FRONTEND_DESCRIPTOR_INVALID", invalid("""{ "title": "x" }""").code)
        assertEquals("id", detail(invalid("""{ "id": "Not A Slug" }"""), "field"))
        assertEquals("document", detail(invalid("[1]"), "field"))
        assertEquals("urls", detail(invalid("""{ "id": "a", "urls": [] }"""), "field"))
        assertEquals("settingsSchema", detail(invalid("""{ "id": "a", "settingsSchema": "x" }"""), "field"))

        // a descriptor with no schema and no urls is fine
        assertNull(FrontendDescriptor.parse("""{ "id": "a" }""").schema)

        // a broken schema inside a descriptor is the schema error
        val broken = assertThrows<FrontendSettingsSchemaInvalid> {
            FrontendDescriptor.parse("""{ "id": "a", "settingsSchema": { "fields": { "x": { "type": "text" } } } }""")
        }

        assertEquals("x", detail(broken, "key"))
    }

    @Test
    fun `the descriptor address defaults to the well-known path of the upstream`() {
        assertEquals("http://127.0.0.1:4000/.well-known/pano-frontend.json", FrontendDescriptor.resolveUrl("", "http://127.0.0.1:4000"))
        assertEquals("https://site.example/d.json", FrontendDescriptor.resolveUrl(" https://site.example/d.json ", "http://127.0.0.1:4000"))
        assertNull(FrontendDescriptor.resolveUrl("", ""))
        assertNull(FrontendDescriptor.resolveUrl("", "not a url"))
    }

    @Test
    fun `the descriptor must come from the upstream host or the site host`() {
        // the upstream host
        FrontendDescriptor.checkHost("http://127.0.0.1:4000/d.json", "http://127.0.0.1:4000", "")
        // another port of the same host is the same host
        FrontendDescriptor.checkHost("http://app.example:81/d.json", "http://app.example:4000", "")
        // the site host
        FrontendDescriptor.checkHost("https://www.example.com/pano.json", "http://127.0.0.1:4000", "https://www.example.com")
        // case does not matter
        FrontendDescriptor.checkHost("https://WWW.example.com/pano.json", "", "https://www.example.com")

        val other = assertThrows<DescriptorHostNotAllowed> {
            FrontendDescriptor.checkHost("https://evil.example/pano.json", "http://127.0.0.1:4000", "https://www.example.com")
        }

        assertEquals("DESCRIPTOR_HOST_NOT_ALLOWED", other.code)

        assertThrows<DescriptorHostNotAllowed> { FrontendDescriptor.checkHost("ftp://127.0.0.1/d.json", "http://127.0.0.1:4000", "") }
        assertThrows<DescriptorHostNotAllowed> { FrontendDescriptor.checkHost("http://127.0.0.1/d.json", "", "") }
        assertThrows<DescriptorHostNotAllowed> { FrontendDescriptor.checkHost("/d.json", "http://127.0.0.1:4000", "") }
    }

    private var vertx: Vertx? = null
    private var server: HttpServer? = null

    @AfterEach
    fun stop() {
        server?.close()?.toCompletionStage()?.toCompletableFuture()?.get(5, TimeUnit.SECONDS)
        vertx?.close()?.toCompletionStage()?.toCompletableFuture()?.get(5, TimeUnit.SECONDS)
    }

    /** A stub front-end: `/ok`, `/missing`, `/big`, `/redirect`, `/endless`. */
    private fun stub(): Pair<HttpClient, String> {
        val v = Vertx.vertx().also { vertx = it }

        server = v.createHttpServer().requestHandler { request ->
            when (request.path()) {
                "/ok" -> request.response().putHeader("Content-Type", "application/json").end("""{ "id": "stub-site", "title": "Stub" }""")
                "/redirect" -> request.response().setStatusCode(302).putHeader("Location", "/ok").end()
                "/big" -> request.response().end("x".repeat(FetchBig))
                "/endless" -> {
                    request.response().setChunked(true)
                    val timer = v.setPeriodic(1) { request.response().write("y".repeat(10_000)) }
                    request.response().closeHandler { v.cancelTimer(timer) }
                }

                else -> request.response().setStatusCode(404).end()
            }
        }.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)

        return v.createHttpClient() to "http://127.0.0.1:${server!!.actualPort()}"
    }

    @Test
    fun `the descriptor is fetched once, with no redirect and a size limit`() {
        val (client, base) = stub()

        assertEquals("stub-site", FrontendDescriptor.parse(runBlocking { FrontendDescriptor.fetch(client, "$base/ok") }).id)

        assertEquals("HTTP 404", detail(assertThrows<FrontendDescriptorUnreachable> { runBlocking { FrontendDescriptor.fetch(client, "$base/missing") } }, "reason"))
        assertEquals("HTTP 302", detail(assertThrows<FrontendDescriptorUnreachable> { runBlocking { FrontendDescriptor.fetch(client, "$base/redirect") } }, "reason"))
        assertEquals("TOO_LARGE", detail(assertThrows<FrontendDescriptorUnreachable> { runBlocking { FrontendDescriptor.fetch(client, "$base/big", maxBytes = 1000) } }, "reason"))
        assertEquals("TOO_LARGE", detail(assertThrows<FrontendDescriptorUnreachable> { runBlocking { FrontendDescriptor.fetch(client, "$base/endless", maxBytes = 50_000) } }, "reason"))
    }

    @Test
    fun `nothing listening is unreachable, not a crash`() {
        val (client, _) = stub()

        assertEquals("FRONTEND_DESCRIPTOR_UNREACHABLE", assertThrows<FrontendDescriptorUnreachable> {
            runBlocking { FrontendDescriptor.fetch(client, "http://127.0.0.1:1/x", timeoutMs = 1000) }
        }.code)
    }

    // --- wiring ----------------------------------------------------------------------------------------

    @Test
    fun `spring builds the descriptor and the settings service`() {
        val objenesis = ObjenesisStd()
        val context = AnnotationConfigApplicationContext()
        val v = Vertx.vertx()

        context.beanFactory.registerSingleton("configManager", objenesis.newInstance(ConfigManager::class.java))
        context.beanFactory.registerSingleton("logger", LoggerFactory.getLogger("test"))
        context.beanFactory.registerSingleton("databaseManager", objenesis.newInstance(DatabaseManager::class.java))
        context.beanFactory.registerSingleton("uiManager", objenesis.newInstance(UIManager::class.java))
        context.beanFactory.registerSingleton("httpClient", v.createHttpClient())
        context.register(FrontendDescriptor::class.java, FrontendSettings::class.java)

        try {
            context.refresh()

            assertNotNull(context.getBean(FrontendSettings::class.java))
            assertNotNull(context.getBean(FrontendDescriptor::class.java))
        } finally {
            context.close()
            v.close()
        }
    }

    private companion object {
        const val FALLBACK_TEXTAREA_CAP = SettingField.TEXTAREA_CAP
        const val FetchBig = 2000
    }
}
