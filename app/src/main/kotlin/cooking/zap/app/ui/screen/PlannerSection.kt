package cooking.zap.app.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import cooking.zap.app.R
import cooking.zap.app.mealplan.PlannerLogic
import cooking.zap.app.mealplan.PlannerMutations
import cooking.zap.app.mealplan.PlannerWeekState
import cooking.zap.app.mealplan.Schema
import cooking.zap.app.mealplan.Week
import cooking.zap.app.nostr.RecipeFormats
import cooking.zap.app.repo.RecipeRepository
import cooking.zap.app.ui.component.PlannerNotesDialog
import cooking.zap.app.ui.component.SlotEditorDialog
import cooking.zap.app.viewmodel.PlannerViewModel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_DATE_FMT = DateTimeFormatter.ofPattern("MMM d", Locale.US)

/**
 * The Planner section of the My Kitchen hub (arc PR 8) — the whole week view
 * in-place (web `/my-kitchen/planner` is a single page, not a section→detail
 * split like grocery). Header with the week display range + prev/next + today;
 * vertically stacked day cards (today marked and scrolled into view), each with
 * the four meal slots; week + day notes.
 *
 * TEXT-ONLY slot editing this PR — recipe entries still RENDER (title +
 * thumbnail, resolved from the recipe cache with the payload's denormalized
 * title as the offline fallback), but adding a recipe is the next PR. See
 * [SlotEditorDialog] for the recipe-picker seam.
 *
 * The four sealed [PlannerWeekState]s render distinctly (acceptance criteria):
 * Loading, Empty (banner + editable grid), Loaded (grid; read-only variant
 * shows a banner and disables editing), DecryptFailed (never shown as empty).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerSection(
    viewModel: PlannerViewModel,
    recipeRepo: RecipeRepository,
    modifier: Modifier = Modifier,
    onDebugLongPress: (() -> Unit)? = null,
) {
    val weeks by viewModel.weeks.collectAsState()
    val currentWeekId by viewModel.currentWeekId.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val canWrite by viewModel.canWrite.collectAsState()

    val state = weeks[currentWeekId]
    val plan = PlannerLogic.planOf(state)
    val editable = canWrite && PlannerLogic.canMutate(state)
    val isCurrentWeek = currentWeekId == Week.currentWeekId()

    // Editing targets survive rotation / process death; the in-progress text
    // lives in the dialogs (rememberSaveable there).
    var slotTarget by rememberSaveable { mutableStateOf<String?>(null) } // "day|slot"
    var notesTarget by rememberSaveable { mutableStateOf<String?>(null) } // "week" or "day|mon"

    Column(modifier.fillMaxSize()) {
        PlannerHeader(
            weekId = currentWeekId,
            saving = saving,
            showToday = !isCurrentWeek,
            onPrev = { viewModel.prevWeek() },
            onNext = { viewModel.nextWeek() },
            onToday = { viewModel.goToCurrentWeek() },
            onDebugLongPress = onDebugLongPress,
        )

        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state == null || state is PlannerWeekState.Loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
                    }

                state is PlannerWeekState.DecryptFailed ->
                    DecryptFailedBody(onRetry = { viewModel.refresh() })

                plan != null ->
                    WeekBody(
                        weekId = currentWeekId,
                        plan = plan,
                        isEmpty = state is PlannerWeekState.Empty,
                        readOnly = state is PlannerWeekState.Loaded && state.readOnly,
                        editable = editable,
                        isCurrentWeek = isCurrentWeek,
                        recipeRepo = recipeRepo,
                        onSlotTap = { day, slot -> if (editable) slotTarget = "$day|$slot" },
                        onWeekNotesTap = { if (editable) notesTarget = "week" },
                        onDayNotesTap = { day -> if (editable) notesTarget = "day|$day" },
                    )
            }
        }
    }

    // ---- dialogs ----
    slotTarget?.let { target ->
        val (day, slot) = target.split("|").let { it[0] to it[1] }
        val entry = plan?.slot(day, slot)
        val existingText = entry?.takeIf { it.stringField("type") == "text" }?.stringField("text")
        val recipeTitle = entry?.takeIf { it.stringField("type") == "recipe" }?.stringField("title")
        SlotEditorDialog(
            dayLabel = dayLabel(day),
            slotLabel = slotLabel(slot),
            existingText = existingText,
            existingRecipeTitle = recipeTitle,
            onSaveText = { text ->
                slotTarget = null
                if (text.isNotEmpty()) viewModel.setSlot(currentWeekId, day, slot, PlannerMutations.textSlot(text))
                else viewModel.clearSlot(currentWeekId, day, slot)
            },
            onClear = {
                slotTarget = null
                viewModel.clearSlot(currentWeekId, day, slot)
            },
            onDismiss = { slotTarget = null },
        )
    }

    notesTarget?.let { target ->
        if (target == "week") {
            PlannerNotesDialog(
                title = stringResource(R.string.planner_week_notes),
                hint = stringResource(R.string.planner_week_notes_hint_long),
                existing = plan?.notes,
                onSave = { notesTarget = null; viewModel.setWeekNotes(currentWeekId, it) },
                onDismiss = { notesTarget = null },
            )
        } else {
            val day = target.removePrefix("day|")
            PlannerNotesDialog(
                title = stringResource(R.string.planner_day_notes_title, dayLabel(day)),
                hint = stringResource(R.string.planner_day_notes_hint),
                existing = plan?.day(day)?.stringField("notes"),
                onSave = { notesTarget = null; viewModel.setDayNotes(currentWeekId, day, it) },
                onDismiss = { notesTarget = null },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlannerHeader(
    weekId: String,
    saving: Boolean,
    showToday: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onDebugLongPress: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.planner_prev_week),
                )
            }
            Text(
                text = Week.weekDisplayRange(weekId),
                style = MaterialTheme.typography.titleMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (onDebugLongPress != null) {
                            Modifier.combinedClickable(onClick = {}, onLongClick = onDebugLongPress)
                        } else Modifier,
                    ),
            )
            IconButton(onClick = onNext) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.planner_next_week),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showToday) {
                TextButton(onClick = onToday) {
                    Icon(Icons.Outlined.CalendarMonth, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.planner_today))
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }
            if (saving) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.planner_saving),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DecryptFailedBody(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text("🔒", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.planner_decrypt_failed_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.planner_decrypt_failed_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.planner_retry)) }
        }
    }
}

@Composable
private fun WeekBody(
    weekId: String,
    plan: Schema.MealPlan,
    isEmpty: Boolean,
    readOnly: Boolean,
    editable: Boolean,
    isCurrentWeek: Boolean,
    recipeRepo: RecipeRepository,
    onSlotTap: (day: String, slot: String) -> Unit,
    onWeekNotesTap: () -> Unit,
    onDayNotesTap: (day: String) -> Unit,
) {
    val monday = remember(weekId) { Week.mondayOfWeek(weekId) }
    val todayKey = remember(isCurrentWeek) {
        if (isCurrentWeek) Schema.DAY_KEYS[LocalDate.now().dayOfWeek.value - 1] else null
    }
    val listState = rememberLazyListState()
    // Current week: scroll today's card into view. Any other week: reset to the
    // top (the header/empty banner) — the shared list state would otherwise
    // carry today's offset from the current week into the week you navigate to.
    LaunchedEffect(weekId, todayKey) {
        if (todayKey != null) {
            val idx = 1 + Schema.DAY_KEYS.indexOf(todayKey) // item 0 = header block
            listState.animateScrollToItem(idx.coerceAtLeast(0))
        } else {
            listState.scrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            Column {
                if (readOnly) ReadOnlyBanner()
                if (isEmpty) EmptyBanner()
                WeekNotesRow(notes = plan.notes, editable = editable, onTap = onWeekNotesTap)
            }
        }
        Schema.DAY_KEYS.forEachIndexed { i, dayKey ->
            item(key = dayKey) {
                DayCard(
                    dayKey = dayKey,
                    dateLabel = monday.plusDays(i.toLong()).format(DAY_DATE_FMT),
                    isToday = dayKey == todayKey,
                    plan = plan,
                    editable = editable,
                    readOnly = readOnly,
                    recipeRepo = recipeRepo,
                    onSlotTap = onSlotTap,
                    onDayNotesTap = onDayNotesTap,
                )
            }
        }
    }
}

@Composable
private fun ReadOnlyBanner() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text(
            "🔒 " + stringResource(R.string.planner_read_only_banner),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyBanner() {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("📅", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.planner_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.planner_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeekNotesRow(notes: String?, editable: Boolean, onTap: () -> Unit) {
    val text = notes?.takeIf { it.isNotBlank() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (editable) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📝", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(6.dp))
        Text(
            text = text ?: stringResource(R.string.planner_week_notes_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = if (text != null) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DayCard(
    dayKey: String,
    dateLabel: String,
    isToday: Boolean,
    plan: Schema.MealPlan,
    editable: Boolean,
    readOnly: Boolean,
    recipeRepo: RecipeRepository,
    onSlotTap: (day: String, slot: String) -> Unit,
    onDayNotesTap: (day: String) -> Unit,
) {
    val cardModifier = Modifier
        .fillMaxWidth()
        .then(
            if (isToday) Modifier.border(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp),
            ) else Modifier,
        )
    Card(modifier = cardModifier, colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dayLabel(dayKey), style = MaterialTheme.typography.titleSmall)
                if (isToday) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.planner_today_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    dateLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Schema.SLOT_KEYS.forEach { slotKey ->
                SlotRow(
                    entry = plan.slot(dayKey, slotKey),
                    slotKey = slotKey,
                    editable = editable,
                    readOnly = readOnly,
                    recipeRepo = recipeRepo,
                    onTap = { onSlotTap(dayKey, slotKey) },
                )
            }
            val dayNotes = plan.day(dayKey)?.stringField("notes")
            if (dayNotes != null || editable) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = dayNotes ?: stringResource(R.string.planner_day_note_add),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .then(if (editable) Modifier.clickable { onDayNotesTap(dayKey) } else Modifier)
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SlotRow(
    entry: JsonObject?,
    slotKey: String,
    editable: Boolean,
    readOnly: Boolean,
    recipeRepo: RecipeRepository,
    onTap: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .then(if (editable) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = slotLabel(slotKey).uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Spacer(Modifier.width(8.dp))
        when (entry?.stringField("type")) {
            "recipe" -> RecipeSlotContent(
                coordinate = entry.stringField("a"),
                denormTitle = entry.stringField("title"),
                recipeRepo = recipeRepo,
            )
            "text" -> Text(
                text = entry.stringField("text").orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            else -> {
                if (readOnly) {
                    Text(
                        stringResource(R.string.planner_slot_readonly_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.planner_slot_add),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * A recipe slot — resolves the coordinate to a live title + thumbnail
 * (cache-first, then network), falling back to the payload's denormalized title
 * so the grid still renders offline / before resolution.
 */
