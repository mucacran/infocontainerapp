package com.mucacran.infocontainer.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper

open class InspectionDatabaseHelper(
    context: Context,
    name: String = DATABASE_NAME
) : SQLiteOpenHelper(
    context,
    name,
    null,
    DATABASE_VERSION
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_DRAFTS (
                $COLUMN_ID TEXT PRIMARY KEY,
                $COLUMN_CONTAINER_IDENTIFIER TEXT NOT NULL,
                $COLUMN_ISO_CODE TEXT NOT NULL,
                $COLUMN_INSPECTION_TYPE TEXT NOT NULL,
                $COLUMN_INSPECTED_AT TEXT NOT NULL,
                $COLUMN_OBSERVATIONS TEXT NOT NULL,
                $COLUMN_CREATED_AT INTEGER NOT NULL,
                $COLUMN_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No hay transiciones de esquema implementadas; SQLiteOpenHelper revierte la apertura.
        throw SQLiteException("Migración no soportada: $oldVersion -> $newVersion")
    }

    companion object {
        const val DATABASE_NAME = "infocontainer.db"
        const val DATABASE_VERSION = 1

        const val TABLE_DRAFTS = "inspection_drafts"
        const val COLUMN_ID = "id"
        const val COLUMN_CONTAINER_IDENTIFIER = "container_identifier"
        const val COLUMN_ISO_CODE = "iso_code"
        const val COLUMN_INSPECTION_TYPE = "inspection_type"
        const val COLUMN_INSPECTED_AT = "inspected_at"
        const val COLUMN_OBSERVATIONS = "observations"
        const val COLUMN_CREATED_AT = "created_at"
        const val COLUMN_UPDATED_AT = "updated_at"
    }
}
