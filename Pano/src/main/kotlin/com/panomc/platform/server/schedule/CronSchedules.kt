package com.panomc.platform.server.schedule

import com.cronutils.descriptor.CronDescriptor
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Reading five-field Unix cron, in a time zone, without pretending time is simple.
 *
 * Everything here works in [ZonedDateTime] rather than in epoch milliseconds, because that is the
 * only representation in which "every day at 04:00" survives a daylight-saving change. On the
 * spring-forward night 02:30 does not exist and on the autumn night it happens twice, and
 * cron-utils resolves both against the zone's rules; arithmetic on milliseconds would quietly
 * shift every run by an hour for half the year.
 *
 * Nothing throws: an expression somebody typed is validated, not trusted, and an unparsable one is
 * reported as invalid rather than propagated as an exception through a panel request or, worse,
 * through a timer tick that would then stop running every other schedule.
 */
object CronSchedules {
    /** Longest expression accepted, so a pathological one never reaches the parser. */
    const val MAX_CRON_LENGTH = 100

    private val definition = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)

    private val parser = CronParser(definition)

    // English only, on purpose. cron-utils ships bundles for a handful of languages and the panel
    // is translated into others it does not cover, so picking the request's locale would produce a
    // sentence in a fourth language on some pages and the right one on others. One predictable
    // language beats a lottery.
    private val descriptor = CronDescriptor.instance(Locale.ENGLISH)

    /** Whether [expression] is a five-field Unix cron this can actually schedule. */
    fun isValid(expression: String?): Boolean = parse(expression) != null

    /** Whether [id] names a time zone this JVM knows. */
    fun isValidZone(id: String?): Boolean = resolveZone(id) != null

    /** [id] as a zone, or the platform's own when it is missing or unknown. */
    fun zoneOf(id: String?): ZoneId = resolveZone(id) ?: ZoneId.systemDefault()

    /**
     * A plain-English rendering of [expression] ("at 04:00", "every 15 minutes"), or null when it
     * cannot be parsed.
     *
     * The point of this is that nobody reads `0 4 * * 0` and is sure. The descriptor is the only
     * thing between a typo and a server that restarts on the wrong day, so when it fails for its
     * own reasons the expression itself is returned rather than nothing: a raw expression is worse
     * than a sentence but far better than an empty field where the confirmation should be.
     */
    fun describe(expression: String?): String? {
        val cron = parse(expression) ?: return null

        return try {
            descriptor.describe(cron).trim().ifEmpty { cron.asString() }
        } catch (_: Exception) {
            cron.asString()
        }
    }

    /**
     * The next [count] times [expression] fires, as epoch milliseconds.
     *
     * Each one is computed from the previous, in the zone, so a series that crosses a DST boundary
     * keeps its wall-clock time instead of drifting by an hour.
     */
    fun nextRuns(expression: String?, zoneId: String?, count: Int, from: Long = System.currentTimeMillis()): List<Long> {
        val cron = parse(expression) ?: return emptyList()

        val zone = zoneOf(zoneId)
        val executionTime = ExecutionTime.forCron(cron)

        var cursor = ZonedDateTime.ofInstant(Instant.ofEpochMilli(from), zone)

        val runs = mutableListOf<Long>()

        repeat(count.coerceIn(0, MAX_PREVIEW)) {
            val next = executionTime.nextExecution(cursor).orElse(null) ?: return runs

            runs.add(next.toInstant().toEpochMilli())

            cursor = next
        }

        return runs
    }

    /** The next time [expression] fires after [from], or null when it never does. */
    fun nextRun(expression: String?, zoneId: String?, from: Long = System.currentTimeMillis()): Long? =
        nextRuns(expression, zoneId, 1, from).firstOrNull()

    /**
     * Whether [expression] fires in the minute that [at] falls in.
     *
     * The tick that drives schedules runs every 60 seconds but never exactly on the second, so a
     * comparison against an exact instant would miss almost every run. Truncating both sides to
     * the minute is what makes a 60-second timer reliable, and the caller's own bookkeeping is
     * what stops a minute from firing twice.
     */
    fun firesAt(expression: String?, zoneId: String?, at: Long): Boolean {
        val cron = parse(expression) ?: return false

        val zone = zoneOf(zoneId)
        val executionTime = ExecutionTime.forCron(cron)

        val moment = ZonedDateTime.ofInstant(Instant.ofEpochMilli(at), zone).withSecond(0).withNano(0)

        // One second before the minute, so a run due exactly at the minute boundary is the next
        // execution rather than one that has already passed.
        val probe = moment.minusSeconds(1)

        val next = executionTime.nextExecution(probe).orElse(null) ?: return false

        return next.withSecond(0).withNano(0) == moment
    }

    private fun parse(expression: String?) = try {
        val value = expression?.trim().orEmpty()

        if (value.isEmpty() || value.length > MAX_CRON_LENGTH) {
            null
        } else {
            parser.parse(value).apply { validate() }
        }
    } catch (_: Exception) {
        null
    }

    private fun resolveZone(id: String?): ZoneId? {
        val value = id?.trim().orEmpty()

        if (value.isEmpty()) {
            return null
        }

        return try {
            ZoneId.of(value)
        } catch (_: Exception) {
            null
        }
    }

    private const val MAX_PREVIEW = 20
}
