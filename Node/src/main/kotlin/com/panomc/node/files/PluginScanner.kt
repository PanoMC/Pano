package com.panomc.node.files

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.panomc.node.util.PathSafety
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** One jar in a server's `plugins` or `mods` directory, as its own descriptor describes it. */
data class ScannedPlugin(
    /** The file name, `.disabled` suffix included when it has one. */
    val file: String,
    val name: String,
    val version: String?,
    val main: String?,
    val api: String?,
    val description: String?,
    val authors: List<String>,
    val enabled: Boolean,
    /** `plugin` or `mod`, which is what the panel calls it. */
    val kind: String
)

/**
 * What is installed in a server, read from the jars rather than from the running server (§2.4.17 B).
 *
 * This is the node's answer to "what plugins does this server have" when there is no Pano plugin
 * inside it to ask. It is deliberately a weaker answer than the plugin's: the jars on disk are
 * what the server *will* load, not what it *has* loaded, so a jar that failed to enable still
 * shows up here. Pano says so in the panel and prefers the plugin's list wherever it has one.
 *
 * Every descriptor format gets a parser of its own rather than a YAML, JSON and TOML library
 * apiece: what is needed out of each file is four or five top-level keys, and a daemon whose whole
 * argument is that it is small enough to trust should not grow three parsers' worth of
 * dependencies (and three parsers' worth of CVEs) to read them.
 *
 * Results are cached by file name, size and modification time, because the panel's plugin page
 * re-asks on every visit and a modded server's `mods/` directory is three hundred zip files.
 */
class PluginScanner {
    private data class Cached(val size: Long, val modified: Long, val plugin: ScannedPlugin)

    private val cache = ConcurrentHashMap<String, Cached>()

    /**
     * Every jar in [directory], parsed or taken from the cache.
     *
     * [fallbackKind] is what a jar whose descriptor says nothing is called, which is decided by
     * the directory it is in: something in `mods/` is a mod even when it carries no `fabric.mod.json`.
     */
    fun scan(directory: File, fallbackKind: String): List<ScannedPlugin> {
        if (!directory.isDirectory) {
            return emptyList()
        }

        val files = directory.listFiles()
            ?.filter { it.isFile && isPluginJar(it.name) }
            ?.sortedBy { it.name.lowercase() }
            ?.take(MAX_ENTRIES)
            .orEmpty()

        val seen = HashSet<String>(files.size)

        val plugins = files.map { file ->
            val key = file.absolutePath

            seen.add(key)

            val size = file.length()
            val modified = file.lastModified()
            val cached = cache[key]

            if (cached != null && cached.size == size && cached.modified == modified) {
                return@map cached.plugin
            }

            val plugin = read(file, fallbackKind)

            cache[key] = Cached(size, modified, plugin)

            plugin
        }

        // A jar that was deleted or renamed must not keep its entry alive forever; this directory
        // is the only place that knows it is gone.
        cache.keys.removeIf { it.startsWith(directory.absolutePath + File.separator) && it !in seen }

        return plugins
    }

