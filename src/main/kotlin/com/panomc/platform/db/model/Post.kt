package com.panomc.platform.db.model

import com.panomc.platform.AppConstants
import com.panomc.platform.ErrorCode
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.util.FileUploadUtil
import com.panomc.platform.util.PostStatus
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
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
) {
    companion object {
        fun from(row: Row) = Post(
            row.getLong(0),
            row.getString(1),
            row.getLong(2),
            row.getLong(3),
            row.getBuffer(4).toString(),
            row.getLong(5),
            row.getLong(6),
            PostStatus.valueOf(row.getInteger(7))!!,
            row.getString(8),
            row.getLong(9),
            row.getString(10)
        )

        fun from(rowSet: RowSet<Row>) = rowSet.map { from(it) }

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
                    contentTypeError = ErrorCode.POST_THUMBNAIL_WRONG_CONTENT_TYPE,
                    fileSizeError = ErrorCode.POST_THUMBNAIL_EXCEEDS_SIZE,
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