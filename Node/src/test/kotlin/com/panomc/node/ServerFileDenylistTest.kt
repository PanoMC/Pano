package com.panomc.node

import com.panomc.node.files.ServerFileDenylist
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerFileDenylistTest {
    @Test
    fun `hides the plugin credentials on every platform`() {
        assertTrue(ServerFileDenylist.isDenied("plugins/Pano/config.conf"))
        assertTrue(ServerFileDenylist.isDenied("plugins/pano/config.conf"))
        assertTrue(ServerFileDenylist.isDenied("config/pano/config.conf"))
        assertTrue(ServerFileDenylist.isDenied("PLUGINS/PANO/CONFIG.CONF"))
        assertTrue(ServerFileDenylist.isDenied("plugins\\Pano\\config.conf"))
    }

    @Test
    fun `hides the node's own launch spec and jvm crash dumps`() {
        assertTrue(ServerFileDenylist.isDenied("server.json"))
        assertTrue(ServerFileDenylist.isDenied("hs_err_pid1234.log"))
        assertTrue(ServerFileDenylist.isDenied("logs/hs_err_pid1.log"))
    }

    @Test
    fun `leaves everything else alone`() {
        assertFalse(ServerFileDenylist.isDenied("server.properties"))
        assertFalse(ServerFileDenylist.isDenied("plugins/Pano/README.txt"))
        assertFalse(ServerFileDenylist.isDenied("plugins"))
        assertFalse(ServerFileDenylist.isDenied("world/level.dat"))
    }

    @Test
    fun `refuses to let a parent directory be deleted out from under a denied file`() {
        assertTrue(ServerFileDenylist.containsDenied("plugins"))
        assertTrue(ServerFileDenylist.containsDenied("plugins/Pano"))
        assertTrue(ServerFileDenylist.containsDenied("config"))
        // The server directory itself is never removable through the file manager.
        assertTrue(ServerFileDenylist.containsDenied(""))

        assertFalse(ServerFileDenylist.containsDenied("world"))
        assertFalse(ServerFileDenylist.containsDenied("plugins/LuckPerms"))
    }

    @Test
    fun `mutability is the two rules together`() {
        assertFalse(ServerFileDenylist.isMutable("plugins"))
        assertFalse(ServerFileDenylist.isMutable("plugins/Pano/config.conf"))
        assertTrue(ServerFileDenylist.isMutable("plugins/LuckPerms"))
        assertTrue(ServerFileDenylist.isMutable("server.properties"))
    }

    @Test
    fun `normalises separators and stray segments`() {
        assertEquals("plugins/Pano", ServerFileDenylist.normalise("/plugins/./Pano/"))
        assertEquals("a/b", ServerFileDenylist.normalise("a\\b"))
        assertEquals("", ServerFileDenylist.normalise(null))
    }
}