    /**
     * Renames a jar between `x.jar` and `x.jar.disabled`.
     *
     * Which is all "disabling a plugin" means without a running server to ask: every loader on
     * every platform loads `*.jar` out of its directory and nothing else, so a jar with another
     * extension is a jar that will not be there on the next start. Hence [Renamed.restartRequired]
     * being a constant rather than a question -- nothing about the process that is running now
     * changes.
     */
    fun toggle(directory: File, file: String?, enabled: Boolean): ToggleResult {
        val requested = file?.trim().orEmpty()

        if (!PathSafety.isSafeSegment(requested) || !isPluginJar(requested)) {
            return ToggleResult.Failed(FileService.ERROR_PATH_DENIED)
        }

        val base = requested.removeSuffix(DISABLED_SUFFIX)

        // The one jar that may never be switched off from the panel: it is the connection the
        // panel is being used through, and turning it off would be the last thing it could do.
        if (isPanoPluginJar(base)) {
            return ToggleResult.Failed(ERROR_PANO_PLUGIN)
        }

        val target = File(directory, if (enabled) base else base + DISABLED_SUFFIX)
        val source = File(directory, if (enabled) base + DISABLED_SUFFIX else base)

        // Already where it was asked to be. Answered as a success, because that is what the panel
        // is asking for and re-sending a toggle after a reload is an ordinary thing to do.
        if (!source.isFile && target.isFile) {
            return ToggleResult.Renamed(target.name, enabled)
        }

        if (!source.isFile) {
            return ToggleResult.Failed(FileService.ERROR_NOT_FOUND)
        }

        if (target.isFile) {
            return ToggleResult.Failed(FileService.ERROR_EXISTS)
        }

        return try {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                // A rename inside one directory is atomic everywhere that matters, but a network
                // share mounted under the server directory is not everywhere that matters.
                Files.move(source.toPath(), target.toPath())
            }

            cache.remove(source.absolutePath)
            cache.remove(target.absolutePath)

            ToggleResult.Renamed(target.name, enabled)
        } catch (_: Exception) {
            ToggleResult.Failed(FileService.ERROR_NOT_MOVED)
        }
    }

    /** What a toggle did, or why it did nothing. */
    sealed interface ToggleResult {
        data class Renamed(val file: String, val enabled: Boolean) : ToggleResult

        data class Failed(val error: String) : ToggleResult
    }

    companion object {
        const val KIND_PLUGIN = "plugin"
        const val KIND_MOD = "mod"

        const val JAR_SUFFIX = ".jar"
        const val DISABLED_SUFFIX = ".disabled"

        /** A descriptor is a handful of lines; anything larger is not read at all. */
        const val MAX_DESCRIPTOR_BYTES = 64 * 1024

        /** A modded server's `mods/` runs to hundreds of files; past this the list is truncated. */
        const val MAX_ENTRIES = 2_000

        /** Refusing to disable the plugin the panel is talking through. */
        const val ERROR_PANO_PLUGIN = "PANO_PLUGIN"

        /** A request that left out a field it cannot be answered without. */
        const val ERROR_BAD_REQUEST = "BAD_REQUEST"

        /** The software that reads `mods/`; everything else reads `plugins/`. */
        val MOD_SOFTWARE = setOf("fabric", "quilt", "forge", "neoforge")

        /**
         * Where this server keeps what it loads.
         *
         * Chosen by software, then corrected by what is actually on disk: an imported server whose
         * software could not be identified has an empty string here, and the directory that exists
         * is a better answer than the default.
         */
        fun resolveDirectory(serverDirectory: File, software: String?): File {
            val primary = File(serverDirectory, directoryName(software))

            if (primary.isDirectory) {
                return primary
            }

            val other = File(serverDirectory, if (primary.name == MODS_DIR) PLUGINS_DIR else MODS_DIR)

            return if (other.isDirectory) other else primary
        }

        fun directoryName(software: String?): String =
            if (software?.trim()?.lowercase() in MOD_SOFTWARE) MODS_DIR else PLUGINS_DIR

        /** What a jar in this directory is, before its descriptor gets a say. */
        fun kindOf(directory: File): String = if (directory.name == MODS_DIR) KIND_MOD else KIND_PLUGIN

        /** Whether [name] is a jar this scanner deals in, enabled or disabled. */
        fun isPluginJar(name: String): Boolean {
            val base = name.removeSuffix(DISABLED_SUFFIX)

            return base.length > JAR_SUFFIX.length && base.endsWith(JAR_SUFFIX, ignoreCase = true)
        }

        /**
         * Whether this file is the Pano plugin.
         *
         * By name, because that is all a disabled jar can be judged by without opening it, and the
         * installer names it after the release asset -- `Pano-1.2.3.jar`, `pano.jar`. Generous on
         * purpose: refusing to toggle somebody's `panoramas.jar` costs them a rename, and
         * accidentally disabling the plugin costs them their link to the panel.
         */
        fun isPanoPluginJar(name: String): Boolean {
            val base = name.removeSuffix(DISABLED_SUFFIX).removeSuffix(JAR_SUFFIX).lowercase()

            return base == "pano" || base.startsWith("pano-") || base.startsWith("pano_")
        }

        /** One jar, parsed. Never throws: an unreadable jar is still a file the panel must list. */
        fun read(file: File, fallbackKind: String): ScannedPlugin {
            val enabled = !file.name.endsWith(DISABLED_SUFFIX)
            val descriptor = try {
                describe(file)
            } catch (_: Exception) {
                null
            }

            val fallbackName = file.name.removeSuffix(DISABLED_SUFFIX).removeSuffix(JAR_SUFFIX)

            return ScannedPlugin(
                file = file.name,
                name = descriptor?.name?.takeIf { it.isNotBlank() } ?: fallbackName,
                version = descriptor?.version,
                main = descriptor?.main,
                api = descriptor?.api,
                description = descriptor?.description,
                authors = descriptor?.authors.orEmpty(),
                enabled = enabled,
                kind = descriptor?.kind ?: fallbackKind
            )
        }

        private const val PLUGINS_DIR = "plugins"
        private const val MODS_DIR = "mods"

        /**
         * The descriptor entries that are looked for, in the order they are believed.
         *
         * `plugin.yml` first because a Paper plugin that ships both is still a Bukkit plugin to
         * everything that reads it, and the Forge pair last because a mod jar that also carries a
         * `fabric.mod.json` is a multi-loader build whose Fabric metadata is the richer one.
         */
        private val DESCRIPTORS = listOf(
            "plugin.yml" to Format.BUKKIT,
            "paper-plugin.yml" to Format.BUKKIT,
            "velocity-plugin.json" to Format.VELOCITY,
            "bungee.yml" to Format.BUKKIT,
            "fabric.mod.json" to Format.FABRIC,
            "quilt.mod.json" to Format.QUILT,
            "META-INF/mods.toml" to Format.FORGE,
            "META-INF/neoforge.mods.toml" to Format.FORGE
        )

        private enum class Format { BUKKIT, VELOCITY, FABRIC, QUILT, FORGE }

        private data class Descriptor(
            val name: String?,
            val version: String?,
            val main: String?,
            val api: String?,
            val description: String?,
            val authors: List<String>,
            val kind: String
        )

        private fun describe(file: File): Descriptor? {
            if (!file.isFile) {
                return null
            }

            ZipFile(file).use { zip ->
                DESCRIPTORS.forEach { (entryName, format) ->
                    val entry = zip.getEntry(entryName) ?: return@forEach
                    val text = readEntry(zip, entry) ?: return@forEach

                    parse(text, format)?.let { return it }
                }
            }

            return null
        }

        private fun readEntry(zip: ZipFile, entry: ZipEntry): String? {
            if (entry.isDirectory || entry.size > MAX_DESCRIPTOR_BYTES) {
                return null
            }

            return zip.getInputStream(entry).use { input ->
                val bytes = input.readNBytes(MAX_DESCRIPTOR_BYTES + 1)

                if (bytes.size > MAX_DESCRIPTOR_BYTES) null else String(bytes, Charsets.UTF_8)
            }
        }

        private fun parse(text: String, format: Format): Descriptor? = when (format) {
            Format.BUKKIT -> parseBukkit(text)
            Format.VELOCITY -> parseVelocity(text)
            Format.FABRIC -> parseFabric(text)
            Format.QUILT -> parseQuilt(text)
            Format.FORGE -> parseForge(text)
        }

        /** `plugin.yml`, `paper-plugin.yml` and `bungee.yml`, which share every key that matters. */
        private fun parseBukkit(text: String): Descriptor? {
            val document = MiniYaml.parse(text)

            val name = document.string("name") ?: return null

            return Descriptor(
                name = name,
                version = document.string("version"),
                main = document.string("main"),
                api = document.string("api-version"),
                description = document.string("description"),
                authors = document.list("authors").ifEmpty { splitAuthors(document.string("author")) },
                kind = KIND_PLUGIN
            )
        }

        private fun parseVelocity(text: String): Descriptor? {
            val root = jsonObject(text) ?: return null

            val name = root.string("name") ?: root.string("id") ?: return null

            return Descriptor(
                name = name,
                version = root.string("version"),
                main = root.string("main"),
                api = null,
                description = root.string("description"),
                authors = root.stringList("authors"),
                kind = KIND_PLUGIN
            )
        }

        private fun parseFabric(text: String): Descriptor? {
            val root = jsonObject(text) ?: return null

            val name = root.string("name") ?: root.string("id") ?: return null

            val main = root.get("entrypoints")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("main")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.firstOrNull()
                ?.let { if (it.isJsonPrimitive) it.asString else null }

            return Descriptor(
                name = name,
                version = root.string("version"),
                main = main,
                // A mod has no API level; the Minecraft range it declares is the nearest thing to
                // one, and it is what the panel wants to show next to it.
                api = root.get("depends")?.takeIf { it.isJsonObject }?.asJsonObject?.string("minecraft"),
                description = root.string("description"),
                authors = root.stringList("authors"),
                kind = KIND_MOD
            )
        }

        private fun parseQuilt(text: String): Descriptor? {
            val root = jsonObject(text) ?: return null

            val loader = root.get("quilt_loader")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val metadata = loader.get("metadata")?.takeIf { it.isJsonObject }?.asJsonObject

            val name = metadata?.string("name") ?: loader.string("id") ?: return null

            val contributors = metadata
                ?.get("contributors")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.keySet()
                ?.toList()
                .orEmpty()

            return Descriptor(
                name = name,
                version = loader.string("version"),
                main = null,
                api = null,
                description = metadata?.string("description"),
                authors = contributors,
                kind = KIND_MOD
            )
        }

        /** `META-INF/mods.toml` and its NeoForge twin: the first `[[mods]]` table and nothing else. */
        private fun parseForge(text: String): Descriptor? {
            val table = MiniToml.firstTable(text, "mods") ?: return null

            val name = table.string("displayName") ?: table.string("modId") ?: return null

            return Descriptor(
                name = name,
                version = table.string("version"),
                main = null,
                api = null,
                description = table.string("description"),
                authors = table.list("authors").flatMap { splitAuthors(it) },
                kind = KIND_MOD
            )
        }

        private fun jsonObject(text: String): JsonObject? = try {
            JsonParser.parseString(text)?.takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        }

        private fun JsonObject.string(key: String): String? = try {
            get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

        /** Authors are a list of names on one mod and a list of `{ name }` objects on the next. */
        private fun JsonObject.stringList(key: String): List<String> {
            val element: JsonElement = get(key) ?: return emptyList()

            if (element.isJsonPrimitive) {
                return splitAuthors(element.asString)
            }

            if (!element.isJsonArray) {
                return emptyList()
            }

            return element.asJsonArray.mapNotNull { entry ->
                when {
                    entry == null || entry.isJsonNull -> null
                    entry.isJsonPrimitive -> entry.asString.trim().takeIf { it.isNotEmpty() }
                    entry.isJsonObject -> entry.asJsonObject.string("name")
                    else -> null
                }
            }
        }

        /** `"Bob, Jane"` is two people; every descriptor format writes it that way at least once. */
        private fun splitAuthors(value: String?): List<String> = value
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
    }
}

