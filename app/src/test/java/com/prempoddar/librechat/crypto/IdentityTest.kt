package com.prempoddar.librechat.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IdentityTest {
    @Test fun peerIdIsStableShortHashOfSigningKey() {
        val id = Identity.generate()
        assertThat(id.peerId.value).hasLength(16)
        assertThat(id.publicIdentity().peerId).isEqualTo(id.peerId)
        assertThat(Identity.fromBytes(id.toBytes()).peerId).isEqualTo(id.peerId)
    }

    @Test fun publicIdentityBindsDhKeyToSigningKey() {
        val pub = Identity.generate().publicIdentity()
        assertThat(pub.isValid()).isTrue()
        val forged = pub.copy(dhKey = X25519KeyPair.generate().public)
        assertThat(forged.isValid()).isFalse()
    }

    @Test fun publicIdentityCodecRoundTrips() {
        val pub = Identity.generate().publicIdentity()
        assertThat(PublicIdentity.fromBytes(pub.toBytes())).isEqualTo(pub)
        assertThat(PublicIdentity.fromBytes(ByteArray(10))).isNull()
    }

    @Test fun signedPrekeyVerifiesAgainstItsIdentityOnly() {
        val me = Identity.generate()
        val spk = SignedPrekey.generate(me, id = 7, now = 1000)
        assertThat(spk.publicPart().verify(me.publicIdentity())).isTrue()
        assertThat(spk.publicPart().verify(Identity.generate().publicIdentity())).isFalse()
        assertThat(SignedPrekey.fromBytes(spk.toBytes()).publicPart()).isEqualTo(spk.publicPart())
    }

    @Test fun safetyNumberIsSymmetricAndSixtyDigits() {
        val a = Identity.generate().publicIdentity()
        val b = Identity.generate().publicIdentity()
        val ab = SafetyNumber.of(a, b)
        assertThat(ab).isEqualTo(SafetyNumber.of(b, a))
        assertThat(ab.replace(" ", "")).matches("\\d{60}")
        assertThat(ab.split(" ")).hasSize(12)
        assertThat(SafetyNumber.of(a, Identity.generate().publicIdentity())).isNotEqualTo(ab)
    }

    @Test fun contactCardRoundTripsAndRejectsForgery() {
        val me = Identity.generate()
        val card = ContactCard.encode(me.publicIdentity(), "Asha")
        assertThat(card).startsWith("librechat:")
        val parsed = ContactCard.decode(card)!!
        assertThat(parsed.identity).isEqualTo(me.publicIdentity())
        assertThat(parsed.name).isEqualTo("Asha")
        assertThat(ContactCard.decode("https://example.com")).isNull()
        assertThat(ContactCard.decode("librechat:AAAA")).isNull()
    }
}
