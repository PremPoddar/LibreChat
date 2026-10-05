package com.prempoddar.librechat.crypto

import com.google.common.truth.Truth.assertThat
import com.prempoddar.librechat.crypto.Bytes.hex
import com.prempoddar.librechat.crypto.Bytes.unhex
import org.junit.Test

class PrimitivesTest {
    // RFC 7748 §6.1
    @Test fun x25519MatchesRfc7748() {
        val alice = X25519KeyPair.fromPrivate(unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
        val bob = X25519KeyPair.fromPrivate(unhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"))
        assertThat(alice.public.hex()).isEqualTo("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        assertThat(bob.public.hex()).isEqualTo("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertThat(X25519.agree(alice.private, bob.public).hex()).isEqualTo(shared)
        assertThat(X25519.agree(bob.private, alice.public).hex()).isEqualTo(shared)
    }

    @Test(expected = CryptoException::class)
    fun x25519RejectsLowOrderPoint() {
        X25519.agree(X25519KeyPair.generate().private, ByteArray(32))
    }

    // RFC 8032 §7.1 test 1
    @Test fun ed25519MatchesRfc8032() {
        val key = Ed25519KeyPair.fromPrivate(unhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"))
        assertThat(key.public.hex()).isEqualTo("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val sig = key.sign(ByteArray(0))
        assertThat(sig.hex()).isEqualTo(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        )
        assertThat(Ed25519.verify(key.public, ByteArray(0), sig)).isTrue()
        assertThat(Ed25519.verify(key.public, byteArrayOf(1), sig)).isFalse()
        assertThat(Ed25519.verify(key.public, ByteArray(0), sig.copyOf(10))).isFalse()
    }

    // RFC 5869 test case 1
    @Test fun hkdfMatchesRfc5869() {
        val okm = Hkdf.derive(
            ikm = ByteArray(22) { 0x0b },
            salt = unhex("000102030405060708090a0b0c"),
            info = unhex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertThat(okm.hex()).isEqualTo("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")
    }

    // RFC 8439 §2.8.2
    @Test fun aeadMatchesRfc8439() {
        val key = unhex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = unhex("070000004041424344454647")
        val ad = unhex("50515253c0c1c2c3c4c5c6c7")
        val text = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
        val sealed = Aead.seal(key, nonce, text.toByteArray(), ad)
        assertThat(sealed.hex()).isEqualTo(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b6116" +
                "1ae10b594f09e26a7e902ecbd0600691",
        )
        assertThat(Aead.open(key, nonce, sealed, ad)!!.decodeToString()).isEqualTo(text)
    }

    @Test fun aeadRejectsTampering() {
        val key = Random.bytes(32); val nonce = Random.bytes(12)
        val sealed = Aead.seal(key, nonce, "hi".toByteArray(), byteArrayOf(1))
        assertThat(Aead.open(key, nonce, sealed, byteArrayOf(2))).isNull()
        sealed[0] = (sealed[0].toInt() xor 1).toByte()
        assertThat(Aead.open(key, nonce, sealed, byteArrayOf(1))).isNull()
        assertThat(Aead.open(key, nonce, ByteArray(5), byteArrayOf(1))).isNull()
    }

    // RFC 4231 test case 2
    @Test fun hmacMatchesRfc4231() {
        val mac = Hmac.sha256("Jefe".toByteArray(), "what do ya want for nothing?".toByteArray())
        assertThat(mac.hex()).isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843")
    }
}
