package com.panomc.platform.route.api.auth

import com.panomc.platform.model.LoggedInApi
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import io.vertx.ext.web.RoutingContext

class LogoutAPI : LoggedInApi() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/auth/logout")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        handler.invoke(Successful())
//        authProvider.logout(databaseManager, context, (this::logoutHandler)(handler))
    }

//    private fun logoutHandler(handler: (result: Result) -> Unit) = handler@{ isLoggedOut: Result?, _: AsyncResult<*>? ->
//        if (isLoggedOut == null) {
//            handler.invoke(Error(ErrorCode.UNKNOWN))
//
//            return@handler
//        }
//
//        handler.invoke(isLoggedOut)
//    }
}