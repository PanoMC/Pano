package com.panomc.platform.util

import com.panomc.platform.util.FirstRunPolicy.Decision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FirstRunPolicyTest {

    @TempDir
    lateinit var dir: File

    private fun touch(path: String) {
        val file = File(dir, path)
        file.parentFile.mkdirs()
        file.writeText("x")
    }

    private fun mkdir(path: String) {
        File(dir, path).mkdirs()
    }

    // ---- first run ----

    @Test
    fun `an empty directory has not been run in`() {
        assertFalse(FirstRunPolicy.hasRunBefore(dir))
    }

    @Test
    fun `foreign files alone do not count as a run`() {
        touch("notes.txt")
        mkdir("photos")

        assertFalse(FirstRunPolicy.hasRunBefore(dir))
    }

    @Test
    fun `config conf means Pano has run here`() {
        touch("config.conf")

        assertTrue(FirstRunPolicy.hasRunBefore(dir))
    }

    @Test
    fun `a moved config file is found where pano configFile points`() {
        touch("elsewhere/pano.conf")

        assertFalse(FirstRunPolicy.hasRunBefore(dir))
        assertTrue(FirstRunPolicy.hasRunBefore(dir, File("elsewhere/pano.conf")))
        assertTrue(FirstRunPolicy.hasRunBefore(dir, File(dir, "elsewhere/pano.conf").absoluteFile))
    }

    @Test
    fun `each file or folder Pano creates counts as a run even without config conf`() {
        FirstRunPolicy.RUN_MARKERS.forEach { marker ->
            val fresh = File(dir, "case-" + marker.replace('/', '_')).also { it.mkdirs() }
            File(fresh, marker).also { it.parentFile.mkdirs() }.writeText("x")

            assertTrue(FirstRunPolicy.hasRunBefore(fresh), marker)
        }
    }

    @Test
    fun `a plugins folder alone is not a run, an operator may fill it before the first start`() {
        mkdir("plugins")

        assertFalse(FirstRunPolicy.hasRunBefore(dir))
    }

    // ---- empty or not ----

    @Test
    fun `the running jar, whatever its name, is not something else`() {
        touch("totally-custom-name.jar")

        assertEquals(emptyList<String>(), FirstRunPolicy.foreignEntries(dir, "totally-custom-name.jar"))
        assertEquals(listOf("totally-custom-name.jar"), FirstRunPolicy.foreignEntries(dir, "Pano-1.0.0.jar"))
    }

    @Test
    fun `release companions are ignored`() {
        touch("Pano-1.0.0.jar")
        touch("Pano-1.0.0.jar.sha256")
        touch("pano-node.jar")
        touch("pano-node.jar.sha256")
        touch("LICENSE")

        assertEquals(emptyList<String>(), FirstRunPolicy.foreignEntries(dir, "Pano-1.0.0.jar"))
    }

    @Test
    fun `file manager junk is ignored but other hidden files are not`() {
        touch(".DS_Store")
        touch("._Pano.jar")
        touch(".directory")
        touch("Thumbs.db")
        touch("desktop.ini")
        mkdir(".Trash-1000")
        touch(".env")
        mkdir(".git")

        assertEquals(listOf(".env", ".git/"), FirstRunPolicy.foreignEntries(dir, null))
    }

    @Test
    fun `another Pano jar is not ignored, only the running one is`() {
        touch("Pano-1.0.0.jar")
        touch("Pano-0.9.0.jar")

        assertEquals(listOf("Pano-0.9.0.jar"), FirstRunPolicy.foreignEntries(dir, "Pano-1.0.0.jar"))
    }

    @Test
    fun `entries are sorted without regard to case and folders carry a slash`() {
        touch("b.txt")
        touch("A.txt")
        mkdir("c-folder")

        assertEquals(listOf("A.txt", "b.txt", "c-folder/"), FirstRunPolicy.foreignEntries(dir, null))
    }

    @Test
    fun `a directory that cannot be listed has no entries`() {
        assertEquals(emptyList<String>(), FirstRunPolicy.foreignEntries(File(dir, "missing"), null))
    }

    // ---- answers ----

    @Test
    fun `only y and yes in any case are a yes`() {
        listOf("y", "Y", "yes", "YES", "Yes", "  y  ", "yes\n").forEach { assertTrue(FirstRunPolicy.parseAnswer(it), it) }
    }

    @Test
    fun `empty, n, no, noise and a closed input are a no`() {
        listOf("", "   ", "n", "N", "no", "yep", "yes please", "ye", "1", "true", "y y").forEach {
            assertFalse(FirstRunPolicy.parseAnswer(it), it)
        }
        assertFalse(FirstRunPolicy.parseAnswer(null))
    }

    // ---- skip switches ----

    @Test
    fun `the flag skips the question`() {
        assertTrue(FirstRunPolicy.isSkipRequested(arrayOf("-nogui", "--allow-non-empty-dir"), emptyMap()))
        assertFalse(FirstRunPolicy.isSkipRequested(arrayOf("-nogui", "--dev"), emptyMap()))
    }

    @Test
    fun `the environment variable skips the question when truthy`() {
        listOf("1", "true", "TRUE", "yes", "on", " 1 ").forEach {
            assertTrue(FirstRunPolicy.isSkipRequested(emptyArray(), mapOf("PANO_ALLOW_NON_EMPTY_DIR" to it)), it)
        }
        listOf("", "0", "false", "no", "off").forEach {
            assertFalse(FirstRunPolicy.isSkipRequested(emptyArray(), mapOf("PANO_ALLOW_NON_EMPTY_DIR" to it)), it)
        }
        assertFalse(FirstRunPolicy.isSkipRequested(emptyArray(), emptyMap()))
    }

    // ---- interactive or not ----

    @Test
    fun `no console is not interactive, a console is unless the JDK says it is not a terminal`() {
        assertFalse(FirstRunPolicy.isInteractive(hasConsole = false))
        assertFalse(FirstRunPolicy.isInteractive(hasConsole = false, isTerminal = true))
        assertTrue(FirstRunPolicy.isInteractive(hasConsole = true))
        assertTrue(FirstRunPolicy.isInteractive(hasConsole = true, isTerminal = true))
        assertFalse(FirstRunPolicy.isInteractive(hasConsole = true, isTerminal = false))
    }

    // ---- the decision ----

    private val entries = listOf("notes.txt")

    @Test
    fun `nothing to ask when skipped, already run, or nothing foreign`() {
        assertEquals(Decision.Proceed, FirstRunPolicy.decide(true, false, entries, gui = true, interactive = true))
        assertEquals(Decision.Proceed, FirstRunPolicy.decide(false, true, entries, gui = true, interactive = true))
        assertEquals(Decision.Proceed, FirstRunPolicy.decide(false, false, emptyList(), gui = true, interactive = true))
    }

    @Test
    fun `the GUI asks in a dialog even when a terminal is attached`() {
        assertEquals(Decision.Ask(entries, viaGui = true), FirstRunPolicy.decide(false, false, entries, gui = true, interactive = true))
        assertEquals(Decision.Ask(entries, viaGui = true), FirstRunPolicy.decide(false, false, entries, gui = true, interactive = false))
    }

    @Test
    fun `without a GUI a terminal asks`() {
        assertEquals(Decision.Ask(entries, viaGui = false), FirstRunPolicy.decide(false, false, entries, gui = false, interactive = true))
    }

    @Test
    fun `without a GUI and without a terminal nobody is asked, the warning is printed and Pano continues`() {
        assertEquals(Decision.WarnAndContinue(entries), FirstRunPolicy.decide(false, false, entries, gui = false, interactive = false))
    }

    // ---- the text ----

    @Test
    fun `the warning lists up to five entries and counts the rest`() {
        val many = (1..8).map { "file$it" }
        val lines = FirstRunPolicy.warningLines(File("/srv/pano"), many)

        assertEquals("This directory is not empty, and Pano has never been started in it before:", lines[0])
        assertEquals("  /srv/pano", lines[1])
        assertEquals("It contains 8 entries, for example:", lines[2])
        assertEquals((1..5).map { "  file$it" }, lines.subList(3, 8))
        assertEquals("  ... and 3 more", lines[8])
        assertEquals(10, lines.size)
    }

    @Test
    fun `the warning shows everything when there are five or fewer`() {
        val lines = FirstRunPolicy.warningLines(File("/srv/pano"), listOf("a", "b/"))

        assertEquals("It contains 2 entries:", lines[2])
        assertEquals(listOf("  a", "  b/"), lines.subList(3, 5))
        assertEquals(6, lines.size)
        assertEquals("It contains 1 entry:", FirstRunPolicy.warningLines(File("/x"), listOf("a"))[2])
    }
}
