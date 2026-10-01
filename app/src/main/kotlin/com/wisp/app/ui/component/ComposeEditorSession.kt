package com.wisp.app.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.wisp.app.viewmodel.ComposeViewModel
import com.wisp.app.viewmodel.ComposerSession

/** Bind every editor, including onboarding, to its own lifecycle and publication owner. */
@Composable
fun rememberComposeEditor(viewModel: ComposeViewModel): State<ComposerSession.Token?> {
    val editor = remember(viewModel) { mutableStateOf<ComposerSession.Token?>(null) }
    DisposableEffect(viewModel) {
        val token = viewModel.beginEditor()
        editor.value = token
        onDispose {
            viewModel.endEditor(token)
            editor.value = null
        }
    }
    return editor
}
