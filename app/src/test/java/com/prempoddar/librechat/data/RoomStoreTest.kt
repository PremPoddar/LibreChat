package com.prempoddar.librechat.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.prempoddar.librechat.crypto.Identity
import com.prempoddar.librechat.crypto.SignedPrekey
import com.prempoddar.librechat.engine.ChatMessage
import com.prempoddar.librechat.engine.MediaInfo
import com.prempoddar.librechat.engine.MsgKind
import com.prempoddar.librechat.engine.MsgStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plain Room here: SQLCipher's native library can't load on the JVM. The mapping is what's tested. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RoomStoreTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ChatDatabase::class.java)
        .allowMainThreadQueries().build()
    private val store = RoomStore(db.dao()) { "Asha" }

    @After fun close() = db.close()

    @Test fun identityAndPrekeysRoundTrip() = runTest {
        val id = Identity.generate()
        store.saveIdentity(id)
        assertThat(store.identity()!!.peerId).isEqualTo(id.peerId)
        val keys = listOf(SignedPrekey.generate(id, 1, 10), SignedPrekey.generate(id, 2, 20))
        store.savePrekeys(keys)
        assertThat(store.prekeys().map { it.publicPart() }).containsExactlyElementsIn(keys.map { it.publicPart() }).inOrder()
    }

    @Test fun messagesWithMediaAndReactionsRoundTrip() = runTest {
        val m = ChatMessage(
            "m1", "dm:x", "x", false, MsgKind.PHOTO, "cap", 5, MsgStatus.RECEIVED,
            reactions = mapOf("🔥" to listOf("a", "b")),
            media = MediaInfo("image/jpeg", 10, 0, 4, 3, byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), "t1", 2),
        )
        store.saveMessage(m)
        val back = store.message("m1")!!
        assertThat(back.reactions).isEqualTo(m.reactions)
        assertThat(back.media!!.transferId).isEqualTo("t1")
        assertThat(store.incompleteMedia().map { it.id }).containsExactly("m1")
        assertThat(store.unreadIncoming("dm:x").map { it.id }).containsExactly("m1")
    }

    @Test fun pendingAndKeyShares() = runTest {
        store.addPending("p", byteArrayOf(1), "m")
        assertThat(store.pending("p")).hasSize(1)
        store.deletePending(store.pending("p").single().id)
        assertThat(store.pending("p")).isEmpty()
        store.saveKeySharedWith("g", setOf("a", "b"))
        assertThat(store.keySharedWith("g")).containsExactly("a", "b")
    }
}
