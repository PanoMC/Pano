package com.panomc.platform.db

import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.annotation.Ignore
import com.panomc.platform.util.TextUtil.convertToSnakeCase
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient

abstract class Dao<T : DBEntity>(private val entityClass: Class<T>) {
    private val databaseManager: DatabaseManager by lazy {
        applicationContext.getBean(DatabaseManager::class.java)
    }

    companion object {
        inline fun <reified T : Dao<*>> get(tableList: List<Dao<*>>): T = tableList.find { it is T } as T
    }

    fun Row.toEntity(): T = DBEntity.gson.fromJson(this.toJson().toString(), entityClass)

    fun RowSet<Row>.toEntities() = this.map { it.toEntity() }

    protected val tableName = entityClass.simpleName.convertToSnakeCase().lowercase()

    open val fields by lazy {
        entityClass.declaredFields
            .filter {
                it.name != "Companion" &&
                        !it.isSynthetic &&
                        it.declaringClass == entityClass &&
                        it.declaredAnnotations.filterIsInstance<Ignore>().isEmpty()
            }
            .map { it.name }
    }

    protected fun List<String>.filter(vararg strings: String) = this.filter { !strings.contains(it) }

    protected fun List<String>.toTableQuery() = this.joinToString(", ") { "`$it`" }

    abstract suspend fun init(sqlClient: SqlClient)

    open suspend fun uninstall(sqlClient: SqlClient) {}

    fun getTablePrefix(): String = databaseManager.getTablePrefix()
}