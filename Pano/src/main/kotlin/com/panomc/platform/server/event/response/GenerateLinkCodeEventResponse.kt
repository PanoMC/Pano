package com.panomc.platform.server.event.response

import com.panomc.platform.server.ServerEventResponse

enum class GenerateLinkCodeStatus {
    // Code generated (or reused) successfully.
    SUCCESS,

    // The Minecraft user already has credentials (password or email) on the website,
    // therefore /link is not needed.
    ALREADY_REGISTERED,

    // No user with the requested username exists in the database yet.
    USER_NOT_FOUND
}

data class GenerateLinkCodeEventResponse(
    val code: String? = null,
    val status: GenerateLinkCodeStatus = GenerateLinkCodeStatus.SUCCESS
) : ServerEventResponse()
