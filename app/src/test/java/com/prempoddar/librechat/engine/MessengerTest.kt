package com.prempoddar.librechat.engine

import com.google.common.truth.Truth.assertThat
import com.prempoddar.librechat.crypto.Random
import com.prempoddar.librechat.protocol.MediaKind
import com.prempoddar.librechat.protocol.Packet
import com.prempoddar.librechat.protocol.PacketType
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MessengerTest {
    private val net = TestMesh()

    /** A ↔ B ↔ C: A and C can only reach each other through B. */
    private suspend fun line(): Triple<TestMesh.Phone, TestMesh.Phone, TestMesh.Phone> {
        val a = net.phone("Asha"); val b = net.phone("Ben"); val c = net.phone("Cy")
        net.connect(a, b); net.connect(b, c)
        net.announceAll(a, b, c)
        return Triple(a, b, c)
    }

    @Test fun announcementsTeachNamesAndHops() = runTest {
        val (a, b, c) = line()
        assertThat(a.store.people[b.id]!!.hops).isEqualTo(0)
        assertThat(a.store.people[c.id]!!.hops).isEqualTo(1)
        assertThat(a.store.people[c.id]!!.name).isEqualTo("Cy")
    }

    @Test fun publicChatCrossesTheMesh() = runTest {
        val (a, _, c) = line()
        a.messenger.sendText(ConvIds.PUBLIC, "hello everyone")
        net.settle()
        val got = c.store.inConv(ConvIds.PUBLIC).single()
        assertThat(got.text).isEqualTo("hello everyone")
        assertThat(got.sender).isEqualTo(a.id)
        assertThat(a.store.inConv(ConvIds.PUBLIC).single().status).isEqualTo(MsgStatus.SENT)
    }

    @Test fun directMessageIsPrivateAndDelivered() = runTest {
        val (a, b, c) = line()
        val sent = a.messenger.sendText(ConvIds.direct(c.id), "psst")
        net.settle()
        assertThat(c.store.inConv(ConvIds.direct(a.id)).single().text).isEqualTo("psst")
        assertThat(b.store.messages.values.none { it.text == "psst" }).isTrue()
        assertThat(a.store.messages[sent.id]!!.status).isEqualTo(MsgStatus.DELIVERED)
    }

    @Test fun firstMessageIsARequestUntilAccepted() = runTest {
        val (a, _, c) = line()
        a.messenger.sendText(ConvIds.direct(c.id), "hi, it's Asha")
        net.settle()
        assertThat(c.store.conversations[ConvIds.direct(a.id)]!!.request).isTrue()
        assertThat(c.store.people[a.id]!!.state).isEqualTo(PersonState.REQUEST)

        // No read receipt while it's still a request.
        c.messenger.markRead(ConvIds.direct(a.id)); net.settle()
        assertThat(a.store.inConv(ConvIds.direct(c.id)).single().status).isEqualTo(MsgStatus.DELIVERED)

        c.messenger.sendText(ConvIds.direct(a.id), "hey!")
        net.settle()
        assertThat(c.store.conversations[ConvIds.direct(a.id)]!!.request).isFalse()
        assertThat(a.store.people[c.id]!!.state).isEqualTo(PersonState.ACCEPTED)
        assertThat(a.store.inConv(ConvIds.direct(c.id)).map { it.text }).contains("hey!")
    }

    @Test fun readReceiptsAfterAccepting() = runTest {
        val (a, _, c) = line()
        val m = a.messenger.sendText(ConvIds.direct(c.id), "hello")
        net.settle()
        c.messenger.accept(a.id); net.settle()
        c.messenger.markRead(ConvIds.direct(a.id)); net.settle()
        assertThat(a.store.messages[m.id]!!.status).isEqualTo(MsgStatus.READ)
        assertThat(c.store.conversations[ConvIds.direct(a.id)]!!.unread).isEqualTo(0)
    }

    @Test fun messageWaitsUntilRecipientIsHeard() = runTest {
        val a = net.phone("Asha"); val c = net.phone("Cy")
        // Asha only has Cy's QR code, so no prekey yet.
        a.messenger.verify(c.messenger.myIdentity.publicIdentity(), "Cy")
        val m = a.messenger.sendText(ConvIds.direct(c.id), "see you later")
        assertThat(a.store.messages[m.id]!!.status).isEqualTo(MsgStatus.SENDING)
        net.connect(a, c)
        net.announceAll(c)
        assertThat(c.store.inConv(ConvIds.direct(a.id)).single().text).isEqualTo("see you later")
        assertThat(a.store.messages[m.id]!!.status).isEqualTo(MsgStatus.DELIVERED)
    }

    @Test fun relayStoresAndForwardsWhenThePathAppearsLater() = runTest {
        val (a, b, c) = line()
        net.disconnect(b, c)
        a.messenger.sendText(ConvIds.direct(c.id), "for later")
        net.settle()
        assertThat(c.store.inConv(ConvIds.direct(a.id))).isEmpty()
        net.connect(b, c)
        net.announceAll(c)
        assertThat(c.store.inConv(ConvIds.direct(a.id)).single().text).isEqualTo("for later")
    }

    @Test fun lostAckIsRepairedByRetry() = runTest {
        val (a, _, c) = line()
        var dropAcks = true
        net.drop = { from, bytes -> dropAcks && from === c && Packet.fromBytes(bytes)?.type == PacketType.ACK }
        val m = a.messenger.sendText(ConvIds.direct(c.id), "ack me")
        net.settle()
        assertThat(a.store.messages[m.id]!!.status).isEqualTo(MsgStatus.SENT)
        dropAcks = false
        // The relay already passed the packet on, so the retry must come straight from A's outbox
        // and B must not swallow it as a duplicate before C can acknowledge again.
        net.now += Messenger.RETRY_MS + 1
        a.messenger.tick(); net.settle()
        assertThat(a.store.messages[m.id]!!.status).isEqualTo(MsgStatus.DELIVERED)
        assertThat(c.store.inConv(ConvIds.direct(a.id))).hasSize(1)
    }

    @Test fun reactionsAndDeleteForEveryone() = runTest {
        val (a, _, c) = line()
        val m = a.messenger.sendText(ConvIds.direct(c.id), "oops")
        net.settle()
        c.messenger.react(m.id, "🔥"); net.settle()
        assertThat(a.store.messages[m.id]!!.reactions["🔥"]).containsExactly(c.id)
        a.messenger.deleteForEveryone(m.id); net.settle()
        assertThat(c.store.messages[m.id]!!.deleted).isTrue()
        assertThat(c.store.messages[m.id]!!.text).isEmpty()
    }

    @Test fun onlyTheAuthorCanDeleteForEveryone() = runTest {
        val (a, _, c) = line()
        val m = a.messenger.sendText(ConvIds.PUBLIC, "mine")
        net.settle()
        // Cy tries to delete Asha's message: Cy's own copy isn't "mine", so nothing is sent.
        c.messenger.deleteForEveryone(m.id); net.settle()
        assertThat(a.store.messages[m.id]!!.deleted).isFalse()
    }

    @Test fun groupsEncryptOnceForEveryone() = runTest {
        val (a, b, c) = line()
        val gid = a.messenger.createGroup("Campers", listOf(b.id, c.id))
        net.settle()
        assertThat(c.store.groups[gid]!!.members).containsExactly(a.id, b.id, c.id)
        a.messenger.sendText(ConvIds.group(gid), "tents up?")
        net.settle()
        assertThat(b.store.inConv(ConvIds.group(gid)).single().text).isEqualTo("tents up?")
        assertThat(c.store.inConv(ConvIds.group(gid)).single().text).isEqualTo("tents up?")
        c.messenger.sendText(ConvIds.group(gid), "yes")
        net.settle()
        assertThat(a.store.inConv(ConvIds.group(gid)).map { it.text }).contains("yes")
        assertThat(b.store.inConv(ConvIds.group(gid)).map { it.text }).contains("yes")
    }

    @Test fun removedMemberCantReadNewGroupMessages() = runTest {
        val (a, b, c) = line()
        val gid = a.messenger.createGroup("Campers", listOf(b.id, c.id))
        net.settle()
        a.messenger.sendText(ConvIds.group(gid), "before"); net.settle()
        a.messenger.updateGroup(gid, remove = listOf(c.id)); net.settle()
        assertThat(c.store.groups[gid]!!.active).isFalse()
        a.messenger.sendText(ConvIds.group(gid), "after"); net.settle()
        assertThat(b.store.inConv(ConvIds.group(gid)).map { it.text }).contains("after")
        assertThat(c.store.inConv(ConvIds.group(gid)).map { it.text }).doesNotContain("after")
    }

    @Test fun leavingPassesAdminOn() = runTest {
        val (a, b, c) = line()
        val gid = a.messenger.createGroup("Campers", listOf(b.id, c.id))
        net.settle()
        a.messenger.leaveGroup(gid); net.settle()
        assertThat(b.store.groups[gid]!!.members).containsExactly(b.id, c.id)
        assertThat(b.store.groups[gid]!!.admin).isEqualTo(minOf(b.id, c.id))
        b.messenger.sendText(ConvIds.group(gid), "still here"); net.settle()
        assertThat(c.store.inConv(ConvIds.group(gid)).map { it.text }).contains("still here")
    }

    @Test fun photosArriveIntactAcrossTheRelay() = runTest {
        val (a, _, c) = line()
        val photo = Random.bytes(50_000)
        a.messenger.sendMedia(ConvIds.direct(c.id), MediaKind.PHOTO, "image/jpeg", photo, byteArrayOf(1, 2), 800, 600, caption = "view")
        net.settle()
        val got = c.store.inConv(ConvIds.direct(a.id)).single()
        assertThat(got.kind).isEqualTo(MsgKind.PHOTO)
        assertThat(got.text).isEqualTo("view")
        assertThat(c.files.files[got.media!!.path]).isEqualTo(photo)
    }

    @Test fun missingMediaPiecesAreRequestedAgain() = runTest {
        val (a, _, c) = line()
        var lose = true
        var dropped = 0
        net.drop = { from, bytes -> lose && from === a && Packet.fromBytes(bytes)?.type == PacketType.MEDIA_CHUNK && dropped++ < 2 }
        val photo = Random.bytes(40_000)
        a.messenger.sendMedia(ConvIds.direct(c.id), MediaKind.PHOTO, "image/jpeg", photo, ByteArray(0))
        net.settle()
        assertThat(c.store.inConv(ConvIds.direct(a.id)).single().media!!.path).isNull()
        lose = false
        net.now += Messenger.MISSING_AFTER_MS + 1
        c.messenger.tick(); net.settle()
        val got = c.store.inConv(ConvIds.direct(a.id)).single()
        assertThat(c.files.files[got.media!!.path]).isEqualTo(photo)
    }

    @Test fun disappearingMessagesExpireOnBothPhones() = runTest {
        val (a, _, c) = line()
        a.messenger.setTimer(ConvIds.direct(c.id), 3_600); net.settle()
        assertThat(c.store.conversations[ConvIds.direct(a.id)]!!.timerSeconds).isEqualTo(3_600)
        a.messenger.sendText(ConvIds.direct(c.id), "gone soon"); net.settle()
        net.now += 3_600_000 + 1
        a.messenger.tick(); c.messenger.tick()
        assertThat(a.store.inConv(ConvIds.direct(c.id))).isEmpty()
        assertThat(c.store.inConv(ConvIds.direct(a.id))).isEmpty()
    }

    @Test fun blockedPeopleAreIgnoredButStillRelayed() = runTest {
        val (a, b, c) = line()
        b.messenger.block(a.id)
        a.messenger.sendText(ConvIds.direct(b.id), "let me in"); net.settle()
        assertThat(b.store.inConv(ConvIds.direct(a.id))).isEmpty()
        a.messenger.sendText(ConvIds.direct(c.id), "through Ben"); net.settle()
        assertThat(c.store.inConv(ConvIds.direct(a.id)).single().text).isEqualTo("through Ben")
    }

    @Test fun forgedAnnouncementIsNotRelayed() = runTest {
        val (a, b, c) = line()
        val forged = Packet(type = PacketType.ANNOUNCE, sender = "0123456789abcdef", timestamp = 0, payload = byteArrayOf(1, 2, 3))
        b.messenger.onPacket("Ben->Asha", forged.toBytes()); net.settle()
        assertThat(c.store.people.keys).doesNotContain("0123456789abcdef")
        assertThat(b.store.people.keys).doesNotContain("0123456789abcdef")
    }
}
