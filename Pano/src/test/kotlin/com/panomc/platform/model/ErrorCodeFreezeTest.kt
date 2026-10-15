package com.panomc.platform.model

import com.panomc.platform.api.ErrorStandIn
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier
import java.util.jar.JarFile

/**
 * The error codes are frozen at the value every class had when the codes became declared
 * (`Pano/src/test/resources/api/error-codes.json`). A client matches on `error.code`, so a code or status
 * that changes, or a class that disappears, is a breaking change.
 *
 * Every concrete [Error] subclass of the core jar is built through its all-default constructor, or, when its
 * constructor needs arguments (`ui.CustomAppActive(id)`), with stand-in arguments. The snapshot is the declared
 * set: a new class must be added to it (the failure prints the entries), a removed one fails.
 */
class ErrorCodeFreezeTest {
    /** Codes two classes answer on purpose (the answer is the same to a client): the preflight and the unsafe-request refusal. */
    private val SHARED_CODES = setOf("ORIGIN_NOT_ALLOWED")

    private fun snapshot(): JsonObject {
        val stream = javaClass.getResourceAsStream("/api/error-codes.json")
            ?: error("api/error-codes.json is missing from the test resources")

        return JsonObject(stream.use { String(it.readBytes(), Charsets.UTF_8) })
    }

    private fun classNamesOfMainOutput(): List<String> {
        val location = Error::class.java.protectionDomain.codeSource.location.toURI()
        val root = File(location)
        val names = mutableListOf<String>()

        if (root.isDirectory) {
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach {
                names.add(it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.'))
            }
        } else {
            JarFile(root).use { jar ->
                jar.entries().asSequence().filter { it.name.endsWith(".class") }.forEach {
                    names.add(it.name.removeSuffix(".class").replace('/', '.'))
                }
            }
        }

        return names
    }

    private fun errorClasses(): List<Class<*>> =
        classNamesOfMainOutput()
            .filter { it.startsWith("com.panomc.platform.") }
            .mapNotNull { name -> runCatching { Class.forName(name, false, Error::class.java.classLoader) }.getOrNull() }
            .filter { Error::class.java.isAssignableFrom(it) && it != Error::class.java }
            .filter { !Modifier.isAbstract(it.modifiers) && !it.isInterface && !it.isAnonymousClass }
            .sortedBy { it.name }

    /** All-default constructor, else the first constructor with stand-in arguments ([ErrorStandIn]): code and status are constants of the class. */
    private fun instantiate(type: Class<*>): Error =
        ErrorStandIn.create(type)
            ?: error("${type.name} cannot be built: give every parameter a default, or use a type ErrorStandIn can fill")

    @Test
    fun `the snapshot is not empty and lists valid codes`() {
        val snapshot = snapshot()

        assertTrue(snapshot.size() > 100, "snapshot has only ${snapshot.size()} entries")

        snapshot.fieldNames().forEach { className ->
            val entry = snapshot.getJsonObject(className)

            assertTrue(Error.CODE_PATTERN.matches(entry.getString("code")), "$className: ${entry.getString("code")}")
        }
    }

    @Test
    fun `every error class keeps its code and status`() {
        val snapshot = snapshot()
        val current = errorClasses().associateBy { it.name }

        val removed = snapshot.fieldNames().filter { it !in current }

        assertTrue(removed.isEmpty(), "error classes removed or renamed (a client may match their code): $removed")

        snapshot.fieldNames().forEach { className ->
            val expected = snapshot.getJsonObject(className)
            val error = instantiate(current.getValue(className))

            assertEquals(expected.getString("code"), error.code, "code of $className")
            assertEquals(expected.getInteger("status"), error.getStatusCode(), "status of $className")
            assertEquals(error.code, error.getErrorCode(), "getErrorCode of $className")
        }
    }

    @Test
    fun `the snapshot holds every declared class`() {
        val snapshot = snapshot()

        val missing = errorClasses().filter { !snapshot.containsKey(it.name) }.associate {
            val error = instantiate(it)

            it.name to JsonObject().put("code", error.code).put("status", error.getStatusCode())
        }

        assertTrue(
            missing.isEmpty(),
            "error classes missing from api/error-codes.json (add them): " +
                missing.entries.joinToString(",\n", prefix = "\n") { "\"${it.key}\": ${it.value.encode()}" }
        )
    }

    @Test
    fun `every error class has a valid code and a code of its own`() {
        val byCode = mutableMapOf<String, MutableList<String>>()

        errorClasses().forEach { type ->
            val error = instantiate(type)

            assertTrue(Error.CODE_PATTERN.matches(error.code), "${type.name}: ${error.code}")

            byCode.getOrPut(error.code) { mutableListOf() }.add(type.name)
        }

        val duplicated = byCode.filterValues { it.size > 1 }.filterKeys { it !in SHARED_CODES }

        assertTrue(duplicated.isEmpty(), "two classes share a code: $duplicated")
    }

    @Test
    fun `an error with a malformed code is refused`() {
        listOf("", "lower", "1ABC", "HAS SPACE", "DASH-ED").forEach { code ->
            val failure = runCatching { object : Error(code) {} }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException, "'$code' was accepted")
        }
    }
}
