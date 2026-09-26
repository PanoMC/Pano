package com.panomc.platform.backup.remote

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Test-only copy of the control plane's SigV4 (website-back-end pano-host storage/SigV4.kt), for the live S3 IT.
 *
 *
 * AWS Signature Version 4 for S3 (hand-rolled: the plugin has no AWS SDK, and only needs query
 * presigning plus a handful of header-signed calls). Pure functions, checked against the AWS
 * documentation test vectors in `SigV4Test`.
 *
 * Canonical URI: every path segment is RFC 3986-encoded (unreserved characters kept, `/` kept between
 * segments, no double encoding - S3 rules). Canonical query: keys and values encoded (`/` too), sorted
 * by encoded key.
 */
internal object TestSigV4 {
    const val ALGORITHM = "AWS4-HMAC-SHA256"
    const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
    const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /** S3 caps presigned URLs at 7 days. */
    const val MAX_EXPIRES_SECONDS = 604_800L

    private val AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    data class Credentials(val accessKey: String, val secretKey: String) {
        override fun toString() = "Credentials(accessKey=${accessKey.take(4)}…, secretKey=***)"
    }

    fun amzDate(epochMillis: Long): String = AMZ_DATE.format(Instant.ofEpochMilli(epochMillis))

    fun encode(value: String, keepSlash: Boolean = false): String {
        val out = StringBuilder()

        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()

            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~' || (keepSlash && ch == '/')) {
                out.append(ch)
            } else {
                out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xf])
            }
        }

        return out.toString()
    }

    /** The canonical (and on-the-wire) path of [rawPath], e.g. `/bucket/a b.txt` -> `/bucket/a%20b.txt`. */
    fun canonicalUri(rawPath: String): String = if (rawPath.isEmpty()) "/" else encode(rawPath, keepSlash = true)

    fun canonicalQuery(params: Map<String, String>): String =
        params.entries
            .map { encode(it.key) to encode(it.value) }
            .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .joinToString("&") { "${it.first}=${it.second}" }

    fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).toHex()

    fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data.toByteArray(Charsets.UTF_8))
        }

    fun signingKey(secretKey: String, date: String, region: String, service: String): ByteArray {
        val kDate = hmac("AWS4$secretKey".toByteArray(Charsets.UTF_8), date)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, service)

        return hmac(kService, "aws4_request")
    }

    fun scope(date: String, region: String, service: String) = "$date/$region/$service/aws4_request"

    /** Lower-cased, trimmed headers (sequential spaces collapsed), sorted by name. */
    private fun canonicalHeaders(headers: Map<String, String>): List<Pair<String, String>> =
        headers.entries
            .map { it.key.lowercase() to it.value.trim().replace(Regex(" +"), " ") }
            .sortedBy { it.first }

    fun canonicalRequest(
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        payloadHash: String
    ): String {
        val canonical = canonicalHeaders(headers)

        return listOf(
            method,
            canonicalUri(path),
            canonicalQuery(query),
            canonical.joinToString("") { "${it.first}:${it.second}\n" },
            canonical.joinToString(";") { it.first },
            payloadHash
        ).joinToString("\n")
    }

    fun signature(credentials: Credentials, amzDate: String, region: String, service: String, canonicalRequest: String): String {
        val date = amzDate.substring(0, 8)
        val stringToSign = listOf(ALGORITHM, amzDate, scope(date, region, service), sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)))
            .joinToString("\n")

        return hmac(signingKey(credentials.secretKey, date, region, service), stringToSign).toHex()
    }

    /**
     * Query-string presign: returns the full query (canonical order, already encoded) to append after
     * `?`. [headers] must include `host`; every header given is signed (the client must send them).
     */
    fun presignQuery(
        credentials: Credentials,
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        region: String,
        epochMillis: Long,
        expiresSeconds: Long,
        service: String = "s3"
    ): String {
        require(expiresSeconds in 1..MAX_EXPIRES_SECONDS) { "expiresSeconds out of range" }

        val amzDate = amzDate(epochMillis)
        val signedHeaders = canonicalHeaders(headers).joinToString(";") { it.first }
        val params = LinkedHashMap(query).apply {
            put("X-Amz-Algorithm", ALGORITHM)
            put("X-Amz-Credential", "${credentials.accessKey}/${scope(amzDate.substring(0, 8), region, service)}")
            put("X-Amz-Date", amzDate)
            put("X-Amz-Expires", expiresSeconds.toString())
            put("X-Amz-SignedHeaders", signedHeaders)
        }
        val signature = signature(credentials, amzDate, region, service, canonicalRequest(method, path, params, headers, UNSIGNED_PAYLOAD))

        return canonicalQuery(params) + "&X-Amz-Signature=" + signature
    }

    /**
     * Header signing: returns the `Authorization` header value for a request that sends [headers]
     * (which must include `host`, `x-amz-date` and `x-amz-content-sha256`).
     */
    fun authorization(
        credentials: Credentials,
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        payloadHash: String,
        region: String,
        service: String = "s3"
    ): String {
        val amzDate = headers.entries.first { it.key.equals("x-amz-date", ignoreCase = true) }.value
        val signedHeaders = canonicalHeaders(headers).joinToString(";") { it.first }
        val signature = signature(credentials, amzDate, region, service, canonicalRequest(method, path, query, headers, payloadHash))

        return "$ALGORITHM Credential=${credentials.accessKey}/${scope(amzDate.substring(0, 8), region, service)}," +
                "SignedHeaders=$signedHeaders,Signature=$signature"
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
