package com.panomc.platform.util

object VersionUtil {
    fun isSemVer(version: String): Boolean {
        return Regex("^v?\\d+\\.\\d+\\.\\d+(-[\\w.]+)?$").matches(version)
    }

    data class ParsedVersion(val coreParts: List<Int>, val preParts: List<Any>)

    fun parseVersion(version: String): ParsedVersion {
        val cleanVersion = version.removePrefix("v")
        val parts = cleanVersion.split("-", limit = 2)
        val coreParts = parts[0].split(".").map { it.toInt() }
        val preParts = if (parts.size > 1) {
            parts[1].split(".").map {
                it.toIntOrNull() ?: it
            }
        } else {
            emptyList()
        }
        return ParsedVersion(coreParts, preParts)
    }

    fun compareVersions(a: String, b: String): Int {
        val va = parseVersion(a)
        val vb = parseVersion(b)

        val maxLen = maxOf(va.coreParts.size, vb.coreParts.size)
        for (i in 0 until maxLen) {
            val ai = va.coreParts.getOrElse(i) { 0 }
            val bi = vb.coreParts.getOrElse(i) { 0 }
            if (ai != bi) return ai - bi
        }

        val prePriority = listOf("alpha", "beta")

        val isAPre = va.preParts.isNotEmpty()
        val isBPre = vb.preParts.isNotEmpty()

        if (!isAPre && isBPre) return 1
        if (isAPre && !isBPre) return -1

        for (i in 0 until maxOf(va.preParts.size, vb.preParts.size)) {
            val aPart = va.preParts.getOrNull(i)
            val bPart = vb.preParts.getOrNull(i)

            if (aPart == bPart) continue

            when {
                aPart is String && bPart is String -> {
                    val ai = prePriority.indexOf(aPart).takeIf { it != -1 } ?: 99
                    val bi = prePriority.indexOf(bPart).takeIf { it != -1 } ?: 99
                    return ai - bi
                }

                aPart is Int && bPart is Int -> {
                    return aPart - bPart
                }

                else -> {
                    return if (aPart is String) -1 else 1
                }
            }
        }

        return 0
    }

    fun isVersionHigher(versionA: String, versionB: String): Boolean {
        if (versionB == "local-build") return false

        return compareVersions(versionA, versionB) > 0
    }

    fun isPanoVersionCompatible(current: String, required: String): Boolean {
        if (current == "local-build") return true
        if (!isSemVer(current) || !isSemVer(required)) return false

        val currentParsed = parseVersion(current)
        val requiredParsed = parseVersion(required)

        val coreEqual = currentParsed.coreParts == requiredParsed.coreParts
        val requiredIsPre = requiredParsed.preParts.isNotEmpty()

        if (coreEqual && !requiredIsPre) {
            return true
        }

        return compareVersions(current, required) >= 0
    }
}