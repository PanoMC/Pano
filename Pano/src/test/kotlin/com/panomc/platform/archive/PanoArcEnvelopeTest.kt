package com.panomc.platform.archive

import com.panomc.platform.archive.PanoArcException.Code
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.random.Random

class PanoArcEnvelopeTest {
    private val chunk = PanoArcEnvelope.CHUNK_SIZE
    private val workloadKey = ByteArray(32) { it.toByte() }
    private val workload = PanoArcEncryption.Workload(workloadKey, "v3")
    private val workloadKeys = PanoArcKeys(workloadKey = { id -> if (id == "v3") workloadKey else null })

    /** Cheap KDF parameters so the many passphrase tests stay fast; one test uses the real defaults. */
    private fun fastPassphrase(value: String) = PanoArcEncryption.Passphrase(value.toCharArray(), t = 1, mKiB = 8192, p = 1)

    private fun encrypt(data: ByteArray, encryption: PanoArcEncryption = workload, sealLast: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()

        PanoArcEnvelope.encrypt(out, encryption, chunk, sealLast).use { it.write(data) }

        return out.toByteArray()
    }

    private fun decrypt(bytes: ByteArray, keys: PanoArcKeys = workloadKeys): ByteArray =
        PanoArcEnvelope.open(bytes.inputStream(), keys).use { it.readBytes() }

    private fun fails(code: Code, block: () -> Unit) {
        val error = assertThrows<PanoArcException> { block() }

        assertEquals(code, error.code, error.message)
    }

    /** magic | u32 headerLen | header | frames (each frame = u32 ctLen | ct). */
    private class Parsed(val header: ByteArray, val frames: List<ByteArray>)

    private fun parse(bytes: ByteArray): Parsed {
        val headerLength = PanoArcEnvelope.readU32(bytes, 8).toInt()
        val header = bytes.copyOfRange(12, 12 + headerLength)
        val frames = mutableListOf<ByteArray>()
        var offset = 12 + headerLength

        while (offset < bytes.size) {
            val length = PanoArcEnvelope.readU32(bytes, offset).toInt()

            frames.add(bytes.copyOfRange(offset, offset + 4 + length))
            offset += 4 + length
        }

        return Parsed(header, frames)
    }

    private fun build(header: ByteArray, frames: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()

        out.write(PanoArcEnvelope.MAGIC)
        out.write(PanoArcEnvelope.u32(header.size.toLong()))
        out.write(header)
        frames.forEach { out.write(it) }

        return out.toByteArray()
    }

