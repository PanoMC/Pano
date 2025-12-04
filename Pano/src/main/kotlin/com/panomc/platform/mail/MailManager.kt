package com.panomc.platform.mail

import com.github.jknack.handlebars.Handlebars
import com.google.gson.Gson
import com.panomc.platform.AppConstants.DEFAULT_WEBSITE_LOGO_FILE
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Translation.Companion.TranslationType
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.NotExists
import com.panomc.platform.i18n.I18nManager
import com.panomc.platform.util.HashUtil.hash
import com.panomc.platform.util.ImageCompressionUtil
import com.panomc.platform.util.MimeTypeUtil
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.ext.mail.*
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class MailManager(
    private val configManager: ConfigManager,
    private val databaseManager: DatabaseManager,
    private val logger: Logger,
    private val gson: Gson,
    private val vertx: Vertx,
    private val i18nManager: I18nManager
) {
    // Handlebars instance for rendering mail templates
    private val handlebars by lazy { Handlebars() }
    private val mailClient: MailClient by lazy {
        val emailConfig = configManager.config.email
        val mailClientConfig = MailConfig(JsonObject.mapFrom(emailConfig))

        MailClient.createShared(vertx, mailClientConfig, "mailClient")
    }

    private val systemClassLoader = ClassLoader.getSystemClassLoader()

    companion object {
        private const val MAX_LOGO_SIZE_BYTES = 20 * 1024 // 20KB
        private const val MAX_LOGO_DIMENSION = 800 // Maximum width or height in pixels
        private const val CACHE_FOLDER_NAME = "cache"
        private const val CACHE_FILE_PREFIX = "mail-logo-"
        
        data class SystemParameters(
            val websiteName: String,
            val websiteUrl: String
        )
    }

    /**
     * Gets or creates cached logo file. Returns cached logo data and mime type.
     * If logo hash changed or cache doesn't exist, updates cache.
     */
    private fun getCachedLogo(currentHash: String, originalBytes: ByteArray, originalMimeType: String, cacheDir: File): Pair<ByteArray, String> {
        val cacheFileExtension = when {
            originalMimeType.startsWith("image/png") -> "png"
            originalMimeType.startsWith("image/jpeg") || originalMimeType.startsWith("image/jpg") -> "jpg"
            originalMimeType.startsWith("image/gif") -> "gif"
            originalMimeType.startsWith("image/webp") -> "webp"
            else -> "jpg" // Default to jpg for compression
        }
        
        val cacheFileName = "$CACHE_FILE_PREFIX$currentHash.$cacheFileExtension"
        val cacheFile = File(cacheDir, cacheFileName)
        
        // Check if cache exists and hash matches
        if (cacheFile.exists()) {
            try {
                val cachedBytes = cacheFile.readBytes()
                val cachedHash = cachedBytes.inputStream().use { it.hash() }
                
                if (cachedHash.equals(currentHash, ignoreCase = true)) {
                    // Cache is valid, return cached version
                    return cachedBytes to MimeTypeUtil.getMimeTypeFromFileName(cacheFile.name)
                }
            } catch (e: Exception) {
                logger.warn("Failed to read cached logo, will regenerate: ${e.message}")
            }
        }
        
        // Cache doesn't exist or hash mismatch, compress and save
        val (compressedBytes, finalMimeType) = ImageCompressionUtil.compressImage(
            originalBytes,
            originalMimeType,
            MAX_LOGO_SIZE_BYTES,
            MAX_LOGO_DIMENSION,
            logger
        )
        
        // Validate compressed bytes before saving
        if (compressedBytes.isEmpty()) {
            logger.error("Compressed logo bytes are empty, using original")
            return originalBytes to originalMimeType
        }
        
        try {
            // Ensure cache directory exists
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            
            if (!cacheDir.exists() || !cacheDir.isDirectory) {
                logger.warn("Failed to create cache directory: ${cacheDir.absolutePath}")
                return compressedBytes to finalMimeType
            }
            
            // Delete old cache files (keep only current one)
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith(CACHE_FILE_PREFIX) && file.name != cacheFileName) {
                    try {
                        file.delete()
                    } catch (e: Exception) {
                        logger.warn("Failed to delete old cache file ${file.name}: ${e.message}")
                    }
                }
            }
            
            // Save new cache
            cacheFile.writeBytes(compressedBytes)
            
            // Verify cache was written correctly
            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                logger.error("Cache file was not written correctly or is empty: ${cacheFile.absolutePath}")
            } else {
                logger.debug("Logo cache saved successfully: ${cacheFile.absolutePath}, size: ${cacheFile.length()} bytes")
            }
        } catch (e: Exception) {
            logger.error("Failed to save logo cache: ${e.message}", e)
        }
        
        return compressedBytes to finalMimeType
    }


    suspend fun sendMail(sqlClient: SqlClient, userId: Long?, mail: Mail, email: String? = null) {
        val config = configManager.config
        val emailConfig = config.email

        if (!emailConfig.enabled) {
            return
        }

        val user = userId?.let { databaseManager.userDao.getById(it, sqlClient) }

        val emailAddress =
            email ?: user?.email
            ?: throw NotExists()
        val locale = user?.localeCode ?: config.locale

        val message = MailMessage()

        message.from = emailConfig.sender
        message.subject = i18nManager.translate(
            TranslationType.PLATFORM,
            locale,
            mail.subject,
            mapOf("websiteName" to config.websiteName)
        )
        message.setTo(emailAddress)

        val mailParameters =
            mail.generateParameters(SystemParameters(config.websiteName, config.websiteUrl), i18nManager, locale)

        // Setup cache directory
        val cacheDir = File(config.fileUploadsFolder + File.separator + CACHE_FOLDER_NAME)

        // Load logo file for attachment
        val (logoData, logoMimeType) = if (config.filePaths.websiteLogoFile == null) {
            // Use default logo
            val logoStream = systemClassLoader.getResourceAsStream(DEFAULT_WEBSITE_LOGO_FILE)!!
            val logoBytes = logoStream.readBytes()
            logoStream.close()
            val mimeType = MimeTypeUtil.getMimeTypeFromFileName(DEFAULT_WEBSITE_LOGO_FILE)
            
            // Calculate hash and get cached/compressed version
            val logoHash = logoBytes.inputStream().use { it.hash() }
            val (cachedBytes, finalMimeType) = getCachedLogo(logoHash, logoBytes, mimeType, cacheDir)
            Buffer.buffer(cachedBytes) to finalMimeType
        } else {
            // Use custom logo
            val logoFile = config.filePaths.websiteLogoFile!!
            val path = config.fileUploadsFolder + File.separator + logoFile.path
            val file = File(path)
            
            if (!file.exists()) {
                // Fallback to default if custom logo doesn't exist
                val logoStream = systemClassLoader.getResourceAsStream(DEFAULT_WEBSITE_LOGO_FILE)!!
                val logoBytes = logoStream.readBytes()
                logoStream.close()
                val mimeType = MimeTypeUtil.getMimeTypeFromFileName(DEFAULT_WEBSITE_LOGO_FILE)
                
                // Calculate hash and get cached/compressed version
                val logoHash = logoBytes.inputStream().use { it.hash() }
                val (cachedBytes, finalMimeType) = getCachedLogo(logoHash, logoBytes, mimeType, cacheDir)
                Buffer.buffer(cachedBytes) to finalMimeType
            } else {
                val logoBytes = file.readBytes()
                val mimeType = MimeTypeUtil.getMimeTypeFromFileName(path)
                
                // Use hash from config if available, otherwise calculate
                val logoHash = logoFile.hash.ifEmpty { 
                    logoBytes.inputStream().use { it.hash() }
                }
                
                // Get cached/compressed version
                val (cachedBytes, finalMimeType) = getCachedLogo(logoHash, logoBytes, mimeType, cacheDir)
                Buffer.buffer(cachedBytes) to finalMimeType
            }
        }

        // Add logo as inline attachment with Content-ID "logo"
        val logoAttachment = MailAttachment.create()
            .setData(logoData)
            .setName("logo")
            .setContentType(logoMimeType)
            .setDisposition("inline")
            .setContentId("<logo>")
        
        message.inlineAttachment = listOf(logoAttachment)

        // Convert MailParameters to Map<String, Any> using JsonObject
        val jsonObject = JsonObject(gson.toJson(mailParameters))
        val parameters = jsonObject.map.toMutableMap()

        parameters["websiteUrl"] = config.websiteUrl
        parameters["websiteLogo"] = "cid:logo" // Use Content-ID reference instead of URL
        parameters["websiteName"] = config.websiteName

        val template = mail.getTemplate(handlebars)
        message.html = template.apply(parameters)

        mailClient.sendMail(message).coAwait()
    }

    suspend fun validateConfig(config: JsonObject, sender: String) {
        try {
            val mailConfig = MailConfig(config)

            val mailClient = MailClient.create(vertx, mailConfig)

            val message = MailMessage()

            message.from = sender
            message.subject = "Pano Platform E-mail test"
            message.setTo("no-reply@duruer.dev")
            message.html = "Hello world!"

            mailClient.sendMail(message).coAwait()

            mailClient.close()
        } catch (e: Exception) {
            logger.error(e.toString())

            throw InvalidData(extras = mapOf("mailError" to e.message))
        } catch (e: SMTPException) {
            logger.error(e.toString())

            throw InvalidData(extras = mapOf("mailError" to e.message))
        }
    }
}