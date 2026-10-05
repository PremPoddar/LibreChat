package com.prempoddar.librechat.engine

import com.prempoddar.librechat.mesh.LinkSpeed

/**
 * Phones joined by in-memory links. Sends are queued and delivered by [settle], so no phone is
 * ever re-entered while it is still handling a packet. [drop] can lose packets on purpose.
 */
class TestMesh {
    var now = 1_000_000L
    inner class Phone(val name: String) {
        val store = InMemoryStore(name)
        val files = InMemoryFiles()
        val messenger = Messenger(store, files, out = { link, bytes -> queue.addLast(Triple(link, bytes, this)) }, clock = { now })
        val id get() = messenger.me
    }

    private val queue = ArrayDeque<Triple<String, ByteArray, Phone>>()
    private val wires = HashMap<String, Pair<Phone, String>>() // my link -> (other phone, its link)
    var drop: (from: Phone, bytes: ByteArray) -> Boolean = { _, _ -> false }

    suspend fun phone(name: String) = Phone(name).also { it.messenger.start() }

    suspend fun connect(a: Phone, b: Phone) {
        val ab = "${a.name}->${b.name}"; val ba = "${b.name}->${a.name}"
        wires[ab] = b to ba; wires[ba] = a to ab
        a.messenger.onLinkUp(ab, LinkSpeed.BLE)
        b.messenger.onLinkUp(ba, LinkSpeed.BLE)
        settle()
    }

    suspend fun disconnect(a: Phone, b: Phone) {
        val ab = "${a.name}->${b.name}"; val ba = "${b.name}->${a.name}"
        wires.remove(ab); wires.remove(ba)
        a.messenger.onLinkDown(ab); b.messenger.onLinkDown(ba)
    }

    suspend fun settle() {
        var guard = 0
        while (queue.isNotEmpty()) {
            check(++guard < 100_000) { "mesh never settled" }
            val (link, bytes, from) = queue.removeFirst()
            val (to, theirLink) = wires[link] ?: continue
            if (drop(from, bytes)) continue
            to.messenger.onPacket(theirLink, bytes)
        }
    }

    suspend fun announceAll(vararg phones: Phone) {
        phones.forEach { it.messenger.announce() }
        settle()
    }
}