    @Test
    fun `hkdf matches RFC 5869 test case 3`() {
        val okm = PanoArcCrypto.hkdfSha256(ByteArray(22) { 0x0b }, ByteArray(0), 42)

        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            ArchiveManifest.hex(okm)
        )
    }

    @Test
    fun `round trips empty, single-chunk and multi-chunk payloads`() {
        listOf(0, 1, chunk - 1, chunk, chunk + 1, 2 * chunk, chunk * 3 + chunk / 2).forEach { size ->
            val data = Random(size).nextBytes(size)
            val sealed = encrypt(data)
            val expectedFrames = maxOf(1, (size + chunk - 1) / chunk)

            assertEquals(expectedFrames, parse(sealed).frames.size, "frames for $size bytes")
            assertArrayEquals(data, decrypt(sealed), "payload of $size bytes")
        }
    }

    @Test
    fun `round trips byte-by-byte writes`() {
        val data = Random(7).nextBytes(5000)
        val out = ByteArrayOutputStream()

        PanoArcEnvelope.encrypt(out, workload).use { stream -> data.forEach { stream.write(it.toInt()) } }

        val input = PanoArcEnvelope.open(out.toByteArray().inputStream(), workloadKeys)
        val read = ByteArrayOutputStream()

        while (true) {
            val b = input.read()

            if (b < 0) break

            read.write(b)
        }

        assertArrayEquals(data, read.toByteArray())
    }

    @Test
    fun `header carries the spec fields`() {
        val sealed = encrypt(ByteArray(10))
        val header = JsonObject(String(parse(sealed).header, StandardCharsets.UTF_8))

        assertArrayEquals(PanoArcEnvelope.MAGIC, sealed.copyOf(8))
        assertEquals("AES-256-GCM-STREAM", header.getString("alg"))
        assertEquals(1048576, header.getInteger("chunkSize"))
        assertEquals("workload", header.getString("keyMode"))
        assertEquals("v3", header.getString("keyId"))
        assertEquals(8, Base64.getDecoder().decode(header.getString("noncePrefix")).size)
        assertArrayEquals(PanoArcCrypto.checkValue(workloadKey), Base64.getDecoder().decode(header.getString("check")))
    }

    @Test
    fun `passphrase round trip with the default argon2id parameters`() {
        val data = Random(1).nextBytes(chunk + 10)
        val sealed = encrypt(data, PanoArcEncryption.Passphrase("correct horse".toCharArray()))
        val header = PanoArcEnvelope.readHeader(sealed.inputStream())!!

        assertEquals(PanoArcKeyMode.PASSPHRASE, header.keyMode)
        assertEquals(PanoArcKdf("argon2id", 3, 65536, 1, header.kdf!!.salt), header.kdf)
        assertEquals(16, header.kdf!!.salt.size)
        assertArrayEquals(data, decrypt(sealed, PanoArcKeys(passphrase = "correct horse".toCharArray())))
    }

    @Test
    fun `wrong or missing passphrase is refused before any chunk`() {
        val sealed = encrypt(ByteArray(100), fastPassphrase("right"))

        fails(Code.WRONG_PASSPHRASE) { decrypt(sealed, PanoArcKeys(passphrase = "wrong".toCharArray())) }
        fails(Code.PASSPHRASE_REQUIRED) { decrypt(sealed, workloadKeys) }
        assertArrayEquals(ByteArray(100), decrypt(sealed, PanoArcKeys(passphrase = "right".toCharArray())))
    }

    @Test
    fun `wrong or unknown workload key is refused`() {
        val sealed = encrypt(ByteArray(100))

        fails(Code.WRONG_KEY) { decrypt(sealed, PanoArcKeys(workloadKey = { ByteArray(32) { 9 } })) }
        fails(Code.KEY_REQUIRED) { decrypt(sealed, PanoArcKeys(workloadKey = { null })) }
        fails(Code.KEY_REQUIRED) { decrypt(sealed, PanoArcKeys.NONE) }
    }

    @Test
    fun `plain zip passes through and anything else is not an archive`() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3)

        assertArrayEquals(zip, decrypt(zip, PanoArcKeys.NONE))
        assertNull(PanoArcEnvelope.readHeader(zip.inputStream()))
        fails(Code.NOT_AN_ARCHIVE) { decrypt("hello world".toByteArray(), PanoArcKeys.NONE) }
        fails(Code.NOT_AN_ARCHIVE) { decrypt(ByteArray(0), PanoArcKeys.NONE) }
    }

    @Test
    fun `modified header fails authentication`() {
        val parsed = parse(encrypt(Random(2).nextBytes(3000)))
        val json = JsonObject(String(parsed.header, StandardCharsets.UTF_8)).put("note", "x")

        fails(Code.TAMPERED) { decrypt(build(json.encode().toByteArray(), parsed.frames)) }

        // The same JSON re-serialised with different whitespace is different AAD, too.
        val spaced = String(parsed.header, StandardCharsets.UTF_8).replace(",", ", ").toByteArray()

        fails(Code.TAMPERED) { decrypt(build(spaced, parsed.frames)) }
    }

    @Test
    fun `unsupported header values are rejected`() {
        val parsed = parse(encrypt(ByteArray(10)))
        val json = JsonObject(String(parsed.header, StandardCharsets.UTF_8))

        fails(Code.UNSUPPORTED_FORMAT) { decrypt(build(json.copy().put("alg", "AES-128").encode().toByteArray(), parsed.frames)) }
        fails(Code.UNSUPPORTED_FORMAT) { decrypt(build(json.copy().put("keyMode", "none").encode().toByteArray(), parsed.frames)) }
        fails(Code.UNSUPPORTED_FORMAT) { decrypt(build(json.copy().put("chunkSize", 1 shl 30).encode().toByteArray(), parsed.frames)) }
        fails(Code.UNSUPPORTED_FORMAT) { decrypt(build("not json".toByteArray(), parsed.frames)) }

        val pass = parse(encrypt(ByteArray(10), fastPassphrase("p")))
        val passJson = JsonObject(String(pass.header, StandardCharsets.UTF_8))

        passJson.getJsonObject("kdf").put("mKiB", 1 shl 28)
        fails(Code.UNSUPPORTED_FORMAT) {
            decrypt(build(passJson.encode().toByteArray(), pass.frames), PanoArcKeys(passphrase = "p".toCharArray()))
        }
    }

    @Test
    fun `modified check value reads as a wrong key`() {
        val parsed = parse(encrypt(ByteArray(10)))
        val json = JsonObject(String(parsed.header, StandardCharsets.UTF_8))
            .put("check", Base64.getEncoder().encodeToString(ByteArray(32)))

        fails(Code.WRONG_KEY) { decrypt(build(json.encode().toByteArray(), parsed.frames)) }

        val pass = parse(encrypt(ByteArray(10), fastPassphrase("p")))
        val passJson = JsonObject(String(pass.header, StandardCharsets.UTF_8))
            .put("check", Base64.getEncoder().encodeToString(ByteArray(32) { 1 }))

        fails(Code.WRONG_PASSPHRASE) {
            decrypt(build(passJson.encode().toByteArray(), pass.frames), PanoArcKeys(passphrase = "p".toCharArray()))
        }
    }

    @Test
    fun `flipped ciphertext or tag byte fails authentication`() {
        val sealed = encrypt(Random(3).nextBytes(chunk + 500))
        val parsed = parse(sealed)

        // First chunk, a ciphertext byte.
        val body = parsed.frames.map { it.copyOf() }.also { it[0][100] = (it[0][100].toInt() xor 1).toByte() }

        fails(Code.TAMPERED) { decrypt(build(parsed.header, body)) }

        // Last chunk, the last tag byte.
        val tag = parsed.frames.map { it.copyOf() }.also { frames ->
            val last = frames.last()

            last[last.size - 1] = (last[last.size - 1].toInt() xor 0x80).toByte()
        }

        fails(Code.TAMPERED) { decrypt(build(parsed.header, tag)) }
    }

    @Test
    fun `invalid chunk length is tampering`() {
        val parsed = parse(encrypt(ByteArray(10)))
        val frame = parsed.frames[0].copyOf().also { it[0] = 0x7F }

        fails(Code.TAMPERED) { decrypt(build(parsed.header, listOf(frame))) }
    }

    @Test
    fun `dropped final chunk is truncation`() {
        val parsed = parse(encrypt(Random(4).nextBytes(chunk * 2 + 10)))

        assertEquals(3, parsed.frames.size)
        fails(Code.TRUNCATED) { decrypt(build(parsed.header, parsed.frames.dropLast(1))) }

        // An exact multiple of the chunk size also has a sealed-last full chunk that must not be droppable.
        val exact = parse(encrypt(Random(5).nextBytes(chunk * 2)))

        fails(Code.TRUNCATED) { decrypt(build(exact.header, exact.frames.dropLast(1))) }
    }

    @Test
    fun `stream cut inside a chunk, a length or the header is truncation`() {
        val sealed = encrypt(Random(6).nextBytes(chunk + 10))
        val parsed = parse(sealed)
        val firstFrameEnd = 12 + parsed.header.size + parsed.frames[0].size

        fails(Code.TRUNCATED) { decrypt(sealed.copyOf(sealed.size - 1)) }
        fails(Code.TRUNCATED) { decrypt(sealed.copyOf(firstFrameEnd - 1000)) }
        fails(Code.TRUNCATED) { decrypt(sealed.copyOf(firstFrameEnd + 2)) }
        fails(Code.TRUNCATED) { decrypt(sealed.copyOf(12 + parsed.header.size)) }
        fails(Code.TRUNCATED) { decrypt(sealed.copyOf(20)) }
        fails(Code.TRUNCATED) { decrypt(sealed.copyOf(10)) }
    }

    @Test
    fun `stream without a lastFlag chunk is truncation`() {
        fails(Code.TRUNCATED) { decrypt(encrypt(Random(8).nextBytes(chunk + 10), sealLast = false)) }
        fails(Code.TRUNCATED) { decrypt(encrypt(ByteArray(0), sealLast = false)) }
    }

    @Test
    fun `reordered or appended chunks fail authentication`() {
        val parsed = parse(encrypt(Random(9).nextBytes(chunk * 3 + 10)))
        val swapped = parsed.frames.toMutableList().also { val a = it[0]; it[0] = it[1]; it[1] = a }

        fails(Code.TAMPERED) { decrypt(build(parsed.header, swapped)) }
        fails(Code.TAMPERED) { decrypt(build(parsed.header, parsed.frames + parsed.frames.last())) }
    }

    @Test
    fun `chunks from another archive with the same key do not splice`() {
        val a = parse(encrypt(Random(10).nextBytes(chunk + 10)))
        val b = parse(encrypt(Random(11).nextBytes(chunk + 10)))

        fails(Code.TAMPERED) { decrypt(build(a.header, listOf(b.frames[0], a.frames[1]))) }
    }
}
