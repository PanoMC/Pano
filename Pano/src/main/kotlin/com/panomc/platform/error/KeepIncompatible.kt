package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * A reinstall asked to carry something over that cannot survive the change (SM-66, §2.4.31):
 * plugins across families, worlds into a proxy, configs between unrelated softwares, or anything
 * beyond the worlds on a node too old to copy it.
 *
 * Carries `keep`, the names of the refused switches (`worlds`, `plugins`, `configs`), so the panel
 * can point at the checkbox. Nothing was touched.
 */
class KeepIncompatible(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(400, statusMessage, extras)
