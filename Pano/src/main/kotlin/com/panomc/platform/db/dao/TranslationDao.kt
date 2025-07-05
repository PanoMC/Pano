package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Translation
import io.vertx.sqlclient.SqlClient

abstract class TranslationDao : Dao<Translation>(Translation::class.java) {
    abstract suspend fun add(
        translation: Translation,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun countByLocaleIdAndType(
        localeId: Long,
        type: Translation.Companion.TranslationType,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getByLocaleIdAndType(
        localeId: Long,
        type: Translation.Companion.TranslationType,
        sqlClient: SqlClient
    ): List<Translation>

    abstract suspend fun addAll(
        translations: List<Translation>,
        sqlClient: SqlClient
    )

    abstract suspend fun removeAll(
        translations: List<Translation>,
        sqlClient: SqlClient
    )

    abstract suspend fun updateAll(
        translations: List<Translation>,
        sqlClient: SqlClient
    )

    abstract suspend fun deleteByLocaleId(
        localeId: Long,
        sqlClient: SqlClient
    )
}