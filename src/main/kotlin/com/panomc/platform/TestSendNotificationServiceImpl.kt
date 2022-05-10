package com.panomc.platform

import org.springframework.stereotype.Service


@Service
class TestSendNotificationServiceImpl : TestSendNotificationService {
    override fun sayHello(): String {
        return "Hojam selamlar"
    }
}