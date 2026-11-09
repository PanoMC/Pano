package com.panomc.platform.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Base64

class UpdaterLaunchArgsTest {
    private fun decode(arg: String) =
        String(Base64.getDecoder().decode(arg.substringAfter('=')), Charsets.UTF_8).split('\u0000')

    @Test
    fun `jvm options and program flags are kept, debugger and mode flags are not`() {
        val (jvm, program) = UpdaterLaunchArgs.arguments(
            listOf("-Xmx2g", "-Dpano.node.jar=C:/a b/pano-node.jar", "-agentlib:jdwp=transport=dt_socket,server=y,address=5005"),
            listOf("--dev", "-nogui", "-bg")
        )

        assertEquals(true, jvm.startsWith("${UpdaterLaunchArgs.JVM_ARGS}="))
        assertEquals(listOf("-Xmx2g", "-Dpano.node.jar=C:/a b/pano-node.jar"), decode(jvm))
        assertEquals(true, program.startsWith("${UpdaterLaunchArgs.PROGRAM_ARGS}="))
        assertEquals(listOf("--dev"), decode(program))
    }

    @Test
    fun `no arguments encode to an empty value`() {
        assertEquals("", UpdaterLaunchArgs.encode(emptyList()))
    }
}
