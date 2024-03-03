package com.panomc.platform.db

abstract class DaoImpl(val databaseManager: DatabaseManager, val tableName: String) {

    fun getTablePrefix(): String = databaseManager.getTablePrefix()
}