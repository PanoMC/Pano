package com.panomc.platform.route.api.panel.frontend.settings

import com.panomc.platform.frontend.FrontendSettingsView

/**
 * The body of `GET /panel/frontend/settings` and of a successful `PUT`: whose settings these are, the schema the
 * form is drawn from (null when the front-end declares no `fields`, then the page links to the theme's own
 * settings page), and the values with defaults filled in.
 */
internal fun frontendSettingsState(view: FrontendSettingsView): Map<String, Any?> = mapOf(
    "id" to view.id,
    "mode" to view.mode.name,
    "hasSchema" to (view.schema != null),
    "schema" to view.schema?.toJson()?.map,
    "settings" to view.reading.settings.map,
    "files" to view.reading.files.map
)
