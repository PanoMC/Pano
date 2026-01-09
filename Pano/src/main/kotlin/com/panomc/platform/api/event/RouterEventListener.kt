package com.panomc.platform.api.event

import com.panomc.platform.model.Route
import io.vertx.ext.web.Router

interface RouterEventListener : PanoEventListener {

    fun onInitRouteList(routes: MutableList<Route>)

    fun onRouterCreate(router: Router)
}