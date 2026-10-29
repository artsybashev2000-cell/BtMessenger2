package com.example.btmessenger

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * История переписки в SQLite. Раньше сообщения жили только на экране и стирались
 * при каждом подключении — теперь хранятся по адресу собеседника.
 * Методы можно вызывать с любого потока (кроме главного — это работа с диском).
 */
class ChatRepository(context: Context) : SQLiteOpenHelper(context, "chat.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE messages (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                id TEXT NOT NULL UNIQUE,
                peer TEXT NOT NULL,
                from_me INTEGER NOT NULL,
                kind TEXT NOT NULL,
                body TEXT NOT NULL,
                lat REAL,
                lon REAL,
                acc REAL,
                fix_time INTEGER,
                sent_at INTEGER NOT NULL,
                received_at INTEGER NOT NULL,
                status INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_messages_peer ON messages(peer, seq)")
        db.execSQL(
            """CREATE TABLE peers (
                address TEXT PRIMARY KEY,
                install_id TEXT NOT NULL DEFAULT '',
                name TEXT NOT NULL DEFAULT '',
                role TEXT NOT NULL DEFAULT '',
                device_name TEXT NOT NULL DEFAULT '',
                last_seen INTEGER NOT NULL DEFAULT 0,
                unread INTEGER NOT NULL DEFAULT 0)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // первая версия схемы
    }

    // ---------- Собеседники ----------

    /** Обновить сведения о собеседнике (пустые значения не затирают известные). */
    fun upsertPeer(address: String, installId: String?, name: String?, role: String?, deviceName: String?) {
        val db = writableDatabase
        val cv = ContentValues()
        if (!installId.isNullOrBlank()) cv.put("install_id", installId)
        if (name != null && (name.isNotBlank() || !installId.isNullOrBlank())) cv.put("name", name)
        if (role != null && (role.isNotBlank() || !installId.isNullOrBlank())) cv.put("role", role)
        if (!deviceName.isNullOrBlank()) cv.put("device_name", deviceName)
        cv.put("last_seen", System.currentTimeMillis())
        if (db.update("peers", cv, "address=?", arrayOf(address)) == 0) {
            cv.put("address", address)
            db.insertWithOnConflict("peers", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    fun peer(address: String): PeerRecord? =
        readableDatabase.rawQuery(
            "SELECT address, install_id, name, role, device_name FROM peers WHERE address=?",
            arrayOf(address)
        ).use { c ->
            if (!c.moveToFirst()) null
            else PeerRecord(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
        }

    fun summaries(): List<PeerSummary> =
        readableDatabase.rawQuery(
            """SELECT p.address, p.name, p.role, p.device_name, p.unread,
                      m.kind, m.body, m.from_me, m.sent_at, m.seq, p.last_seen
               FROM peers p
               LEFT JOIN messages m ON m.seq = (SELECT MAX(seq) FROM messages WHERE peer = p.address)
               ORDER BY COALESCE(m.seq, 0) DESC, p.last_seen DESC""",
            null
        ).use { c ->
            val out = ArrayList<PeerSummary>(c.count)
            while (c.moveToNext()) {
                val hasMsg = !c.isNull(5)
                out += PeerSummary(
                    address = c.getString(0),
                    name = c.getString(1),
                    role = c.getString(2),
                    deviceName = c.getString(3),
                    unread = c.getInt(4),
                    lastKind = if (hasMsg) Kind.of(c.getString(5)) else null,
                    lastText = if (hasMsg) c.getString(6) else null,
                    lastFromMe = hasMsg && c.getInt(7) != 0,
                    lastTime = if (hasMsg) c.getLong(8) else c.getLong(10)
                )
            }
            out
        }

    fun incUnread(address: String) {
        writableDatabase.execSQL("UPDATE peers SET unread = unread + 1 WHERE address=?", arrayOf<Any>(address))
    }

    fun clearUnread(address: String) {
        writableDatabase.execSQL("UPDATE peers SET unread = 0 WHERE address=?", arrayOf<Any>(address))
    }

    fun deletePeer(address: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("messages", "peer=?", arrayOf(address))
            db.delete("peers", "address=?", arrayOf(address))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- Сообщения ----------

    fun insertOutgoing(m: ChatMessage) {
        writableDatabase.insertWithOnConflict("messages", null, values(m), SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** @return true, если сообщение новое (повторы после переподключения отбрасываются по id). */
    fun insertIncoming(m: ChatMessage): Boolean =
        writableDatabase.insertWithOnConflict("messages", null, values(m), SQLiteDatabase.CONFLICT_IGNORE) != -1L

    /** Повышает статус своего сообщения (PENDING → SENT → DELIVERED), понижать нельзя. */
    fun upgradeStatus(id: String, peer: String, status: Status): Boolean {
        val cv = ContentValues().apply { put("status", status.code) }
        return writableDatabase.update(
            "messages", cv,
            "id=? AND peer=? AND from_me=1 AND status<?",
            arrayOf(id, peer, status.code.toString())
        ) > 0
    }

    /** Свои сообщения, получение которых собеседник ещё не подтвердил. */
    fun unacked(peer: String): List<ChatMessage> =
        query("SELECT * FROM messages WHERE peer=? AND from_me=1 AND status<? ORDER BY seq",
            arrayOf(peer, Status.DELIVERED.code.toString()))

    fun messages(peer: String, limit: Int = 500): List<ChatMessage> =
        query("SELECT * FROM (SELECT * FROM messages WHERE peer=? ORDER BY seq DESC LIMIT ${limit.coerceIn(1, 5000)}) ORDER BY seq",
            arrayOf(peer))

    fun clearHistory(peer: String) {
        writableDatabase.delete("messages", "peer=?", arrayOf(peer))
        clearUnread(peer)
    }

    private fun query(sql: String, args: Array<String>): List<ChatMessage> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<ChatMessage>(c.count)
            while (c.moveToNext()) out += read(c)
            out
        }

    private fun values(m: ChatMessage) = ContentValues().apply {
        put("id", m.id)
        put("peer", m.peer)
        put("from_me", if (m.fromMe) 1 else 0)
        put("kind", m.kind.code)
        put("body", m.text)
        val g = m.geo
        if (g != null) {
            put("lat", g.lat)
            put("lon", g.lon)
            if (g.accuracy != null) put("acc", g.accuracy.toDouble())
            put("fix_time", g.fixTime)
        }
        put("sent_at", m.sentAt)
        put("received_at", m.receivedAt)
        put("status", m.status.code)
    }

    private fun read(c: Cursor): ChatMessage {
        fun col(name: String) = c.getColumnIndexOrThrow(name)
        val geo = if (c.isNull(col("lat")) || c.isNull(col("lon"))) null else GeoPoint(
            lat = c.getDouble(col("lat")),
            lon = c.getDouble(col("lon")),
            accuracy = if (c.isNull(col("acc"))) null else c.getDouble(col("acc")).toFloat(),
            fixTime = if (c.isNull(col("fix_time"))) c.getLong(col("sent_at")) else c.getLong(col("fix_time"))
        )
        return ChatMessage(
            id = c.getString(col("id")),
            peer = c.getString(col("peer")),
            fromMe = c.getInt(col("from_me")) != 0,
            kind = Kind.of(c.getString(col("kind"))) ?: Kind.TEXT,
            text = c.getString(col("body")),
            geo = geo,
            sentAt = c.getLong(col("sent_at")),
            receivedAt = c.getLong(col("received_at")),
            status = Status.of(c.getInt(col("status")))
        )
    }
}
