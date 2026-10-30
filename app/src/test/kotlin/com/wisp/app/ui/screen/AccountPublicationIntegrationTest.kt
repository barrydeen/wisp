package com.wisp.app.ui.screen

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.wisp.app.nostr.*
import com.wisp.app.repo.*
import com.wisp.app.viewmodel.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Keep real FeedViewModel wiring, account scope, signed events and Android AtomicFile storage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AccountPublicationIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val first = Keys.fromPrivkey(ByteArray(32) { 1 })
    private val second = Keys.fromPrivkey(ByteArray(32) { 2 })
    private var activeKey: Keys.Keypair? = null
    private lateinit var keys: MockedConstruction<KeyRepository>
    private lateinit var nwc: MockedConstruction<NwcRepository>
    private lateinit var wallet: MockedConstruction<SparkRepository>
    private lateinit var feed: FeedViewModel
    private lateinit var editor: ComposeViewModel
    private lateinit var app: Application
    private val owners = ViewModelStore()

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        java.io.File(app.filesDir, "note_publications").deleteRecursively()
        keys = Mockito.mockConstruction(KeyRepository::class.java) { mock, _ ->
            Mockito.`when`(mock.getPubkeyHex()).thenAnswer { activeKey?.pubkey?.toHex() }
            Mockito.`when`(mock.getKeypair()).thenAnswer { activeKey }
        }
        // Spark's unrelated encrypted wallet requires AndroidKeyStore on a physical device.
        wallet = Mockito.mockConstruction(SparkRepository::class.java)
        nwc = Mockito.mockConstruction(NwcRepository::class.java)
        InterfacePreferences(app).setPostUndoTimerEnabled(false)
        val socket = Mockito.mock(okhttp3.WebSocket::class.java)
        val client = Mockito.mock(okhttp3.OkHttpClient::class.java, org.mockito.stubbing.Answer { call ->
            if (call.method.name == "newWebSocket") socket else Mockito.RETURNS_DEFAULTS.answer(call)
        })
        feed = FeedViewModel(app, client)
        owners.put("feed", feed)
        editor = ComposeViewModel(app, SavedStateHandle())
        owners.put("editor", editor)
        editor.init(feed.profileRepo, feed.contactRepo, feed.relayPool, feed.eventRepo)
    }

    @After fun cleanup() {
        compose.runOnIdle { owners.clear() }
        nwc.close()
        wallet.close()
        keys.close()
    }

    private fun await(condition: () -> Boolean) {
        compose.waitUntil(5_000) { ShadowLooper.idleMainLooper(); condition() }
    }

    private fun switchTo(key: Keys.Keypair) {
        compose.runOnIdle {
            assertTrue(feed.beginAccountSwitch(beforeKeySwap = {}, swapKey = { activeKey = key }))
        }
        await { !feed.accountSwitching.value }
        assertEquals(key.pubkey.toHex(), feed.eventRepo.notePublisher?.accountPubkey)
    }

    @Test fun `first post uses the real publisher created by login without restarting FeedViewModel`() {
        assertNull(feed.eventRepo.notePublisher)
        // This is the login/onboarding reload invoked by Navigation before the first-post route.
        activeKey = first
        compose.runOnIdle { feed.reloadForNewAccount() }
        val publisher = requireNotNull(feed.eventRepo.notePublisher)
        val signer = LocalSigner(first.privkey, first.pubkey)
        var completed = false
        compose.setContent {
            MaterialTheme {
                OnboardingFirstPostScreen(editor, feed.relayPool, feed.outboxRouter, signer,
                    onPosted = { completed = true }, onSkip = {})
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("#introductions\n\nA real first post")
        compose.onNodeWithText("Post introduction").assertIsEnabled().performClick()
        await { completed || editor.error.value != null }
        assertNull(editor.error.value)
        assertTrue(completed)
        assertFalse(editor.publishing.value)
        val saved = publisher.publications.value.values.single()
        assertTrue(saved.event.verifySignature())
        assertEquals(first.pubkey.toHex(), saved.event.pubkey)
        assertEquals(saved.event, FileNotePublicationStore(java.io.File(app.filesDir,
            "note_publications/${first.pubkey.toHex()}")).load().single().event)
    }

    @Test fun `real A B A switch isolates recovery and cancels old account collectors`() {
        switchTo(first)
        val a = requireNotNull(feed.eventRepo.notePublisher)
        val noteA = runBlocking { LocalSigner(first.privkey, first.pubkey).signEvent(1, "Only account A") }
        runBlocking { a.submit(noteA) }
        switchTo(second)
        val b = requireNotNull(feed.eventRepo.notePublisher)
        assertFalse(a.isActive)
        assertFalse(b.canPublish(noteA))
        val noteB = runBlocking { LocalSigner(second.privkey, second.pubkey).signEvent(1, "Only account B") }
        runBlocking { b.submit(noteB) }
        switchTo(first)
        val restored = requireNotNull(feed.eventRepo.notePublisher)
        await { noteA.id in restored.publications.value }
        assertNotSame(a, restored)
        assertFalse(b.isActive)
        assertEquals(listOf(noteA), restored.publications.value.values.map { it.event })
        assertFalse(restored.publications.value.getValue(noteA.id).inFlight)
        assertEquals(noteB, FileNotePublicationStore(java.io.File(app.filesDir,
            "note_publications/${second.pubkey.toHex()}")).load().single().event)
    }

    @Test fun `adding an account keeps old recovery paused until the new login reload`() {
        switchTo(first)
        val a = requireNotNull(feed.eventRepo.notePublisher)
        val note = runBlocking { LocalSigner(first.privkey, first.pubkey).signEvent(1, "Pending before adding B") }
        runBlocking { a.submit(note) }
        compose.runOnIdle {
            // Match Navigation's add-account preparation, which intentionally does not swap the key yet.
            assertTrue(feed.beginAccountSwitch(beforeKeySwap = {}, swapKey = {}, resumePublications = false))
        }
        await { !feed.accountSwitching.value }
        assertNull(feed.eventRepo.notePublisher)
        assertFalse(a.isActive)
        assertNull(feed.eventRepo.getEvent(note.id))
        activeKey = second
        compose.runOnIdle { feed.reloadForNewAccount() }
        val b = requireNotNull(feed.eventRepo.notePublisher)
        assertEquals(second.pubkey.toHex(), b.accountPubkey)
        assertTrue(b.publications.value.isEmpty())
        assertNull(feed.eventRepo.getEvent(note.id))
        switchTo(first)
        val restored = requireNotNull(feed.eventRepo.notePublisher)
        await { note.id in restored.publications.value }
        assertEquals(note, restored.publications.value.getValue(note.id).event)
    }
}
