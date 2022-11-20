package com.panomc.platform.util

import com.panomc.platform.ErrorCode
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
                    throw Error(fieldConfig.contentTypeError)
                }

                if (fieldConfig.size != -1L && fileUpload.size() > fieldConfig.size) {
                    fieldConfig.fileSizeError?.let {
                        throw Error(it)
                    }
                }

                val newPath = configManager.getConfig().getString("file-uploads-folder") + "/" +
                        fieldConfig.path +
                        (if (fieldConfig.withTempName)
                            fileUpload.uploadedFileName().split("/").last()
                        else "") +
                        "." + fileUpload.fileName().split(".").last()

                val file = File(fileUpload.uploadedFileName())

                file.copyTo(File(newPath), true)

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

    fun List<FileUpload>.areFieldsExist(acceptedFileFields: List<Field>) =
        this.getFieldNameFilteredFiles(acceptedFileFields).size == acceptedFileFields.size

    private fun String.removeUploadFolderPath(configManager: ConfigManager) =
        this.replaceFirst(configManager.getConfig().getString("file-uploads-folder") + "/", "")

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
        val contentTypeError: ErrorCode,
        val fileSizeError: ErrorCode?,
        val withTempName: Boolean = true,
        val size: Long = -1
    )

    class SavedFile(
        val path: String,
        val field: Field,
        val fieldConfig: FieldConfig
    )
}