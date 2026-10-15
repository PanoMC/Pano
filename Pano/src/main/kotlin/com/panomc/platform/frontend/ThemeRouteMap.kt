package com.panomc.platform.frontend

import io.vertx.core.json.JsonObject

/**
 * The route config of a theme (`routes.rename`, `routes.disable` of its `core-meta.json`), applied to
 * a path. A port of `theme-core/packages/theme-core/src/kit/route-map.js` (doc 01 section 9): canonical
 * paths never change in the theme's route files or in plugin registrations, a theme only says which
 * public path a canonical one is served at, or that it is not served at all.
 *
 * Patterns use the `[param]` syntax of the theme's route matcher (`/store/[slug]`, and a final
 * `[...rest]`). The URL map writes the same thing as `{param}`, so [publicTemplate] converts both ways.
 * Params are carried over as the raw path segments, so percent-encoding is never touched.
 */
class ThemeRouteMap(rename: Map<String, String> = emptyMap(), disable: List<String> = emptyList()) {
    private sealed interface Segment {
        data class Literal(val value: String) : Segment
        data class Param(val name: String) : Segment
        data class Rest(val name: String) : Segment
    }

    private class Pattern(val source: String, val segments: List<Segment>, val names: List<String>) {
        /** Specificity per segment (literal 2, param 1, rest 0); compared lexicographically. */
        val score: List<Int> = segments.map {
            when (it) {
                is Segment.Literal -> 2
                is Segment.Param -> 1
                is Segment.Rest -> 0
            }
        }
    }

    private class Hit(val pattern: Pattern, val params: Map<String, String>, val trailingSlash: Boolean, val suffix: String)

    private class Rename(val canonical: Pattern, val public: Pattern)

    private val byPublic: List<Pattern>
    private val byCanonical: List<Pattern>
    private val canonicalOfPublic = HashMap<Pattern, Pattern>()
    private val publicOfCanonical = HashMap<Pattern, Pattern>()
    private val disabled: List<Pattern>

    /** True when the config changes nothing. */
    val isIdentity: Boolean

    init {
        val renames = mutableListOf<Rename>()
        val publicSeen = HashMap<String, String>()

        for ((canonicalSource, publicSource) in rename) {
            val canonical = parsePattern(canonicalSource)
            val public = parsePattern(publicSource)

            val same = canonical.names.size == public.names.size && canonical.names.all { it in public.names }

            require(same) {
                "Route rename \"$canonicalSource\" -> \"$publicSource\": both sides need the same params " +
                    "([${canonical.names.joinToString(", ")}] vs [${public.names.joinToString(", ")}])."
            }

            // A rest param must stay a rest param (and a plain param a plain param), otherwise the
            // reverse mapping would merge or split segments.
            require(kinds(canonical) == kinds(public)) {
                "Route rename \"$canonicalSource\" -> \"$publicSource\": a [...rest] param must be a [...rest] param on both sides."
            }

            // `/a/[x]` and `/a/[y]` are the same public shape.
            val shape = public.segments.joinToString("/") {
                when (it) {
                    is Segment.Literal -> it.value
                    is Segment.Param -> "*"
                    is Segment.Rest -> "**"
                }
            }

            publicSeen[shape]?.let {
                throw IllegalArgumentException("Route rename: \"$publicSource\" is the public path of both \"$it\" and \"$canonicalSource\".")
            }

            publicSeen[shape] = canonicalSource
            renames.add(Rename(canonical, public))
        }

        disabled = disable.map { parsePattern(it) }.sortedWith(::compareSpecificity)
        byPublic = renames.map { it.public }.sortedWith(::compareSpecificity)
        byCanonical = renames.map { it.canonical }.sortedWith(::compareSpecificity)

        renames.forEach {
            canonicalOfPublic[it.public] = it.canonical
            publicOfCanonical[it.canonical] = it.public
        }

        isIdentity = renames.isEmpty() && disabled.isEmpty()
    }

