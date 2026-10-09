package com.panomc.platform.mail

import io.vertx.sqlclient.SqlClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.javaMethod

/**
 * P-4.2: shipped plugins link against the old JVM signatures, so they must stay; the new overload must exist
 * and return [MailResult].
 */
class MailManagerSignatureTest {
    private val manager = MailManager::class.java

    @Test
    fun `old four argument sendMail keeps its exact JVM signature`() {
        val method = manager.getDeclaredMethod(
            "sendMail",
            SqlClient::class.java,
            java.lang.Long::class.java,
            Mail::class.java,
            String::class.java,
            Continuation::class.java
        )

        assertTrue(Modifier.isPublic(method.modifiers))
        assertEquals(Any::class.java, method.returnType)
    }

    @Test
    fun `sendMail default bridge still exists`() {
        val method = manager.getDeclaredMethod(
            "sendMail\$default",
            MailManager::class.java,
            SqlClient::class.java,
            java.lang.Long::class.java,
            Mail::class.java,
            String::class.java,
            Continuation::class.java,
            Int::class.javaPrimitiveType,
            Any::class.java
        )

        assertTrue(Modifier.isStatic(method.modifiers))
        assertTrue(Modifier.isPublic(method.modifiers))
    }

    @Test
    fun `new MailOptions overload exists`() {
        val method = manager.getDeclaredMethod(
            "sendMail",
            SqlClient::class.java,
            java.lang.Long::class.java,
            Mail::class.java,
            MailOptions::class.java,
            Continuation::class.java
        )

        assertTrue(Modifier.isPublic(method.modifiers))
    }

    @Test
    fun `new overload returns MailResult and has no default arguments`() {
        val function = MailManager::class.declaredFunctions.single { function ->
            function.name == "sendMail" &&
                function.parameters.any { (it.type.classifier as? kotlin.reflect.KClass<*>) == MailOptions::class }
        }

        assertEquals(MailResult::class, function.returnType.classifier)
        assertTrue(function.parameters.none { it.isOptional }, "the new overload must not have default arguments")
        assertNotNull(function.javaMethod)

        val defaultBridges = manager.declaredMethods.filter {
            it.name == "sendMail\$default" && it.parameterTypes.contains(MailOptions::class.java)
        }

        assertTrue(defaultBridges.isEmpty(), "no sendMail\$default bridge for the MailOptions overload")
    }

    @Test
    fun `exactly the two sendMail overloads plus the default bridge exist`() {
        val names = manager.declaredMethods.filter { it.name.startsWith("sendMail") && !it.name.contains("\$lambda") }

        assertEquals(3, names.size, names.joinToString { it.toString() })
    }

    @Test
    fun `MailResult has exactly SENT and DISABLED`() {
        assertEquals(listOf("SENT", "DISABLED"), MailResult.values().map { it.name })
    }

    @Test
    fun `MailOptions and MailFile have the contract constructors`() {
        val options = MailOptions(
            email = "a@b.com", locale = "tr", subject = "s", text = "t", replyTo = "r@b.com",
            attachments = listOf(MailFile("n", "a/b", byteArrayOf(1)))
        )

        assertEquals("a@b.com", options.email)
        assertEquals("tr", options.locale)
        assertEquals(1, options.attachments.size)
        assertEquals("n", options.attachments.single().name)
    }
}
