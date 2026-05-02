package com.panomc.platform.util

import java.lang.management.ManagementFactory

/**
 * IntelliJ/IDEA run configurations inject `idea_rt.jar` as a javaagent — stdout is not a real TTY.
 */
fun launchedWithIdeaRtAgent(): Boolean =
    try {
        ManagementFactory.getRuntimeMXBean().inputArguments.any {
            it.contains("idea_rt.jar", ignoreCase = true)
        }
    } catch (_: Exception) {
        false
    }
