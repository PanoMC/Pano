package com.panomc.platform.model

import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.PlatformAlreadyInstalled
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.RequestOptions
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** [Api.authorizedBodyHandler]: the checks answer before any of a large upload is read. */
class AuthorizedBodyHandlerTest {
    private lateinit var vertx: Vertx

    @TempDir
    lateinit var uploads: File

    private val beforeHandleCalls = AtomicInteger()
    private val bodyRead = AtomicBoolean()

    private inner class UploadApi(private val installed: Boolean) : Api() {
        override val paths = listOf(Path("/upload", RouteType.POST))

        override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

        override fun bodyHandler(): Handler<RoutingContext> {
            val body = BodyHandler.create().setUploadsDirectory(uploads.absolutePath).setBodyLimit(64L shl 30)

            return authorizedBodyHandler(Handler { bodyRead.set(true); body.handle(it) })
        }

        override suspend fun onBeforeHandle(context: RoutingContext) {
            beforeHandleCalls.incrementAndGet()

            if (installed) throw PlatformAlreadyInstalled()
        }

        override suspend fun checkBeforeBody(context: RoutingContext) {
            if (context.request().getHeader("x-allowed") == null) throw NoPermission()
        }

        override suspend fun handle(context: RoutingContext): Result =
            Successful(mapOf("length" to context.body().length()))
    }

    @BeforeEach
    fun setUp() {
        vertx = Vertx.vertx()
    }

    @AfterEach
    fun tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)
    }

    private fun <T> io.vertx.core.Future<T>.blockingGet(): T =
        toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)

    private fun serve(api: Api): Int {
        val router = Router.router(vertx)

        router.route(HttpMethod.POST, "/upload")
            .handler(api.bodyHandler()!!)
            .handler(api.getHandler())
            .failureHandler(api.getFailureHandler())

        return vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").blockingGet().actualPort()
    }

    /** Announces a 10 GiB body, sends only 64 KiB of it and waits for the answer. */
    private fun startHugeUpload(port: Int, allowed: Boolean): Int {
        val client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))
        val options = RequestOptions().setMethod(HttpMethod.POST).setPort(port).setHost("127.0.0.1").setURI("/upload")
            .putHeader("content-length", (10L shl 30).toString())

        if (allowed) options.putHeader("x-allowed", "1")

        return client.request(options).compose { request ->
            request.write(Buffer.buffer(ByteArray(64 * 1024)))

            request.response()
        }.blockingGet().statusCode()
    }

    @Test
    fun `an installed Pano refuses the upload before reading it`() {
        val port = serve(UploadApi(installed = true))

        assertEquals(422, startHugeUpload(port, allowed = true))
        assertFalse(bodyRead.get())
        assertEquals(0, uploads.listFiles()!!.size)
    }

    @Test
    fun `a caller without the permission is refused before the body is read`() {
        val port = serve(UploadApi(installed = false))

        assertEquals(NoPermission().getStatusCode(), startHugeUpload(port, allowed = false))
        assertFalse(bodyRead.get())
        assertEquals(0, uploads.listFiles()!!.size)
    }

    @Test
    fun `an allowed upload is read and the checks run once`() {
        val port = serve(UploadApi(installed = false))
        val client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))

        val response = client.request(HttpMethod.POST, port, "127.0.0.1", "/upload").compose { request ->
            request.putHeader("x-allowed", "1").send(Buffer.buffer("hello world"))
        }.compose { response -> response.body().map { response.statusCode() to it.toString() } }.blockingGet()

        assertEquals(200, response.first)
        assertEquals(true, response.second.contains("\"length\":11"), response.second)
        assertEquals(1, beforeHandleCalls.get())
    }
}