    /**
     * Public pathname to canonical pathname. Null = the route is disabled. A canonical path that was
     * renamed away comes back unchanged.
     */
    fun toCanonical(pathname: String): String? {
        val canonical = findPattern(byPublic, pathname)?.let { rewrite(it, canonicalOfPublic.getValue(it.pattern)) } ?: pathname

        return if (findPattern(disabled, canonical) != null) null else canonical
    }

    /**
     * Canonical path to public path. A path without a rename comes back as given; query and hash are
     * kept. A disabled route is not looked at: ask [isDisabled].
     */
    fun toPublic(canonicalPath: String): String =
        findPattern(byCanonical, canonicalPath)?.let { rewrite(it, publicOfCanonical.getValue(it.pattern)) } ?: canonicalPath

    /** True for a canonical path whose public path is different (and which is not itself the public path of another rename). */
    fun isRenamedAway(pathname: String): Boolean {
        if (findPattern(byCanonical, pathname) == null) {
            return false
        }

        return findPattern(byPublic, pathname) == null
    }

    /** True when the theme disables the canonical [canonicalPath]. */
    fun isDisabled(canonicalPath: String): Boolean = findPattern(disabled, canonicalPath) != null

    /**
     * A target's default path with `{param}` placeholders (the way the URL map writes it) through this
     * config: the public path, again with `{param}` placeholders, or null when the route is disabled.
     * A query string is kept as written.
     */
    fun publicTemplate(template: String): String? {
        if (isIdentity) {
            return template
        }

        val (pathOnly, suffix) = splitSuffix(template)
        val canonical = braceToBracket(pathOnly) + suffix

        if (isDisabled(canonical)) {
            return null
        }

        val (publicPath, publicSuffix) = splitSuffix(toPublic(canonical))

        return bracketToBrace(publicPath) + publicSuffix
    }

    private fun parsePattern(source: String): Pattern {
        require(source.startsWith("/") && !source.startsWith("//")) {
            "Invalid route pattern ${quote(source)}: a route starts with a single \"/\"."
        }
        require(!source.contains('?') && !source.contains('#')) { "Invalid route pattern \"$source\": no query string or hash." }

        val parts = source.split("/").filter { it.isNotEmpty() }
        val segments = mutableListOf<Segment>()
        val names = mutableListOf<String>()

        parts.forEachIndexed { index, part ->
            val match = PARAM.matchEntire(part)

            if (match == null) {
                require(!part.contains('[') && !part.contains(']')) { "Invalid route pattern \"$source\": unsupported segment \"$part\"." }

                segments.add(Segment.Literal(part))

                return@forEachIndexed
            }

            val dots = match.groupValues[1].isNotEmpty()
            val name = match.groupValues[2]

            require(name !in names) { "Invalid route pattern \"$source\": param \"$name\" is used twice." }

            names.add(name)

            if (dots) {
                require(index == parts.size - 1) { "Invalid route pattern \"$source\": a [...$name] segment must be the last one." }

                segments.add(Segment.Rest(name))
            } else {
                segments.add(Segment.Param(name))
            }
        }

        return Pattern(source, segments, names)
    }

    private fun kinds(pattern: Pattern): List<String> =
        pattern.segments.mapNotNull {
            when (it) {
                is Segment.Param -> "p:${it.name}"
                is Segment.Rest -> "r:${it.name}"
                is Segment.Literal -> null
            }
        }.sorted()

    private fun compareSpecificity(a: Pattern, b: Pattern): Int {
        val length = maxOf(a.score.size, b.score.size)

        for (i in 0 until length) {
            val left = a.score.getOrElse(i) { -1 }
            val right = b.score.getOrElse(i) { -1 }

            if (left != right) {
                return right - left
            }
        }

        return 0
    }

