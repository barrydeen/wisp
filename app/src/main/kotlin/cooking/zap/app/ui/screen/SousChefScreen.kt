package cooking.zap.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cooking.zap.app.ui.component.recipeBody
import cooking.zap.app.viewmodel.SousChefViewModel
import cooking.zap.app.viewmodel.SousChefViewModel.State

/**
 * Sous Chef import screen (concern 2.1) — paste a recipe URL, preview the
 * AI-extracted recipe read-only via the shared [recipeBody]. Saving to your
 * account is deferred to 2.2 (mirrors the web's preview-until-sign-in).
 *
 * NOTE (pre-ship): the drawer/icon use a placeholder mark — port the real
 * Sous Chef SVG for symbol parity with the web before a user-facing release.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SousChefScreen(
    viewModel: SousChefViewModel,
    onImport: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    var url by remember { mutableStateOf("") }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Sous Chef") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "Paste a recipe link and Sous Chef pulls out the ingredients and steps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Recipe URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onGo = { onImport(url) },
                    ),
                    trailingIcon = {
                        IconButton(onClick = { clipboard.getText()?.text?.let { url = it } }) {
                            Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste")
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onImport(url) },
                    enabled = url.isNotBlank() && state != State.Loading,
                ) { Text("Import") }
            }

            HorizontalDivider()

            when (val s = state) {
                State.Idle -> Unit
                State.Loading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
                is State.Error -> Box(
                    Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                is State.Preview -> RecipePreview(s)
            }
        }
    }
}

@Composable
private fun RecipePreview(preview: State.Preview) {
    var multiplier by remember(preview.recipe) { mutableStateOf(1.0) }
    LazyColumn(Modifier.fillMaxSize()) {
        // Read-only: no byline/engagement slots — an imported recipe has no event yet.
        recipeBody(
            recipe = preview.recipe,
            multiplier = multiplier,
            onMultiplierChange = { multiplier = it },
        )
        item(key = "save-hint") {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Saving imported recipes to your account is coming soon.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = {}, enabled = false) { Text("Save to my recipes") }
            }
        }
        item(key = "footer") { Spacer(Modifier.height(32.dp)) }
    }
}
