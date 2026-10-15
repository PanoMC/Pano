package com.panomc.platform.ui

import com.panomc.platform.model.Error

/**
 * The error codes of the front-end modes (doc 05 §8). They sit next to the code that throws them
 * instead of in `error/`, which belongs to other units.
 */

/** `manifest.json` of an uploaded app is missing a field, has a wrong one, or is not the manifest of an app. */
class CustomAppInvalidManifest(field: String, message: String) : Error(
    "CUSTOM_APP_INVALID_MANIFEST",
    400,
    "",
    mapOf("message" to message, "field" to field),
    mapOf(field to "INVALID")
)

/** The zip has no `index.js` at its root. */
class CustomAppNoEntry : Error(
    "CUSTOM_APP_NO_ENTRY",
    400,
    "",
    mapOf("message" to "The app has no index.js at the root of the zip.")
)

/** The id belongs to an installed theme, `panel-ui` or `setup-ui`. */
class CustomAppIdTaken(id: String) : Error(
    "CUSTOM_APP_ID_TAKEN",
    409,
    "",
    mapOf("message" to "The id '$id' belongs to a theme or a built-in UI.", "id" to id)
)

/** `apiLevel` of the app is outside what this Pano supports (doc 04 §7). */
class CustomAppApiLevel(apiLevel: Int, min: Int, current: Int) : Error(
    "CUSTOM_APP_API_LEVEL",
    400,
    "",
    mapOf(
        "message" to "The app was built for API level $apiLevel; this Pano supports $min to $current.",
        "apiLevel" to apiLevel,
        "min" to min,
        "current" to current
    )
)

/** The app is the one being served, so it cannot be deleted or replaced. */
class CustomAppActive(id: String) : Error(
    "CUSTOM_APP_ACTIVE",
    409,
    "",
    mapOf("message" to "The app '$id' is the active front-end; switch to another one first.", "id" to id)
)

class UpstreamInvalidUrl(field: String = "upstreamUrl") : Error(
    "UPSTREAM_INVALID_URL",
    400,
    "",
    mapOf("message" to "Expected an http:// or https:// address with a host and no path, e.g. http://127.0.0.1:4000."),
    mapOf(field to "INVALID")
)

/** The upstream is Pano itself, so proxying to it would loop. */
class UpstreamIsPano(field: String = "upstreamUrl") : Error(
    "UPSTREAM_IS_PANO",
    400,
    "",
    mapOf("message" to "That address is this Pano; proxying to it would loop."),
    mapOf(field to "IS_PANO")
)

/** Nothing answered within the probe time. Saving anyway needs `force: true`. */
class UpstreamUnreachable : Error(
    "UPSTREAM_UNREACHABLE",
    400,
    "",
    mapOf("message" to "Nothing answered at that address within 5 seconds. Send force: true to save it anyway.")
)

class DescriptorHostNotAllowed : Error(
    "DESCRIPTOR_HOST_NOT_ALLOWED",
    400,
    "",
    mapOf("message" to "The descriptor must be served from the host of the upstream URL or the site URL."),
    mapOf("descriptorUrl" to "HOST_NOT_ALLOWED")
)

/** The new front-end could not be started; the previous one keeps serving. */
class FrontendStartFailed(message: String) : Error(
    "FRONTEND_START_FAILED",
    500,
    "",
    mapOf("message" to message)
)
