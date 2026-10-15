package com.panomc.platform.model

import com.panomc.platform.error.InvalidFields
import com.panomc.platform.error.PageNotFound
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestParameters
import io.vertx.ext.web.validation.ValidationHandler.REQUEST_CONTEXT_KEY
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.common.dsl.Schemas
import io.vertx.json.schema.common.dsl.Schemas.intSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/** A validated page request: [number] is 1-based, [size] is the number of items per page. */
class PageRequest(val number: Int, val size: Int) {
    /** Rows to skip in front of this page. */
    val offset: Long get() = (number - 1L) * size

    /** Rows to read for this page (alias of [size] for `LIMIT ? OFFSET ?` readers). */
    val limit: Int get() = size
}

/**
 * The one page shape of every list endpoint (doc 04 section 4).
 *
 * Request: `page` (1-based, default 1) and `pageSize` (the endpoint's default, at most [MAX_SIZE]).
 * Response: `{ "items": [...], "page": { "number", "size", "totalItems", "totalPages" } }` plus any extra
 * top-level keys the endpoint wants (`category`, `filters`).
 *
 * A `page` below 1, a `pageSize` outside `1..maxSize` or a value that is not an integer is refused (never
 * clamped) with 400 `INVALID_FIELDS` and `fields: {"pageSize": "OUT_OF_RANGE"}`. A page beyond the last is
 * 404 `PAGE_NOT_FOUND`. An empty result has `totalPages: 0` and page 1 is valid.
 */
object Paging {
    const val DEFAULT_SIZE = 10
    const val MAX_SIZE = 100

    const val OUT_OF_RANGE = "OUT_OF_RANGE"

    private val RESERVED_KEYS = setOf("items", "page")

    /** Reads `page` and `pageSize` of [context]; throws [InvalidFields] when one is out of range. */
    fun request(
        context: RoutingContext,
        defaultSize: Int = DEFAULT_SIZE,
        maxSize: Int = MAX_SIZE
    ): PageRequest {
        val parameters = context.get<RequestParameters>(REQUEST_CONTEXT_KEY)

        // A validated integer arrives as a Number (its `string` is null), a raw value as a String.
        fun raw(name: String) = parameters?.queryParameter(name)?.get()?.toString()
            ?: context.queryParam(name).firstOrNull()

        return parse(raw("page"), raw("pageSize"), defaultSize, maxSize)
    }

    /** [request] on the raw text of the two query parameters (null = absent). */
    fun parse(
        page: String?,
        pageSize: String?,
        defaultSize: Int = DEFAULT_SIZE,
        maxSize: Int = MAX_SIZE
    ): PageRequest {
        require(defaultSize in 1..maxSize) { "defaultSize $defaultSize must be in 1..$maxSize" }

        val fields = linkedMapOf<String, Any?>()

        val number = if (page == null) 1L else page.trim().toLongOrNull()?.takeIf { it >= 1 }
        val size = if (pageSize == null) defaultSize.toLong() else pageSize.trim().toLongOrNull()?.takeIf { it in 1..maxSize }

        if (number == null) {
            fields["page"] = OUT_OF_RANGE
        }

        if (size == null) {
            fields["pageSize"] = OUT_OF_RANGE
        }

        if (fields.isNotEmpty()) {
            throw InvalidFields(fields)
        }

        // A page above Int.MAX_VALUE cannot exist; it falls through to PAGE_NOT_FOUND.
        return PageRequest(minOf(number!!, Int.MAX_VALUE.toLong()).toInt(), size!!.toInt())
    }

    /** Declares the optional `page` and `pageSize` query parameters on [builder]. */
    fun params(builder: ValidationHandlerBuilder): ValidationHandlerBuilder = builder
        .queryParameter(optionalParam("page", Schemas.intSchema()))
        .queryParameter(optionalParam("pageSize", Schemas.intSchema()))

    /** Number of pages for [totalItems] items of [size]; 0 when there are none. */
    fun totalPages(totalItems: Long, size: Int): Long = (totalItems + size - 1) / size

    /**
     * Throws [PageNotFound] when [page] is beyond the last page of [totalItems] items. Page 1 is always valid,
     * so an empty list is a normal answer. Call it before reading the rows of the page.
     */
    fun requireInRange(page: PageRequest, totalItems: Long) {
        if (page.number > 1 && page.number > totalPages(totalItems, page.size)) {
            throw PageNotFound()
        }
    }

    /** The response body: [items], the `page` object and the [extra] top-level keys. */
    fun response(
        items: List<Any?>,
        totalItems: Long,
        page: PageRequest,
        extra: Map<String, Any?> = mapOf()
    ): Map<String, Any?> {
        require(extra.keys.none { it in RESERVED_KEYS }) { "extra keys may not use ${RESERVED_KEYS}" }

        requireInRange(page, totalItems)

        val body = linkedMapOf<String, Any?>()

        body["items"] = items
        body["page"] = mapOf(
            "number" to page.number,
            "size" to page.size,
            "totalItems" to totalItems,
            "totalPages" to totalPages(totalItems, page.size)
        )
        body.putAll(extra)

        return body
    }
}

