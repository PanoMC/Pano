package com.panomc.platform.util

import com.panomc.platform.model.Route
import com.panomc.platform.setup.SetupManager
import io.vertx.core.http.HttpClient
import io.vertx.ext.web.Router
import io.vertx.ext.web.proxy.handler.ProxyHandler
import io.vertx.httpproxy.HttpProxy
import io.vertx.httpproxy.ProxyOptions

object UIHelper {
    private var activatedUIList: MutableList<Route.Type> = mutableListOf()

    fun activateSetupUI(httpClient: HttpClient, router: Router) {
        if (activatedUIList.indexOf(Route.Type.SETUP_UI) != -1) {
            return
        }

        val setupUI = HttpProxy.reverseProxy(ProxyOptions().setSupportWebSocket(true), httpClient)

        setupUI.origin(3002, "localhost")

        val setupUIHandler = ProxyHandler.create(setupUI)

        router.route("/*").order(5).putMetadata("type", Route.Type.SETUP_UI).handler(setupUIHandler)

        activatedUIList.add(Route.Type.SETUP_UI)
    }

    fun activateThemeUI(httpClient: HttpClient, router: Router) {
        if (activatedUIList.indexOf(Route.Type.THEME_UI) != -1) {
            return
        }

        val themeUI = HttpProxy.reverseProxy(ProxyOptions().setSupportWebSocket(true), httpClient)

        themeUI.origin(3000, "localhost")

        val themeUIHandler = ProxyHandler.create(themeUI)

        router.route("/*").order(5).putMetadata("type", Route.Type.THEME_UI).handler(themeUIHandler)

        activatedUIList.add(Route.Type.THEME_UI)
    }

    fun activatePanelUI(httpClient: HttpClient, router: Router) {
        if (activatedUIList.indexOf(Route.Type.PANEL_UI) != -1) {
            return
        }

        val panelUI = HttpProxy.reverseProxy(ProxyOptions().setSupportWebSocket(true), httpClient)

        panelUI.origin(3001, "localhost")

        val panelUIHandler = ProxyHandler.create(panelUI)

        router.route("/panel/*").order(4).putMetadata("type", Route.Type.PANEL_UI).handler(panelUIHandler)

        activatedUIList.add(Route.Type.PANEL_UI)
    }

    fun removeUI(router: Router, UI: Route.Type) {

        val foundUI = router.routes.firstOrNull {
            val metadata = it.metadata() ?: mapOf()

            val type = metadata.getOrDefault("type", null)

            type != null && (type as Route.Type) == UI
        }

        foundUI?.let {
            it.disable()
            it.remove()
        }

        if (activatedUIList.indexOf(UI) == -1) {
            return
        }

        activatedUIList.remove(UI)
    }

    fun prepareUI(setupManager: SetupManager, httpClient: HttpClient, router: Router) {
        if (setupManager.isSetupDone()) {

            removeUI(router, Route.Type.SETUP_UI)

            activateThemeUI(httpClient, router)
            activatePanelUI(httpClient, router)
        } else {
            removeUI(router, Route.Type.THEME_UI)
            removeUI(router, Route.Type.PANEL_UI)

            activateSetupUI(httpClient, router)
        }
    }

    fun getActivatedUIList() = activatedUIList.toList()
}