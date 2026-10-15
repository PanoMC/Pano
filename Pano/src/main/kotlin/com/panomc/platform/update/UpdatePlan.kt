package com.panomc.platform.update

import com.panomc.platform.InstallManager.Companion.ResourceType
import com.panomc.platform.gate.ApiLevelGate
import com.panomc.platform.gate.InstalledResource
import com.panomc.platform.gate.Verdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

/** What a later platform update would do to one installed resource. */
enum class PlanVerdict {
    /** Runs on the target's API levels as it is. */
    COMPATIBLE,

    /** Too old (or too new) for the target, but the store has a compatible version; gate 0 installs it after the restart. */
    UPDATE,

    /** Too old for the target and the store has nothing: it stays refused after the restart. */
    DISABLE
}

/**
 * The release an update would install. [apiLevel] is null when the release does not say (built before levels, or the
 * lookup did not carry it): the plan is then unknown and every resource reads `COMPATIBLE`.
 */
data class PlanTarget(val version: String, val apiLevel: Int?, val minApiLevel: Int?) {
    val known: Boolean get() = apiLevel != null

    /** Level range of the target; a missing minimum is level 1, the first level there is (level 0 means "before levels"). */
    val range: Pair<Int, Int>? get() = apiLevel?.let { (minApiLevel ?: 1) to it }
}

data class PlanResource(
    val id: String,
    val type: ResourceType,
    val installedVersion: String,
    val verdict: PlanVerdict,
    val updateVersionId: String? = null
)

/**
 * @property storeReachable whether the store answered the question for the resources the target would refuse; null
 *   when it was not asked (target unknown, or every resource fits).
 */
data class UpdatePlanResult(
    val target: PlanTarget?,
    val resources: List<PlanResource>,
    val storeReachable: Boolean? = null
) {
    fun toMap(agents: List<Map<String, Any?>>): Map<String, Any?> = linkedMapOf(
        "target" to target?.let {
            linkedMapOf("version" to it.version, "apiLevel" to it.apiLevel, "minApiLevel" to it.minApiLevel)
        },
        "resources" to resources.map {
            val row = linkedMapOf<String, Any?>(
                "id" to it.id,
                "type" to it.type.name,
                "installedVersion" to it.installedVersion,
                "verdict" to it.verdict.name
            )

            it.updateVersionId?.let { versionId -> row["updateVersionId"] = versionId }

            row
        },
        "agents" to agents,
        "storeReachable" to storeReachable
    )
}

/**
 * Gate 2 of the compatibility gate (doc 04 section 7): before a later platform update, what would happen to the
 * installed plugins and themes on the target's API levels. Nothing is staged: after the restart gate 0 installs the
 * `UPDATE` rows. The plan is a question to the store about the target's level range, never an install.
 */
object UpdatePlan {
    /** The same 20 seconds the reconcile gives the store. */
    const val STORE_BUDGET_MS = 20_000L

    suspend fun build(
        target: PlanTarget?,
        installed: List<InstalledResource>,
        store: CompatibilityStore,
        budgetMs: Long = STORE_BUDGET_MS
    ): UpdatePlanResult {
        // Plugins an admin switched off do not run either way; the plan is about what would stop working.
        val resources = installed.filter { !it.disabledByAdmin }.sortedWith(compareBy({ it.type }, { it.id }))
        val range = target?.range

        if (target == null || range == null) {
            return UpdatePlanResult(target, resources.map { it.asPlan(PlanVerdict.COMPATIBLE) })
        }

        val (min, max) = range
        val refused = resources.filter { ApiLevelGate.check(it.apiLevel, min, max) != Verdict.OK }

        if (refused.isEmpty()) {
            return UpdatePlanResult(target, resources.map { it.asPlan(PlanVerdict.COMPATIBLE) })
        }

        var reachable = true

        val hits = try {
            val query = CompatibleQuery(max, min, refused.map { QueriedResource(it.id, it.type, it.version) })

            withTimeout(budgetMs) { store.newestCompatible(query) }
                .filter { hit -> refused.any { it.id == hit.id && it.type == hit.type } }
                .associateBy { it.id }
        } catch (e: CancellationException) {
            // A timeout of the budget is "not reachable"; a cancelled caller is not ours to swallow.
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                reachable = false

                emptyMap()
            } else {
                throw e
            }
        } catch (_: Exception) {
            reachable = false

            emptyMap()
        }

        val rows = resources.map { resource ->
            if (ApiLevelGate.check(resource.apiLevel, min, max) == Verdict.OK) {
                return@map resource.asPlan(PlanVerdict.COMPATIBLE)
            }

            val hit = hits[resource.id]

            if (hit != null) {
                resource.asPlan(PlanVerdict.UPDATE, hit.versionId)
            } else {
                resource.asPlan(PlanVerdict.DISABLE)
            }
        }

        return UpdatePlanResult(target, rows, reachable)
    }

    private fun InstalledResource.asPlan(verdict: PlanVerdict, updateVersionId: String? = null) =
        PlanResource(id, type, version, verdict, updateVersionId)
}
