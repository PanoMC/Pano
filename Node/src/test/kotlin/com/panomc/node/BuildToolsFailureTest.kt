package com.panomc.node

import com.panomc.node.host.JavaRuntime
import com.panomc.node.java.JavaPackageResolver
import com.panomc.node.java.JavaTarget
import com.panomc.node.task.BuildToolsInstaller
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BuildToolsFailureTest {
    @TempDir
    lateinit var root: File

    /** The tail of a real BuildTools run on a JRE (2026-09-25, Spigot 26.3 on Temurin 25 JRE). */
    private val jreRun = listOf(
        "[INFO] BUILD FAILURE",
        "[ERROR] COMPILATION ERROR : ",
        "[INFO] -------------------------------------------------------------",
        "[ERROR] No compiler is provided in this environment. Perhaps you are running on a JRE rather than a JDK?",
        "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.16.0:compile (default-compile) on project spigot-api: Compilation failure",
        "[ERROR] No compiler is provided in this environment. Perhaps you are running on a JRE rather than a JDK?",
        "[ERROR] ",
        "[ERROR] -> [Help 1]",
        "[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.",
        "[ERROR] For more information about the errors and possible solutions, please read the following articles:",
        "java.lang.RuntimeException: Error running command, return status !=0: [sh, /x/apache-maven-3.9.6/bin/mvn, -Dbt.name=4659, clean, install]"
    )

    @Test
    fun `the failure a build reports is Maven's cause, not BuildTools' command line`() {
        assertEquals(
            "No compiler is provided in this environment. Perhaps you are running on a JRE rather than a JDK?",
            jreRun.firstNotNullOfOrNull { BuildToolsInstaller.mavenCause(it) }
        )

        // Without it, the summary line is what was left: the whole Maven command and no reason.
        assertTrue(jreRun.mapNotNull { BuildToolsInstaller.failureLine(it) }.last().startsWith("java.lang.RuntimeException"))
    }

    @Test
    fun `a goal failure with nothing under it is the cause`() {
        val lines = listOf(
            "[ERROR] Failed to execute goal on project spigot: Could not resolve dependencies for project org.spigotmc:spigot",
            "[ERROR] -> [Help 1]"
        )

        assertNull(lines.firstNotNullOfOrNull { BuildToolsInstaller.mavenCause(it) })
        assertTrue(lines.firstNotNullOfOrNull { BuildToolsInstaller.mavenGoalFailure(it) }!!.contains("Could not resolve dependencies"))
    }

    @Test
    fun `a runtime is a JDK only when it has javac`() {
        val jre = File(root, "jre").apply { File(this, "bin").mkdirs(); File(this, "bin/java").writeText("") }
        val jdk = File(root, "jdk").apply { File(this, "bin").mkdirs(); File(this, "bin/java").writeText(""); File(this, "bin/javac").writeText("") }

        assertFalse(JavaRuntime(25, jre.path, "temurin").hasCompiler)
        assertTrue(JavaRuntime(25, jdk.path, "temurin").hasCompiler)
    }

    @Test
    fun `a JDK is asked for as a JDK, a server's runtime stays a JRE`() {
        val asked = mutableListOf<String>()
        val resolver = JavaPackageResolver(JavaTarget("linux", "x64"), { url ->
            asked.add(url)
            JavaPackageResolver.Http.Response(404, "")
        })

        resolver.resolve(25)
        resolver.resolve(25, jdk = true)

        assertTrue(asked.any { it.contains("image_type=jre") })
        assertTrue(asked.any { it.contains("image_type=jdk") })
        assertTrue(asked.any { it.contains("java_package_type=jdk") })
    }
}
