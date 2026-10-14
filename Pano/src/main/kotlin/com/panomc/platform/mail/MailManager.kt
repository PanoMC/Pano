package com.panomc.platform.mail

import com.github.jknack.handlebars.Handlebars
import com.google.gson.Gson
import com.panomc.platform.AppConstants.DEFAULT_WEBSITE_LOGO_FILE
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.frontend.FrontendUrlMap
import com.panomc.platform.db.model.Translation.Companion.TranslationType
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.MailDisabled
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.PanoMailConnectRequired
import com.panomc.platform.error.PanoMailUnavailable
import com.panomc.platform.i18n.I18nManager
import com.panomc.platform.mail.template.MailTemplateContent
import com.panomc.platform.mail.template.MailTemplateService
import com.panomc.platform.model.Error
import com.panomc.platform.util.HashUtil.hash
import com.panomc.platform.util.ImageCompressionUtil
import com.panomc.platform.util.MimeTypeUtil
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.ext.mail.*
import io.vertx.ext.mail.mailencoder.MailEncoder
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
    private val i18nManager: I18nManager,
    private val frontendUrlMap: FrontendUrlMap? = null,
    private val panoMailService: PanoMailService? = null,
    private val mailTemplateService: MailTemplateService? = null
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
        const val PROVIDER_SMTP = "SMTP"
        const val PROVIDER_PANO_MAIL = "PANO_MAIL"

        /** The raw MIME message (CRLF) of [message]; the Message-ID's host is the sender's domain. */
        fun encodeMime(message: MailMessage): ByteArray {
            val domain = message.from.substringAfterLast('@', "pano").trim('>', ' ').ifBlank { "pano" }

            return MailEncoder(message, domain).encode().toByteArray(Charsets.UTF_8)
        }

        /** The error a strict send ([sendRequiredMail]) answers for a Pano Mail failure. */
        fun strictFailure(e: PanoMailException): Error =
            if (e.code == PanoMailException.CONNECT_REQUIRED) PanoMailConnectRequired()
            else PanoMailUnavailable(e.message ?: "", mapOf("reason" to e.reason))

        private const val MAX_LOGO_SIZE_BYTES = 20 * 1024 // 20KB
        private const val MAX_LOGO_DIMENSION = 800 // Maximum width or height in pixels
        private const val CACHE_FOLDER_NAME = "cache"
        private const val CACHE_FILE_PREFIX = "mail-logo-"
        
        data class SystemParameters(
            val websiteName: String,
            val websiteUrl: String,
            /** Absolute URL of a front-end page (doc 05 section 10.1), or null when nothing serves it; the default knows no targets. */
            val frontendUrl: (target: String, params: Map<String, String>) -> String? = { _, _ -> null }
        ) {
            /** [frontendUrl] with [legacyPath] (appended to [websiteUrl]) for the day a target is unknown. */
            fun linkTo(target: String, params: Map<String, String>, legacyPath: String): String =
                frontendUrl(target, params) ?: (websiteUrl + legacyPath)
        }
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


    /**
     * Kept with its exact JVM signature (and its `$default` bridge): shipped plugins link against it.
     * Delegates to the [MailOptions] overload and ignores the result.
     */
    @Deprecated("Use a TemplateMail with sendTemplate")
    suspend fun sendMail(sqlClient: SqlClient, userId: Long?, mail: Mail, email: String? = null) {
        @Suppress("DEPRECATION")
        sendMail(sqlClient, userId, mail, MailOptions(email = email))
    }

    /**
     * Sends [mail]; [options] override recipient, locale, subject, text, Reply-To and attachments.
     * No default arguments on purpose (see the String overload).
     *
     * @throws IllegalArgumentException when the attachments violate the limits (before any SMTP work)
     * @return [MailResult.DISABLED] when e-mail is turned off, [MailResult.SENT] otherwise; SMTP failures throw
     */
    @Deprecated("Use a TemplateMail with sendTemplate")
    suspend fun sendMail(sqlClient: SqlClient, userId: Long?, mail: Mail, options: MailOptions): MailResult =
        deliver(sqlClient, userId, options, strict = false) { locale, system -> legacyContent(mail, locale, system) }

    /**
     * [sendMail] for an action whose whole point is the e-mail (an admin sending one on purpose): a message that did not
     * go out is an error, not a quiet result.
     *
     * @throws MailDisabled when e-mail is turned off
     * @throws PanoMailConnectRequired when Pano Mail is chosen and this Pano is not connected
     * @throws PanoMailUnavailable when Pano Mail did not take the message (limit used up, paused, website unreachable);
     *   `extras.reason` says why
     */
    @Deprecated("Use a TemplateMail with sendRequiredTemplate")
    suspend fun sendRequiredMail(sqlClient: SqlClient, userId: Long?, mail: Mail, options: MailOptions) {
        deliver(sqlClient, userId, options, strict = true) { locale, system -> legacyContent(mail, locale, system) }
    }

    /**
     * Sends [mail] rendered from its template in the recipient's language (`options.locale` > the user's locale >
     * the site locale). Recipient: `options.email`, else the user's address. `options.subject` / `options.text`
     * override the rendered subject / plain-text alternative.
     *
     * @throws IllegalArgumentException when the attachments violate the limits (before any SMTP work)
     * @throws MailTemplateService.TemplateNotFoundException when the mail is not registered or has no template
     * @return [MailResult.DISABLED] when e-mail is turned off, [MailResult.FAILED] when Pano Mail did not take it,
     *   [MailResult.SENT] otherwise; SMTP failures throw
     */
    suspend fun sendTemplate(sqlClient: SqlClient, userId: Long?, mail: TemplateMail, options: MailOptions = MailOptions()): MailResult =
        deliver(sqlClient, userId, options, strict = false) { locale, system -> templateContent(mail, null, locale, system, sqlClient) }

    /**
     * [sendTemplate] for an action whose whole point is the e-mail: a message that did not go out is an error.
     *
     * @throws MailDisabled when e-mail is turned off
     * @throws PanoMailConnectRequired when Pano Mail is chosen and this Pano is not connected
     * @throws PanoMailUnavailable when Pano Mail did not take the message; `extras.reason` says why
     */
    suspend fun sendRequiredTemplate(sqlClient: SqlClient, userId: Long?, mail: TemplateMail, options: MailOptions = MailOptions()) {
        deliver(sqlClient, userId, options, strict = true) { locale, system -> templateContent(mail, null, locale, system, sqlClient) }
    }

    /** The panel's "send test": [draft] (unsaved) rendered with [mail]'s variables, strict. */
    internal suspend fun sendRequiredDraft(
        sqlClient: SqlClient,
        userId: Long?,
        mail: TemplateMail,
        draft: MailTemplateContent,
        options: MailOptions
    ) {
        deliver(sqlClient, userId, options, strict = true) { locale, system -> templateContent(mail, draft, locale, system, sqlClient) }
    }

    /** What one send puts into the message, decided once the recipient's locale is known. */
    private class PreparedMail(val subject: String, val html: String, val text: String?)

    fun systemParameters(): SystemParameters {
        val config = configManager.config

        return SystemParameters(
            config.websiteName,
            config.websiteUrl,
            frontendUrlMap?.let { map -> { target, params -> map.url(target, params) } } ?: { _, _ -> null }
        )
    }

    @Suppress("DEPRECATION")
    private suspend fun legacyContent(mail: Mail, locale: String, system: SystemParameters): PreparedMail {
        val config = configManager.config

        val subject = MailOptions.resolveSubject(
            null,
            {
                i18nManager.translate(
                    mail.subjectTranslationType,
                    locale,
                    mail.subject,
                    mapOf("websiteName" to config.websiteName)
                )
            },
            config.websiteName
        )

        val mailParameters = mail.generateParameters(system, i18nManager, locale)

        // Convert MailParameters to Map<String, Any> using JsonObject
        val parameters = JsonObject(gson.toJson(mailParameters)).map.toMutableMap()

        parameters["websiteUrl"] = config.websiteUrl
        parameters["websiteLogo"] = MailTemplateService.LOGO_CID // Use Content-ID reference instead of URL
        parameters["websiteName"] = config.websiteName

        return PreparedMail(subject, mail.getTemplate(handlebars).apply(parameters), null)
    }

    private suspend fun templateContent(
        mail: TemplateMail,
        draft: MailTemplateContent?,
        locale: String,
        system: SystemParameters,
        sqlClient: SqlClient
    ): PreparedMail {
        val service = mailTemplateService ?: throw IllegalStateException("Mail templates are not available")

        val variables = mail.variables(locale, system)
        val systemVariables = MailTemplateService.systemVariables(system.websiteName, system.websiteUrl, locale)

        val rendered = try {
            if (draft == null) {
                service.renderForSend(mail.owner, mail.key, mail.variant, locale, variables, systemVariables, sqlClient)
            } else {
                val layout = if (draft.mode == MailTemplateContent.Mode.FIELDS) service.layout(sqlClient) else null

                service.render(draft, layout, variables, systemVariables)
            }
        } catch (e: MailTemplateService.TemplateNotFoundException) {
            logger.error("Could not send mail ${mail.owner}/${mail.key}/${mail.variant}: ${e.message}")

            throw e
        }

        return PreparedMail(rendered.subject, rendered.html, rendered.text)
    }

    /**
     * Shared by every send: recipient and locale resolution, the inline logo, attachments, and SMTP or Pano Mail.
     * [prepare] renders subject / HTML / text for the resolved locale.
     */
    private suspend fun deliver(
        sqlClient: SqlClient,
        userId: Long?,
        options: MailOptions,
        strict: Boolean,
        prepare: suspend (locale: String, system: SystemParameters) -> PreparedMail
    ): MailResult {
        val config = configManager.config
        val emailConfig = config.email

        val attachments = MailOptions.validateAttachments(options.attachments)

        if (!emailConfig.enabled) {
            if (strict) throw MailDisabled()

            return MailResult.DISABLED
        }

        val user = userId?.let { databaseManager.userDao.getById(it, sqlClient) }

        val emailAddress = MailOptions.resolveRecipient(options.email, user?.email)
            ?: throw NotExists()
        val locale = MailOptions.resolveLocale(options.locale, user?.localeCode, config.locale)

        val prepared = prepare(locale, systemParameters())

        val message = MailMessage()

        message.from = emailConfig.sender
        message.subject = MailOptions.sanitizeSubject(options.subject) ?: prepared.subject
        message.setTo(emailAddress)

        MailOptions.sanitizeReplyTo(options.replyTo)?.let { message.addHeader("Reply-To", it) }

        // Add logo as inline attachment with Content-ID "logo"
        message.inlineAttachment = listOf(logoAttachment())

        message.html = prepared.html

        (MailOptions.sanitizeText(options.text) ?: MailOptions.sanitizeText(prepared.text))?.let { message.text = it }

        if (attachments.isNotEmpty()) {
            message.attachment = attachments.map {
                MailAttachment.create()
                    .setData(Buffer.buffer(it.data))
                    .setName(it.name)
                    .setContentType(it.contentType)
                    .setDisposition("attachment")
            }
        }

        if (emailConfig.provider == PROVIDER_PANO_MAIL) {
            try {
                sendThroughPanoMail(message)
            } catch (e: PanoMailException) {
                // Already logged. A full limit or a paused account must not turn a registration into an error
                // after the account was created; an admin's own "send" says so.
                if (strict) throw strictFailure(e)

                return MailResult.FAILED
            }
        } else {
            mailClient.sendMail(message).coAwait()
        }

        return MailResult.SENT
    }

    /** The website logo (or the default one), compressed and cached, as the `cid:logo` inline attachment. */
    private fun logoAttachment(): MailAttachment {
        val (logoData, logoMimeType) = logo()

        return MailAttachment.create()
            .setData(Buffer.buffer(logoData))
            .setName("logo")
            .setContentType(logoMimeType)
            .setDisposition("inline")
            .setContentId("<logo>")
    }

    /** The same logo as a `data:` URI, for previews shown in the panel (they cannot resolve `cid:logo`). */
    fun logoDataUri(): String {
        val (data, mimeType) = logo()

        return "data:$mimeType;base64," + java.util.Base64.getEncoder().encodeToString(data)
    }

    /** The website logo (or the default one), compressed and cached: bytes and MIME type. */
    private fun logo(): Pair<ByteArray, String> {
        val config = configManager.config
        val cacheDir = File(config.fileUploadsFolder + File.separator + CACHE_FOLDER_NAME)

        fun defaultLogo(): Pair<ByteArray, String> {
            val logoBytes = systemClassLoader.getResourceAsStream(DEFAULT_WEBSITE_LOGO_FILE)!!.use { it.readBytes() }
            val mimeType = MimeTypeUtil.getMimeTypeFromFileName(DEFAULT_WEBSITE_LOGO_FILE)

            // Calculate hash and get cached/compressed version
            val logoHash = logoBytes.inputStream().use { it.hash() }
            return getCachedLogo(logoHash, logoBytes, mimeType, cacheDir)
        }

        val logoFile = config.filePaths.websiteLogoFile

        return if (logoFile == null) {
            defaultLogo()
        } else {
            val path = config.fileUploadsFolder + File.separator + logoFile.path
            val file = File(path)

            if (!file.exists()) {
                // Fallback to default if custom logo doesn't exist
                defaultLogo()
            } else {
                val logoBytes = file.readBytes()
                val mimeType = MimeTypeUtil.getMimeTypeFromFileName(path)

                // Use hash from config if available, otherwise calculate
                val logoHash = logoFile.hash.ifEmpty { logoBytes.inputStream().use { it.hash() } }

                getCachedLogo(logoHash, logoBytes, mimeType, cacheDir)
            }
        }
    }

    /**
     * Sends [message] through the Pano Mail of the connected panomc.com account.
     *
     * @throws PanoMailException when the website refuses, is unreachable, or this Pano is not connected
     */
    private suspend fun sendThroughPanoMail(message: MailMessage) {
        val service = panoMailService ?: throw PanoMailException(PanoMailException.UNAVAILABLE, "Pano Mail is not available.")

        // The website wants the bare address; the MIME From header keeps the display name.
        val from = PanoMailService.bareAddress(message.from)
        val to = message.to.orEmpty()

        try {
            val result = service.send(from, to, encodeMime(message))

            logger.debug("Pano Mail accepted message ${result.getString("id")} for ${to.size} recipient(s).")
        } catch (e: PanoMailException) {
            logger.error("Pano Mail did not send the e-mail (${e.code}): ${e.message}")

            throw e
        }
    }

    suspend fun validateConfig(config: JsonObject, sender: String) {
        try {
            val mailConfig = MailConfig(config)

            val mailClient = MailClient.create(vertx, mailConfig)

            val message = MailMessage()

            message.from = sender
            message.subject = "Pano Platform E-mail test"
            message.setTo("no-reply@panomc.com")
            message.html = "Hello world! E-mail is working!"

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