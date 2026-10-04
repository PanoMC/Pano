package com.panomc.platform.archive

import com.panomc.platform.archive.PanoArcException.Code
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** One archived file: path, size and lowercase hex sha256 (`{"p","s","h"}` in the manifest). */
data class ArchiveEntry(val path: String, val size: Long, val sha256: String) {
    fun toJson(): JsonObject = JsonObject().put("p", path).put("s", size).put("h", sha256)
}

data class ArchiveSource(val workloadId: String? = null, val instanceName: String? = null, val hosted: Boolean = false) {
    fun toJson(): JsonObject = JsonObject().put("workloadId", workloadId).put("instanceName", instanceName).put("hosted", hosted)

    companion object {
        fun fromJson(json: JsonObject?) = ArchiveSource(
            json?.getString("workloadId"), json?.getString("instanceName"), json?.getBoolean("hosted") ?: false
        )
    }
}

data class ArchivePanoInfo(val version: String, val dbPrefix: String, val schemeVersions: Map<String, Int> = emptyMap()) {
    fun toJson(): JsonObject = JsonObject()
        .put("version", version)
        .put("dbPrefix", dbPrefix)
        .put("schemeVersions", JsonObject(schemeVersions.toMutableMap<String, Any>()))

    companion object {
        fun fromJson(json: JsonObject?): ArchivePanoInfo? = json?.let {
            ArchivePanoInfo(
                it.getString("version") ?: "",
                it.getString("dbPrefix") ?: "",
                it.getJsonObject("schemeVersions")?.map?.mapValues { (_, v) -> (v as Number).toInt() } ?: emptyMap()
            )
        }
    }
}

data class ArchiveDbInfo(val engine: String, val dumpTool: String, val sizeBytes: Long, val sha256: String) {
    fun toJson(): JsonObject = JsonObject().put("engine", engine).put("dumpTool", dumpTool).put("sizeBytes", sizeBytes).put("sha256", sha256)

    companion object {
        fun fromJson(json: JsonObject?): ArchiveDbInfo? = json?.let {
            ArchiveDbInfo(it.getString("engine") ?: "", it.getString("dumpTool") ?: "", it.getLong("sizeBytes") ?: -1, it.getString("sha256") ?: "")
        }
    }
}

data class ArchiveFilesInfo(val count: Int, val sizeBytes: Long, val sha256OfList: String) {
    fun toJson(): JsonObject = JsonObject().put("count", count).put("sizeBytes", sizeBytes).put("sha256OfList", sha256OfList)
}

/**
 * `manifest.json`, the last entry of every archive (archive-format.md section 1).
 *
 * `db` describes the [DB_DUMP_ENTRY] entry when there is one; `files` summarises every other
 * entry, with `sha256OfList` = sha256 over `"<p>\t<s>\t<h>\n"` of those entries in manifest order.
 */
data class ArchiveManifest(
    val kind: String,
    val createdAt: Long,
    val producer: String,
    val source: ArchiveSource = ArchiveSource(),
    val pano: ArchivePanoInfo? = null,
    val db: ArchiveDbInfo? = null,
    val files: ArchiveFilesInfo,
    val entries: List<ArchiveEntry>,
    val skipped: List<String> = emptyList(),
    val formatVersion: Int = FORMAT_VERSION
) {
    fun toJson(): JsonObject = JsonObject()
        .put("format", FORMAT)
        .put("formatVersion", formatVersion)
        .put("kind", kind)
        .put("createdAt", createdAt)
        .put("producer", producer)
        .put("source", source.toJson())
        .put("pano", pano?.toJson())
        .put("db", db?.toJson())
        .put("files", files.toJson())
        .put("entries", JsonArray(entries.map { it.toJson() }))
        .put("skipped", JsonArray(skipped))

    fun encode(): ByteArray = toJson().encodePrettily().toByteArray(StandardCharsets.UTF_8)

    companion object {
        const val FORMAT = "panoarc"
        const val FORMAT_VERSION = 1
        const val MANIFEST_ENTRY = "manifest.json"
        const val DB_DUMP_ENTRY = "db/dump.sql.gz"

        const val KIND_PANO_INSTANCE = "pano-instance"
        const val KIND_MC_SERVER = "mc-server"

        fun filesInfo(entries: List<ArchiveEntry>): ArchiveFilesInfo {
            val files = entries.filter { it.path != DB_DUMP_ENTRY }
            val digest = MessageDigest.getInstance("SHA-256")

            files.forEach { digest.update("${it.path}\t${it.size}\t${it.sha256}\n".toByteArray(StandardCharsets.UTF_8)) }

            return ArchiveFilesInfo(files.size, files.sumOf { it.size }, hex(digest.digest()))
        }

        fun parse(bytes: ByteArray): ArchiveManifest {
            try {
                val json = JsonObject(String(bytes, StandardCharsets.UTF_8))

                if (json.getString("format") != FORMAT) {
                    throw PanoArcException(Code.INVALID_ARCHIVE, "Not a panoarc manifest.")
                }

                val formatVersion = json.getInteger("formatVersion") ?: -1

                if (formatVersion != FORMAT_VERSION) {
                    throw PanoArcException(Code.UNSUPPORTED_FORMAT, "Unsupported archive format version $formatVersion.")
                }

                val entries = (json.getJsonArray("entries") ?: JsonArray()).map {
                    val entry = it as JsonObject

                    ArchiveEntry(entry.getString("p")!!, entry.getLong("s")!!, entry.getString("h")!!)
                }

                val filesJson = json.getJsonObject("files") ?: JsonObject()

                return ArchiveManifest(
                    kind = json.getString("kind") ?: "",
                    createdAt = json.getLong("createdAt") ?: 0,
                    producer = json.getString("producer") ?: "",
                    source = ArchiveSource.fromJson(json.getJsonObject("source")),
                    pano = ArchivePanoInfo.fromJson(json.getJsonObject("pano")),
                    db = ArchiveDbInfo.fromJson(json.getJsonObject("db")),
                    files = ArchiveFilesInfo(
                        filesJson.getInteger("count") ?: -1,
                        filesJson.getLong("sizeBytes") ?: -1,
                        filesJson.getString("sha256OfList") ?: ""
                    ),
                    entries = entries,
                    skipped = (json.getJsonArray("skipped") ?: JsonArray()).map { it.toString() },
                    formatVersion = formatVersion
                )
            } catch (e: PanoArcException) {
                throw e
            } catch (e: Exception) {
                throw PanoArcException(Code.INVALID_ARCHIVE, "Invalid manifest.", e)
            }
        }

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
