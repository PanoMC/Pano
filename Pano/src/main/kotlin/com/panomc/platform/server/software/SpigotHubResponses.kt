package com.panomc.platform.server.software

import io.vertx.core.json.JsonObject

/**
 * Reads the two things hub.spigotmc.org publishes about a Spigot revision.
 *
 * Unlike every other provider in the catalog this one has no API: the revision list is an Nginx
 * directory index (`<a href="1.21.8.json">`) and the per-revision file is the metadata BuildTools
 * itself reads. Parsing HTML is not something to be proud of, but the alternative is asking people
 * to type a revision by hand into a wizard that lists every other software for them.
 *
 * Kept apart from [ServerSoftwareCatalog] so both shapes can be asserted against captured
 * responses rather than against the live site.
 */
object SpigotHubResponses {
    /**
     * The revisions worth offering, newest first.
     *
     * The index holds about four thousand entries, and almost all of them are BuildTools' own
     * build numbers (`996.json`) plus development revisions (`1.13-pre7.json`) and `latest.json`.
     * Only `x.y` and `x.y.z` survive: those are the revisions somebody means when they pick a
     * Minecraft version, and the rest would make the list unreadable without making it more
     * useful.
     */
    fun versions(listing: String?): List<String> {
        val html = listing ?: return emptyList()

        val names = LISTING_ENTRY.findAll(html)
            .map { it.groupValues[1] }
            .filter { VERSION_NAME.matches(it) }
            .distinct()
            .toList()

        // The index is sorted as text, which puts 1.9 after 1.10 and 26.3 in the middle; the
        // wizard wants the newest first, and that is a numeric question.
        return SoftwareVersions.newestFirst(names)
    }

    /**
     * The Java major a revision is compiled with, or null when the hub does not say.
     *
     * `javaVersions` is a pair of *class file* majors -- `[65, 69]` on 1.21.8 -- naming the range
     * BuildTools accepts. The lower bound is what is taken: it is the version the revision was
     * actually made for, and BuildTools refuses to compile an old revision on a JDK past its
     * upper bound, so aiming at the bottom of the range is the choice that works on every host
     * that has it.
     *
     * Older revisions carry no `javaVersions` at all (1.8.8 has nothing but its refs), which is
     * why the caller has [com.panomc.platform.server.MinecraftJavaVersions] to fall back on.
     */
    fun javaMajor(version: JsonObject?): Int? {
        val majors = version?.getJsonArray("javaVersions") ?: return null

        val minimum = majors.list.firstOrNull() as? Number ?: return null

        return javaMajorOf(minimum.toInt())
    }

    /**
     * Java's own numbering for a class file major, e.g. 65 -> 21.
     *
     * The table is the one the contract names; anything past it is worked out arithmetically
     * rather than treated as unknown, because the offset has been constant since Java 1.1 and a
     * revision released after this code was written is not a reason to guess the Java wrong.
     */
    fun javaMajorOf(classMajor: Int): Int? {
        CLASS_FILE_MAJORS[classMajor]?.let { return it }

        return if (classMajor < OLDEST_CLASS_MAJOR) null else classMajor - CLASS_MAJOR_OFFSET
    }

    /** The class-file majors the contract pins, so the common revisions never depend on the sum. */
    private val CLASS_FILE_MAJORS = mapOf(52 to 8, 55 to 11, 60 to 16, 61 to 17, 65 to 21)

    /** Java 8's class file major; nothing BuildTools still builds is older. */
    private const val OLDEST_CLASS_MAJOR = 52

    /** Class major minus Java major, constant across every release Java has ever made. */
    private const val CLASS_MAJOR_OFFSET = 44

    private val LISTING_ENTRY = Regex("href=\"([^\"/?#]+)\\.json\"", RegexOption.IGNORE_CASE)

    private val VERSION_NAME = Regex("^\\d+\\.\\d+(\\.\\d+)?$")
}
