package cooking.zap.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.ui.component.NourishCard
import cooking.zap.app.viewmodel.RecipeDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NourishHubScreen(
    viewModel: RecipeDetailViewModel,
    selectedAuthor: String?,
    selectedDTag: String?,
    canSign: Boolean,
    onComputeNourish: () -> Unit,
    onBack: () -> Unit,
    onBrowseRecipes: () -> Unit,
    onOpenSelectedRecipe: (String, String) -> Unit,
    onExploreRecipes: () -> Unit,
) {
    val recipe by viewModel.recipe.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val nourishUi by viewModel.nourishUi.collectAsState()

    val hasSelection = !selectedAuthor.isNullOrBlank() && !selectedDTag.isNullOrBlank()
    val selectedCoordinate = if (hasSelection) {
        checkNotNull(selectedAuthor) to checkNotNull(selectedDTag)
    } else {
        null
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_nourish_hub)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "hero") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.nourish_hub_hero_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.nourish_hub_hero_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.nourish_hub_deferred_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!hasSelection) {
                item(key = "pick") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.nourish_hub_pick_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.nourish_hub_pick_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onBrowseRecipes) {
                                Text(stringResource(R.string.nourish_hub_pick_action))
                            }
                        }
                    }
                }
            } else {
                item(key = "selected-recipe") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            Text(
                                text = recipe?.title ?: stringResource(R.string.nourish_hub_loading_recipe),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.nourish_hub_selected_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (selectedCoordinate != null) {
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { onOpenSelectedRecipe(selectedCoordinate.first, selectedCoordinate.second) }) {
                                    Text(stringResource(R.string.nourish_hub_open_recipe))
                                }
                            }
                        }
                    }
                }

                when {
                    isLoading && recipe == null -> item(key = "loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    recipe == null -> item(key = "missing") {
                        NourishMessage(stringResource(R.string.error_recipe_not_found))
                    }

                    else -> when (val n = nourishUi) {
                        is RecipeDetailViewModel.NourishUi.Scored ->
                            item(key = "score") { NourishCard(n.score) }

                        RecipeDetailViewModel.NourishUi.NotScored ->
                            item(key = "compute") { NourishCompute(onComputeNourish, computing = false) }

                        RecipeDetailViewModel.NourishUi.Computing ->
                            item(key = "computing") { NourishCompute(onComputeNourish, computing = true) }

                        RecipeDetailViewModel.NourishUi.MembersOnly ->
                            item(key = "members-only") { NourishMessage(stringResource(R.string.nourish_members_only)) }

                        is RecipeDetailViewModel.NourishUi.Error ->
                            item(key = "error") { NourishMessage(n.message, retry = onComputeNourish) }

                        RecipeDetailViewModel.NourishUi.Hidden ->
                            if (!canSign) item(key = "readonly") { NourishMessage(stringResource(R.string.nourish_signing_required)) }

                        RecipeDetailViewModel.NourishUi.Loading ->
                            item(key = "score-loading") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                    }
                }
            }

            item(key = "explore") {
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.nourish_hub_explore_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.nourish_hub_explore_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onExploreRecipes) {
                            Text(stringResource(R.string.nourish_hub_explore_action))
                        }
                    }
                }
            }

            item(key = "footer") { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun NourishCompute(onCompute: () -> Unit, computing: Boolean) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            text = stringResource(R.string.nourish_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.nourish_compute_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onCompute, enabled = !computing) {
            if (computing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.nourish_compute_loading))
            } else {
                Text(stringResource(R.string.nourish_compute_action))
            }
        }
    }
}

@Composable
private fun NourishMessage(message: String, retry: (() -> Unit)? = null) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            text = stringResource(R.string.nourish_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (retry != null) {
            TextButton(onClick = retry) { Text(stringResource(R.string.btn_retry)) }
        }
    }
}
