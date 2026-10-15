package com.panomc.platform

/**
 * Pano cannot go on booting, and [message] already tells the owner why. Thrown instead of calling
 * `System.exit`: [Main.start] logs it (so it reaches `logs/latest.log`) and shuts Pano down in order.
 */
class StartupFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
