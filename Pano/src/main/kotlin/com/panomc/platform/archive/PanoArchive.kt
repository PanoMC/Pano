package com.panomc.platform.archive

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Entry points tying the envelope and the zip together: a `.panoarc` is an [ArchiveWriter] zip,
 * wrapped in a [PanoArcEnvelope] unless it is keyMode none (then it is the plain zip).
 */
object PanoArchive {
    const val EXTENSION = ".panoarc"

    /** An [ArchiveWriter] over [output]; [encryption] null = plain zip. Closing the writer closes [output]. */
    fun writer(output: OutputStream, encryption: PanoArcEncryption?, limits: ArchiveLimits = ArchiveLimits()): ArchiveWriter =
        ArchiveWriter(if (encryption == null) output else PanoArcEnvelope.encrypt(output, encryption), limits)

    /** Decrypts (if enveloped) and verifies [input] into [target]; returns the verified manifest. */
    fun extract(input: InputStream, keys: PanoArcKeys, target: File, limits: ArchiveLimits = ArchiveLimits()): ArchiveManifest =
        PanoArcEnvelope.open(input, keys).use { ArchiveReader(limits).extract(it, target) }

    /** Decrypts (if enveloped) and verifies [input] without writing anything. */
    fun verify(input: InputStream, keys: PanoArcKeys, limits: ArchiveLimits = ArchiveLimits()): ArchiveManifest =
        PanoArcEnvelope.open(input, keys).use { ArchiveReader(limits).verify(it) }
}