/**
 * The cursor variant of the page shape (doc 04 section 4), for the lists that grow at one end and are read in
 * steps: console search, server activity, alerts and the notifications.
 *
 * Request: `limit` (the endpoint's default, at most its maximum) and `cursor` (what the previous answer
 * gave as `page.nextCursor`; absent = the first page). Response:
 * `{ "items": [...], "page": { "size": 50, "nextCursor": "..." | null } }` plus any extra top-level keys.
 * A `limit` outside `1..max` is refused with 400 `INVALID_FIELDS` and `fields: {"limit":"OUT_OF_RANGE"}`,
 * never clamped, like `pageSize` in [Paging].
 */
object CursorPaging {
    /** `fields` value of a `cursor` that is not one this list could have given out. */
    const val INVALID = "INVALID"

    private val RESERVED_KEYS = setOf("items", "page")

    /** Declares the optional `limit` and `cursor` query parameters on [builder]. */
    fun params(builder: ValidationHandlerBuilder): ValidationHandlerBuilder = builder
        .queryParameter(optionalParam("limit", intSchema()))
        .queryParameter(optionalParam("cursor", stringSchema()))

    /** The raw text of query parameter [name] (null = absent). */
    fun raw(context: RoutingContext, name: String): String? {
        val parameters = context.get<RequestParameters>(REQUEST_CONTEXT_KEY)

        // A validated integer arrives as a Number (its `string` is null), a raw value as a String.
        return parameters?.queryParameter(name)?.get()?.toString() ?: context.queryParam(name).firstOrNull()
    }

    /** Reads `limit` of [context]; throws [InvalidFields] when it is not in `1..maxSize`. */
    fun limit(context: RoutingContext, defaultSize: Int, maxSize: Int): Int =
        limit(raw(context, "limit"), defaultSize, maxSize)

    /** [limit] on the raw text of the parameter (null = absent). */
    fun limit(raw: String?, defaultSize: Int, maxSize: Int): Int {
        require(defaultSize in 1..maxSize) { "defaultSize $defaultSize must be in 1..$maxSize" }

        if (raw == null) {
            return defaultSize
        }

        val value = raw.trim().toLongOrNull()?.takeIf { it in 1..maxSize }
            ?: throw InvalidFields(mapOf("limit" to Paging.OUT_OF_RANGE))

        return value.toInt()
    }

    /**
     * The cursor of a list whose cursor is the id of the last row it showed: null for the first page (absent
     * or blank), the id otherwise. Anything else is refused with `fields: {"cursor":"INVALID"}`.
     */
    fun idCursor(raw: String?): Long? {
        if (raw.isNullOrBlank()) {
            return null
        }

        return raw.trim().toLongOrNull()?.takeIf { it > 0 } ?: throw InvalidFields(mapOf("cursor" to INVALID))
    }

    /** [idCursor] on the `cursor` parameter of [context]. */
    fun idCursor(context: RoutingContext): Long? = idCursor(raw(context, "cursor"))

    /**
     * The response body: [items], `page: { size, nextCursor }` and the [extra] top-level keys. [nextCursor] is
     * null when there is nothing older to ask for.
     */
    fun response(
        items: List<Any?>,
        size: Int,
        nextCursor: String?,
        extra: Map<String, Any?> = mapOf()
    ): Map<String, Any?> {
        require(extra.keys.none { it in RESERVED_KEYS }) { "extra keys may not use $RESERVED_KEYS" }

        val body = linkedMapOf<String, Any?>()

        body["items"] = items
        body["page"] = mapOf("size" to size, "nextCursor" to nextCursor)
        body.putAll(extra)

        return body
    }

    /**
     * Splits rows read with `limit + 1` into the page and its next cursor: the cursor is the id of the last
     * row shown, and only when a further row exists.
     */
    fun <T> split(rows: List<T>, limit: Int, idOf: (T) -> Long): Pair<List<T>, String?> {
        if (rows.size <= limit) {
            return rows to null
        }

        val shown = rows.take(limit)

        return shown to idOf(shown.last()).toString()
    }
}

/**
 * A list that is always read whole (installed plugins, licenses, a locale's texts) in the page shape: one page
 * that holds everything, so `items` and `page` are the same keys as on every other list. `page.size` is the
 * number of items (at least 1), so `totalPages` is 1, or 0 for an empty list.
 */
object WholeList {
    /** The single page of a list of [count] items. */
    fun page(count: Int): PageRequest = PageRequest(1, maxOf(count, 1))

    /** The response body: [items], the `page` object and the [extra] top-level keys. */
    fun response(items: List<Any?>, extra: Map<String, Any?> = mapOf()): Map<String, Any?> =
        Paging.response(items, items.size.toLong(), page(items.size), extra)
}
