package com.panomc.platform

import org.springframework.stereotype.Component

@Component
class PlatformStateManager {
    var restartRequired: Boolean = false
}
