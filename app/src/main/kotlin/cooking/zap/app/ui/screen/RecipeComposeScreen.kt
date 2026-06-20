package cooking.zap.app.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import cooking.zap.app.viewmodel.RecipeComposeViewModel
import cooking.zap.app.viewmodel.RecipeComposeViewModel.ImageItem

/**
 * Author a recipe from scratch and publish it as a kind-30023 event (concern:
 * recipe-compose). Fields and order mirror the web `/create` form; publish runs
 * through the shared 2.2 spine via [RecipeComposeViewModel.publish]. Reached
 * only by signing accounts (the Recipes-feed FAB is hidden for READ_ONLY), but
 * the publish button still gates on [canSign] defensively.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeComposeScreen(
    viewModel: RecipeComposeViewModel,
    canSign: Boolean,
    onPickImages: (uris: List<android.net.Uri>) -> Unit,
    onPublish: () -> Unit,
    onPublished: (author: String, dTag: String) -> Unit,
    onBack: () -> Unit,
) {
    val title by viewModel.title.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val chefNotes by viewModel.chefNotes.collectAsState()
    val prepTime by viewModel.prepTime.collectAsState()
    val cookTime by viewModel.cookTime.collectAsState()
    val servings by viewModel.servings.collectAsState()
    val additional by viewModel.additionalResources.collectAsState()
    val ingredients by viewModel.ingredients.collectAsState()
    val directions by viewModel.directions.collectAsState()
    val images by viewModel.images.collectAsState()
    val publishState by viewModel.publishState.collectAsState()

    // Optimistic nav once the event is signed + cached.
    val published = publishState as? RecipeComposeViewModel.PublishState.Published
    if (published != null) {
        androidx.compose.runtime.LaunchedEffect(published) {
            onPublished(published.author, published.dTag)
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> if (uris.isNotEmpty()) onPickImages(uris) }

    val reason = viewModel.blockReason(canSign)
    val publishing = publishState is RecipeComposeViewModel.PublishState.Publishing
    val errorMsg = (publishState as? RecipeComposeViewModel.PublishState.Error)?.message

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Create recipe") },
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
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // 1. Title
            item("title") {
                FieldSection("Title*", "Remember to make your title unique!") {
                    OutlinedTextField(
                        value = title,
                        onValueChange = viewModel::setTitle,
                        placeholder = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 2. Categories
            item("categories") {
                FieldSection("Tags*", "Add at least one category that describes your recipe") {
                    CategoryEditor(
                        categories = categories,
                        onAdd = viewModel::addCategory,
                        onRemove = viewModel::removeCategory,
                    )
                }
            }

            // 3. Summary
            item("summary") {
                FieldSection("Brief Summary", null) {
                    OutlinedTextField(
                        value = summary,
                        onValueChange = viewModel::setSummary,
                        placeholder = { Text("Some brief description of the dish") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 4. Chef's notes
            item("chefnotes") {
                FieldSection("Chef's Notes", "Markdown is supported") {
                    OutlinedTextField(
                        value = chefNotes,
                        onValueChange = viewModel::setChefNotes,
                        placeholder = { Text("Eg. where the recipe is from, or any extra info") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 5. Details
            item("details") {
                FieldSection("Details", null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = prepTime, onValueChange = viewModel::setPrepTime,
                            label = { Text("Prep time") }, placeholder = { Text("20 min") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = cookTime, onValueChange = viewModel::setCookTime,
                            label = { Text("Cooking time") }, placeholder = { Text("1 hour and 5 min") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = servings, onValueChange = viewModel::setServings,
                            label = { Text("Servings") }, placeholder = { Text("4") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // 6. Ingredients
            item("ingredients") {
                FieldSection("Ingredients*", null) {
                    RowEditor(
                        rows = ingredients,
                        placeholder = "2 eggs",
                        onUpdate = viewModel::updateIngredient,
                        onRemove = viewModel::removeIngredient,
                        onAdd = viewModel::addIngredient,
                        addLabel = "Add ingredient",
                    )
                }
            }

            // 7. Directions
            item("directions") {
                FieldSection("Directions*", null) {
                    RowEditor(
                        rows = directions,
                        placeholder = "Bake for 30 min",
                        onUpdate = viewModel::updateDirection,
                        onRemove = viewModel::removeDirection,
                        onAdd = viewModel::addDirection,
                        addLabel = "Add direction",
                        ordered = true,
                    )
                }
            }

            // 8. Photos
            item("photos") {
                FieldSection("Photos*", "First image will be your cover photo") {
                    PhotoEditor(
                        images = images,
                        onPick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onRemove = viewModel::removeImage,
                    )
                }
            }

            // 9. Additional resources (optional)
            item("additional") {
                FieldSection("Additional Resources", null) {
                    OutlinedTextField(
                        value = additional,
                        onValueChange = viewModel::setAdditionalResources,
                        placeholder = { Text("Eg. where the recipe is from, or links") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Publish
            item("publish") {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    reason?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    errorMsg?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = onPublish,
                        enabled = reason == null && !publishing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (publishing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Publish Recipe")
                        }
                    }
                }
            }

            item("footer") { Box(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun FieldSection(title: String, caption: String?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        caption?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditor(
    categories: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (categories.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { cat ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(cat) },
                        label = { Text(cat) },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = "Remove $cat", modifier = Modifier.size(16.dp))
                        },
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("e.g. italian") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { if (draft.isNotBlank()) { onAdd(draft); draft = "" } }
                ),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { if (draft.isNotBlank()) { onAdd(draft); draft = "" } }) { Text("Add") }
        }
    }
}

@Composable
private fun RowEditor(
    rows: List<RecipeComposeViewModel.Row>,
    placeholder: String,
    onUpdate: (Long, String) -> Unit,
    onRemove: (Long) -> Unit,
    onAdd: () -> Unit,
    addLabel: String,
    ordered: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { index, row ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (ordered) {
                    Text(
                        "${index + 1}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = row.text,
                    onValueChange = { onUpdate(row.id, it) },
                    placeholder = { Text(placeholder) },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemove(row.id) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove")
                }
            }
        }
        OutlinedButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(addLabel)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoEditor(
    images: List<ImageItem>,
    onPick: () -> Unit,
    onRemove: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (images.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                images.forEach { item -> PhotoThumb(item, onRemove) }
            }
        }
        OutlinedButton(onClick = onPick) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add photos")
        }
    }
}

@Composable
private fun PhotoThumb(item: ImageItem, onRemove: (Long) -> Unit) {
    Box(Modifier.size(96.dp).clip(RoundedCornerShape(8.dp))) {
        when (val s = item.status) {
            is ImageItem.Status.Done -> AsyncImage(
                model = s.url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            is ImageItem.Status.Uploading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            is ImageItem.Status.Failed -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Failed",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                )
            }
        }
        // Remove button — always available, so a failed/pending upload can be cleared
        // (which unblocks publish).
        IconButton(
            onClick = { onRemove(item.id) },
            modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove photo",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
