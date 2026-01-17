package com.panomc.platform.model

class Progress(val progress: Double) : Successful(mapOf("status" to "progress", "progress" to progress))
