package com.panomc.platform.db.model

import com.panomc.platform.AppConstants
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DBEntity
import com.panomc.platform.error.PostThumbnailExceedsSize
import com.panomc.platform.error.PostThumbnailWrongContentType
import com.panomc.platform.util.FileUploadUtil
import com.panomc.platform.util.PostStatus
import io.vertx.ext.web.FileUpload
import org.imgscalr.Scalr
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

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
                        "image/png",
                        "image/gif",
                        "image/jpeg",
                        "image/webp"
                    ),
                    contentTypeError = PostThumbnailWrongContentType(),
                    fileSizeError = PostThumbnailExceedsSize(),
                    size = 5 * 1024 * 1024 // 5 MB
                )
            )
        )

        fun Post.deleteThumbnailFile(configManager: ConfigManager) = deleteThumbnailFile(thumbnailUrl, configManager)

        /** Deletes the cover file at [thumbnailUrl] and its preview; nothing for an empty address. */
        fun deleteThumbnailFile(thumbnailUrl: String, configManager: ConfigManager) {
            val fileName = thumbnailUrl.split(File.separator).last()

            if (fileName.isBlank()) {
                return
            }

            val path = thumbnailPath(fileName, configManager)
            val oldThumbnailFile = File(path)
            val oldThumbnailPreviewFile = File(previewPath(path))

            if (oldThumbnailFile.isFile) {
                oldThumbnailFile.delete()
            }

            if (oldThumbnailPreviewFile.isFile) {
                oldThumbnailPreviewFile.delete()
            }
        }

        /**
         * Saves the uploaded cover (field `thumbnail`) with its small preview next to it and answers its address;
         * null when the request carries no cover.
         */
        fun saveThumbnail(fileUploads: List<FileUpload>, configManager: ConfigManager): String? {
            val savedFiles = FileUploadUtil.saveFiles(fileUploads, acceptedFileFields, configManager)

            if (savedFiles.isEmpty()) {
                return null
            }

            val fileName = savedFiles[0].path.split(File.separator).last()
            val path = thumbnailPath(fileName, configManager)

            var image: BufferedImage? = null

            try {
                image = ImageIO.read(File(path))
            } catch (_: Exception) {
            }

            if (image != null) {
                val bufferedImage = if (image.type != BufferedImage.TYPE_INT_ARGB) {
                    val tmp = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                    val g = tmp.createGraphics()
                    g.composite = java.awt.AlphaComposite.Src
                    g.drawImage(image, 0, 0, null)
                    g.dispose()
                    tmp
                } else image

                val scaled = Scalr.resize(bufferedImage, Scalr.Method.QUALITY, Scalr.Mode.AUTOMATIC, 100)

                ImageIO.write(scaled, "png", File(previewPath(path)))
            }

            return AppConstants.POST_THUMBNAIL_URL_PREFIX + fileName
        }

        private fun thumbnailPath(fileName: String, configManager: ConfigManager) =
            configManager.config.fileUploadsFolder + File.separator + AppConstants.DEFAULT_POST_THUMBNAIL_UPLOAD_PATH + File.separator + fileName

        private fun previewPath(path: String) = path.lastIndexOf('.').let { dotIndex ->
            if (dotIndex > 0) {
                path.take(dotIndex) + "-preview" + path.substring(dotIndex)
            } else {
                "$path-preview"
            }
        }
    }
}