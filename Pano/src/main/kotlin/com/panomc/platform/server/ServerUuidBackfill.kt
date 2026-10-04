package com.panomc.platform.server

import java.util.UUID

/**
 * Hands every server row that predates managed servers its own stable uuid.
 *
 * Servers used to be addressed by their database id alone, but node traffic must never carry one:
 * a node would then be able to name any row in the table. The uuid is what it is allowed to name
 * instead, so every row needs one, including the linked servers that will never see a node.
 *
 * The generator is a parameter purely so this can be tested without depending on
 * [UUID.randomUUID] actually being unique; collisions are retried rather than allowed through,
 * because the column is unique and a duplicate would fail the migration.
 */
object ServerUuidBackfill {
    /** Attempts per row before giving up on a generator that keeps repeating itself. */
    private const val MAX_ATTEMPTS_PER_ROW = 16

    /**
     * Pairs each id in [ids] with a fresh uuid, preserving order and never repeating a value.
     *
     * Throws [IllegalStateException] when [newUuid] cannot produce a value that is unused after
     * [MAX_ATTEMPTS_PER_ROW] tries, which only a broken generator can do.
     */
    fun assign(ids: List<Long>, newUuid: () -> String = { UUID.randomUUID().toString() }): List<Pair<Long, String>> {
        val used = mutableSetOf<String>()
        val result = mutableListOf<Pair<Long, String>>()

        ids.distinct().forEach { id ->
            var attempt = 0
            var candidate = newUuid()

            while (!used.add(candidate)) {
                attempt++

                check(attempt < MAX_ATTEMPTS_PER_ROW) { "Could not generate a unique server uuid" }

                candidate = newUuid()
            }

            result.add(id to candidate)
        }

        return result
    }
}