/**
 * Enough YAML to read the top of a `plugin.yml`, and no more.
 *
 * What a plugin descriptor needs is a handful of top-level scalars and one list of authors. Nested
 * maps (`commands:`, `permissions:`) are skipped rather than represented, anchors and multiple
 * documents are not YAML this ever sees, and a key it cannot make sense of is left out instead of
 * failing the file -- the caller's fallback is the jar's own name, which is never wrong enough to
 * be worth an exception.
 */
internal object MiniYaml {
    data class Document(val scalars: Map<String, String>, val lists: Map<String, List<String>>) {
        fun string(key: String): String? = scalars[key]?.takeIf { it.isNotBlank() }

        fun list(key: String): List<String> = lists[key] ?: scalars[key]?.let { listOf(it) }.orEmpty()
    }

    fun parse(text: String): Document {
        val scalars = LinkedHashMap<String, String>()
        val lists = LinkedHashMap<String, MutableList<String>>()

        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

        var index = 0
        var openKey: String? = null

        while (index < lines.size) {
            val raw = lines[index]

            index++

            val trimmed = raw.trim()

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue
            }

            if (indentOf(raw) > 0) {
                val key = openKey ?: continue

                if (trimmed.startsWith("-")) {
                    val item = unquote(trimmed.removePrefix("-").trim())

                    if (item.isNotEmpty()) {
                        lists.getOrPut(key) { mutableListOf() }.add(item)
                    }
                }

                continue
            }

            openKey = null

            val separator = trimmed.indexOf(':')

            if (separator <= 0) {
                continue
            }

            val key = unquote(trimmed.substring(0, separator).trim()).lowercase()

            if (key.isEmpty()) {
                continue
            }

            val value = trimmed.substring(separator + 1).trim()

            when {
                // Nothing after the colon: either a nested map to skip or a list whose items
                // follow on the next lines. Which one it is, the next line decides.
                value.isEmpty() -> openKey = key

                value.startsWith("|") || value.startsWith(">") -> {
                    val (block, next) = readBlock(lines, index, folded = value.startsWith(">"))

                    scalars[key] = block
                    index = next
                }

                value.startsWith("[") && value.endsWith("]") ->
                    lists[key] = value.substring(1, value.length - 1)
                        .split(',')
                        .map { unquote(it.trim()) }
                        .filter { it.isNotEmpty() }
                        .toMutableList()

                else -> scalars[key] = unquote(stripComment(value))
            }
        }

