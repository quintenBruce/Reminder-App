package com.qcbru.remind.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

class LocalStore(context: Context) : SQLiteOpenHelper(context, "remind.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE reminders(uid TEXT NOT NULL, id TEXT NOT NULL, data TEXT NOT NULL, PRIMARY KEY(uid,id))")
        db.execSQL("CREATE TABLE alerts(uid TEXT NOT NULL, alert_id TEXT NOT NULL, occurrence_id TEXT NOT NULL, shown_at INTEGER NOT NULL, source TEXT NOT NULL, PRIMARY KEY(uid,alert_id))")
        db.execSQL("CREATE TABLE actions(request_id TEXT PRIMARY KEY, uid TEXT NOT NULL, reminder_id TEXT NOT NULL, occurrence_id TEXT NOT NULL, action TEXT NOT NULL, snooze_minutes INTEGER, local_delivery INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun replaceReminders(uid: String, reminders: List<Reminder>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val ids = reminders.map { it.id }.toSet()
            db.rawQuery("SELECT id FROM reminders WHERE uid=?", arrayOf(uid)).use { cursor ->
                while (cursor.moveToNext()) if (cursor.getString(0) !in ids)
                    db.delete("reminders", "uid=? AND id=?", arrayOf(uid, cursor.getString(0)))
            }
            for (reminder in reminders) {
                val hasPending = db.rawQuery("SELECT 1 FROM actions WHERE uid=? AND reminder_id=? LIMIT 1",
                    arrayOf(uid, reminder.id)).use { it.moveToFirst() }
                if (!hasPending) db.insertWithOnConflict("reminders", null, ContentValues().apply {
                    put("uid", uid); put("id", reminder.id); put("data", reminder.asJson().toString())
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun reminders(uid: String): List<Reminder> {
        val results = mutableListOf<Reminder>()
        readableDatabase.rawQuery("SELECT data FROM reminders WHERE uid=?", arrayOf(uid)).use { cursor ->
            while (cursor.moveToNext()) results += Reminder.fromJson(JSONObject(cursor.getString(0)))
        }
        return results
    }

    fun reminder(uid: String, id: String): Reminder? = readableDatabase.rawQuery(
        "SELECT data FROM reminders WHERE uid=? AND id=?", arrayOf(uid, id)
    ).use { if (it.moveToFirst()) Reminder.fromJson(JSONObject(it.getString(0))) else null }

    fun saveReminder(uid: String, reminder: Reminder) {
        writableDatabase.insertWithOnConflict("reminders", null, ContentValues().apply {
            put("uid", uid); put("id", reminder.id); put("data", reminder.asJson().toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeReminder(uid: String, id: String) {
        writableDatabase.delete("reminders", "uid=? AND id=?", arrayOf(uid, id))
    }

    /** Returns false if this alert was already shown by either delivery path. */
    @Synchronized fun claimAlert(uid: String, alertId: String, occurrenceId: String, now: Long,
        source: String, minGapMs: Long): Boolean {
        val db = writableDatabase
        val latest = db.rawQuery("SELECT shown_at FROM alerts WHERE uid=? AND occurrence_id=? ORDER BY shown_at DESC LIMIT 1",
            arrayOf(uid, occurrenceId)).use { if (it.moveToFirst()) it.getLong(0) else null }
        if (latest != null && now - latest < minGapMs) return false
        return db.insertWithOnConflict("alerts", null, ContentValues().apply {
            put("uid", uid); put("alert_id", alertId); put("occurrence_id", occurrenceId)
            put("shown_at", now); put("source", source)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun hasAlert(uid: String, alertId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM alerts WHERE uid=? AND alert_id=?", arrayOf(uid, alertId)
    ).use { it.moveToFirst() }

    fun isLocalAlert(uid: String, alertId: String): Boolean = readableDatabase.rawQuery(
        "SELECT source FROM alerts WHERE uid=? AND alert_id=?", arrayOf(uid, alertId)
    ).use { it.moveToFirst() && it.getString(0) == "local" }

    fun alertIds(uid: String, occurrenceId: String): List<String> {
        val ids = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT alert_id FROM alerts WHERE uid=? AND occurrence_id=?",
            arrayOf(uid, occurrenceId)).use { cursor -> while (cursor.moveToNext()) ids += cursor.getString(0) }
        return ids
    }

    fun hasPendingAction(uid: String, occurrenceId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM actions WHERE uid=? AND occurrence_id=? LIMIT 1", arrayOf(uid, occurrenceId)
    ).use { it.moveToFirst() }

    fun queue(action: PendingAction) {
        writableDatabase.insertWithOnConflict("actions", null, ContentValues().apply {
            put("request_id", action.requestId); put("uid", action.uid); put("reminder_id", action.reminderId)
            put("occurrence_id", action.occurrenceId); put("action", action.action)
            put("snooze_minutes", action.snoozeMinutes)
            put("local_delivery", if (action.localDelivery) 1 else 0)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun pending(uid: String): List<PendingAction> {
        val result = mutableListOf<PendingAction>()
        readableDatabase.rawQuery("SELECT request_id, reminder_id, occurrence_id, action, snooze_minutes, local_delivery FROM actions WHERE uid=?", arrayOf(uid)).use { cursor ->
            while (cursor.moveToNext()) result += PendingAction(cursor.getString(0), uid, cursor.getString(1),
                cursor.getString(2), cursor.getString(3), if (cursor.isNull(4)) null else cursor.getInt(4), cursor.getInt(5) == 1)
        }
        return result
    }

    fun removeAction(requestId: String) { writableDatabase.delete("actions", "request_id=?", arrayOf(requestId)) }

    fun clearAccount(uid: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (table in listOf("reminders", "alerts", "actions")) db.delete(table, "uid=?", arrayOf(uid))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
