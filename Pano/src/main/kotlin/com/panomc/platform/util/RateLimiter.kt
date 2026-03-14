package com.panomc.platform.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory rate limiter using the Token Bucket algorithm.
 * Thread-safe and suitable for a single-instance Pano deployment.
 *
 * Each "bucket" is identified by a key (typically IP address or IP+path).
 * Buckets are automatically cleaned up when they exceed the configured expiry time.
 *
 * @param maxTokens   Maximum number of tokens (requests) allowed in the window.
 * @param refillRateMs How often (in ms) a single token is added back.
 * @param expiryMs    How long (in ms) an idle bucket is kept before cleanup.
 */
class RateLimiter(
    private val maxTokens: Int,
    private val refillRateMs: Long,
    private val expiryMs: Long = 10 * 60 * 1000L // 10 minutes default
) {
    private data class Bucket(
        var tokens: Double,
        var lastRefill: Long,
        val lastAccess: AtomicLong = AtomicLong(System.currentTimeMillis())
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()

    @Volatile
    private var lastCleanup = System.currentTimeMillis()
    private val cleanupIntervalMs = 60_000L // cleanup every 60s

    /**
     * Attempts to consume one token for the given key.
     *
     * @param key Unique identifier (e.g. IP address, or "IP:/path")
     * @return true if allowed, false if rate limited
     */
    fun tryAcquire(key: String): Boolean {
        maybeCleanup()

        val now = System.currentTimeMillis()
        val bucket = buckets.computeIfAbsent(key) {
            Bucket(tokens = maxTokens.toDouble(), lastRefill = now)
        }

        synchronized(bucket) {
            // Refill tokens based on elapsed time
            val elapsed = now - bucket.lastRefill
            val tokensToAdd = elapsed.toDouble() / refillRateMs
            bucket.tokens = (bucket.tokens + tokensToAdd).coerceAtMost(maxTokens.toDouble())
            bucket.lastRefill = now
            bucket.lastAccess.set(now)

            // Try to consume
            return if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                true
            } else {
                false
            }
        }
    }

    /**
     * Returns the number of remaining tokens for the given key.
     */
    fun remainingTokens(key: String): Int {
        val bucket = buckets[key] ?: return maxTokens
        synchronized(bucket) {
            val now = System.currentTimeMillis()
            val elapsed = now - bucket.lastRefill
            val tokensToAdd = elapsed.toDouble() / refillRateMs
            return (bucket.tokens + tokensToAdd).coerceAtMost(maxTokens.toDouble()).toInt()
        }
    }

    /**
     * Returns the retry-after time in seconds (how long the client should wait
     * until at least 1 token is available).
     */
    fun retryAfterSeconds(key: String): Int {
        val bucket = buckets[key] ?: return 0
        synchronized(bucket) {
            return if (bucket.tokens >= 1.0) {
                0
            } else {
                val deficit = 1.0 - bucket.tokens
                ((deficit * refillRateMs) / 1000.0).toInt().coerceAtLeast(1)
            }
        }
    }

    private fun maybeCleanup() {
        val now = System.currentTimeMillis()
        if (now - lastCleanup < cleanupIntervalMs) return
        lastCleanup = now

        val expiredKeys = buckets.entries
            .filter { now - it.value.lastAccess.get() > expiryMs }
            .map { it.key }

        expiredKeys.forEach { buckets.remove(it) }
    }

    /**
     * Returns current number of tracked buckets (for monitoring).
     */
    fun bucketCount(): Int = buckets.size
}
