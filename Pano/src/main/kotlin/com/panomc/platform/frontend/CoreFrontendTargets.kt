package com.panomc.platform.frontend

/**
 * The front-end URLs core itself links to (doc 05 section 10.2, the rows of owner `core`). A plugin's
 * own targets come from its `frontend-targets.json`; ids there are prefixed with the plugin's namespace,
 * so these unprefixed-by-plugin ids (`auth.*`, `user.*`) are core's.
 *
 * `fallback` = Pano has a plain built-in page at `/_pano/<id>` for visitors until a front-end claims
 * the target (doc 05 section 10.3). A default path may carry a query with `{param}` placeholders.
 */
object CoreFrontendTargets {
    const val OWNER = "core"

    const val AUTH_ACTIVATE = "auth.activate"
    const val AUTH_RENEW_PASSWORD = "auth.renew-password"
    const val AUTH_ACTIVATE_NEW_EMAIL = "auth.activate-new-email"
    const val AUTH_LOGIN = "auth.login"
    const val AUTH_RESET_PASSWORD = "auth.reset-password"
    const val AUTH_REGISTER = "auth.register"
    const val USER_PROFILE = "user.profile"

    val all: List<FrontendTarget> = listOf(
        FrontendTarget(AUTH_ACTIVATE, "/activate?token={token}", fallback = true),
        FrontendTarget(AUTH_RENEW_PASSWORD, "/renew-password?token={token}", fallback = true),
        FrontendTarget(AUTH_ACTIVATE_NEW_EMAIL, "/activate-new-email?token={token}", fallback = true),
        FrontendTarget(AUTH_LOGIN, "/login", fallback = true),
        FrontendTarget(AUTH_RESET_PASSWORD, "/reset-password"),
        FrontendTarget(AUTH_REGISTER, "/register"),
        FrontendTarget(USER_PROFILE, "/profile")
    )

    val byId: Map<String, FrontendTarget> = all.associateBy { it.id }
}
