package com.panomc.platform.db.model

import com.panomc.platform.AppConstants
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DBEntity
import com.panomc.platform.error.PostThumbnailExceedsSize
import com.panomc.platform.error.PostThumbnailWrongContentType
import com.panomc.platform.util.FileUploadUtil
import com.panomc.platform.util.PostStatus
import java.io.File

data class Post(
    val id: Long = -1,
    val title: String,
    val categoryId: Long = -1,
    val writerUserId: Long,
    val text: String,
    val date: Long = System.currentTimeMillis(),
    val moveDate: Long = System.currentTimeMillis(),
    var status: PostStatus = PostStatus.PUBLISHED,
    val thumbnailUrl: String,
    val views: Long = 0,
    val url: String
) : DBEntity() {
    companion object {
        val acceptedFileFields = listOf(
            FileUploadUtil.Field(
                name = "thumbnail",
                fieldConfig = FileUploadUtil.FieldConfig(
                    path = AppConstants.DEFAULT_POST_THUMBNAIL_UPLOAD_PATH,
                    acceptedContentTypes = listOf(
                        "image/x-icon",
                        "image/vnd.microsoft.icon",
                        "image/svg+xml",
                        "image/png",
                        "image/gif",
                        "image/jpeg"
                    ),
                    contentTypeError = PostThumbnailWrongContentType(),
                    fileSizeError = PostThumbnailExceedsSize(),
                    size = 5 * 1024 * 1024 // 5 MB
                )
            )
        )

        fun Post.deleteThumbnailFile(configManager: ConfigManager) {
            val oldThumbnailFile = File(
                configManager.getConfig()
                    .getString("file-uploads-folder") + File.separator + AppConstants.DEFAULT_POST_THUMBNAIL_UPLOAD_PATH + File.separator + thumbnailUrl.split(
                    File.separator
                ).last()
            )

            if (oldThumbnailFile.exists()) {
                oldThumbnailFile.delete()
            }
        }
    }
}