package com.panomc.platform.server.dto

/**
 * One entry of the online roster as the plugin reports it inside a metrics sample.
 *
 * [op], [whitelisted] and [gamemode] drive the players page's menu ("de-op" instead of "op", the
 * current game mode ticked). They are null from a proxy, from a node's ping sample and from a
 * plugin older than the fields: "unknown", which the panel answers by offering both choices.
 */
data class ServerMetricPlayerData(
    val uuid: String = "",
    val username: String = "",
    val ping: Long = 0,
    val op: Boolean? = null,
    val whitelisted: Boolean? = null,
    val gamemode: String? = null
)
