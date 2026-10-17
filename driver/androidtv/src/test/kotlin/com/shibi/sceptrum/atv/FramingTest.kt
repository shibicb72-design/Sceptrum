package com.shibi.sceptrum.atv

import java.io.ByteArrayInputStream
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramingTest {
    @Test
    fun varintLengths() {
        assertArrayEquals(bytes(0x00), Framing.encodeVarint(0))
        assertArrayEquals(bytes(0x7F), Framing.encodeVarint(127))
        assertArrayEquals(bytes(0x80, 0x01), Framing.encodeVarint(128))
        assertArrayEquals(bytes(0xAC, 0x02), Framing.encodeVarint(300))
        assertArrayEquals(bytes(0x80, 0x80, 0x01), Framing.encodeVarint(16_384))
    }

    /** Short (1-byte length) and long (2-byte length) messages, split at every byte offset. */
    @Test
    fun splitAtEveryOffsetStillYieldsEveryMessage() {
        val messages = listOf(ByteArray(3) { 1 }, ByteArray(200) { (it % 251).toByte() }, ByteArray(0), ByteArray(127) { 7 })
        val stream = messages.fold(ByteArray(0)) { acc, m -> acc + Framing.frame(m) }
        for (cut in 0..stream.size) {
            val decoder = FrameDecoder()
            val out = decoder.feed(stream, 0, cut) + decoder.feed(stream, cut, stream.size - cut)
            assertEquals("cut at $cut", messages.size, out.size)
            messages.zip(out).forEach { (want, got) -> assertArrayEquals("cut at $cut", want, got) }
            assertEquals(0, decoder.pending)
        }
    }

    @Test
    fun oneByteAtATime() {
        val messages = listOf(ByteArray(130) { 9 }, ByteArray(5) { 2 })
        val stream = messages.fold(ByteArray(0)) { acc, m -> acc + Framing.frame(m) }
        val decoder = FrameDecoder()
        val out = stream.indices.flatMap { decoder.feed(stream, it, 1) }
        assertEquals(2, out.size)
        assertArrayEquals(messages[0], out[0])
        assertArrayEquals(messages[1], out[1])
    }

    @Test
    fun manyMessagesInOneChunk() {
        val stream = (1..50).fold(ByteArray(0)) { acc, n -> acc + Framing.frame(ByteArray(n) { n.toByte() }) }
        val out = FrameDecoder().feed(stream)
        assertEquals(50, out.size)
        assertEquals(50, out.last().size)
    }

    @Test(expected = FramingException::class)
    fun corruptVarintClosesTheStream() {
        FrameDecoder().feed(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01))
    }

    @Test(expected = FramingException::class)
    fun oversizedFrameIsRefused() {
        FrameDecoder(maxFrameBytes = 1_000).feed(Framing.encodeVarint(1_001))
    }

    @Test
    fun readerReturnsNullOnCleanEndAndThrowsMidMessage() {
        val reader = FrameReader(ByteArrayInputStream(Framing.frame(bytes(1, 2, 3))))
        assertArrayEquals(bytes(1, 2, 3), reader.read())
        assertNull(reader.read())

        val truncated = FrameReader(ByteArrayInputStream(Framing.frame(bytes(1, 2, 3)).copyOf(3)))
        val thrown = runCatching { truncated.read() }.exceptionOrNull()
        assertTrue("got $thrown", thrown is EOFException)
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
