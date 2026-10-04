package com.panomc.platform.setup

import com.panomc.platform.config.PanoConfig
import com.panomc.platform.hosted.HostedEnvConfig
import com.panomc.platform.setup.SetupManager.Companion.databasePasswordView
import com.panomc.platform.setup.SetupManager.Companion.effectiveDatabasePassword
import com.panomc.platform.setup.SetupManager.Companion.emailView
import com.panomc.platform.setup.SetupManager.Companion.skippedSteps
import com.panomc.platform.setup.SetupManager.Companion.stepBackTo
import com.panomc.platform.setup.SetupManager.Companion.stepForward
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SetupStepsTest {
    @Test
    fun `nothing managed walks every step`() {
        val none = skippedSteps(false, false)

        assertEquals(listOf(1, 2, 3, 4, 4), listOf(0, 1, 2, 3, 4).map { stepForward(it, none) })
        assertEquals(listOf(0, 0, 1, 2, 3), listOf(-1, 0, 1, 2, 3).map { stepBackTo(it, none) })
    }

    @Test
    fun `managed database skips step 2`() {
        val db = skippedSteps(true, false)

        assertEquals(3, stepForward(1, db))
        assertEquals(1, stepBackTo(2, db))
        assertEquals(3, stepBackTo(3, db))
    }

    @Test
    fun `managed database and mail go from 1 straight to 4 and back to 1`() {
        val both = skippedSteps(true, true)

        assertEquals(4, stepForward(1, both))
        assertEquals(1, stepBackTo(3, both))
        assertEquals(1, stepBackTo(2, both))
    }

    @Test
    fun `managed mail alone skips step 3 both ways`() {
        val mail = skippedSteps(false, true)

        assertEquals(4, stepForward(2, mail))
        assertEquals(2, stepBackTo(3, mail))
    }

    @Test
    fun `step 3 data never carries the smtp password`() {
        val mail = PanoConfig.Companion.EmailConfig(enabled = true, hostname = "relay.portal", username = "wl_abc", password = "relay-s3cret-value")

        val view = emailView(mail)

        assertEquals("relay.portal", view["hostname"])
        assertEquals("", view["password"])
        assertFalse(view.toString().contains("relay-s3cret-value"))
    }

    private val envDb = HostedEnvConfig(
        mapOf("PANO_CONTAINER" to "1", "PANO_DB_HOST" to "db", "PANO_DB_NAME" to "pano", "PANO_DB_USER" to "pano", "PANO_DB_PASSWORD" to "env-s3cret")
    ).database!!

    private fun seeded() = PanoConfig.Companion.DatabaseConfig(host = "db:3306", name = "pano", username = "pano", password = "env-s3cret")

    @Test
    fun `step 2 never returns a password seeded from PANO_DB_PASSWORD on a self-run image`() {
        assertEquals("", databasePasswordView(seeded(), envDb, managed = false))
        assertEquals("", databasePasswordView(seeded(), envDb, managed = true))
    }

    @Test
    fun `step 2 still returns a password the wizard stored itself`() {
        val typed = seeded().copy(password = "typed-in-wizard")

        assertEquals("typed-in-wizard", databasePasswordView(typed, envDb, managed = false))
        assertEquals("typed-in-wizard", databasePasswordView(typed, null, managed = false))
    }

    @Test
    fun `empty step 2 password keeps the env one only for the same host, name and user`() {
        val db = seeded()

        assertEquals("env-s3cret", effectiveDatabasePassword("", "db:3306", "pano", "pano", db, envDb))
        assertEquals("env-s3cret", effectiveDatabasePassword(null, "db:3306", "pano", "pano", db, envDb))
        assertEquals("", effectiveDatabasePassword("", "evil.example:3306", "pano", "pano", db, envDb))
        assertEquals("", effectiveDatabasePassword("", "db:3306", "other", "pano", db, envDb))
        assertEquals("", effectiveDatabasePassword("", "db:3306", "pano", "root", db, envDb))
        assertEquals("new", effectiveDatabasePassword("new", "db:3306", "pano", "pano", db, envDb))
    }

    @Test
    fun `empty step 2 password stays empty without an env-seeded one`() {
        val typed = seeded().copy(password = "typed-in-wizard")

        assertEquals("", effectiveDatabasePassword("", "db:3306", "pano", "pano", typed, envDb))
        assertEquals("", effectiveDatabasePassword("", "db:3306", "pano", "pano", seeded(), null))
    }
}
