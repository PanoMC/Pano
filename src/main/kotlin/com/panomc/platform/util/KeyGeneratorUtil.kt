package com.panomc.platform.util

import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import java.security.KeyPair

object KeyGeneratorUtil {
    fun generateJWTKeys(): KeyPair = Keys.keyPairFor(SignatureAlgorithm.RS512)
}