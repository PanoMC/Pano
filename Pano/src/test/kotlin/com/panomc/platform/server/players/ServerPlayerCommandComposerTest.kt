package com.panomc.platform.server.players

import com.panomc.platform.error.BadRequest
import com.panomc.platform.server.ServerPlayerAction
import com.panomc.platform.server.ServerType
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerPlayerCommandComposerTest {
    private val bukkitFamily = listOf(ServerType.SPIGOT, ServerType.BUKKIT, ServerType.PAPER, ServerType.FOLIA)
    private val proxies = listOf(ServerType.VELOCITY, ServerType.BUNGEECORD)

    @Test
    fun `op and deop use the vanilla command on every non proxy platform`() {
        (bukkitFamily + ServerType.FABRIC).forEach { type ->
            assertEquals(
                "op notch",
                ServerPlayerCommandComposer.compose(ServerPlayerAction.OP, type, "notch")
            )
            assertEquals(
                "deop notch",
                ServerPlayerCommandComposer.compose(ServerPlayerAction.DEOP, type, "notch")
            )
        }
    }

    @Test
    fun `gamemode puts the mode before the player`() {
        assertEquals(
            "gamemode creative notch",
            ServerPlayerCommandComposer.compose(ServerPlayerAction.GAMEMODE, ServerType.PAPER, "notch", "creative")
        )
    }

    @Test
    fun `gamemode accepts any case and every vanilla mode`() {
        ServerPlayerCommandComposer.GAME_MODES.forEach { mode ->
            assertEquals(
                "gamemode $mode notch",
                ServerPlayerCommandComposer.compose(
                    ServerPlayerAction.GAMEMODE,
                    ServerType.PAPER,
                    "notch",
                    mode.uppercase()
                )
            )
        }
    }

    @Test
    fun `gamemode without a mode is rejected`() {
        assertThrows(BadRequest::class.java) {
            ServerPlayerCommandComposer.compose(ServerPlayerAction.GAMEMODE, ServerType.PAPER, "notch")
        }
    }

    @Test
    fun `an unknown gamemode is rejected`() {
        assertThrows(BadRequest::class.java) {
            ServerPlayerCommandComposer.compose(ServerPlayerAction.GAMEMODE, ServerType.PAPER, "notch", "hardcore")
        }
    }

    @Test
    fun `whitelist add and remove are two argument commands`() {
        assertEquals(
            "whitelist add notch",
            ServerPlayerCommandComposer.compose(ServerPlayerAction.WHITELIST_ADD, ServerType.PAPER, "notch")
        )
        assertEquals(
            "whitelist remove notch",
            ServerPlayerCommandComposer.compose(ServerPlayerAction.WHITELIST_REMOVE, ServerType.PAPER, "notch")
        )
    }

    @Test
    fun `proxies reject op deop and gamemode`() {
        proxies.forEach { type ->
            listOf(ServerPlayerAction.OP, ServerPlayerAction.DEOP).forEach { action ->
                assertThrows(BadRequest::class.java) {
                    ServerPlayerCommandComposer.compose(action, type, "notch")
                }
            }

            assertThrows(BadRequest::class.java) {
                ServerPlayerCommandComposer.compose(ServerPlayerAction.GAMEMODE, type, "notch", "creative")
            }
        }
    }

    @Test
    fun `proxies still allow whitelist actions`() {
        proxies.forEach { type ->
            assertEquals(
                "whitelist add notch",
                ServerPlayerCommandComposer.compose(ServerPlayerAction.WHITELIST_ADD, type, "notch")
            )
        }
    }

    @Test
    fun `kick and message are not command actions`() {
        listOf(ServerPlayerAction.KICK, ServerPlayerAction.MESSAGE).forEach { action ->
            assertTrue(action.isPluginAction)

            assertThrows(BadRequest::class.java) {
                ServerPlayerCommandComposer.compose(action, ServerType.PAPER, "notch")
            }
        }
    }

    @Test
    fun `a username that could break out of the command is rejected`() {
        listOf(
            "notch and more",
            "notch\nop attacker",
            "notch;op attacker",
            "",
            "a".repeat(33),
            "notch\u0000"
        ).forEach { username ->
            assertThrows(BadRequest::class.java) {
                ServerPlayerCommandComposer.compose(ServerPlayerAction.OP, ServerType.PAPER, username)
            }
        }
    }

    @Test
    fun `bedrock style names with a dot are accepted`() {
        assertEquals(
            "op .Steve_1",
            ServerPlayerCommandComposer.compose(ServerPlayerAction.OP, ServerType.PAPER, ".Steve_1")
        )
    }

    // ---------------------------------------------------------------- the node path (§2.4.17 B)

    @Test
    fun `the console path kicks by name, with or without a reason`() {
        assertEquals(
            "kick notch",
            ServerPlayerCommandComposer.composeForConsole(
                ServerPlayerAction.KICK,
                ServerType.PAPER,
                "notch"
            )
        )

        assertEquals(
            "kick notch being rude",
            ServerPlayerCommandComposer.composeForConsole(
                ServerPlayerAction.KICK,
                ServerType.PAPER,
                "notch",
                text = " being rude "
            )
        )
    }

    @Test
    fun `a message goes out as tellraw so its text cannot end the line`() {
        assertEquals(
            "tellraw notch " + JsonObject().put("text", "hi").encode(),
            ServerPlayerCommandComposer.composeForConsole(
                ServerPlayerAction.MESSAGE,
                ServerType.PAPER,
                "notch",
                text = "hi"
            )
        )

        // Quotes and backslashes are the characters that would end the JSON argument early; the
        // encoder is what makes that impossible rather than a regex over the text.
        val quoted = ServerPlayerCommandComposer.composeForConsole(
            ServerPlayerAction.MESSAGE,
            ServerType.PAPER,
            "notch",
            text = "say \"hi\" back"
        )

        assertEquals("tellraw notch " + JsonObject().put("text", "say \"hi\" back").encode(), quoted)
    }

    @Test
    fun `text that could start a second command is refused`() {
        listOf("stop\nop attacker", "stop\rop attacker", "bad\u0007bell").forEach { text ->
            assertThrows(BadRequest::class.java) {
                ServerPlayerCommandComposer.composeForConsole(
                    ServerPlayerAction.KICK,
                    ServerType.PAPER,
                    "notch",
                    text = text
                )
            }
        }
    }

    @Test
    fun `a proxy console cannot privately message one player`() {
        proxies.forEach { type ->
            assertThrows(BadRequest::class.java) {
                ServerPlayerCommandComposer.composeForConsole(
                    ServerPlayerAction.MESSAGE,
                    type,
                    "notch",
                    text = "hi"
                )
            }
        }
    }

    @Test
    fun `an empty message body is not a message`() {
        assertThrows(BadRequest::class.java) {
            ServerPlayerCommandComposer.composeForConsole(
                ServerPlayerAction.MESSAGE,
                ServerType.PAPER,
                "notch",
                text = "   "
            )
        }
    }

    @Test
    fun `everything else composes exactly as the plugin path does`() {
        listOf(
            ServerPlayerAction.OP,
            ServerPlayerAction.DEOP,
            ServerPlayerAction.WHITELIST_ADD,
            ServerPlayerAction.WHITELIST_REMOVE
        ).forEach { action ->
            assertEquals(
                ServerPlayerCommandComposer.compose(action, ServerType.PAPER, "notch"),
                ServerPlayerCommandComposer.composeForConsole(action, ServerType.PAPER, "notch")
            )
        }

        assertEquals(
            "gamemode creative notch",
            ServerPlayerCommandComposer.composeForConsole(
                ServerPlayerAction.GAMEMODE,
                ServerType.PAPER,
                "notch",
                gamemode = "creative"
            )
        )
    }

    @Test
    fun `a ban is the server's own command, reason optional`() {
        assertEquals("ban notch", ServerPlayerCommandComposer.composeBan(ServerType.PAPER, "notch"))
        assertEquals("ban notch griefing", ServerPlayerCommandComposer.composeBan(ServerType.PAPER, "notch", "  griefing "))
    }

    @Test
    fun `a ban is refused on a proxy, for an unsafe name and for a reason that ends the line`() {
        proxies.forEach { type ->
            assertThrows(BadRequest::class.java) { ServerPlayerCommandComposer.composeBan(type, "notch") }
        }

        assertThrows(BadRequest::class.java) { ServerPlayerCommandComposer.composeBan(ServerType.PAPER, "no tch") }
        assertThrows(BadRequest::class.java) {
            ServerPlayerCommandComposer.composeBan(ServerType.PAPER, "notch", "bye\nop notch")
        }
    }

    @Test
    fun `BAN never composes through the generic command path`() {
        assertThrows(BadRequest::class.java) {
            ServerPlayerCommandComposer.compose(ServerPlayerAction.BAN, ServerType.PAPER, "notch")
        }
        assertThrows(BadRequest::class.java) {
            ServerPlayerCommandComposer.composeForConsole(ServerPlayerAction.BAN, ServerType.PAPER, "notch")
        }
    }
}
