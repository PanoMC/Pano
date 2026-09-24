package com.panomc.platform.route.api.panel.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageNodesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.CoolifyBootstrapFailed
import com.panomc.platform.error.CurrentPasswordNotCorrect
import com.panomc.platform.model.*
import com.panomc.platform.node.PanoUrlOverride
import com.panomc.platform.node.coolify.NodeCoolifyBootstrapService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import com.panomc.platform.util.UsageMode

/**
 * Deploys a node to a Coolify instance (`POST /api/panel/nodes/coolify-bootstrap`).
 *
 * The API token is used for this one deployment and never written anywhere: an operator handing
 * Pano a token that can create applications on their infrastructure is doing it for a single
 * action, not granting standing access.
 *
 * Asks for the caller's own password for the same reason the SSH bootstrap does — this reaches
 * outside Pano and spends somebody else's credentials.
 *
 * The optional `panoUrl` is the address the deployed container should use to reach Pano, for NAT,
 * tunnel and lab setups where the public website URL does not resolve or does not route from the
 * Coolify host. It becomes the container's `PANO_URL` exactly as given, port and all, instead of
 * the website URL; see [PanoUrlOverride] for what is accepted.
 *
 * `image` and `imageTag` are for a Coolify host that cannot pull the published image: a private
 * registry, a mirror on an air-gapped network, or a fork somebody builds themselves. Both are
 * checked against what a registry reference may contain rather than passed through, because they
 * become the name of something this deployment will run.
 *
 * A deployment Coolify refuses answers [CoolifyBootstrapFailed] with what it said and the id of
 * the bootstrap task, rather than a bare 400: the operator is the only one who can fix a rejected
 * project, token or port field, and they cannot fix what they are not told.
 */
@Endpoint
class PanelCoolifyBootstrapNodeAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val nodeCoolifyBootstrapService: NodeCoolifyBootstrapService
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/nodes/coolify-bootstrap", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .requiredProperty("currentPassword", stringSchema())
                        .requiredProperty("coolifyUrl", stringSchema())
                        .requiredProperty("apiToken", stringSchema())
                        .requiredProperty("serverUuid", stringSchema())
                        .requiredProperty("name", stringSchema())
                        .optionalProperty("projectUuid", stringSchema())
                        .optionalProperty("environmentName", stringSchema())
                        .optionalProperty("environmentUuid", stringSchema())
                        .optionalProperty("dataVolume", stringSchema())
                        .optionalProperty("portRange", stringSchema())
                        .optionalProperty("panoUrl", stringSchema())
                        .optionalProperty("image", stringSchema())
                        .optionalProperty("imageTag", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageNodesPermission(), context)

        val data = getParameters(context).body().jsonObject

        val sqlClient = getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)

        if (!databaseManager.userDao.isPasswordCorrectWithId(userId, data.getString("currentPassword"), sqlClient)) {
            throw CurrentPasswordNotCorrect()
        }

        val coolifyUrl = data.getString("coolifyUrl").trim()

        // Only http(s): the URL is pasted by hand and is about to receive an API token, so
        // anything else is a typo at best.
        if (!coolifyUrl.startsWith("http://") && !coolifyUrl.startsWith("https://")) {
            throw BadRequest()
        }

        val name = data.getString("name").trim().take(MAX_NAME_LENGTH)
        val projectUuid = data.getString("projectUuid")?.trim().orEmpty()
        val serverUuid = data.getString("serverUuid").trim()
        val portRange = data.getString("portRange")?.trim()?.ifEmpty { null } ?: DEFAULT_PORT_RANGE
        val panoUrl = PanoUrlOverride.sanitize(data.getString("panoUrl"))

        val image = data.getString("image")?.trim()?.ifEmpty { null }
        val imageTag = data.getString("imageTag")?.trim()?.ifEmpty { null }

        if (name.isEmpty() || serverUuid.isEmpty() || projectUuid.isEmpty() || !PORT_RANGE.matches(portRange)) {
            throw BadRequest()
        }

        // Refused rather than sanitised: a registry reference somebody edited into something else
        // is a different image, and deploying a different image than was asked for is worse than
        // refusing the request.
        if (image != null && (image.length > MAX_IMAGE_LENGTH || !IMAGE_NAME.matches(image))) {
            throw BadRequest()
        }

        if (imageTag != null && !IMAGE_TAG.matches(imageTag)) {
            throw BadRequest()
        }

        val task = try {
            nodeCoolifyBootstrapService.start(
                request = NodeCoolifyBootstrapService.Request(
                    coolifyUrl = coolifyUrl,
                    apiToken = data.getString("apiToken").trim(),
                    serverUuid = serverUuid,
                    projectUuid = projectUuid,
                    environmentName = data.getString("environmentName")?.trim()?.ifEmpty { null }
                        ?: DEFAULT_ENVIRONMENT,
                    environmentUuid = data.getString("environmentUuid")?.trim()?.ifEmpty { null },
                    name = name,
                    dataVolume = data.getString("dataVolume")?.trim()?.ifEmpty { null } ?: DEFAULT_VOLUME,
                    portRange = portRange,
                    panoUrl = panoUrl,
                    image = image,
                    imageTag = imageTag
                ),
                createdBy = userId
            )
        } catch (e: NodeCoolifyBootstrapService.BootstrapFailed) {
            // Coolify's own words, and the task they were written to. The panel needs both: the
            // sentence to show now, and the task id to open the deployment that failed.
            throw CoolifyBootstrapFailed(extras = mapOf("message" to e.message, "taskId" to e.task.id))
        } catch (e: Exception) {
            throw BadRequest()
        }

        return Successful(mapOf("taskId" to task.id, "taskUuid" to task.uuid))
    }

    companion object {
        private const val DEFAULT_ENVIRONMENT = "production"
        private const val DEFAULT_VOLUME = "pano-node-data"
        private const val DEFAULT_PORT_RANGE = "25565-25600"
        private const val MAX_NAME_LENGTH = 64

        /** A single port or an inclusive range, which is all Coolify's `ports_exposes` takes. */
        private val PORT_RANGE = Regex("^\\d{1,5}(-\\d{1,5})?$")

        /** Comfortably longer than any registry, namespace and repository put together. */
        const val MAX_IMAGE_LENGTH = 255

        /**
         * What an image reference may contain: `registry.example.com:5000/team/pano-node`.
         *
         * Lower case because that is all a repository name is allowed to be, and the tag is a
         * field of its own here, so no `:tag` suffix is expected in this one.
         */
        val IMAGE_NAME = Regex("^[a-z0-9.:/_-]+$")

        /** Docker's own tag rule, which is case-sensitive unlike the name. */
        val IMAGE_TAG = Regex("^[A-Za-z0-9._-]{1,128}$")
    }
}
