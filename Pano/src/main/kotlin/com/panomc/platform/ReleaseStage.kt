package com.panomc.platform

import java.util.*

enum class ReleaseStage(val stage: String) {
    ALPHA("alpha"),
    BETA("beta"),
    RELEASE("release");

    override fun toString(): String {
        return super.toString()
            .lowercase()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    companion object {
        fun valueOf(stage: String) = ReleaseStage.values().find { it.stage == stage }
    }
}