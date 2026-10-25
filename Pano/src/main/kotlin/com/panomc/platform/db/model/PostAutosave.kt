package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

/**
 * What the post editor holds for a post that is not saved yet (`post_autosave`): one row per post, written by the
 * editor while an admin types and removed when the post is saved, published or the changes are thrown away. The
 * post itself is not touched until then, so the site keeps showing the saved post.
 *
 * [thumbnailUrl] is a cover picked in the editor and uploaded with the autosave (empty for none); [removeThumbnail]
 * says the saved cover was removed in the editor. Neither set means the saved cover stays.
 */
data class PostAutosave(
    val id: Long = -1,
    val postId: Long,
    /** The admin whose editor wrote the row last. */
    val userId: Long,
    val title: String,
    val text: String,
    val categoryId: Long = -1,
    val thumbnailUrl: String = "",
    val removeThumbnail: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
) : DBEntity()
