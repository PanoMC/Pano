package com.panomc.platform.archive

import java.io.IOException

/**
 * Every way reading or writing a `.panoarc` can fail, as a stable code the APIs and UIs can show.
 *
 * It is an [IOException] so it travels through the stream stack (zip reader over decrypting
 * stream) unchanged instead of being wrapped into something the caller has to unpick.
 */
class PanoArcException(val code: Code, message: String? = null, cause: Throwable? = null) :
    IOException(message ?: code.name, cause) {

    enum class Code {
        /** Neither an envelope nor a zip. */
        NOT_AN_ARCHIVE,

        /** A format, algorithm or version this Pano does not know. */
        UNSUPPORTED_FORMAT,

        /** The archive is passphrase-encrypted and no passphrase was given. */
        PASSPHRASE_REQUIRED,

        /** The archive is workload-key encrypted and no key was available for its keyId. */
        KEY_REQUIRED,

        /** The passphrase does not open this archive (header check value mismatch). */
        WRONG_PASSPHRASE,

        /** The workload key does not open this archive (header check value mismatch). */
        WRONG_KEY,

        /** Header, a chunk or its tag was modified, or chunks were reordered. */
        TAMPERED,

        /** The stream ends before its final chunk. */
        TRUNCATED,

        /** The zip inside is malformed (duplicates, manifest not last, bad manifest, ...). */
        INVALID_ARCHIVE,

        /** An entry name escapes the extraction directory or is otherwise unsafe. */
        UNSAFE_ENTRY,

        /** More bytes than the configured limit. */
        TOO_LARGE,

        /** More entries than the configured limit. */
        TOO_MANY_ENTRIES,

        /** An entry does not match the size or sha256 its manifest recorded. */
        HASH_MISMATCH
    }
}
