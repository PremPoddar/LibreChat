package com.prempoddar.librechat.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionTest {
    private class Device {
        val identity = Identity.generate()
        val prekey = SignedPrekey.generate(identity, id = 1, now = 0)
        val pub get() = identity.publicIdentity()
        var sessions = SessionSet()
        fun prekeys(id: Int) = prekey.takeIf { it.id == id }

        fun send(to: Device, text: String): Envelope {
            if (sessions.current == null) sessions = sessions.withNew(Session.initiate(identity, to.pub, to.prekey.publicPart()))
            val (next, env) = sessions.encrypt(text.toByteArray())
            sessions = next
            return env
        }

        fun recv(env: Envelope): String {
            val (next, plain, _) = SessionSet.receive(sessions, identity, ::prekeys, env)
            sessions = next
            return plain.decodeToString()
        }
    }

    @Test fun firstMessagesCarryPrekeyUntilReply() {
        val a = Device(); val b = Device()
        val e1 = a.send(b, "hi"); val e2 = a.send(b, "you there?")
        assertThat(e1.prekey).isNotNull(); assertThat(e2.prekey).isNotNull()
        assertThat(b.recv(e2)).isEqualTo("you there?")
        assertThat(b.recv(e1)).isEqualTo("hi")
        val reply = b.send(a, "yes")
        assertThat(reply.prekey).isNull()
        assertThat(a.recv(reply)).isEqualTo("yes")
        assertThat(a.send(b, "great").prekey).isNull()
    }

    @Test fun senderIdentityComesFromPrekeyHeader() {
        val a = Device(); val b = Device()
        val (_, _, from) = SessionSet.receive(b.sessions, b.identity, b::prekeys, a.send(b, "hi"))
        assertThat(from).isEqualTo(a.pub)
    }

    @Test fun simultaneousStartStillWorksBothWays() {
        val a = Device(); val b = Device()
        val fromA = a.send(b, "a first")
        val fromB = b.send(a, "b first")
        assertThat(b.recv(fromA)).isEqualTo("a first")
        assertThat(a.recv(fromB)).isEqualTo("b first")
        repeat(3) { i ->
            assertThat(b.recv(a.send(b, "a$i"))).isEqualTo("a$i")
            assertThat(a.recv(b.send(a, "b$i"))).isEqualTo("b$i")
        }
    }

    @Test fun replayedPrekeyMessageDoesNotResetSession() {
        val a = Device(); val b = Device()
        val first = a.send(b, "hi")
        b.recv(first)
        a.recv(b.send(a, "hello"))
        assertThrows(CryptoException::class.java) { b.recv(first) }
        assertThat(b.recv(a.send(b, "still fine"))).isEqualTo("still fine")
    }

    @Test fun forgedPrekeyIsRefused() {
        val a = Device(); val b = Device(); val mallory = Device()
        assertThrows(CryptoException::class.java) {
            Session.initiate(a.identity, b.pub, mallory.prekey.publicPart())
        }
    }

    @Test fun unknownPrekeyIdIsRefused() {
        val a = Device(); val b = Device()
        val env = a.send(b, "hi")
        assertThrows(CryptoException::class.java) {
            SessionSet.receive(SessionSet(), b.identity, { null }, env)
        }
    }

    @Test fun envelopeAndSessionsSerialise() {
        val a = Device(); val b = Device()
        val env = a.send(b, "hi")
        assertThat(b.recv(Envelope.fromBytes(env.toBytes()))).isEqualTo("hi")
        b.sessions = SessionSet.fromBytes(b.sessions.toBytes())
        a.recv(b.send(a, "back"))
    }
}
