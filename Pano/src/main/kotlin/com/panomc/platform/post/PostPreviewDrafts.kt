package com.panomc.platform.post

import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * What the post editor shows as a preview before anything is saved: the title, text, category and cover the
 * author has on screen right now, kept in memory under a random token for a short while. The theme renders it
 * with the same view as a saved post (`GET /api/v1/posts/previews/drafts/:token`).
 *
 * Nothing here touches the posts table: a preview is not a save, and closing the tab leaves no trace once the
 * entry has expired. A restart forgets every draft, which only means pressing "preview" again.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PostPreviewDrafts {
    data class Draft(
        val userId: Long,
        /** The saved post being edited, null for a post that does not exist yet. */
        val postId: Long?,
        val title: String,
        val text: String,
        val categoryId: Long,
        /** An address or a `data:` image, as the editor shows it; empty for none. */
        val thumbnailUrl: String,
        val createdAt: Long
    )

    private val drafts = ConcurrentHashMap<String, Draft>()
    private val random = SecureRandom()

    /** Keeps [draft] and returns the token it is read back with. An author's older drafts beyond [PER_USER] go. */
    fun put(draft: Draft, now: Long = System.currentTimeMillis()): String {
        prune(now)

        val own = drafts.entries.filter { it.value.userId == draft.userId }.sortedBy { it.value.createdAt }

        own.take((own.size - PER_USER + 1).coerceAtLeast(0)).forEach { drafts.remove(it.key) }

        val bytes = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        drafts[token] = draft

        return token
    }

    /** The draft under [token], while it is fresh; null for an unknown, malformed or expired one. */
    fun get(token: String, now: Long = System.currentTimeMillis()): Draft? {
        if (!TOKEN.matches(token)) {
            return null
        }

        val draft = drafts[token] ?: return null

        if (now - draft.createdAt > TTL_MS) {
            drafts.remove(token)

            return null
        }

        return draft
    }

    private fun prune(now: Long) {
        drafts.entries.removeIf { now - it.value.createdAt > TTL_MS }
    }

    companion object {
        /** Long enough to read a preview and reload it a few times; short enough to hold little. */
        const val TTL_MS = 30 * 60 * 1000L

        /** An author previews one or two posts at a time; older drafts of the same author are dropped. */
        const val PER_USER = 5

        /** Title, text and an inline cover image together, in characters. */
        const val MAX_CHARS = 10 * 1024 * 1024

        private const val TOKEN_BYTES = 24
        private val TOKEN = Regex("^[A-Za-z0-9_-]{32}$")
    }
}
