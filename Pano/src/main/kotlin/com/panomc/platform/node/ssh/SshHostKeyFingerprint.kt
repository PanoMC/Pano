package com.panomc.platform.node.ssh

import net.schmizz.sshj.common.Buffer
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * How a host key is shown to the person who has to decide whether it is the right machine.
 *
 * `SHA256:<base64>` is the format OpenSSH prints, which matters more than it sounds: the only way
 * to check a fingerprint is to compare it with what `ssh-keyscan` or the server's own MOTD says,
 * and a format nobody else uses makes that comparison impossible. The base64 is unpadded for the
 * same reason — OpenSSH drops the `=`.
 */
object SshHostKeyFingerprint {
    /** The fingerprint of an already-encoded SSH public key blob. */
    fun format(keyBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBlob)

        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    /** The fingerprint of a key as the transport handed it over. */
    fun of(key: PublicKey): String = format(Buffer.PlainBuffer().putPublicKey(key).compactData)

    /**
     * Whether two fingerprints are the same one.
     *
     * Compared after trimming and with the `SHA256:` prefix optional, because the string comes
     * back from a panel round trip and from people pasting what `ssh-keyscan` printed.
     */
    fun matches(expected: String?, actual: String?): Boolean {
        if (expected.isNullOrBlank() || actual.isNullOrBlank()) {
            return false
        }

        return normalize(expected) == normalize(actual)
    }

    private fun normalize(value: String) = value.trim().removePrefix("SHA256:").trim()
}
