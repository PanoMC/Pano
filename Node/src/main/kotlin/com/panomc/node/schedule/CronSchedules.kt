package com.panomc.node.schedule

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Reading five-field Unix cron, in a time zone, on the node.
 *
 * Deliberately the same library and the same rules as Pano's own `CronSchedules`, so a schedule
 * saved in the panel and the schedule this daemon fires cannot disagree — including about the two
 * nights a year when a wall-clock time either does not exist or happens twice.
 *
 * Nothing throws. An expression that somehow arrived unparsable simply never fires, because the
 * alternative is one bad schedule stopping the timer that runs all the others.
 */
object CronSchedules {
    private val definition = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)

    private val parser = CronParser(definition)

    fun isValid(expression: String?): Boolean = parse(expression) != null

    fun zoneOf(id: String?): ZoneId {
        val value = id?.trim().orEmpty()

        if (value.isEmpty()) {
            return ZoneId.systemDefault()
        }

        return try {
            ZoneId.of(value)
        } catch (_: Exception) {
            ZoneId.systemDefault()
        }
    }

    /**
     * Whether [expression] fires in the minute [at] falls in.
     *
     * The minute rather than the instant, because the tick that asks this runs every sixty seconds
     * but never on the same millisecond twice.
     */
    fun firesAt(expression: String?, zoneId: String?, at: Long): Boolean {
        val cron = parse(expression) ?: return false

        val executionTime = ExecutionTime.forCron(cron)

        val moment = ZonedDateTime.ofInstant(Instant.ofEpochMilli(at), zoneOf(zoneId)).withSecond(0).withNano(0)

        val next = executionTime.nextExecution(moment.minusSeconds(1)).orElse(null) ?: return false

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

    private const val MAX_CRON_LENGTH = 100
}
