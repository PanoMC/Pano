package com.panomc.platform.update

import com.panomc.platform.InstallManager.Companion.ResourceType
import com.panomc.platform.gate.InstalledResource
import com.panomc.platform.gate.Verdict
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** PF-25: gate 2, what a later platform update would do to the installed resources (doc 04 section 7). */
class UpdatePlanTest {
    private fun resource(id: String, level: Int, type: ResourceType = ResourceType.PLUGIN, disabledByAdmin: Boolean = false) =
        InstalledResource(id, type, id, "1.0.0", level, Verdict.OK, disabledByAdmin = disabledByAdmin)

    private class FakeStore(
        private val hits: List<CompatibleHit> = emptyList(),
        private val failure: Exception? = null,
        private val delayMs: Long = 0
    ) : CompatibilityStore {
        val queries = CopyOnWriteArrayList<CompatibleQuery>()

        override suspend fun newestCompatible(query: CompatibleQuery): List<CompatibleHit> {
            queries += query

            if (delayMs > 0) delay(delayMs)

            failure?.let { throw it }

            return hits
        }

        override suspend fun download(hit: CompatibleHit, targetFolder: File): DownloadedResource =
            throw UnsupportedOperationException("a plan never downloads")
    }

    private fun hit(id: String, level: Int, type: ResourceType = ResourceType.PLUGIN) =
        CompatibleHit(id, type, "vid-$id", "v2.0.0", level)

    private val installed = listOf(
        resource("market", 1),
        resource("blog", 2),
        resource("legacy", 0),
        resource("blaze-theme", 1, ResourceType.THEME)
    )

    @Test
    fun `an unknown target makes everything compatible and asks nobody`() = runBlocking {
        val store = FakeStore()

        for (target in listOf(PlanTarget("1.0.0", null, null), PlanTarget("1.0.0", null, 1), null)) {
            val plan = UpdatePlan.build(target, installed, store)

            assertTrue(plan.resources.all { it.verdict == PlanVerdict.COMPATIBLE })
            assertEquals(installed.size, plan.resources.size)
            assertNull(plan.storeReachable)
        }

        assertTrue(store.queries.isEmpty())
    }

    @Test
    fun `resources that fit the target are compatible and the store is not asked`() = runBlocking {
        val store = FakeStore()
        val plan = UpdatePlan.build(PlanTarget("1.1.0", 2, 1), listOf(resource("market", 1), resource("blog", 2)), store)

        assertTrue(plan.resources.all { it.verdict == PlanVerdict.COMPATIBLE })
        assertTrue(store.queries.isEmpty())
        assertNull(plan.storeReachable)
    }

    @Test
    fun `a break raises the minimum, those with a compatible version are UPDATE and the rest DISABLE`() = runBlocking {
        val store = FakeStore(hits = listOf(hit("market", 2), hit("blaze-theme", 2, ResourceType.THEME)))

        // Target 2..3: level 1 is too old now, level 2 fits, level 0 was never fine.
        val plan = UpdatePlan.build(PlanTarget("2.0.0", 3, 2), installed, store)
        val byId = plan.resources.associateBy { it.id }

        assertEquals(PlanVerdict.UPDATE, byId.getValue("market").verdict)
        assertEquals("vid-market", byId.getValue("market").updateVersionId)
        assertEquals(PlanVerdict.UPDATE, byId.getValue("blaze-theme").verdict)
        assertEquals(PlanVerdict.COMPATIBLE, byId.getValue("blog").verdict)
        assertNull(byId.getValue("blog").updateVersionId)
        assertEquals(PlanVerdict.DISABLE, byId.getValue("legacy").verdict)
        assertEquals(true, plan.storeReachable)

        // One question, with the target's range and only what the target would refuse.
        val query = store.queries.single()

        assertEquals(3, query.apiLevel)
        assertEquals(2, query.minApiLevel)
        assertEquals(setOf("market", "legacy", "blaze-theme"), query.resources.map { it.id }.toSet())
    }

    @Test
    fun `a store that cannot be asked turns the refused ones into DISABLE and says so`() = runBlocking {
        val failing = UpdatePlan.build(PlanTarget("2.0.0", 3, 2), installed, FakeStore(failure = java.io.IOException("offline")))

        assertEquals(false, failing.storeReachable)
        assertEquals(PlanVerdict.DISABLE, failing.resources.single { it.id == "market" }.verdict)
        assertEquals(PlanVerdict.COMPATIBLE, failing.resources.single { it.id == "blog" }.verdict)

        val slow = UpdatePlan.build(PlanTarget("2.0.0", 3, 2), installed, FakeStore(delayMs = 10_000), budgetMs = 100)

        assertEquals(false, slow.storeReachable)
        assertEquals(PlanVerdict.DISABLE, slow.resources.single { it.id == "legacy" }.verdict)
    }

    @Test
    fun `a missing minimum is level 1 and a plugin the admin disabled is not part of the plan`() = runBlocking {
        val store = FakeStore()
        val plan = UpdatePlan.build(
            PlanTarget("1.1.0", 1, null),
            listOf(resource("market", 1), resource("off", 0, disabledByAdmin = true)),
            store
        )

        assertEquals(listOf("market"), plan.resources.map { it.id })
        assertEquals(PlanVerdict.COMPATIBLE, plan.resources.single().verdict)
        assertEquals(1 to 1, PlanTarget("1.1.0", 1, null).range)
    }

    @Test
    fun `the body has the documented shape`() = runBlocking {
        val store = FakeStore(hits = listOf(hit("market", 2)))
        val plan = UpdatePlan.build(PlanTarget("2.0.0", 2, 2), listOf(resource("market", 1), resource("legacy", 0)), store)
        val agents = listOf<Map<String, Any?>>(mapOf("type" to "SERVER", "id" to 1L))

        val body = plan.toMap(agents)

        assertEquals(setOf("target", "resources", "agents", "storeReachable"), body.keys)
        assertEquals(mapOf("version" to "2.0.0", "apiLevel" to 2, "minApiLevel" to 2), body["target"])
        assertEquals(agents, body["agents"])

        @Suppress("UNCHECKED_CAST")
        val rows = body["resources"] as List<Map<String, Any?>>

        assertEquals(
            mapOf("id" to "legacy", "type" to "PLUGIN", "installedVersion" to "1.0.0", "verdict" to "DISABLE"),
            rows[0]
        )
        assertEquals(
            mapOf("id" to "market", "type" to "PLUGIN", "installedVersion" to "1.0.0", "verdict" to "UPDATE", "updateVersionId" to "vid-market"),
            rows[1]
        )
    }
}
