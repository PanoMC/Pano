package com.panomc.platform.route.api.auth

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.LoggedInApi
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class LogoutAPI(setupManager: SetupManager, authProvider: AuthProvider) : LoggedInApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/auth/logout")

    override suspend fun handler(context: RoutingContext): Result {
        return Successful()
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