        return Document(scalars, lists.mapValues { it.value.toList() })
    }

    /** The indented lines under a `|` or `>` scalar, and the line to carry on from. */
    private fun readBlock(lines: List<String>, from: Int, folded: Boolean): Pair<String, Int> {
        val block = StringBuilder()

        var index = from

        while (index < lines.size) {
            val line = lines[index]

            if (line.isBlank()) {
                index++

                continue
            }

            if (indentOf(line) == 0) {
                break
            }

            if (block.isNotEmpty()) {
                block.append(if (folded) ' ' else '\n')
            }

            block.append(line.trim())

            index++
        }

        return block.toString() to index
    }

    private fun indentOf(line: String): Int = line.length - line.trimStart(' ', '\t').length

    /** A `#` starts a comment only when something separates it from the value. */
    private fun stripComment(value: String): String {
        if (value.startsWith("\"") || value.startsWith("'")) {
            return value
        }

        val comment = value.indexOf(" #")

        return if (comment >= 0) value.substring(0, comment).trim() else value
    }

    private fun unquote(value: String): String {
        if (value.length < 2) {
            return value
        }

        val first = value.first()

        if ((first == '"' || first == '\'') && value.last() == first) {
            return value.substring(1, value.length - 1).replace("\\\"", "\"").replace("''", "'")
        }

        return value
    }
}

