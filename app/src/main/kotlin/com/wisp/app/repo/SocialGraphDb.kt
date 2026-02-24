package com.wisp.app.repo

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import android.util.Log

class SocialGraphDb(context: Context) : SQLiteOpenHelper(context, "social_graph.db", null, 1) {

    companion object {
        private const val TAG = "SocialGraphDb"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE followed_by (
                pubkey TEXT NOT NULL,
                follower TEXT NOT NULL,
                PRIMARY KEY (pubkey, follower)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_followed_by_pubkey ON followed_by(pubkey)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insertBatch(rows: List<Pair<String, String>>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stmt: SQLiteStatement = db.compileStatement("INSERT OR IGNORE INTO followed_by (pubkey, follower) VALUES (?, ?)")
            for ((pubkey, follower) in rows) {
                stmt.bindString(1, pubkey)
                stmt.bindString(2, follower)
                stmt.executeInsert()
                stmt.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getFollowers(pubkey: String): List<String> {
        val result = mutableListOf<String>()
        val db = readableDatabase
        db.rawQuery("SELECT follower FROM followed_by WHERE pubkey = ?", arrayOf(pubkey)).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(cursor.getString(0))
            }
        }
        return result
    }

    fun getFollowerCount(pubkey: String): Int {
        val db = readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM followed_by WHERE pubkey = ?", arrayOf(pubkey)).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun clearAll() {
        try {
            writableDatabase.execSQL("DELETE FROM followed_by")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear social graph", e)
        }
    }
}
