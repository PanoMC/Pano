package com.panomc.node

import com.panomc.node.host.JavaRuntime
import com.panomc.node.java.JavaNeed
import com.panomc.node.java.JavaRemoval
import com.panomc.node.java.JavaUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which Java a server needs, when that means a download, and when a runtime may go (SM-63). */
class JavaNeedTest {
    @Test
    fun `automatic takes the ladder's minimum`() {
        assertEquals(21, JavaNeed.neededMajor(null, "1.21.4", null))
        assertEquals(17, JavaNeed.neededMajor(null, "1.18.2", null))
        assertEquals(16, JavaNeed.neededMajor(null, "1.16.5", null))
        assertEquals(8, JavaNeed.neededMajor(null, "1.12.2", null))
        assertEquals(25, JavaNeed.neededMajor(null, "26.1", null))
        // Zero is how a stored spec says "automatic".
        assertEquals(21, JavaNeed.neededMajor(0, "1.21.4", null))
    }

    @Test
    fun `a pin wins over the ladder`() {
        assertEquals(16, JavaNeed.neededMajor(16, "1.21.4", null))
        assertEquals(25, JavaNeed.neededMajor(25, "1.8.8", null))
    }

    @Test
    fun `the jar's own requirement is a floor under both`() {
        // Velocity 4 compiled for 25 while its "version" says nothing the ladder knows.
        assertEquals(25, JavaNeed.neededMajor(null, "4.2.1-SNAPSHOT", 25))
        assertEquals(21, JavaNeed.neededMajor(17, "1.20.4", 21))
        assertEquals(21, JavaNeed.neededMajor(null, "1.21.4", 17))
    }

    @Test
    fun `a download is wanted exactly when that major is missing`() {
        // A host with 26 can run a 26.1 server, but 25 is what it asks for.
        assertEquals(JavaNeed.Decision.DOWNLOAD, JavaNeed.decide(25, listOf(8, 11, 17, 21, 26), true))
        assertEquals(JavaNeed.Decision.PRESENT, JavaNeed.decide(21, listOf(8, 11, 17, 21, 26), true))
        assertEquals(JavaNeed.Decision.DISABLED, JavaNeed.decide(16, listOf(17), false))
        assertEquals(JavaNeed.Decision.PRESENT, JavaNeed.decide(17, listOf(17), false))
    }

    @Test
    fun `the refusal says why in the contract's words`() {
        assertEquals(
            "No Java 21 runtime on this host; automatic Java download is disabled",
            JavaNeed.missingReason(21, autoDownload = false)
        )
        assertEquals(
            "No Java 8 runtime on this host; downloading it failed: Java 8 is not available for windows/arm64",
            JavaNeed.missingReason(8, true, "Java 8 is not available for windows/arm64")
        )
    }

    private fun runtime(major: Int, path: String, version: String, managed: Boolean = true) =
        JavaRuntime(major, path, "Eclipse Adoptium", version, managed)

    @Test
    fun `a runtime a live server runs from cannot be removed`() {
        val java21 = runtime(21, "/data/java/temurin-21.0.12", "21.0.12")

        val blockers = JavaRemoval.blockers(
            listOf(java21),
            listOf(java21, runtime(21, "/usr/lib/jvm/java-21", "21.0.8", managed = false)),
            listOf(
                JavaUser("running", alive = true, javaHome = "/data/java/temurin-21.0.12", pinnedMajor = null),
                JavaUser("elsewhere", alive = true, javaHome = "/usr/lib/jvm/java-21", pinnedMajor = null),
                JavaUser("stopped", alive = false, javaHome = null, pinnedMajor = null)
            )
        )

        assertEquals(listOf("running"), blockers)
    }

    @Test
    fun `a pinned major blocks removal only when nothing of it would remain`() {
        val managed = runtime(16, "/data/java/zulu-16.0.2", "16.0.2+7")
        val users = listOf(JavaUser("pinned", alive = false, javaHome = null, pinnedMajor = 16))

        assertEquals(listOf("pinned"), JavaRemoval.blockers(listOf(managed), listOf(managed), users))

        val other = runtime(16, "/opt/java/jdk-16", "16.0.1", managed = false)

        assertTrue(JavaRemoval.blockers(listOf(managed), listOf(managed, other), users).isEmpty())
    }

    @Test
    fun `usedBy names running servers and pins on the runtime they would start with`() {
        val old = runtime(21, "/data/java/temurin-21.0.11", "21.0.11")
        val new = runtime(21, "/data/java/temurin-21.0.12", "21.0.12")
        val installed = listOf(old, new)

        val users = listOf(
            JavaUser("on-old", alive = true, javaHome = old.path, pinnedMajor = null),
            JavaUser("pinned", alive = false, javaHome = null, pinnedMajor = 21)
        )

        assertEquals(listOf("on-old"), JavaRemoval.usedBy(old, installed, users))
        assertEquals(listOf("pinned"), JavaRemoval.usedBy(new, installed, users))
    }
}
