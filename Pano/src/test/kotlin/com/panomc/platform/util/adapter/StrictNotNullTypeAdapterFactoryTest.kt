package com.panomc.platform.util.adapter

import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.panomc.platform.util.annotation.StrictValidation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StrictNotNullTypeAdapterFactoryTest {
    @StrictValidation
    data class WithDefault(
        val id: String,
        val premium: Boolean = false,
        val fileFingerprint: String? = null
    )

    @StrictValidation
    data class WithoutDefault(
        val id: String,
        val price: Double
    )

    private val gson = GsonBuilder()
        .registerTypeAdapterFactory(StrictNotNullTypeAdapterFactory())
        .create()

    @Test
    fun `parameter with a default value can be omitted from JSON`() {
        val parsed = gson.fromJson("""{"id":"foo"}""", WithDefault::class.java)
        assertNotNull(parsed)
        assertEquals("foo", parsed.id)
        assertEquals(false, parsed.premium, "premium default must be applied")
        assertEquals(null, parsed.fileFingerprint, "nullable field must default to null")
    }

    @Test
    fun `parameter without a default value is still required`() {
        val ex = assertThrows(JsonParseException::class.java) {
            gson.fromJson("""{"id":"foo"}""", WithoutDefault::class.java)
        }
        assertEquals("Missing non-nullable fields: price", ex.message)
    }

    @Test
    fun `JSON value overrides a Kotlin default`() {
        val parsed = gson.fromJson("""{"id":"foo","premium":true}""", WithDefault::class.java)
        assertEquals(true, parsed.premium)
    }
}
