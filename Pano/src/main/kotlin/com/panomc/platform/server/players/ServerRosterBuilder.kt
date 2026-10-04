package com.panomc.platform.server.players

import com.panomc.platform.db.dao.UserDao
import com.panomc.platform.db.model.ServerPlayer
import com.panomc.platform.server.dto.ServerMetricSample
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient

/**
 * Builds the live roster the panel renders, from the two sources Pano has for it.
 *
 * `server_player` is the authoritative list of who is on the server, because it is written by the
 * join and quit events. Pings only exist in the latest metrics sample, which is up to ten seconds
 * old, so the two are merged here instead of writing a ping into the database every ten seconds
 * for every player.
 */
object ServerRosterBuilder {
    /**
     * [panoUsernames] are the roster's names that are also Pano accounts, in any case: those are
     * the players a Pano-wide ban can reach, and the panel says so before an admin bans one.
     */
    fun build(
        players: List<ServerPlayer>,
        sample: ServerMetricSample?,
        panoUsernames: Set<String> = emptySet()
    ): List<JsonObject> {
        val sampled = sample?.players.orEmpty().associateBy { it.uuid.lowercase() }
        val accounts = panoUsernames.mapTo(HashSet()) { it.lowercase() }

        return players
            .sortedBy { it.username.lowercase() }
            .map { player ->
                val uuid = player.uuid.toString()
                val live = sampled[uuid.lowercase()]

                JsonObject()
                    .put("uuid", uuid)
                    .put("username", player.username)
                    .put("ping", live?.ping ?: player.ping)
                    .put("loginTime", player.loginTime)
                    .put("op", live?.op)
                    .put("whitelisted", live?.whitelisted)
                    .put("gamemode", live?.gamemode)
                    .put("panoUser", player.username.lowercase() in accounts)
            }
    }

    /**
     * The roster a node's server list ping can produce (§2.4.17 B).
     *
     * Deliberately thinner than [build] and honest about it: the ping's count is exact but its
     * name list stops at twelve and carries no uuid, no ping time and no session start, so those
     * come back as nulls rather than as zeros that would read as facts. The panel labels this list
     * as a sample; nothing here pretends it is the whole server.
     */
    fun fromSample(sample: ServerMetricSample?, panoUsernames: Set<String> = emptySet()): List<JsonObject> {
        val accounts = panoUsernames.mapTo(HashSet()) { it.lowercase() }

        return sample?.players
        .orEmpty()
        .map { it.username }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
        .sortedBy { it.lowercase() }
        .map { username ->
            JsonObject()
                .put("uuid", null as String?)
                .put("username", username)
                .put("ping", null as Long?)
                .put("loginTime", null as Long?)
                .put("panoUser", username.lowercase() in accounts)
        }
    }

    /** The usernames that [build] and [fromSample] need to know are Pano accounts. */
    suspend fun panoUsernames(
        usernames: Collection<String>,
        userDao: UserDao,
        sqlClient: SqlClient
    ): Set<String> = userDao.getIdsByListOfUsername(usernames.filter { it.isNotBlank() }.distinct(), sqlClient).keys
}
