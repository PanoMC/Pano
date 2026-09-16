package com.panomc.platform

import com.panomc.platform.UIManager.Companion.uiCachePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UICachePolicyTest {
    private val immutable = "public, max-age=31536000, immutable"

    @Test
    fun `html is always revalidated`() {
        assertEquals("no-cache", uiCachePolicy("/", 200, "text/html; charset=utf-8", false))
        assertEquals("no-cache", uiCachePolicy("/posts/x", 404, "text/html", false))
    }

    @Test
    fun `hashed lib bundles are immutable only on success`() {
        assertEquals(immutable, uiCachePolicy("/lib/be8641ae3b66de78/bootstrap.js", 200, "text/javascript", false))
        assertEquals(immutable, uiCachePolicy("/panel/lib/be8641ae3b66de78/x.js", 200, "text/javascript", false))
        assertEquals("no-store", uiCachePolicy("/lib/be8641ae3b66de78/x.js", 404, "text/html", false))
    }

    @Test
    fun `runtime shims are immutable with a version param and revalidated without`() {
        assertEquals(immutable, uiCachePolicy("/runtime/svelte/index.js", 200, "text/javascript", true))
        assertEquals("no-cache", uiCachePolicy("/runtime/svelte/index.js", 200, "text/javascript", false))
        assertNull(uiCachePolicy("/runtime/svelte/index.js", 304, null, true))
    }

    @Test
    fun `errors on asset paths are never stored, whatever the body looks like`() {
        assertEquals("no-store", uiCachePolicy("/_app/immutable/chunks/-C3NeDH5.js", 502, "text/html; charset=UTF-8", false))
        assertEquals("no-store", uiCachePolicy("/_app/immutable/chunks/-C3NeDH5.js", 404, "text/html", false))
        assertEquals("no-store", uiCachePolicy("/plugins/pano-plugin-slider/resources/plugin-ui/client/main.js", 500, null, false))
        assertEquals("no-store", uiCachePolicy("/panel/_app/immutable/nodes/0.js", 503, "text/plain", false))
        assertEquals("no-store", uiCachePolicy("/runtime/svelte/index.js", 404, null, true))
    }

    @Test
    fun `successful assets outside the special namespaces keep the upstream headers`() {
        assertNull(uiCachePolicy("/_app/immutable/chunks/-C3NeDH5.js", 200, "text/javascript", false))
        assertNull(uiCachePolicy("/favicon.png", 200, "image/png", false))
        assertNull(uiCachePolicy("/_app/immutable/chunks/x.js", 304, null, false))
    }
}
