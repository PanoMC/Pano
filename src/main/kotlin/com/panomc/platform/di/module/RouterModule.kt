package com.panomc.platform.di.module

import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Template
import com.panomc.platform.route.api.TestSendNotificationAPI
import com.panomc.platform.route.api.auth.CredentialsAPI
import com.panomc.platform.route.api.auth.LoginAPI
import com.panomc.platform.route.api.auth.LogoutAPI
import com.panomc.platform.route.api.auth.RegisterAPI
import com.panomc.platform.route.api.panel.*
import com.panomc.platform.route.api.panel.dashboard.CloseConnectServerCardAPI
import com.panomc.platform.route.api.panel.dashboard.CloseGettingStartedCardAPI
import com.panomc.platform.route.api.panel.permission.*
import com.panomc.platform.route.api.panel.platformAuth.RefreshKeyAPI
import com.panomc.platform.route.api.panel.playerDetail.PlayerDetailAPI
import com.panomc.platform.route.api.panel.playerDetail.PlayerEditInfoAPI
import com.panomc.platform.route.api.panel.playerDetail.PlayerSetPermissionGroupAPI
import com.panomc.platform.route.api.panel.post.*
import com.panomc.platform.route.api.panel.post.category.*
import com.panomc.platform.route.api.panel.ticket.*
import com.panomc.platform.route.api.panel.ticket.category.TicketCategoryAddAPI
import com.panomc.platform.route.api.panel.ticket.category.TicketCategoryDeleteAPI
import com.panomc.platform.route.api.panel.ticket.category.TicketCategoryUpdateAPI
import com.panomc.platform.route.api.posts.CategoryPostsAPI
import com.panomc.platform.route.api.posts.PostDetailAPI
import com.panomc.platform.route.api.posts.PostsAPI
import com.panomc.platform.route.api.server.ConnectNewAPI
import com.panomc.platform.route.api.setup.CheckAPI
import com.panomc.platform.route.api.setup.DBConnectionTestAPI
import com.panomc.platform.route.api.setup.FinishAPI
import com.panomc.platform.route.api.setup.step.BackStepAPI
import com.panomc.platform.route.api.setup.step.NextStepAPI
import com.panomc.platform.route.staticFolder.assets.*
import com.panomc.platform.route.staticFolder.common.*
import com.panomc.platform.route.staticFolder.src.SrcFolderRoute
import com.panomc.platform.route.template.IndexTemplate
import dagger.Module
import dagger.Provides
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.ext.web.handler.SessionHandler
import io.vertx.ext.web.sstore.LocalSessionStore
import javax.inject.Singleton

@Module
class RouterModule(private val mVertx: Vertx) {
    @Singleton
    private val mRouter by lazy {
        val router = Router.router(mVertx)

        init(router)

        router
    }

    private val mStaticFolderRouteList by lazy {
        arrayOf(
            CommonCSSFolder(),
            CommonFontsFolder(),
            CommonImgFolder(),
            CommonJsFolder(),
            CommonFaviconFolder(),

            AssetsCssFolder(),
            AssetsFontsFolder(),
            AssetsJsFolder(),
            AssetsImgFolder(),
            AssetsLangFolder(),

            SrcFolderRoute()
        )
    }

    private val mTemplateRouteList by lazy {
        arrayOf<Template>(
            IndexTemplate()
        )
    }

