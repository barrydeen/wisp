package com.wisp.app.ui.screen

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.wisp.app.R
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.PublishResult
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.*
import com.wisp.app.viewmodel.ComposeViewModel
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Exercise the actual editor, ViewModel, atomic store and completion callback. No native signing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class OnboardingFirstPostScreenTest {
    @get:Rule val compose = createComposeRule()
    private val pubkey = "a".repeat(64)
    private lateinit var app: Application
    private lateinit var keys: MockedConstruction<KeyRepository>
    private lateinit var viewModel: ComposeViewModel
    private lateinit var publisher: NotePublisher
    private lateinit var store: FileNotePublicationStore
    private lateinit var scope: CoroutineScope
    private val viewModels = ViewModelStore()
    private val directory = Files.createTempDirectory("wisp-first-post").toFile()
    private lateinit var relayPool: RelayPool
    private val route = mutableStateOf("onboarding")
    private var posted = 0
    private var skipped = 0
    @Volatile private var failWrites = false
    private var signGate: CompletableDeferred<Unit>? = null
    private val introduction = "#introductions\n\nHello from my first post"

    private val signer = object : NostrSigner {
        override val pubkeyHex: String get() = pubkey
        override suspend fun signEvent(kind: Int, content: String, tags: List<List<String>>, createdAt: Long): NostrEvent {
            signGate?.await()
            return NostrEvent.createUnsigned(pubkey, kind, content, tags, createdAt).withSignature("c".repeat(128))
        }
        override suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String = error("Unused")
        override suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String = error("Unused")
    }

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        // Isolate keystore construction only; publication uses the real ViewModel and screen.
        keys = Mockito.mockConstruction(KeyRepository::class.java) { mock, _ ->
            Mockito.`when`(mock.getPubkeyHex()).thenReturn(pubkey)
        }
        InterfacePreferences(app).setPostUndoTimerEnabled(false)
        viewModel = ComposeViewModel(app, SavedStateHandle())
        viewModels.put("compose", viewModel)
        relayPool = RelayPool()
        val eventRepo = EventRepository().apply { currentUserPubkey = pubkey }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        store = FileNotePublicationStore(directory)
        val controlledStore = object : NotePublicationStore by store {
            override fun save(publication: NotePublication) {
                if (failWrites) throw IOException("Storage unavailable")
                store.save(publication)
            }
        }
        val transport = object : NotePublicationTransport {
            override val results = MutableSharedFlow<PublishResult>()
            override fun targetRelays(inboxPubkeys: Collection<String>) = setOf("wss://offline.example")
            override suspend fun send(relayUrl: String, event: NostrEvent) = false
        }
        publisher = NotePublisher(pubkey, controlledStore, transport, scope, eventRepo::addEvent)
        eventRepo.notePublisher = publisher
        viewModel.init(ProfileRepository(app), ContactRepository(app, pubkey), relayPool, eventRepo)
    }

    @After fun cleanup() {
        val stopped = publisher.close()
        compose.runOnIdle { viewModels.clear(); scope.cancel(); relayPool.disconnectAll() }
        await { stopped.isCompleted }
        keys.close()
        directory.deleteRecursively()
    }

    private fun showOnboarding() {
        compose.setContent {
            MaterialTheme {
                if (route.value == "onboarding") {
                    OnboardingFirstPostScreen(viewModel, relayPool, null, signer,
                        onPosted = { posted++; route.value = "feed" },
                        onSkip = { skipped++; route.value = "feed" })
                } else Text(route.value)
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement(introduction)
    }

    private fun assertCompleted(expectedPosts: Int = 1) {
        await { posted == expectedPosts || (!viewModel.publishing.value && viewModel.error.value != null) }
        assertNull("Publication failed", viewModel.error.value)
        assertEquals(expectedPosts, posted)
        compose.onNodeWithText("feed").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(viewModel.publishing.value)
            assertEquals("", viewModel.content.value.text)
            assertNull(viewModel.error.value)
        }
        assertEquals(0, skipped)
        assertTrue(store.load().any { it.event.content == introduction })
    }

    private fun await(condition: () -> Boolean) {
        compose.waitUntil(5_000) {
            // Drive ViewModel callbacks posted by the real IO dispatcher to Android's main loop.
            ShadowLooper.idleMainLooper()
            condition()
        }
    }

    @Test fun `first introduction completes onboarding after durable save without relay acceptance`() {
        showOnboarding()
        compose.onNodeWithText("Post introduction").assertIsEnabled().performClick()
        assertCompleted()
    }

    @Test fun `post now completes the onboarding undo flow`() {
        InterfacePreferences(app).apply { setPostUndoTimerEnabled(true); setPostUndoTimerSeconds(5) }
        showOnboarding()
        compose.onNodeWithText("Post introduction").performClick()
        compose.onNodeWithText("Skip").assertIsNotEnabled()
        compose.onNodeWithText(app.getString(R.string.compose_post_now, 5)).performClick()
        assertCompleted()
    }

    @Test fun `undo keeps the introduction and enables skip without publishing`() {
        InterfacePreferences(app).setPostUndoTimerEnabled(true)
        showOnboarding()
        compose.onNodeWithText("Post introduction").performClick()
        compose.onNodeWithText(app.getString(R.string.btn_undo)).performClick()
        compose.onNodeWithText("Skip").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertFalse(viewModel.publishing.value)
            assertEquals(introduction, viewModel.content.value.text)
        }
        assertEquals(0, posted)
        assertEquals(1, skipped)
        assertTrue(store.load().isEmpty())
    }

    @Test fun `storage failure keeps the introduction and enables retry and skip`() {
        failWrites = true
        showOnboarding()
        compose.onNodeWithText("Post introduction").performClick()
        await { viewModel.error.value != null }
        compose.onNodeWithText("Skip").assertIsEnabled()
        compose.onNodeWithText("Post introduction").assertIsEnabled()
        compose.runOnIdle {
            assertFalse(viewModel.publishing.value)
            assertEquals(introduction, viewModel.content.value.text)
        }
        assertEquals(0, posted)
        assertTrue(store.load().isEmpty())
        failWrites = false
        compose.onNodeWithText("Post introduction").performClick()
        assertCompleted()
    }

    @Test fun `leaving during signing cannot complete or clear the next onboarding edit`() {
        val gate = CompletableDeferred<Unit>()
        signGate = gate
        showOnboarding()
        compose.onNodeWithText("Post introduction").performClick()
        await { viewModel.publishing.value }
        compose.runOnIdle { route.value = "profile" }
        compose.onNodeWithText("profile").assertIsDisplayed()
        val nextIntroduction = "#introductions\n\nMy next edit"
        compose.runOnIdle {
            viewModel.updateContent(androidx.compose.ui.text.input.TextFieldValue(nextIntroduction))
            route.value = "onboarding"
        }
        compose.onNodeWithText("Post introduction").assertIsEnabled()
        compose.runOnIdle { gate.complete(Unit) }
        await { store.load().any { it.event.content == introduction } }
        compose.runOnIdle {
            assertEquals(0, posted)
            assertFalse(viewModel.publishing.value)
            assertEquals(nextIntroduction, viewModel.content.value.text)
        }
        compose.onNodeWithText("Post introduction").performClick()
        assertCompleted()
        assertEquals(2, store.load().size)
    }
}
