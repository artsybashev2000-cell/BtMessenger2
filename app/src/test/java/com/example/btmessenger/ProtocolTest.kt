package com.example.btmessenger

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Проверка протокола: кадры кодируются и разбираются без потерь, мусор отбрасывается. */
class ProtocolTest {

    private fun roundTrip(vararg frames: Frame): List<Frame> {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        frames.forEach { Protocol.write(out, it) }
        val inp = DataInputStream(ByteArrayInputStream(bos.toByteArray()))
        return frames.map { Protocol.read(inp) }
    }

    private fun readRaw(bytes: ByteArray): Frame = Protocol.read(DataInputStream(ByteArrayInputStream(bytes)))

    @Test
    fun allFrameTypesSurviveRoundTrip() {
        val hello = Frame.Hello(1, "id-123", "Иван Петров", "мастер участка")
        val text = WireMessage("m1", 1_700_000_000_500L, Kind.TEXT, "Привет 👋\nвторая строка", null)
        val sos = WireMessage("m3", 1_700_000_002_000L, Kind.SOS, "Нужна помощь!", null)
        val back = roundTrip(
            hello, Frame.Accept, Frame.Reject("busy"), Frame.Msg(text), Frame.Msg(sos),
            Frame.Ack("m1"), Frame.Ping, Frame.Pong, Frame.Bye
        )
        assertEquals(hello, back[0])
        assertSame(Frame.Accept, back[1])
        assertEquals(Frame.Reject("busy"), back[2])
        assertEquals(Frame.Msg(text), back[3])
        assertEquals(Frame.Msg(sos), back[4])
        assertEquals(Frame.Ack("m1"), back[5])
        assertSame(Frame.Ping, back[6])
        assertSame(Frame.Pong, back[7])
        assertSame(Frame.Bye, back[8])
    }

    @Test
    fun geoKeepsAccuracyAndFixTime() {
        val geo = GeoPoint(56.838011, 60.597474, 12.5f, 1_700_000_000_000L)
        val msg = WireMessage("m2", 1_700_000_001_000L, Kind.GEO, "", geo)
        val back = (roundTrip(Frame.Msg(msg))[0] as Frame.Msg).message
        assertEquals(Kind.GEO, back.kind)
        assertEquals(geo.lat, back.geo!!.lat, 1e-9)
        assertEquals(geo.lon, back.geo!!.lon, 1e-9)
        assertEquals(12.5f, back.geo!!.accuracy!!, 0.001f)
        assertEquals(geo.fixTime, back.geo!!.fixTime)
    }

    @Test
    fun textLookingLikeOldGeoPrefixStaysText() {
        val fake = WireMessage("m4", 1L, Kind.TEXT, "GEO:55.7,37.6", null)
        val back = (roundTrip(Frame.Msg(fake))[0] as Frame.Msg).message
        assertEquals(Kind.TEXT, back.kind)
        assertEquals(null, back.geo)
    }

    @Test
    fun badInputIsRejected() {
        assertTrue(Frame.fromJson(JSONObject("{\"t\":\"voice\"}")) is Frame.Unknown)
        assertTrue(Frame.fromJson(JSONObject("{\"t\":\"msg\",\"id\":\"x\",\"k\":\"geo\",\"lat\":500,\"lon\":10}")) is Frame.Unknown)
        assertTrue(Frame.fromJson(JSONObject("{\"t\":\"msg\",\"k\":\"text\",\"text\":\"hi\"}")) is Frame.Unknown)
        val hello = Frame.fromJson(JSONObject("{\"t\":\"hello\",\"id\":\"a\",\"name\":\"Ив\\u0000ан\\nX\"}")) as Frame.Hello
        assertEquals("ИванX", hello.name)
    }

    @Test(expected = IOException::class)
    fun oversizedFrameIsRejected() {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).writeInt(10_000_000)
        readRaw(bos.toByteArray())
    }

    @Test(expected = IOException::class)
    fun oldWriteUtfFormatIsRejected() {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).writeUTF("привет")
        readRaw(bos.toByteArray())
    }
}
