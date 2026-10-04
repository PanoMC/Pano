package com.panomc.platform.server.backup

import com.panomc.platform.db.model.ServerBackup

/**
 * Which backups a retention setting says are surplus.
 *
 * Pure and separate from the service that acts on it, because getting this wrong deletes somebody
 * else's worlds. The rules, and every one of them matters:
 *
 * - a pinned backup is never chosen, whatever the limit, the disk cap or its status. It also does
 *   not count towards the limit: a pin is "keep this one as well", not "spend a slot on it", so
 *   pinning last month's world does not quietly push out yesterday's.
 * - the two modes are counted apart. Full zips are held to `keepLast`, snapshots to
 *   `snapshotKeepLast`; a burst of hourly snapshots must never be what pushes out the only full
 *   backup, and a nightly zip must never eat into a day of snapshots.
 * - only finished backups count towards a limit. A backup that is still being taken is not yet
 *   one of the copies you have, and one that failed is not a copy at all — counting either would
 *   mean a failed run quietly pushing out the last good backup.
 * - a failed row is surplus on sight. It holds nothing, so keeping it would spend one of the
 *   slots that are supposed to hold real backups.
 * - a limit at zero or below is read as "keep the default", never as "delete everything". A
 *   setting that has not been filled in must not be the one that empties the list.
 * - the snapshot disk cap is applied after the count: while the `storedBytes` of the READY
 *   snapshots still kept (pinned ones included — they are on the disk too) add up to more than the
 *   cap, the oldest unpinned one goes. The newest snapshot is never removed for the cap, because a
 *   cap that deletes the backup just taken leaves a server with nothing recent at all, and neither
 *   is one that wrote nothing (no `storedBytes`), because removing it frees nothing. A cap of
 *   zero or null means no cap.
 *
 * The cap is an estimate by design. `storedBytes` is what each snapshot wrote when it was taken,
 * and deleting an old snapshot frees only the chunks nothing newer shares — so the repository may
 * shrink by less than the number says. It never deletes more than it would with exact numbers,
 * only occasionally too little, which is the right way round for a rule that removes backups.
 */
object BackupRetention {
    /**
     * The backups to remove, oldest first.
     *
     * [backups] may be in any order; it is sorted here so a caller cannot change the answer by
     * handing them over differently.
     */
    fun selectForDeletion(
        backups: List<ServerBackup>,
        keepLast: Int,
        snapshotKeepLast: Int = DEFAULT_SNAPSHOT_KEEP_LAST,
        snapshotMaxBytes: Long? = null
    ): List<ServerBackup> {
        val fullLimit = if (keepLast <= 0) DEFAULT_KEEP_LAST else keepLast
        val snapshotLimit = if (snapshotKeepLast <= 0) DEFAULT_SNAPSHOT_KEEP_LAST else snapshotKeepLast

        val newestFirst = backups.sortedWith(compareByDescending<ServerBackup> { it.createdAt }.thenByDescending { it.id })

        val unpinned = newestFirst.filter { !it.pinned }

        val failed = unpinned.filter { it.status == ServerBackupStatus.FAILED }

        val readyFull = unpinned.filter { it.status == ServerBackupStatus.READY && it.mode == BackupMode.FULL }

        val readySnapshots = unpinned.filter { it.status == ServerBackupStatus.READY && it.mode == BackupMode.SNAPSHOT }

        val fullSurplus = readyFull.drop(fullLimit)

        val snapshotSurplus = readySnapshots.drop(snapshotLimit).toMutableList()

        if (snapshotMaxBytes != null && snapshotMaxBytes > 0) {
            snapshotSurplus += overCap(newestFirst, snapshotSurplus.toSet(), snapshotMaxBytes)
        }

        return (failed + fullSurplus + snapshotSurplus).distinct().sortedWith(compareBy<ServerBackup> { it.createdAt }.thenBy { it.id })
    }

    /** The oldest unpinned snapshots to drop, beyond [alreadyGoing], until the rest fit in [cap]. */
    private fun overCap(newestFirst: List<ServerBackup>, alreadyGoing: Set<ServerBackup>, cap: Long): List<ServerBackup> {
        val kept = newestFirst.filter {
            it.status == ServerBackupStatus.READY && it.mode == BackupMode.SNAPSHOT && it !in alreadyGoing
        }

        if (kept.isEmpty()) {
            return emptyList()
        }

        val newest = kept.first()

        var total = kept.sumOf { it.storedBytes ?: 0L }

        val going = mutableListOf<ServerBackup>()

        for (candidate in kept.asReversed()) {
            if (total <= cap) {
                break
            }

            // A snapshot that wrote nothing frees nothing; deleting it would cost a restore point
            // and bring the total no closer to the cap.
            if (candidate.pinned || candidate == newest || (candidate.storedBytes ?: 0L) <= 0L) {
                continue
            }

            going += candidate
            total -= candidate.storedBytes ?: 0L
        }

        return going
    }

    /** Used when a server's full-backup setting is missing or nonsensical. */
    const val DEFAULT_KEEP_LAST = 10

    /** Used when a server's snapshot setting is missing or nonsensical. */
    const val DEFAULT_SNAPSHOT_KEEP_LAST = 24
}
