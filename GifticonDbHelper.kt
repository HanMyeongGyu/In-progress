package com.example.giftguard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class GifticonDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val TAG = "GifticonDbHelper"

    companion object {
        const val DATABASE_VERSION = 2
        const val DATABASE_NAME = "GifticonDB.db"

        const val TABLE_NAME = "gifticons"
        const val COLUMN_ID = "_id"
        const val COLUMN_TITLE = "title"
        const val COLUMN_CODE = "code"
        const val COLUMN_MEMO = "memo"
        const val COLUMN_IMAGE_URI = "image_uri"
        const val COLUMN_SAVED_DATE = "saved_date"

        // 신규
        const val COLUMN_MENU_NAME = "menu_name"
        const val COLUMN_MERCHANT = "merchant"
        const val COLUMN_EXPIRY_DATE = "expiry_date" // TEXT: YYYY-MM-DD
    }

    private val SQL_CREATE_ENTRIES =
        "CREATE TABLE $TABLE_NAME (" +
                "$COLUMN_ID INTEGER PRIMARY KEY," +
                "$COLUMN_TITLE TEXT," +
                "$COLUMN_CODE TEXT UNIQUE," +
                "$COLUMN_MEMO TEXT," +
                "$COLUMN_IMAGE_URI TEXT," +
                "$COLUMN_SAVED_DATE INTEGER," +
                "$COLUMN_MENU_NAME TEXT," +
                "$COLUMN_MERCHANT TEXT," +
                "$COLUMN_EXPIRY_DATE TEXT" +
                ")"

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_ENTRIES)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COLUMN_MENU_NAME TEXT")
            db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COLUMN_MERCHANT TEXT")
            db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COLUMN_EXPIRY_DATE TEXT")
        }
    }

    // 기존 호환용
    fun insertGifticon(title: String, code: String?, memo: String?, imageUri: String): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_TITLE, title)
            put(COLUMN_CODE, code) // UNIQUE지만 NULL 허용
            put(COLUMN_MEMO, memo)
            put(COLUMN_IMAGE_URI, imageUri)
            put(COLUMN_SAVED_DATE, System.currentTimeMillis())
            put(COLUMN_MENU_NAME, title) // 호환: title = menu_name
        }
        val id = db.insert(TABLE_NAME, null, values)
        db.close()
        val ok = id != -1L
        if (!ok) Log.e(TAG, "insertGifticon 실패")
        return ok
    }

    // 경량 저장 (추천)
    fun insertGifticonLite(
        menuName: String,
        merchant: String,
        expiryDate: String,
        imageUri: String,
        code: String? = null,
        memo: String? = null
    ): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_TITLE, menuName)
            put(COLUMN_CODE, code)
            put(COLUMN_MEMO, memo)
            put(COLUMN_IMAGE_URI, imageUri)
            put(COLUMN_SAVED_DATE, System.currentTimeMillis())
            put(COLUMN_MENU_NAME, menuName)
            put(COLUMN_MERCHANT, merchant)
            put(COLUMN_EXPIRY_DATE, expiryDate)
        }
        val id = db.insert(TABLE_NAME, null, values)
        db.close()
        val ok = id != -1L
        if (!ok) Log.e(TAG, "insertGifticonLite 실패")
        return ok
    }

    // 삭제
    fun deleteGifticonById(id: Long): Boolean {
        val db = writableDatabase
        val rows = db.delete(TABLE_NAME, "$COLUMN_ID = ?", arrayOf(id.toString()))
        db.close()
        return rows > 0
    }
}