/**
 * Enough TOML to read the first `[[mods]]` table of a Forge or NeoForge descriptor.
 *
 * Which is the only table anybody asks about: a jar declaring several mods is one artifact in the
 * panel either way, and its first entry is the one its file is named after. Everything outside the
 * table -- `modLoader`, `loaderVersion`, the dependency tables underneath -- is stepped over.
 */
internal object MiniToml {
    data class Table(val values: Map<String, List<String>>) {
        fun string(key: String): String? = values[key]?.firstOrNull()?.takeIf { it.isNotBlank() }

        fun list(key: String): List<String> = values[key].orEmpty()
    }

    fun firstTable(text: String, name: String): Table? {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

        var index = lines.indexOfFirst { it.trim() == "[[$name]]" }

        if (index < 0) {
            return null
        }

        index++

        val values = LinkedHashMap<String, List<String>>()

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()

            index++

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue
            }

            // The next table header ends this one, whether it is a sibling or a child.
            if (trimmed.startsWith("[")) {
                break
            }

            val separator = trimmed.indexOf('=')

            if (separator <= 0) {
                continue
            }

            val key = trimmed.substring(0, separator).trim().trim('"', '\'')
            val raw = trimmed.substring(separator + 1).trim()

            if (key.isEmpty()) {
                continue
            }

            if (raw.startsWith(TRIPLE_DOUBLE) || raw.startsWith(TRIPLE_SINGLE)) {
                val (block, next) = readMultiline(lines, index, raw)

                values[key] = listOf(block)
                index = next

                continue
            }

            values[key] = parseValue(raw)
        }

        return Table(values)
    }

    /**
     * A `"""` string, which is how a Forge descriptor writes anything longer than a line.
     *
     * [opening] is the line the string started on, so a one-line `"""text"""` never enters the
     * loop and a description that runs over ten lines comes back as one paragraph.
     */
    private fun readMultiline(lines: List<String>, from: Int, opening: String): Pair<String, Int> {
        val delimiter = if (opening.startsWith(TRIPLE_DOUBLE)) TRIPLE_DOUBLE else TRIPLE_SINGLE

        val head = opening.removePrefix(delimiter)

        if (head.endsWith(delimiter)) {
            return head.removeSuffix(delimiter).trim() to from
        }

        val block = StringBuilder(head.trim())

        var index = from

        while (index < lines.size) {
            val line = lines[index]

            index++

            val end = line.indexOf(delimiter)

            if (end >= 0) {
                append(block, line.substring(0, end).trim())

                break
            }

            append(block, line.trim())
        }

        return block.toString().trim() to index
    }

    private fun append(block: StringBuilder, part: String) {
        if (part.isEmpty()) {
            return
        }

        if (block.isNotEmpty()) {
            block.append(' ')
        }

        block.append(part)
    }

    private fun parseValue(raw: String): List<String> {
        val value = stripComment(raw)

        if (value.startsWith("[") && value.endsWith("]")) {
            return value.substring(1, value.length - 1)
                .split(',')
                .map { unquote(it.trim()) }
                .filter { it.isNotEmpty() }
        }

        return listOf(unquote(value))
    }

    private fun stripComment(value: String): String {
        if (value.startsWith("\"") || value.startsWith("'")) {
            return value
        }

        val comment = value.indexOf('#')

        return if (comment >= 0) value.substring(0, comment).trim() else value
    }

    private fun unquote(value: String): String {
        if (value.length < 2) {
            return value
        }

        val first = value.first()

        if ((first == '"' || first == '\'') && value.last() == first) {
            return value.substring(1, value.length - 1)
        }

        return value
    }

    private const val TRIPLE_DOUBLE = "\"\"\""
    private const val TRIPLE_SINGLE = "'''"
}
