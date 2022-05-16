package com.panomc.platform.model

class Errors(val errors: Map<String, Any>) : Throwable(), Result