@Composable
private fun RecipeSlotContent(
    coordinate: String?,
    denormTitle: String?,
    recipeRepo: RecipeRepository,
) {
    var resolvedTitle by remember(coordinate) { mutableStateOf<String?>(null) }
    var resolvedImage by remember(coordinate) { mutableStateOf<String?>(null) }
    LaunchedEffect(coordinate) {
        val parts = coordinate?.split(":", limit = 3)
        if (parts == null || parts.size < 3 || parts[0] != "30023") return@LaunchedEffect
        val (_, author, dTag) = parts
        val event = recipeRepo.findRecipeEventByCoordinate(30023, author, dTag)
            ?: recipeRepo.requestRecipeEventByCoordinate(30023, author, dTag)
        val recipe = event?.let { RecipeFormats.forEvent(it)?.parse(it) }
        resolvedTitle = recipe?.title
        resolvedImage = recipe?.image
    }
    val title = resolvedTitle ?: denormTitle ?: stringResource(R.string.planner_slot_recipe_fallback)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (resolvedImage != null) {
            SubcomposeAsyncImage(
                model = resolvedImage,
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp)),
                loading = {},
                error = {},
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- helpers ----

private fun JsonObject.stringField(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

@Composable
private fun dayLabel(dayKey: String): String = stringResource(
    when (dayKey) {
        "mon" -> R.string.planner_day_mon
        "tue" -> R.string.planner_day_tue
        "wed" -> R.string.planner_day_wed
        "thu" -> R.string.planner_day_thu
        "fri" -> R.string.planner_day_fri
        "sat" -> R.string.planner_day_sat
        else -> R.string.planner_day_sun
    },
)

@Composable
private fun slotLabel(slotKey: String): String = stringResource(
    when (slotKey) {
        "breakfast" -> R.string.planner_meal_breakfast
        "lunch" -> R.string.planner_meal_lunch
        "dinner" -> R.string.planner_meal_dinner
        else -> R.string.planner_meal_snack
    },
)
