package com.panomc.platform.maintenance

import com.panomc.platform.api.event.AuthEventListener.LoginDecision
import com.panomc.platform.route.api.maintenance.MaintenanceLoginAPI
import com.panomc.platform.route.api.maintenance.MaintenanceLoginAPI.SecondFactor
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MaintenanceSecondFactorTest {
    /** What auth-guard answers: a challenge without a code, a refusal for a wrong one. */
    private val twoFactor: (JsonObject) -> LoginDecision? = { body ->
        when (body.getString("totpCode")) {
            null -> LoginDecision.Deny("TWO_FACTOR_REQUIRED", mapOf("challengeField" to "totpCode"))
            "123456" -> null
            else -> LoginDecision.Deny("TWO_FACTOR_INVALID_CODE")
        }
    }

    /** A plugin that wants something else first; the recovery door ignores it. */
    private val other: (JsonObject) -> LoginDecision? = { LoginDecision.Deny("SOMETHING_ELSE") }

    private val broken: (JsonObject) -> LoginDecision? = { throw IllegalStateException("broken plugin") }

    private fun decide(listeners: List<(JsonObject) -> LoginDecision?>, code: String?): SecondFactor = runBlocking {
        MaintenanceLoginAPI.secondFactor(listeners, "admin", code) { listener, body ->
            // The endpoint treats a hook that throws as one that did not answer.
            try {
                listener(body)
            } catch (_: Exception) {
                null
            }
        }
    }

    @Test
    fun `an account without a second factor signs in with its password alone`() {
        assertEquals(SecondFactor.PASSED, decide(emptyList(), null))
        assertEquals(SecondFactor.PASSED, decide(listOf(other), null))
    }

    @Test
    fun `an account with a second factor needs a valid code`() {
        assertEquals(SecondFactor.REQUIRED, decide(listOf(twoFactor), null))
        assertEquals(SecondFactor.REQUIRED, decide(listOf(twoFactor), "  "))
        assertEquals(SecondFactor.INVALID, decide(listOf(twoFactor), "000000"))
        assertEquals(SecondFactor.PASSED, decide(listOf(twoFactor), " 123456 "))
    }

    @Test
    fun `other plugins and broken ones never lock staff out`() {
        assertEquals(SecondFactor.PASSED, decide(listOf(other, twoFactor), "123456"))
        assertEquals(SecondFactor.PASSED, decide(listOf(broken), null))
    }
}
