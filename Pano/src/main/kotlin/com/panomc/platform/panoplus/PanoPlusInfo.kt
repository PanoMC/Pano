package com.panomc.platform.panoplus

import io.vertx.core.json.JsonObject

/**
 * What panomc.com says about Pano+ for the account this Pano is connected to
 * (`GET /platform/api/pano-plus`). [enabled] false means Pano+ shows nowhere in the panel.
 */
data class PanoPlusInfo(
    val enabled: Boolean,
    val priceMonthly: String? = null,
    val currency: String? = null,
    val benefits: Map<String, Number> = emptyMap(),
    val me: Me? = null
) {
    data class Me(
        val active: Boolean,
        /** `ACTIVE`, `GRACE` or `NONE`. */
        val status: String,
        /** `SUBSCRIPTION`, `HOST`, `ADMIN` or null. */
        val source: String?,
        val renewsAt: Long?,
        val graceUntil: Long?,
        val cancelAtPeriodEnd: Boolean,
        val hostPackageId: String?
    ) {
        fun toMap() = mapOf(
            "active" to active,
            "status" to status,
            "source" to source,
            "renewsAt" to renewsAt,
            "graceUntil" to graceUntil,
            "cancelAtPeriodEnd" to cancelAtPeriodEnd,
            "hostPackageId" to hostPackageId
        )
    }

    companion object {
        val DISABLED = PanoPlusInfo(enabled = false)

        private val STATUSES = setOf("ACTIVE", "GRACE", "NONE")
        private val SOURCES = setOf("SUBSCRIPTION", "HOST", "ADMIN")
        private val PRICE = Regex("^\\d{1,6}(\\.\\d{1,2})?$")
        private val CURRENCY = Regex("^[A-Z]{3}$")
        private val ID = Regex("^[a-z0-9-]{1,40}$")

        /**
         * Reads the platform API answer (`{result, data: {...}}` or the bare object). Anything that
         * does not look right is dropped, and an answer without `enabled: true` is [DISABLED].
         */
        fun fromApi(body: JsonObject?): PanoPlusInfo {
            val data = (body?.getValue("data") as? JsonObject) ?: body ?: return DISABLED

            if (data.getValue("enabled") != true) return DISABLED

            val benefits = (data.getValue("benefits") as? JsonObject)?.map
                ?.mapNotNull { (key, value) -> (value as? Number)?.takeIf { key.length <= 40 }?.let { key to it } }
                ?.toMap()
                ?: emptyMap()

            return PanoPlusInfo(
                enabled = true,
                priceMonthly = (data.getValue("priceMonthly") as? String)?.takeIf(PRICE::matches),
                currency = (data.getValue("currency") as? String)?.takeIf(CURRENCY::matches),
                benefits = benefits,
                me = (data.getValue("me") as? JsonObject)?.let(::meOf)
            )
        }

        private fun meOf(me: JsonObject): Me {
            val status = (me.getValue("status") as? String)?.takeIf { it in STATUSES } ?: "NONE"

            return Me(
                active = status != "NONE" && me.getValue("active") == true,
                status = status,
                source = (me.getValue("source") as? String)?.takeIf { it in SOURCES },
                renewsAt = (me.getValue("renewsAt") as? Number)?.toLong(),
                graceUntil = (me.getValue("graceUntil") as? Number)?.toLong(),
                cancelAtPeriodEnd = me.getValue("cancelAtPeriodEnd") == true,
                hostPackageId = (me.getValue("hostPackageId") as? String)?.takeIf(ID::matches)
            )
        }
    }
}
