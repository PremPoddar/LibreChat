package com.prempoddar.librechat.mesh

import com.prempoddar.librechat.protocol.Packet
import com.prempoddar.librechat.protocol.PacketType

enum class LinkSpeed { BLE, WIFI }

data class Send(val link: String, val packet: Packet)

/** [duplicate]: a direct message for me seen before; only worth acknowledging again. */
data class Routed(val deliver: Packet?, val sends: List<Send>, val duplicate: Boolean = false)

/**
 * Flood routing with duplicate suppression and a hop limit, plus store-and-forward: packets are
 * kept for a day and handed to each new neighbour once, so a message can cross the mesh even
 * when the path only exists at different times. Pure logic; transports call it.
 */
class Router(
    private val me: String,
    /** True if an ACK packet's signature is from its claimed sender. */
    private val ackVerifier: (Packet) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Stored(val packet: Packet, val until: Long, val sentToPeers: MutableSet<String>)

    private val seen = object : LinkedHashMap<String, Long>(4096, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > SEEN_MAX
    }
    private val links = LinkedHashMap<String, LinkSpeed>()
    private val linkPeer = HashMap<String, String>()
    private val store = LinkedHashMap<String, Stored>()
    private var storedBytes = 0L

    @Synchronized fun linkUp(link: String, speed: LinkSpeed) {
        links[link] = speed
    }

    @Synchronized fun linkDown(link: String) {
        links.remove(link)
        linkPeer.remove(link)
    }

    /** The neighbour on [link], once it has announced itself. */
    @Synchronized fun peerOn(link: String): String? = linkPeer[link]

    /** Peer id → its links, for neighbours that announced themselves directly. */
    @Synchronized fun neighbours(): Map<String, List<String>> =
        linkPeer.entries.filter { it.key in links }.groupBy({ it.value }, { it.key })

    /** A packet this phone created. */
    @Synchronized fun send(packet: Packet): List<Send> {
        seen[packet.key] = clock()
        val sends = targets(packet, from = null).map { Send(it, packet) }
        remember(packet, sends)
        return sends
    }

    /** Re-sends one of our own packets that hasn't been acknowledged yet. */
    @Synchronized fun resend(packet: Packet): List<Send> = targets(packet, from = null).map { Send(it, packet) }

    /** [ackedIds] are the packet keys an ACK packet covers (parsed by the caller). */
    @Synchronized fun receive(link: String, packet: Packet, ackedIds: List<String> = emptyList()): Routed {
        val sends = ArrayList<Send>()

        // A direct neighbour's announcement (ttl untouched) tells us who is on this link.
        if (packet.type == PacketType.ANNOUNCE && packet.ttl == Packet.DEFAULT_TTL && packet.sender != me) {
            val known = linkPeer[link] == packet.sender
            linkPeer[link] = packet.sender
            if (!known) sends += replayTo(link, packet.sender)
        }

        val seenAt = seen[packet.key]
        if (seenAt != null) {
            // The sender is retrying because our acknowledgement got lost.
            val again = packet.type == PacketType.DIRECT && packet.recipient == me
            // A retry of someone else's direct message: pass it on again, but rarely enough that
            // copies circling the mesh die out.
            val now = clock()
            if (packet.type == PacketType.DIRECT && !again && packet.ttl > 1 && now - seenAt > REFORWARD_MS) {
                seen[packet.key] = now
                val next = packet.copy(ttl = packet.ttl - 1)
                sends += targets(next, from = link).map { Send(it, next) }
            }
            return Routed(if (again) packet else null, sends, duplicate = again)
        }
        seen[packet.key] = clock()
        if (packet.sender == me) return Routed(null, sends)

        if (packet.type == PacketType.ACK && ackVerifier(packet)) {
            ackedIds.forEach { forget(it) }
        }

        val forMe = packet.recipient == me
        val deliver = if (packet.isBroadcast || forMe) packet else null
        if (!forMe && packet.ttl > 1) {
            val next = packet.copy(ttl = packet.ttl - 1)
            val out = targets(next, from = link).map { Send(it, next) }
            sends += out
            remember(next, out, fromPeer = linkPeer[link])
        }
        return Routed(deliver, sends)
    }

    /** Drops expired stored packets. */
    @Synchronized fun tick() {
        val now = clock()
        store.values.filter { it.until < now }.map { it.packet.key }.forEach(::forget)
    }

    @Synchronized fun forget(key: String) {
        store.remove(key)?.let { storedBytes -= it.packet.payload.size }
    }

    private fun targets(packet: Packet, from: String?): List<String> {
        if (!packet.isBroadcast) {
            val direct = bestLink(packet.recipient)
            if (direct != null && direct != from) return listOf(direct)
        }
        // One link per known neighbour (the fastest); every link whose peer we don't know yet.
        val byPeer = HashMap<String, String>()
        val unknown = ArrayList<String>()
        links.keys.filter { it != from }.forEach { l ->
            val peer = linkPeer[l]
            if (peer == null) unknown += l
            else if (peer != linkPeer[from]) {
                val current = byPeer[peer]
                if (current == null || links[l]!! > links[current]!!) byPeer[peer] = l
            }
        }
        return byPeer.values + unknown
    }

    private fun bestLink(peer: String): String? =
        linkPeer.entries.filter { it.value == peer && it.key in links }.maxByOrNull { links[it.key]!! }?.key

    private fun remember(packet: Packet, sends: List<Send>, fromPeer: String? = null) {
        if (packet.type == PacketType.ANNOUNCE) return
        val peers = sends.mapNotNull { linkPeer[it.link] }.toMutableSet()
        fromPeer?.let { peers += it }
        store[packet.key] = Stored(packet, clock() + STORE_FOR_MS, peers)
        storedBytes += packet.payload.size
        while (store.size > STORE_MAX_PACKETS || storedBytes > STORE_MAX_BYTES) {
            forget(store.keys.first())
        }
    }

    private fun replayTo(link: String, peer: String): List<Send> {
        val now = clock()
        return store.values.filter { it.until >= now && peer !in it.sentToPeers && it.packet.sender != peer }
            .map { s ->
                s.sentToPeers += peer
                Send(link, s.packet)
            }
    }

    companion object {
        const val SEEN_MAX = 5_000
        const val REFORWARD_MS = 20_000L
        const val STORE_FOR_MS = 24L * 60 * 60 * 1000
        const val STORE_MAX_PACKETS = 600
        const val STORE_MAX_BYTES = 4L * 1024 * 1024
    }
}
