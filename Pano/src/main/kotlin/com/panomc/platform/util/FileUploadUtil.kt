package com.panomc.platform.util

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.Error
import io.vertx.ext.web.FileUpload
import java.io.File

object FileUploadUtil {
    fun saveFiles(
        fileUploads: List<FileUpload>,
        acceptedFileFields: List<Field>,
        configManager: ConfigManager
    ): List<SavedFile> {
        val fieldNameFilteredFiles = fileUploads.getFieldNameFilteredFiles(acceptedFileFields)
        val savedFiles = mutableListOf<SavedFile>()

        fieldNameFilteredFiles.forEach { fileUpload ->
            val isContentTypeCorrect = fileUpload.isContentTypeCorrect(acceptedFileFields)

            fileUpload.findField(acceptedFileFields)?.let { field ->
                val fieldConfig = field.fieldConfig

                if (!isContentTypeCorrect) {
                    throw fieldConfig.contentTypeError
                }

                if (fieldConfig.size != -1L && fileUpload.size() > fieldConfig.size) {
                    fieldConfig.fileSizeError?.let {
                        throw it
                    }
                }

                val file = File(fileUpload.uploadedFileName())

                // The extension comes from what the bytes are, never from the client's file name,
                // and only for an image type this field accepts.
                val detected = detectImage(file)
                    ?.takeIf { (contentType, _) -> fieldConfig.accepts(contentType) }
                    ?: throw fieldConfig.contentTypeError

                val newPath = configManager.config.fileUploadsFolder + File.separator +
                        fieldConfig.path +
                        (if (fieldConfig.withTempName)
                            File.separator + fileUpload.uploadedFileName().split(File.separator).last()
                        else "") +
                        "." + detected.second

                val newPathFile = File(newPath)

                if (newPathFile.exists()) {
                    newPathFile.delete()
                }

                file.copyTo(newPathFile, true)

                savedFiles.add(
                    SavedFile(
                        path = newPath.removeUploadFolderPath(configManager),
                        field,
                        fieldConfig
                    )
                )
            }
        }

        return savedFiles
    }

    private val ICO_TYPES = setOf("image/x-icon", "image/vnd.microsoft.icon")

    private fun FieldConfig.accepts(contentType: String) =
        acceptedContentTypes.contains(contentType) ||
                (contentType in ICO_TYPES && acceptedContentTypes.any { it in ICO_TYPES })

    /**
     * The image type and file extension of [file] read from its first bytes, or null when it is
     * not a PNG, JPEG, GIF, WebP or ICO image.
     */
    fun detectImage(file: File): Pair<String, String>? {
        val head = ByteArray(12)
        val read = file.inputStream().use { input ->
            var total = 0
            while (total < head.size) {
                val n = input.read(head, total, head.size - total)
                if (n < 0) break
                total += n
            }
            total
        }

        fun startsWith(vararg bytes: Int) = read >= bytes.size && bytes.indices.all { head[it] == bytes[it].toByte() }
        fun ascii(offset: Int, text: String) =
            read >= offset + text.length && text.indices.all { head[offset + it] == text[it].code.toByte() }

        return when {
            startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png" to "png"
            startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg" to "jpg"
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif" to "gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp" to "webp"
            startsWith(0x00, 0x00, 0x01, 0x00) -> "image/x-icon" to "ico"
            else -> null
        }
    }

    fun List<FileUpload>.areFieldsExist(acceptedFileFields: List<Field>) =
        this.getFieldNameFilteredFiles(acceptedFileFields).size == acceptedFileFields.size

    private fun String.removeUploadFolderPath(configManager: ConfigManager) =
        this.replaceFirst(configManager.config.fileUploadsFolder + File.separator, "")

    private fun List<FileUpload>.getFieldNameFilteredFiles(acceptedFileFields: List<Field>) =
        this.filter { fileUpload ->
            acceptedFileFields.find { field -> field.name == fileUpload.name() } != null
        }

    private fun FileUpload.isContentTypeCorrect(acceptedFileFields: List<Field>): Boolean {
        acceptedFileFields
            .filter { it.name == this.name() }
            .forEach {
                if (it.fieldConfig.acceptedContentTypes.contains(this.contentType())) {
                    return true
                }
            }

        return false
    }

    private fun FileUpload.findField(acceptedFileFields: List<Field>): Field? {
        return acceptedFileFields
            .find { it.name == this.name() }
    }

    class Field(
        val name: String,
        val fieldConfig: FieldConfig
    )

    class FieldConfig(
        val path: String,
        val acceptedContentTypes: List<String>,
        val contentTypeError: Error,
        val fileSizeError: Error?,
        val withTempName: Boolean = true,
        val size: Long = -1
    )

    class SavedFile(
        val path: String,
        val field: Field,
        val fieldConfig: FieldConfig
    )
}