    private fun matchPattern(pattern: Pattern, parts: List<String>): Map<String, String>? {
        val params = HashMap<String, String>()
        val segments = pattern.segments
        val last = segments.lastOrNull()
        val hasRest = last is Segment.Rest
        val fixed = if (hasRest) segments.size - 1 else segments.size

        if (if (hasRest) parts.size < fixed else parts.size != fixed) {
            return null
        }

        for (i in 0 until fixed) {
            when (val segment = segments[i]) {
                is Segment.Literal -> if (segment.value != parts[i]) return null
                is Segment.Param -> params[segment.name] = parts[i]
                is Segment.Rest -> Unit
            }
        }

        if (last is Segment.Rest) {
            params[last.name] = parts.drop(fixed).joinToString("/")
        }

        return params
    }

    private fun fillPattern(pattern: Pattern, params: Map<String, String>): String {
        val parts = mutableListOf<String>()

        for (segment in pattern.segments) {
            when (segment) {
                is Segment.Literal -> parts.add(segment.value)
                is Segment.Param -> params[segment.name]?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
                is Segment.Rest -> params[segment.name]?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            }
        }

        return "/" + parts.joinToString("/")
    }

    private fun findPattern(patterns: List<Pattern>, path: String): Hit? {
        val (pathOnly, suffix) = splitSuffix(path)

        if (!pathOnly.startsWith("/") || pathOnly.startsWith("//")) {
            return null
        }

        val parts = pathOnly.split("/").filter { it.isNotEmpty() }
        val trailingSlash = parts.isNotEmpty() && pathOnly.endsWith("/")

        for (pattern in patterns) {
            val params = matchPattern(pattern, parts)

            if (params != null) {
                return Hit(pattern, params, trailingSlash, suffix)
            }
        }

        return null
    }

    private fun rewrite(hit: Hit, target: Pattern): String {
        val path = fillPattern(target, hit.params)

        return path + (if (hit.trailingSlash && path != "/") "/" else "") + hit.suffix
    }

    companion object {
        /** A theme with no route config. */
        val IDENTITY = ThemeRouteMap()

        private val PARAM = Regex("""^\[(\.\.\.)?([A-Za-z_$][\w$]*)]$""")
        private val BRACE_SEGMENT = Regex("""^\{([A-Za-z_][\w-]*)}$""")
        private val BRACKET_SEGMENT = Regex("""^\[([A-Za-z_$][\w$]*)]$""")

        private fun quote(value: String) = "\"" + value.replace("\"", "\\\"") + "\""

        /** Splits `/a/b/?x=1#y` into the path part and the untouched suffix. */
        internal fun splitSuffix(value: String): Pair<String, String> {
            val index = value.indexOfFirst { it == '?' || it == '#' }

            return if (index == -1) value to "" else value.substring(0, index) to value.substring(index)
        }

        private fun mapSegments(path: String, transform: (String) -> String): String =
            path.split("/").joinToString("/", transform = transform)

        private fun braceToBracket(path: String) =
            mapSegments(path) { segment -> BRACE_SEGMENT.matchEntire(segment)?.let { "[${it.groupValues[1]}]" } ?: segment }

        private fun bracketToBrace(path: String) =
            mapSegments(path) { segment -> BRACKET_SEGMENT.matchEntire(segment)?.let { "{${it.groupValues[1]}}" } ?: segment }

        /**
         * Reads the `routes` object of a theme's `core-meta.json` (`{ rename, disable, add }`; `add` only
         * names new pages and changes no target). Null or empty gives [IDENTITY]. A config the theme build
         * should never have written (a bad pattern) throws [IllegalArgumentException].
         */
        fun fromCoreMeta(routes: JsonObject?): ThemeRouteMap {
            if (routes == null) {
                return IDENTITY
            }

            val rename = routes.getJsonObject("rename")?.let { renames ->
                renames.fieldNames().associateWith { key ->
                    renames.getValue(key) as? String
                        ?: throw IllegalArgumentException("Route rename of \"$key\" must be a path string.")
                }
            } ?: emptyMap()

            val disable = routes.getJsonArray("disable")?.map {
                it as? String ?: throw IllegalArgumentException("A disabled route must be a path string.")
            } ?: emptyList()

            return if (rename.isEmpty() && disable.isEmpty()) IDENTITY else ThemeRouteMap(rename, disable)
        }
    }
}
