package com.shibi.sceptrum.atv

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Both ports frame every protobuf message as a varint length followed by the
 * message bytes (androidtvremote2 `base.py`). A read may deliver part of a
 * message, several messages, or a split in the middle of the varint.
 */
object Framing {
    /** Far above any real message; voice chunks, the largest, are 20 KB. */
    const val MAX_FRAME_BYTES = 1 shl 20

    /** Longest varint that can still hold a valid frame length (32 bits). */
    private const val MAX_VARINT_BYTES = 5

    fun encodeVarint(value: Int): ByteArray {
        require(value >= 0) { "negative length $value" }
        val out = ByteArray(MAX_VARINT_BYTES)
        var v = value
        var i = 0
        while (v >= 0x80) {
            out[i++] = ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
        out[i++] = v.toByte()
        return out.copyOf(i)
    }

    fun frame(payload: ByteArray): ByteArray = encodeVarint(payload.size) + payload

    fun write(output: OutputStream, payload: ByteArray) {
        output.write(frame(payload))
        output.flush()
    }

    /**
     * Reads a varint at [offset] in [buf] (bytes up to [end]). Returns the value
     * and the number of bytes it used, or null if the varint is not complete yet.
     */
    internal fun decodeVarint(buf: ByteArray, offset: Int, end: Int, maxValue: Int): Pair<Int, Int>? {
        var result = 0L
        var shift = 0
        var i = offset
        while (i < end) {
            val b = buf[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) {
                if (result > maxValue) throw FramingException("frame of $result bytes exceeds $maxValue")
                return result.toInt() to (i - offset)
            }
            shift += 7
            if (i - offset >= MAX_VARINT_BYTES) throw FramingException("corrupt length varint")
        }
        return null
    }
}

/** The stream cannot be resynchronised after a corrupt length, so the connection must close. */
class FramingException(message: String) : IOException(message)

/** Incremental decoder: feed any chunks, get back every complete message. */
class FrameDecoder(private val maxFrameBytes: Int = Framing.MAX_FRAME_BYTES) {
    private var buffer = ByteArray(512)
    private var size = 0

    /** Bytes buffered towards the next, incomplete message. */
    val pending: Int get() = size

    fun feed(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset): List<ByteArray> {
        append(chunk, offset, length)
        val frames = mutableListOf<ByteArray>()
        var start = 0
        while (start < size) {
            val (length, used) = Framing.decodeVarint(buffer, start, size, maxFrameBytes) ?: break
            val bodyStart = start + used
            if (size - bodyStart < length) break
            frames += buffer.copyOfRange(bodyStart, bodyStart + length)
            start = bodyStart + length
        }
        if (start > 0) {
            buffer.copyInto(buffer, 0, start, size)
            size -= start
        }
        return frames
    }

    private fun append(chunk: ByteArray, offset: Int, length: Int) {
        if (size + length > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, size + length))
        }
        chunk.copyInto(buffer, size, offset, offset + length)
        size += length
    }
}

/** Blocking reader over a socket stream. */
class FrameReader(
    private val input: InputStream,
    maxFrameBytes: Int = Framing.MAX_FRAME_BYTES,
) {
    private val decoder = FrameDecoder(maxFrameBytes)
    private val ready = ArrayDeque<ByteArray>()
    private val chunk = ByteArray(8 * 1024)

    /**
     * Next whole message, or null on a clean end of stream between messages.
     * @throws EOFException if the stream ends inside a message.
     */
    fun read(): ByteArray? {
        while (ready.isEmpty()) {
            val n = input.read(chunk)
            if (n < 0) {
                if (decoder.pending > 0) throw EOFException("stream ended inside a message")
                return null
            }
            ready.addAll(decoder.feed(chunk, 0, n))
        }
        return ready.removeFirst()
    }
}
