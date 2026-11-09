package com.panomc.platform.util

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PasswordHasherAsyncTest {
    private val hasher = PasswordHasher()

    @Test
    fun `hashing runs off the calling thread`() = runBlocking {
        val caller = Thread.currentThread().name
        var hashThread = ""

        val hash = hasher.hashAsync("şifre-123").also { hashThread = Thread.currentThread().name }

        // the continuation is back on the caller; the work itself ran on a hash thread
        assertTrue(hasher.verifyAsync("şifre-123", hash))
        assertFalse(hasher.verifyAsync("wrong", hash))
        assertTrue(hashThread == caller)
    }

    @Test
    fun `the work runs on the hash threads`() = runBlocking {
        val caller = Thread.currentThread().name
        val field = PasswordHasher::class.java.getDeclaredField("hashDispatcher").apply { isAccessible = true }
        val dispatcher = field.get(hasher) as kotlinx.coroutines.CoroutineDispatcher

        val worker = kotlinx.coroutines.withContext(dispatcher) { Thread.currentThread().name }

        assertNotEquals(caller, worker)
        assertTrue(worker.startsWith("pano-password-hash-"))
    }

    @Test
    fun `a dummy check never succeeds`() = runBlocking {
        assertFalse(hasher.verifyDummyAsync("anything"))
        assertFalse(hasher.verifyDummyAsync(""))
    }
}
