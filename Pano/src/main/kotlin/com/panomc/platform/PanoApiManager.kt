package com.panomc.platform

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.error.AlreadyConnectedToPano
import com.panomc.platform.error.PanoConnectFailed
import com.panomc.platform.error.PanoDisconnectFailed
import com.panomc.platform.util.HashUtil
import com.panomc.platform.util.KeyGeneratorUtil
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.HttpRequest
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.coroutines.coAwait
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*

@Component
class PanoApiManager(
    private val configManager: ConfigManager,
    private val webClient: WebClient,
    private val pluginManager: PluginManager
) {
    companion object {
        private const val HEADER_PREFIX = "Bearer "
    }

    private fun getPanoAccountConfig() = configManager.config.panoAccount

    fun isConnected(): Boolean {
        val panoAccountConfig = getPanoAccountConfig()

        return panoAccountConfig.accessToken.isNotBlank()
    }

    fun isConnecting(): Boolean {
        val panoAccountConfig = getPanoAccountConfig()

        return panoAccountConfig.connect != null
    }

    fun createRequest(httpMethod: HttpMethod, uri: String): HttpRequest<Buffer> {
        val panoApiUrl = configManager.config.panoApiUrl

        val request = webClient
            .requestAbs(httpMethod, panoApiUrl + uri)

        if (isConnected()) {
            val panoAccountConfig = getPanoAccountConfig()

            request.putHeader("Authorization", HEADER_PREFIX + panoAccountConfig.accessToken)
        }

        return request
    }

    suspend fun connectPlatform(encodedData: String, state: String): Triple<String, String, String> {
        if (isConnected()) {
            throw AlreadyConnectedToPano()
        }

        if (!isConnecting()) {
            throw PanoConnectFailed()
        }

        val panoAccountConfig = getPanoAccountConfig()

        val connectJsonObject = panoAccountConfig.connect!!
        val connectState = connectJsonObject.state

        if (state != connectState) {
            throw PanoConnectFailed()
        }

        val decoder = Base64.getDecoder()

        val encodedPrivateKey = connectJsonObject.privateKey
        val decodedPrivateKey = decoder.decode(encodedPrivateKey)

        val keySpec = PKCS8EncodedKeySpec(decodedPrivateKey)
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(keySpec)

        val decryptedData: String

        try {
            val decodedData = Base64.getDecoder().decode(encodedData)

            decryptedData = HashUtil.decryptData(decodedData, privateKey)
        } catch (e: Exception) {
            throw PanoConnectFailed()
        }

        val requestBody = JsonObject()

        requestBody.put("code", decryptedData)
        requestBody.put("version", Main.VERSION)

        val username: String
        val email: String
        val platformId: String

        try {
            val authorizeResponse = createRequest(HttpMethod.POST, "/platform/authorize")
                .sendJson(requestBody)
                .coAwait()

            val responseBody = authorizeResponse.bodyAsJsonObject()

            if (responseBody.getString("result") != "ok") {
                throw PanoConnectFailed()
            }

            val responseData = responseBody.getJsonObject("data")

            val jwt = responseData.getString("jwt")
            platformId = responseData.getString("id")
            username = responseData.getString("username")
            email = responseData.getString("email")

            panoAccountConfig.accessToken = jwt
            panoAccountConfig.platformId = platformId
            panoAccountConfig.username = username
            panoAccountConfig.email = email

            panoAccountConfig.connect = null

            configManager.saveConfig()

        } catch (e: Exception) {
            throw PanoConnectFailed()
        }

        return Triple(username, email, platformId)
    }

    suspend fun disconnectPlatform() {
        if (!isConnected()) {
            return
        }

        try {
            val response = createRequest(HttpMethod.POST, "/platform/api/disconnect")
                .send()
                .coAwait()

            val responseBody = response.bodyAsJsonObject()
            val result = responseBody.getString("result")
            val error = responseBody.getString("error")

            if (result != "ok" && error != "UNAUTHORIZED") {
                throw PanoDisconnectFailed()
            }
        } catch (e: Exception) {
            throw PanoDisconnectFailed()
        }

        val panoAccountConfig = getPanoAccountConfig()

        panoAccountConfig.accessToken = ""
        panoAccountConfig.platformId = ""
        panoAccountConfig.username = ""
        panoAccountConfig.email = ""

        panoAccountConfig.connect = null

        configManager.saveConfig()
    }

    fun createPanoCode(): Pair<String, String> {
        if (isConnected()) {
            throw AlreadyConnectedToPano()
        }

        val keyPair = KeyGeneratorUtil.generateKeyPair()
        val base64Encoder = Base64.getEncoder()

        val publicKey = String(base64Encoder.encode(keyPair.public.encoded))
        val privateKey = String(base64Encoder.encode(keyPair.private.encoded))

        val state = UUID.randomUUID()

        val panoAccountConfig = getPanoAccountConfig()

        val connectConfig = PanoConfig.Companion.PanoAccountConnectConfig(publicKey, privateKey, state.toString())

        panoAccountConfig.connect = connectConfig

        configManager.saveConfig()

        return Pair(publicKey, state.toString())
    }

    suspend fun getStoreAuthorizeToken(): Pair<String, String> {
        if (!isConnected()) {
            throw PanoConnectFailed()
        }

        try {
            val authorizeResponse = createRequest(HttpMethod.GET, "/platform/api/store/authorize/token")
                .send()
                .coAwait()

            val responseBody = authorizeResponse.bodyAsJsonObject()

            if (responseBody.getString("result") != "ok") {
                throw PanoConnectFailed()
            }

            val responseData = responseBody.getJsonObject("data")

            val base64Encoder = Base64.getEncoder()

            val token = String(base64Encoder.encode(responseData.getString("token").toByteArray(Charsets.UTF_8)))
            val state = responseData.getString("state")

            return Pair(token, state)
        } catch (e: Exception) {
            throw PanoConnectFailed()
        }
    }

    suspend fun updatePlatformMetadata() {
        if (!isConnected()) {
            return
        }

        val resources = mutableListOf<ResourceObj>()

        resources.addAll(pluginManager.plugins.map { plugin ->
            ResourceObj(
                plugin.pluginId,
                plugin.descriptor.version,
                ResourceObjType.ADDON
            )
        })

        val request = JsonObject(
            mapOf(
                "version" to Main.VERSION,
                "resources" to resources
            )
        )

        try {
            val response = createRequest(HttpMethod.PUT, "/platform/api/metadata")
                .sendJson(request)
                .coAwait()

            val responseBody = response.bodyAsJsonObject()
            val result = responseBody.getString("result")

            if (result != "ok") {
                throw PanoConnectFailed()
            }
        } catch (e: Exception) {
            throw PanoConnectFailed()
        }
    }

    private enum class ResourceObjType {
        ADDON, THEME
    }

    private data class ResourceObj(val id: String, val version: String, val type: ResourceObjType)
}