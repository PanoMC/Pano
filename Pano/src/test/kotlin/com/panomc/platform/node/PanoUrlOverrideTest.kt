package com.panomc.platform.node

import com.panomc.platform.error.BadRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PanoUrlOverrideTest {
    @Test
    fun `nothing given means no override`() {
        assertNull(PanoUrlOverride.sanitize(null))
        assertNull(PanoUrlOverride.sanitize(""))
        assertNull(PanoUrlOverride.sanitize("   "))
    }

    @Test
    fun `a tunnel address survives untouched apart from a trailing slash`() {
        // The whole point of the override is a non-default port on loopback; normalization must
        // not "helpfully" drop it, or the node reports to the wrong place.
        assertEquals("http://127.0.0.1:18088", PanoUrlOverride.sanitize("http://127.0.0.1:18088"))
        assertEquals("http://127.0.0.1:18088", PanoUrlOverride.sanitize("  http://127.0.0.1:18088/  "))
        assertEquals("https://pano.lan", PanoUrlOverride.sanitize("https://pano.lan"))
        assertEquals("http://192.168.1.10:8088", PanoUrlOverride.sanitize("http://192.168.1.10:8088"))
    }

    @Test
    fun `only http and https are addresses a node can be sent to`() {
        assertThrows<BadRequest> { PanoUrlOverride.sanitize("javascript:alert(1)") }
        assertThrows<BadRequest> { PanoUrlOverride.sanitize("file:///etc/passwd") }
        assertThrows<BadRequest> { PanoUrlOverride.sanitize("ws://127.0.0.1:18088") }
        // No scheme at all: guessing one would be guessing what somebody meant on a machine we
        // are about to run an installer on.
        assertThrows<BadRequest> { PanoUrlOverride.sanitize("127.0.0.1:18088") }
    }

    @Test
    fun `something that is not a host is refused rather than passed on`() {
        assertThrows<BadRequest> { PanoUrlOverride.sanitize("http://") }
        assertThrows<BadRequest> { PanoUrlOverride.sanitize("http://not a host/") }
        assertThrows<BadRequest> { PanoUrlOverride.sanitize("https://" + "a".repeat(PanoUrlOverride.MAX_LENGTH)) }
    }
}
