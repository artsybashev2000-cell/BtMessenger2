package com.example.btmessenger

import org.json.JSONException
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Протокол обмена между телефонами.
 *
 * Каждый кадр: 4 байта длины (big-endian) + JSON в UTF-8. Поле "t" — тип кадра.
 * Неизвестные типы кадров и лишние поля игнорируются, поэтому новые версии приложения
 * могут добавлять свои кадры, не ломая старые. Формат простой и не завязан на Java,
 * его легко разобрать и на микроконтроллере (например, ESP32-метке).
 *
 * Порядок при подключении:
 *   1. обе стороны сразу шлют HELLO (версия, id установки, имя, должность);
 *   2. принимающая сторона отвечает ACCEPT (или REJECT) — сразу, если собеседник
 *      уже знаком, иначе после подтверждения пользователем;
 *   3. дальше идут MSG, на каждое — ACK с тем же id; PING/PONG держат связь;
 *   4. BYE — осознанное отключение (без автопереподключения).
 */
object Protocol {
    const val VERSION = 1
    const val MAX_FRAME_BYTES = 64 * 1024
    const val MAX_TEXT = 4000

    /**
     * UUID службы. Изменён по сравнению с версией 0.1 (writeUTF), чтобы старая и новая
     * версии просто не соединялись, а не принимали друг от друга мусор.
     */
    val APP_UUID: UUID = UUID.fromString("5d3f9b2a-7c41-4e0b-9f6d-1a8e2c7b4f90")
    const val SERVICE_NAME = "BtMessenger"

    @Throws(IOException::class)
    fun write(out: DataOutputStream, frame: Frame) {
        val bytes = frame.toJson().toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FRAME_BYTES) throw IOException("frame too large: ${bytes.size}")
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    @Throws(IOException::class)
    fun read(inp: DataInputStream): Frame {
        val len = inp.readInt()
        if (len <= 0 || len > MAX_FRAME_BYTES) throw IOException("bad frame length: $len")
        val buf = ByteArray(len)
        inp.readFully(buf)
        val json = try {
            JSONObject(String(buf, Charsets.UTF_8))
        } catch (e: JSONException) {
            throw IOException("bad frame json", e)
        }
        return Frame.fromJson(json)
    }
}

sealed class Frame {
    abstract fun toJson(): JSONObject

    data class Hello(val version: Int, val installId: String, val name: String, val role: String) : Frame() {
        override fun toJson(): JSONObject = JSONObject()
            .put("t", "hello").put("v", version).put("id", installId).put("name", name).put("role", role)
    }

    object Accept : Frame() {
        override fun toJson(): JSONObject = JSONObject().put("t", "accept")
    }

    data class Reject(val reason: String) : Frame() {
        override fun toJson(): JSONObject = JSONObject().put("t", "reject").put("reason", reason)
    }

    data class Msg(val message: WireMessage) : Frame() {
        override fun toJson(): JSONObject = message.toJson().put("t", "msg")
    }

    data class Ack(val id: String) : Frame() {
        override fun toJson(): JSONObject = JSONObject().put("t", "ack").put("id", id)
    }

    object Ping : Frame() {
        override fun toJson(): JSONObject = JSONObject().put("t", "ping")
    }

    object Pong : Frame() {
        override fun toJson(): JSONObject = JSONObject().put("t", "pong")
    }

    object Bye : Frame() {
        override fun toJson(): JSONObject = JSONObject().put("t", "bye")
    }

    data class Unknown(val type: String) : Frame() {
        override fun toJson(): JSONObject = JSONObject().put("t", type)
    }

    companion object {
        const val REJECT_DECLINED = "declined"
        const val REJECT_BUSY = "busy"
        const val REJECT_TIMEOUT = "timeout"

        fun fromJson(j: JSONObject): Frame = when (val t = j.optString("t")) {
            "hello" -> Hello(
                version = j.optInt("v", 1),
                installId = j.optString("id").trim().take(64),
                name = j.optString("name").cleanLine(64),
                role = j.optString("role").cleanLine(64)
            )
            "accept" -> Accept
            "reject" -> Reject(j.optString("reason").take(32))
            "msg" -> WireMessage.fromJson(j)?.let { Msg(it) } ?: Unknown(t)
            "ack" -> Ack(j.optString("id").take(64))
            "ping" -> Ping
            "pong" -> Pong
            "bye" -> Bye
            else -> Unknown(t.take(32))
        }
    }
}

