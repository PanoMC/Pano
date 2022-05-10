package com.panomc.platform.model

import com.panomc.platform.ErrorCode

open class Error(open val errorCode: ErrorCode) : Result()