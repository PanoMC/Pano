package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PendingAuthSession
import io.vertx.sqlclient.SqlClient

abstract class PendingAuthSessionDao : Dao<PendingAuthSession>(PendingAuthSession::class.java) {

    abstract suspend fun add(session: PendingAuthSession, sqlClient: SqlClient): Long

    /** Peek (does NOT consume) — wrong-input retries should keep the token alive until TTL. */
    abstract suspend fun getByToken(token: String, sqlClient: SqlClient): PendingAuthSession?

    abstract suspend fun deleteByToken(token: String, sqlClient: SqlClient)

    abstract suspend fun deleteExpired(sqlClient: SqlClient)
}
