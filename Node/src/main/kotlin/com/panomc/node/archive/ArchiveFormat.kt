package com.panomc.node.archive

/**
 * The archive kinds the node can unpack.
 *
 * [streamable] is what decides how an upload is handled: those formats are read front to back, so
 * they are extracted while the bytes are still arriving and never touch the disk as an archive.
 * 7z and RAR keep their index at the end (or need random access to solid blocks), so they are
 * saved to a temp file first. [id] is the stable name sent over the wire; never rename one.
 */
enum class ArchiveFormat(val id: String, val streamable: Boolean) {
    ZIP("zip", true),
    TAR("tar", true),
    TAR_GZ("tar.gz", true),
    TAR_BZ2("tar.bz2", true),
    TAR_XZ("tar.xz", true),
    GZ("gz", true),
    SEVEN_Z("7z", false),
    RAR("rar", false),
    UNKNOWN("unknown", false);

    companion object {
        fun fromId(id: String?): ArchiveFormat = values().firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/**
 * Why an extraction stopped, as a code the panel can translate.
 *
 * An [IllegalStateException] so the existing "operation failed" paths that catch that keep
 * catching it; the [code] is what tells the panel *which* failure it was.
 */
open class ArchiveException(val code: String, message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause) {
    companion object {
        const val NOT_ENOUGH_SPACE = "NOT_ENOUGH_SPACE"
        const val UNSUPPORTED_ARCHIVE = "UNSUPPORTED_ARCHIVE"
        const val CORRUPT_ARCHIVE = "CORRUPT_ARCHIVE"
        const val RAR_UNAVAILABLE = "RAR_UNAVAILABLE"
    }
}

/**
 * The disk ran (or would run) below the safety reserve. [required] is what was needed in total
 * (bytes plus the reserve) when that was known up front, null when the disk filled mid-write.
 */
class NotEnoughSpaceException(val required: Long?, val free: Long) : ArchiveException(
    NOT_ENOUGH_SPACE,
    if (required != null) {
        "Not enough disk space: $required bytes needed, $free bytes free."
    } else {
        "The disk is full: only $free bytes are left, extraction was stopped."
    }
)
