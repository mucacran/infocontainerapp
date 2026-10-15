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

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

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
        createPhotosTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) {
            // La única migración soportada añade metadatos; conserva íntegra la tabla v1.
            createPhotosTable(db)
        } else {
            // SQLiteOpenHelper revierte la apertura y no adelanta user_version.
            throw SQLiteException("Migración no soportada: $oldVersion -> $newVersion")
        }
    }

    private fun createPhotosTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_PHOTOS (
                $COLUMN_ID TEXT PRIMARY KEY NOT NULL,
                $COLUMN_DRAFT_ID TEXT NOT NULL REFERENCES $TABLE_DRAFTS($COLUMN_ID) ON DELETE CASCADE,
                $COLUMN_POSITION INTEGER NOT NULL CHECK ($COLUMN_POSITION >= 0),
                $COLUMN_MIME_TYPE TEXT NOT NULL,
                $COLUMN_BYTE_SIZE INTEGER NOT NULL CHECK ($COLUMN_BYTE_SIZE > 0),
                $COLUMN_WIDTH INTEGER NOT NULL CHECK ($COLUMN_WIDTH > 0),
                $COLUMN_HEIGHT INTEGER NOT NULL CHECK ($COLUMN_HEIGHT > 0),
                UNIQUE ($COLUMN_DRAFT_ID, $COLUMN_POSITION)
            )
            """.trimIndent()
        )
    }

    companion object {
        const val DATABASE_NAME = "infocontainer.db"
        const val DATABASE_VERSION = 2

        const val TABLE_DRAFTS = "inspection_drafts"
        const val TABLE_PHOTOS = "inspection_photos"
        const val COLUMN_ID = "id"
        const val COLUMN_CONTAINER_IDENTIFIER = "container_identifier"
        const val COLUMN_ISO_CODE = "iso_code"
        const val COLUMN_INSPECTION_TYPE = "inspection_type"
        const val COLUMN_INSPECTED_AT = "inspected_at"
        const val COLUMN_OBSERVATIONS = "observations"
        const val COLUMN_CREATED_AT = "created_at"
        const val COLUMN_UPDATED_AT = "updated_at"
        const val COLUMN_DRAFT_ID = "draft_id"
        const val COLUMN_POSITION = "position"
        const val COLUMN_MIME_TYPE = "mime_type"
        const val COLUMN_BYTE_SIZE = "byte_size"
        const val COLUMN_WIDTH = "width"
        const val COLUMN_HEIGHT = "height"
    }
}
