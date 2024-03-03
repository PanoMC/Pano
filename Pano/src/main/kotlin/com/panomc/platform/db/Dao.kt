package com.panomc.platform.db

import com.google.gson.GsonBuilder
import com.panomc.platform.annotation.Ignore
import com.panomc.platform.util.TextUtil.convertToSnakeCase
import com.panomc.platform.util.deserializer.BooleanDeserializer
import com.panomc.platform.util.deserializer.JsonObjectDeserializer
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.annotation.Autowired

abstract class Dao<T : DBEntity>(private val entityClass: Class<T>) {
    @Autowired
    private lateinit var databaseManager: DatabaseManager

    companion object {
        private val gson by lazy {
            GsonBuilder()
                .registerTypeAdapter(Boolean::class.java, BooleanDeserializer())
                .registerTypeAdapter(JsonObject::class.java, JsonObjectDeserializer())
                .create()
        }

        inline fun <reified T : Dao<*>> get(tableList: List<Dao<*>>): T = tableList.find { it is T } as T
    }

    fun Row.toEntity(): T = gson.fromJson(this.toJson().toString(), entityClass)

    fun RowSet<Row>.toEntities() = this.map { it.toEntity() }

    protected val tableName = entityClass.simpleName.convertToSnakeCase().lowercase()

    protected val fields by lazy {
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

    fun getTablePrefix(): String = databaseManager.getTablePrefix()
}