    private val mAPIRouteList by lazy {
        arrayOf(
            CheckAPI(),
            BackStepAPI(),
            NextStepAPI(),
            DBConnectionTestAPI(),
            FinishAPI(),

            BasicDataAPI(),

            DashboardAPI(),

            RefreshKeyAPI(),

            CloseGettingStartedCardAPI(),
            CloseConnectServerCardAPI(),

            LogoutAPI(),

            ConnectNewAPI(),

            PanelNotificationsAPI(),
            PanelQuickNotificationsAPI(),
            PanelQuickNotificationsAndReadAPI(),
            TestSendNotificationAPI(),


            TicketsPageInitAPI(),
            TicketCategoryPageInitAPI(),

            PostsPageInitAPI(),
            PostCategoryPageInitAPI(),

            CategoriesAPI(),

            TicketCategoryDeleteAPI(),
            TicketCategoryAddAPI(),
            TicketCategoryUpdateAPI(),

            PostCategoryAddAPI(),
            PostCategoryUpdateAPI(),
            PostCategoryDeleteAPI(),

            EditPostPageInitAPI(),

            PostDeleteAPI(),
            PostOnlyPublishAPI(),
            PostMoveTrashAPI(),
            PostMoveDraftAPI(),
            PostPublishAPI(),

            TicketPageCloseTicketsAPI(),
            TicketPageDeleteTicketsAPI(),

            PlayersPageInitAPI(),

            TicketDetailAPI(),
            TicketDetailMessagePageAPI(),
            TicketDetailSendMessageAPI(),

            PlayerDetailAPI(),

            PanelNotificationsPageAPI(),
            PanelNotificationDeleteAPI(),
            PanelNotificationDeleteAllAPI(),

            PermissionsPageInitAPI(),
            PermissionSetAPI(),
            PermissionDeleteGroupAPI(),
            PermissionAddGroupAPI(),
            PermissionUpdateGroupAPI(),
            PermissionGetGroupsAPI(),

            PlayerSetPermissionGroupAPI(),
            PlayerEditInfoAPI(),

            LoginAPI(),
            PostsAPI(),
            PostDetailAPI(),
            CategoryPostsAPI(),

            CredentialsAPI(),
            RegisterAPI(),
            PostsByCategoryAPI()
        )
    }

    private val mRouteList by lazy {
        listOf(
            *mStaticFolderRouteList,
            *mAPIRouteList,
            *mTemplateRouteList
        )
    }

    private fun init(router: Router) {
        val allowedHeaders: MutableSet<String> = HashSet()
        allowedHeaders.add("x-requested-with")
        allowedHeaders.add("Access-Control-Allow-Origin")
        allowedHeaders.add("origin")
        allowedHeaders.add("Content-Type")
        allowedHeaders.add("accept")
        allowedHeaders.add("X-PINGARUNER")

        val allowedMethods = mutableSetOf<HttpMethod>()
        allowedMethods.add(HttpMethod.GET)
        allowedMethods.add(HttpMethod.POST)
        allowedMethods.add(HttpMethod.OPTIONS)

        allowedMethods.add(HttpMethod.DELETE)
        allowedMethods.add(HttpMethod.PATCH)
        allowedMethods.add(HttpMethod.PUT)

        router.route()
            .handler(BodyHandler.create())
            .handler(SessionHandler.create(LocalSessionStore.create(mVertx)))
            .handler(
                CorsHandler.create(".*.")
                    .allowCredentials(true)
                    .allowedHeaders(allowedHeaders)
                    .allowedMethods(allowedMethods)
            )

        mRouteList.forEach { route ->
            route.routes.forEach { url ->
                when (route.routeType) {
                    RouteType.ROUTE -> router.route(url).order(route.order).handler(route.getHandler())
                        .failureHandler(route.getFailureHandler())
                    RouteType.GET -> router.get(url).order(route.order).handler(route.getHandler())
                        .failureHandler(route.getFailureHandler())
                    RouteType.POST -> router.post(url).order(route.order).handler(route.getHandler())
                        .failureHandler(route.getFailureHandler())
                    RouteType.DELETE -> router.delete(url).order(route.order).handler(route.getHandler())
                        .failureHandler(route.getFailureHandler())
                    RouteType.PUT -> router.put(url).order(route.order).handler(route.getHandler())
                        .failureHandler(route.getFailureHandler())
                }
            }
        }
    }

    @Provides
    @Singleton
    fun provideRouter() = mRouter

}