package com.panomc.platform.mail.template

import com.panomc.platform.error.InvalidFields
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Plugins link against InvalidFields() and InvalidFields(Map); the template endpoints add a message. */
class InvalidFieldsCompatTest {
    @Test
    fun `the old constructors keep their JVM signatures`() {
        InvalidFields::class.java.getConstructor()
        InvalidFields::class.java.getConstructor(Map::class.java)
    }

    @Test
    fun `a message extra becomes error message next to the fields`() {
        val encoded = InvalidFields(mapOf("body" to "TEMPLATE_ERROR"), mapOf("message" to "broken")).encode()

        assertTrue(encoded.contains("\"message\":\"broken\""), encoded)
        assertTrue(encoded.contains("\"fields\":{\"body\":\"TEMPLATE_ERROR\"}"), encoded)
        assertTrue(encoded.contains("\"code\":\"INVALID_FIELDS\""), encoded)
    }
}
