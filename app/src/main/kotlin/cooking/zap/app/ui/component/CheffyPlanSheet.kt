package cooking.zap.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cooking.zap.app.R
import cooking.zap.app.cheffy.Cheffy
import cooking.zap.app.mealplan.MealPlanGeneration
import cooking.zap.app.mealplan.Schema
import cooking.zap.app.viewmodel.CheffyPlanViewModel

/**
 * Plan with Cheffy sheet — [NoteReviewSheet] scaffold, phase body from
 * [CheffyPlanViewModel]. Header stays stable across phases.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CheffyPlanSheet(
    state: CheffyPlanViewModel.UiState,
    onDismiss: () -> Unit,
    onToggleSlot: (String) -> Unit,
    onToggleDay: (String) -> Unit,
    onToggleStyle: (MealPlanGeneration.PreferenceStyleId) -> Unit,
    onMaxMinutes: (String) -> Unit,
    onServings: (String) -> Unit,
    onExclude: (String) -> Unit,
    onNotes: (String) -> Unit,
    onSource: (MealPlanGeneration.RecipeSource) -> Unit,
    onStrategy: (MealPlanGeneration.MealPlanStrategy) -> Unit,
    onGenerate: () -> Unit,
    onApply: () -> Unit,
    onRemove: (MealPlanGeneration.GeneratedMeal) -> Unit,
    onView: (MealPlanGeneration.GeneratedMeal) -> Unit,
    onBackToForm: () -> Unit,
    onTryAgain: () -> Unit,
    onViewMembership: (() -> Unit)?,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val maxSheetHeight = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight)
                    .imePadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val busy = state.phase == CheffyPlanViewModel.Phase.SIGNING ||
                    state.phase == CheffyPlanViewModel.Phase.WORKING
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CheffyIcon(
                        size = 28.dp,
                        expression = when (state.phase) {
                            CheffyPlanViewModel.Phase.WORKING -> Cheffy.Expression.COOKING
                            CheffyPlanViewModel.Phase.SIGNING -> Cheffy.Expression.THINKING
                            CheffyPlanViewModel.Phase.ERROR -> Cheffy.Expression.CONCERNED
                            else -> Cheffy.Expression.HAPPY
                        },
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.cheffy_plan_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                if (state.phase == CheffyPlanViewModel.Phase.PREVIEW) {
                    PreviewBody(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        state = state,
                        onRemove = onRemove,
                        onView = onView,
                        onBackToForm = onBackToForm,
                    )
                    Button(
                        onClick = onApply,
                        enabled = state.meals.isNotEmpty() && !state.applying,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.cheffy_plan_apply)) }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        when (state.phase) {
                            CheffyPlanViewModel.Phase.FORM -> FormContent(
                                state = state,
                                onToggleSlot = onToggleSlot,
                                onToggleDay = onToggleDay,
                                onToggleStyle = onToggleStyle,
                                onMaxMinutes = onMaxMinutes,
                                onServings = onServings,
                                onExclude = onExclude,
                                onNotes = onNotes,
                                onSource = onSource,
                                onStrategy = onStrategy,
                            )
                            CheffyPlanViewModel.Phase.SIGNING -> WaitBlock(
                                expression = Cheffy.Expression.THINKING,
                                line = stringResource(R.string.cheffy_plan_signing_line),
                                sub = stringResource(R.string.cheffy_plan_signing_sub),
                            )
                            CheffyPlanViewModel.Phase.WORKING -> WaitBlock(
                                expression = Cheffy.Expression.COOKING,
                                line = state.thinkingLine,
                                sub = stringResource(R.string.cheffy_plan_working_sub),
                            )
                            CheffyPlanViewModel.Phase.UPSELL -> UpsellBlock(
                                message = state.message,
                                onViewMembership = onViewMembership,
                            )
                            CheffyPlanViewModel.Phase.READ_ONLY -> Text(
                                stringResource(R.string.cheffy_plan_readonly_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            CheffyPlanViewModel.Phase.SIGN_IN -> SignInBlock()
                            CheffyPlanViewModel.Phase.ERROR -> WaitBlock(
                                expression = Cheffy.Expression.CONCERNED,
                                line = state.message,
                            )
                            CheffyPlanViewModel.Phase.PREVIEW -> Unit
                        }
                    }
                    when (state.phase) {
                        CheffyPlanViewModel.Phase.FORM -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            ) {
                                TextButton(onClick = onDismiss) {
                                    Text(stringResource(R.string.cheffy_plan_cancel))
                                }
                                Button(onClick = onGenerate, enabled = state.canSubmit && !busy) {
                                    Text(stringResource(R.string.cheffy_plan_submit))
                                }
                            }
                        }
                        CheffyPlanViewModel.Phase.ERROR -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            ) {
                                OutlinedButton(onClick = onTryAgain) {
                                    Text(stringResource(R.string.cheffy_plan_try_again))
                                }
                                TextButton(onClick = onBackToForm) {
                                    Text(stringResource(R.string.cheffy_plan_back))
                                }
                            }
                        }
                        CheffyPlanViewModel.Phase.SIGN_IN,
                        CheffyPlanViewModel.Phase.READ_ONLY,
                        CheffyPlanViewModel.Phase.UPSELL,
                        -> {
                            TextButton(
                                onClick = onDismiss,
                                modifier = Modifier.align(Alignment.End),
                            ) { Text(stringResource(R.string.cheffy_plan_close)) }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun WaitBlock(expression: Cheffy.Expression, line: String, sub: String? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CheffyIcon(size = 72.dp, expression = expression)
        Text(line, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        if (!sub.isNullOrBlank()) {
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SignInBlock() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CheffyIcon(size = 72.dp, expression = Cheffy.Expression.NEUTRAL)
        Text(stringResource(R.string.cheffy_plan_signin_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.cheffy_plan_signin_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun UpsellBlock(message: String, onViewMembership: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CheffyIcon(size = 64.dp, expression = Cheffy.Expression.NEUTRAL)
        Text(
            stringResource(R.string.cheffy_plan_upsell_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            message.ifBlank { stringResource(R.string.cheffy_plan_upsell_body) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onViewMembership != null) {
            Button(onClick = onViewMembership) {
                Text(stringResource(R.string.cheffy_plan_view_membership))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormContent(
    state: CheffyPlanViewModel.UiState,
    onToggleSlot: (String) -> Unit,
    onToggleDay: (String) -> Unit,
    onToggleStyle: (MealPlanGeneration.PreferenceStyleId) -> Unit,
    onMaxMinutes: (String) -> Unit,
    onServings: (String) -> Unit,
    onExclude: (String) -> Unit,
    onNotes: (String) -> Unit,
    onSource: (MealPlanGeneration.RecipeSource) -> Unit,
    onStrategy: (MealPlanGeneration.MealPlanStrategy) -> Unit,
) {
    if (state.message.isNotBlank()) {
        Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    Text(stringResource(R.string.cheffy_plan_meals_legend), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Schema.SLOT_KEYS.forEach { slot ->
            FilterChip(
                selected = slot in state.mealSlots,
                onClick = { onToggleSlot(slot) },
                label = { Text(slotLabel(slot)) },
            )
        }
    }

    Text(stringResource(R.string.cheffy_plan_days_legend), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Schema.DAY_KEYS.forEach { day ->
            FilterChip(
                selected = day in state.days,
                onClick = { onToggleDay(day) },
                label = { Text(dayChipShortLabel(day)) },
            )
        }
    }

    Text(stringResource(R.string.cheffy_plan_styles_legend), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.cheffy_plan_styles_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MealPlanGeneration.PREFERENCE_STYLES.forEach { style ->
            FilterChip(
                selected = style.id in state.styles,
                onClick = { onToggleStyle(style.id) },
                label = { Text(style.label) },
            )
        }
    }

    OutlinedTextField(
        value = state.maxMinutesText,
        onValueChange = onMaxMinutes,
        label = { Text(stringResource(R.string.cheffy_plan_max_minutes)) },
        placeholder = { Text(stringResource(R.string.cheffy_plan_max_minutes_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.servingsText,
        onValueChange = onServings,
        label = { Text(stringResource(R.string.cheffy_plan_servings)) },
        placeholder = { Text(stringResource(R.string.cheffy_plan_servings_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.excludeText,
        onValueChange = onExclude,
        label = { Text(stringResource(R.string.cheffy_plan_exclude)) },
        placeholder = { Text(stringResource(R.string.cheffy_plan_exclude_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.notes,
        onValueChange = onNotes,
        label = { Text(stringResource(R.string.cheffy_plan_notes)) },
        placeholder = { Text(stringResource(R.string.cheffy_plan_notes_hint)) },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )

    Text(stringResource(R.string.cheffy_plan_source_legend), style = MaterialTheme.typography.titleSmall)
    sourceOptions().forEach { (source, labelRes) ->
        RadioRow(
            selected = state.source == source,
            onClick = { onSource(source) },
            label = stringResource(labelRes),
        )
    }

    Text(stringResource(R.string.cheffy_plan_existing_legend), style = MaterialTheme.typography.titleSmall)
    RadioRow(
        selected = state.strategy == MealPlanGeneration.MealPlanStrategy.FILL_EMPTY,
        onClick = { onStrategy(MealPlanGeneration.MealPlanStrategy.FILL_EMPTY) },
        label = stringResource(R.string.cheffy_plan_fill_empty),
        hint = stringResource(R.string.cheffy_plan_fill_empty_hint),
    )
    RadioRow(
        selected = state.strategy == MealPlanGeneration.MealPlanStrategy.REPLACE_SELECTED,
        onClick = { onStrategy(MealPlanGeneration.MealPlanStrategy.REPLACE_SELECTED) },
        label = stringResource(R.string.cheffy_plan_replace),
        hint = stringResource(R.string.cheffy_plan_replace_hint),
    )
}

@Composable
private fun PreviewBody(
    modifier: Modifier,
    state: CheffyPlanViewModel.UiState,
    onRemove: (MealPlanGeneration.GeneratedMeal) -> Unit,
    onView: (MealPlanGeneration.GeneratedMeal) -> Unit,
    onBackToForm: () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.coverageNote.isNullOrBlank()) {
            Text(
                state.coverageNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.message.isNotBlank()) {
            Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (state.meals.isEmpty()) {
            Text(
                stringResource(R.string.cheffy_plan_preview_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.meals, key = { MealPlanGeneration.slotKey(it.day, it.slot) }) { meal ->
                    MealCard(meal = meal, onView = { onView(meal) }, onRemove = { onRemove(meal) })
                }
            }
        }
        TextButton(onClick = onBackToForm) {
            Text(stringResource(R.string.cheffy_plan_edit_prefs))
        }
    }
}

@Composable
private fun MealCard(
    meal: MealPlanGeneration.GeneratedMeal,
    onView: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AsyncImage(
            model = meal.image,
            contentDescription = stringResource(R.string.cheffy_plan_meal_thumb),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                "${dayFullLabel(meal.day)} · ${slotLabel(meal.slot)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                meal.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            meal.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row {
                TextButton(onClick = onView) { Text(stringResource(R.string.cheffy_plan_view)) }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.cheffy_plan_remove)) }
            }
        }
    }
}

@Composable
private fun RadioRow(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    hint: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun slotLabel(slot: String): String = stringResource(
    when (slot) {
        "breakfast" -> R.string.planner_meal_breakfast
        "lunch" -> R.string.planner_meal_lunch
        "dinner" -> R.string.planner_meal_dinner
        "snack" -> R.string.planner_meal_snack
        else -> R.string.planner_meal_dinner
    },
)

@Composable
private fun dayChipShortLabel(day: String): String = stringResource(
    when (day) {
        "mon" -> R.string.cheffy_plan_chip_mon
        "tue" -> R.string.cheffy_plan_chip_tue
        "wed" -> R.string.cheffy_plan_chip_wed
        "thu" -> R.string.cheffy_plan_chip_thu
        "fri" -> R.string.cheffy_plan_chip_fri
        "sat" -> R.string.cheffy_plan_chip_sat
        "sun" -> R.string.cheffy_plan_chip_sun
        else -> R.string.cheffy_plan_chip_mon
    },
)

@Composable
private fun dayFullLabel(day: String): String = stringResource(
    when (day) {
        "mon" -> R.string.planner_day_mon
        "tue" -> R.string.planner_day_tue
        "wed" -> R.string.planner_day_wed
        "thu" -> R.string.planner_day_thu
        "fri" -> R.string.planner_day_fri
        "sat" -> R.string.planner_day_sat
        "sun" -> R.string.planner_day_sun
        else -> R.string.planner_day_mon
    },
)

private fun sourceOptions() = listOf(
    MealPlanGeneration.RecipeSource.ALL to R.string.cheffy_plan_source_all,
    MealPlanGeneration.RecipeSource.MY_RECIPES to R.string.cheffy_plan_source_my,
    MealPlanGeneration.RecipeSource.SAVED to R.string.cheffy_plan_source_saved,
    MealPlanGeneration.RecipeSource.EXPLORE to R.string.cheffy_plan_source_explore,
)
