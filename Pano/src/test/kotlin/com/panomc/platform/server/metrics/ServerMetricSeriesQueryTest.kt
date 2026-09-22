package com.panomc.platform.server.metrics

import com.panomc.platform.db.implementation.ServerMetricDailyDaoImpl
import com.panomc.platform.db.implementation.ServerMetricDaoImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shape of the bulk series query behind the servers modal (§2.4.18 B).
 *
 * It cannot be run here — that needs a MariaDB — so what is checked is what a database would not
 * catch until production: that the server ids are placeholders rather than interpolated text, that
 * there is one placeholder per id in the order the Tuple fills them, and that the statement really
 * is one grouped query rather than a loop that happens to compile.
 */
class ServerMetricSeriesQueryTest {
    @Test
    fun `every server id is a placeholder, never written into the statement`() {
        val query = ServerMetricDaoImpl.seriesForServersQuery("pano_server_metric", 3)

        assertTrue(query.contains("`serverId` IN (?, ?, ?)"), query)
        // Four for the bucket (offset, size, size, offset), three ids, then the two ends of the window.
        assertEquals(9, query.count { it == '?' })
    }

    @Test
    fun `one server asks the same question as many`() {
        val query = ServerMetricDaoImpl.seriesForServersQuery("pano_server_metric", 1)

        assertTrue(query.contains("`serverId` IN (?)"), query)
        assertEquals(7, query.count { it == '?' })
    }

    @Test
    fun `the buckets are grouped and ordered per server`() {
        val query = ServerMetricDaoImpl.seriesForServersQuery("pano_server_metric", 2)

        assertTrue(query.contains("GROUP BY `serverId`, `bucket`"), query)
        assertTrue(query.contains("ORDER BY `serverId` ASC, `bucket` ASC"), query)
        assertTrue(query.startsWith("SELECT `serverId`, FLOOR((`ts` + ?) / ?) * ? - ? AS `bucket`"), query)
        assertTrue(query.contains("FROM `pano_server_metric`"), query)
        assertTrue(query.contains("`ts` >= ? AND `ts` <= ?"), query)
    }

    @Test
    fun `disk is the bucket's biggest figure, not its average`() {
        val query = ServerMetricDaoImpl.seriesForServersQuery("pano_server_metric", 1)

        // Measured every few minutes at best, so most of the minutes in a bucket carry no figure
        // at all and an average would drag the line towards zero.
        assertTrue(query.contains("CAST(MAX(`diskUsed`) AS SIGNED) AS `diskUsed`"), query)
        assertFalse(query.contains("AVG(`diskUsed`)"), query)
    }

    @Test
    fun `the hour and day views ask one grouped question for their minutes or hours`() {
        val query = ServerMetricDaoImpl.playerActivityQuery("pano_server_metric")

        assertTrue(query.startsWith("SELECT FLOOR(`ts` / ?) * ? AS `bucket`"), query)
        assertTrue(query.contains("CAST(MAX(`players`) AS SIGNED) AS `peak`"), query)
        assertTrue(query.contains("AVG(`players`) AS `average`"), query)
        assertTrue(query.contains("WHERE `serverId` = ? AND `ts` >= ?"), query)
        assertTrue(query.contains("GROUP BY `bucket` ORDER BY `bucket` ASC"), query)
        assertEquals(4, query.count { it == '?' })
    }

    @Test
    fun `a minute is folded into its day in one statement, the average before the count moves on`() {
        val query = ServerMetricDailyDaoImpl.recordQuery("pano_server_metric_daily")

        assertTrue(query.startsWith("INSERT INTO `pano_server_metric_daily`"), query)
        assertTrue(query.contains("VALUES (?, ?, ?, ?, 1)"), "a new day starts at one sample: $query")
        assertTrue(query.contains("ON DUPLICATE KEY UPDATE"), query)
        assertTrue(query.contains("`peakPlayers` = GREATEST(`peakPlayers`, VALUES(`peakPlayers`))"), query)

        val average = "`avgPlayers` = (`avgPlayers` * `samples` + VALUES(`avgPlayers`)) / (`samples` + 1)"
        val count = "`samples` = `samples` + 1"

        assertTrue(query.contains(average), query)
        assertTrue(query.contains(count), query)
        // MariaDB applies the assignments left to right: the mean must use the old count.
        assertTrue(query.indexOf(average) < query.indexOf(count), query)
        assertEquals(4, query.count { it == '?' })
    }

    @Test
    fun `the daily table is keyed by server and day, so the upsert has something to collide on`() {
        val ddl = ServerMetricDailyDaoImpl.createTableQuery("pano_server_metric_daily")

        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS `pano_server_metric_daily`"), ddl)
        assertTrue(ddl.contains("UNIQUE KEY `uk_server_metric_daily_server_day` (`serverId`, `day`)"), ddl)
    }

    @Test
    fun `traffic is the bucket's average rate, as an integer`() {
        val query = ServerMetricDaoImpl.seriesForServersQuery("pano_server_metric", 1)

        assertTrue(query.contains("CAST(AVG(`netRx`) AS SIGNED) AS `netRx`"), query)
        assertTrue(query.contains("CAST(AVG(`netTx`) AS SIGNED) AS `netTx`"), query)
        // Still one parameter per id, the bucket and the window: nothing new to bind.
        assertEquals(7, query.count { it == '?' })
    }

    @Test
    fun `the activity chart asks one grouped question per period, not one per day`() {
        val query = ServerMetricDaoImpl.dailyPlayerActivityQuery("pano_server_metric")

        // The offset is a parameter, the day is arithmetic on the stored timestamp, and the three
        // placeholders are offset, server and window start in that order.
        assertTrue(query.contains("FLOOR((`ts` + ?) / 86400000) AS `day`"), query)
        assertTrue(query.contains("CAST(MAX(`players`) AS SIGNED) AS `peak`"), query)
        assertTrue(query.contains("AVG(`players`) AS `average`"), query)
        assertTrue(query.contains("WHERE `serverId` = ? AND `ts` >= ?"), query)
        assertTrue(query.contains("GROUP BY `day` ORDER BY `day` ASC"), query)
        assertEquals(3, query.count { it == '?' }, query)
    }

    @Test
    fun `a bucket is shifted by an offset, which is zero for everything but local days`() {
        // With offset 0, FLOOR((ts + 0) / b) * b - 0 is the plain FLOOR(ts / b) * b every series had
        // before, so no existing range changes its keys.
        val query = ServerMetricDaoImpl.seriesForServersQuery("pano_server_metric", 1)

        assertTrue(query.contains("FLOOR((`ts` + ?) / ?) * ? - ? AS `bucket`"), query)
        assertEquals(0L, MetricsSeriesWindow.of(MetricsRange.WEEK, null)!!.offsetMs(System.currentTimeMillis()))
    }

    @Test
    fun `a range names the same window and bucket wherever it is asked for`() {
        assertEquals(60L * 60L * 1000L, MetricsRange.HOUR.durationMs)
        assertEquals(60L * 1000L, MetricsRange.HOUR.bucketMs)
        assertEquals(MetricsRange.DAY, MetricsRange.fromId("24h"))
        assertEquals(MetricsRange.MONTH, MetricsRange.fromId("30d"))
    }

    @Test
    fun `anything else is the hour view rather than an error`() {
        assertEquals(MetricsRange.HOUR, MetricsRange.fromId(null))
        assertEquals(MetricsRange.HOUR, MetricsRange.fromId(""))
        assertEquals(MetricsRange.HOUR, MetricsRange.fromId("all of it"))
    }
}
