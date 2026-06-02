package com.example.test_micrott.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 微头条草稿 SQLite 数据库 Helper（原生 SQLiteOpenHelper，禁用 Room）。
 *
 * 题目硬性要求：「本地存储 → SQLite → 原生 SQLiteOpenHelper（手写 SQL），禁用 Room」
 *
 * 单表设计：
 *   drafts(id, text, images_json, spans_json, saved_at)
 *
 * 图片路径用 JSONArray 序列化到 TEXT 字段，Span 同理。
 * 草稿箱场景：读永远整体读、写永远整体写，拆分多表只会增加 Cursor 泄漏风险。
 */
class DraftDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        const val DATABASE_NAME = "wtt_draft.db"
        const val DATABASE_VERSION = 3

        const val TABLE_DRAFTS = "drafts"

        const val COL_ID = "id"
        const val COL_TEXT = "text"
        const val COL_IMAGES_JSON = "images_json"
        const val COL_SPANS_JSON = "spans_json"
        const val COL_SAVED_AT = "saved_at"
        const val COL_IS_TEMPORARY = "is_temporary"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_DRAFTS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TEXT TEXT NOT NULL DEFAULT '',
                $COL_IMAGES_JSON TEXT NOT NULL DEFAULT '[]',
                $COL_SPANS_JSON TEXT NOT NULL DEFAULT '[]',
                $COL_SAVED_AT INTEGER NOT NULL,
                $COL_IS_TEMPORARY INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 → v2: 三表结构废弃，重建为单表
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS draft_spans")
            db.execSQL("DROP TABLE IF EXISTS draft_images")
            db.execSQL("DROP TABLE IF EXISTS draft_meta")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_DRAFTS")
            // 重建 v2 结构（无 is_temporary 列）
            db.execSQL("""
                CREATE TABLE $TABLE_DRAFTS (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_TEXT TEXT NOT NULL DEFAULT '',
                    $COL_IMAGES_JSON TEXT NOT NULL DEFAULT '[]',
                    $COL_SPANS_JSON TEXT NOT NULL DEFAULT '[]',
                    $COL_SAVED_AT INTEGER NOT NULL
                )
            """.trimIndent())
        }
        // v2 → v3: 添加 is_temporary 列
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE_DRAFTS ADD COLUMN $COL_IS_TEMPORARY INTEGER NOT NULL DEFAULT 0")
        }
    }
}