/** Тип сообщения. Раньше гео определялось по префиксу «GEO:» в тексте — теперь это отдельное поле. */
enum class Kind(val code: String) {
    TEXT("text"), GEO("geo"), SOS("sos");

    companion object {
        fun of(code: String?): Kind? = values().firstOrNull { it.code == code }
    }
}

enum class Status(val code: Int) {
    /** Сохранено, ещё не ушло в канал (нет связи). */
    PENDING(0),
    /** Записано в канал. */
    SENT(1),
    /** Собеседник подтвердил получение (ACK). */
    DELIVERED(2),
    /** Входящее сообщение. */
    RECEIVED(3);

    companion object {
        fun of(code: Int): Status = values().firstOrNull { it.code == code } ?: PENDING
    }
}

/**
 * Точка геопозиции.
 * [fixTime] — момент замера по часам отправителя (мс), [accuracy] — радиус точности в метрах.
 */
data class GeoPoint(val lat: Double, val lon: Double, val accuracy: Float?, val fixTime: Long) {
    fun isValid(): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
}

/** Сообщение в том виде, в каком оно идёт по каналу. */
data class WireMessage(
    val id: String,
    val sentAt: Long,
    val kind: Kind,
    val text: String,
    val geo: GeoPoint?
) {
    fun toJson(): JSONObject {
        val j = JSONObject()
            .put("id", id)
            .put("ts", sentAt)
            .put("k", kind.code)
            .put("text", text)
        if (geo != null) {
            j.put("lat", geo.lat).put("lon", geo.lon).put("fix", geo.fixTime)
            val acc = geo.accuracy
            if (acc != null && acc.isFinite()) j.put("acc", acc.toDouble())
        }
        return j
    }

    companion object {
        fun fromJson(j: JSONObject): WireMessage? {
            val id = j.optString("id").trim()
            if (id.isEmpty() || id.length > 64) return null
            val kind = Kind.of(j.optString("k")) ?: Kind.TEXT
            val text = j.optString("text").take(Protocol.MAX_TEXT)
            val geo = if (j.has("lat") && j.has("lon")) {
                val acc = if (j.has("acc")) j.optDouble("acc").toFloat() else null
                GeoPoint(
                    lat = j.optDouble("lat"),
                    lon = j.optDouble("lon"),
                    accuracy = acc?.takeIf { it.isFinite() && it >= 0f },
                    fixTime = j.optLong("fix", j.optLong("ts"))
                ).takeIf { it.isValid() }
            } else null
            if (kind == Kind.GEO && geo == null) return null
            if (kind == Kind.TEXT && text.isBlank()) return null
            return WireMessage(id, j.optLong("ts", System.currentTimeMillis()), kind, text, geo)
        }
    }
}

/** Сообщение в истории переписки. */
data class ChatMessage(
    val id: String,
    val peer: String,
    val fromMe: Boolean,
    val kind: Kind,
    val text: String,
    val geo: GeoPoint?,
    val sentAt: Long,
    val receivedAt: Long,
    val status: Status
) {
    fun toWire() = WireMessage(id, sentAt, kind, text, geo)
}

/** Собеседник из истории (для списка чатов). */
data class PeerSummary(
    val address: String,
    val name: String,
    val role: String,
    val deviceName: String,
    val unread: Int,
    val lastKind: Kind?,
    val lastText: String?,
    val lastFromMe: Boolean,
    val lastTime: Long
) {
    val title: String get() = displayName(name, deviceName, address)
}

data class PeerRecord(
    val address: String,
    val installId: String,
    val name: String,
    val role: String,
    val deviceName: String
) {
    val title: String get() = displayName(name, deviceName, address)
}

fun displayName(name: String, deviceName: String, address: String): String =
    name.ifBlank { deviceName.ifBlank { address } }

/** Одна строка без управляющих символов, не длиннее [max]. */
fun String.cleanLine(max: Int): String =
    filter { !it.isISOControl() }.trim().take(max)
