package com.prempoddar.librechat.protocol

import java.nio.ByteBuffer

/**
 * Bluetooth writes are a few hundred bytes at most, so packets travel as numbered frames:
 * `[frame id u16][index u16][count u16] + bytes`.
 */
object Fragmenter {
    const val HEADER = 6
    const val MAX_FRAMES = 65_535

    fun split(frameId: Int, data: ByteArray, maxFrame: Int): List<ByteArray> {
        val body = maxFrame - HEADER
        require(body > 0)
        val count = maxOf(1, (data.size + body - 1) / body)
        require(count <= MAX_FRAMES) { "packet too large for one transfer: ${data.size} bytes" }
        return (0 until count).map { i ->
            val from = i * body
            val part = data.copyOfRange(from, minOf(data.size, from + body))
            ByteBuffer.allocate(HEADER + part.size)
                .putShort(frameId.toShort()).putShort(i.toShort()).putShort(count.toShort()).put(part).array()
        }
    }
}

/** Puts frames back together per link. Incomplete packets are dropped after [timeoutMs]. */
class Reassembler(private val timeoutMs: Long = 30_000, private val clock: () -> Long = System::currentTimeMillis) {
    private class Partial(val count: Int, val parts: Array<ByteArray?>, var lastSeen: Long)
    private val partials = HashMap<Int, Partial>()

    /** The whole packet once its last frame arrives, else null. */
    @Synchronized
    fun accept(frame: ByteArray): ByteArray? {
        if (frame.size < Fragmenter.HEADER) return null
        val b = ByteBuffer.wrap(frame)
        val id = b.short.toInt() and 0xFFFF
        val index = b.short.toInt() and 0xFFFF
        val count = b.short.toInt() and 0xFFFF
        if (count == 0 || index >= count) return null
        val now = clock()
        partials.entries.removeIf { now - it.value.lastSeen > timeoutMs }
        val body = frame.copyOfRange(Fragmenter.HEADER, frame.size)
        if (count == 1) return body
        val p = partials[id]?.takeIf { it.count == count } ?: Partial(count, arrayOfNulls(count), now).also { partials[id] = it }
        p.parts[index] = body
        p.lastSeen = now
        if (p.parts.any { it == null }) return null
        partials.remove(id)
        val out = ByteArray(p.parts.sumOf { it!!.size })
        var at = 0
        p.parts.forEach { it!!.copyInto(out, at); at += it.size }
        return out
    }
}
