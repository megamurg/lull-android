package com.lull.app.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lull.app.model.AppModel
import com.lull.app.model.Format
import com.lull.app.model.Phase
import com.lull.app.model.Schedule
import com.lull.app.model.SideModel
import com.lull.app.model.defaultTemperature
import com.lull.app.model.maxMinutes
import com.lull.app.model.title
import com.lull.bedjetkit.Limits
import com.lull.bedjetkit.Mode
import kotlinx.coroutines.launch
import java.text.BreakIterator
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen editor for one schedule (Swift's `.fullScreenCover`). Nothing is saved until Save or Use Tonight;
 * Cancel, or the system back gesture, drops the changes. The library is shared; [side] is the side selected when the
 * editor was opened, which Use Tonight applies to and whose kind of BedJet the phases are edited for.
 */
@Composable
fun ScheduleEditor(model: AppModel, side: SideModel, schedule: Schedule, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        EditorContent(model = model, side = side, initial = schedule, onDismiss = onDismiss)
    }
}

@OptIn(ExperimentalMaterial3Api::class) // ModalBottomSheet (PhaseEditor)
@Composable
private fun EditorContent(model: AppModel, side: SideModel, initial: Schedule, onDismiss: () -> Unit) {
    var schedule by remember { mutableStateOf(initial) }
    var selection by remember { mutableStateOf<String?>(null) }
    var showsBedtimePicker by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    val degrees = rememberDegrees(model)
    val adjustsTemperature = rememberAdjustsTemperature(side)
    val isValid = schedule.name.isNotBlank() && schedule.phases.isNotEmpty()

    // `.scrollDismissesKeyboard(.immediately)`
    LaunchedEffect(scrollState.isScrollInProgress) {
        if (scrollState.isScrollInProgress) focusManager.clearFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LullColors.background)
            // Clear of the system bars and, while typing the name, the keyboard (zero where the dialog window
            // already fits inside the system bars).
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Toolbar: Cancel (cancellation action) and Save (confirmation action).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarButton("Cancel", onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                ToolbarButton("Save", bold = true, enabled = isValid) {
                    model.save(schedule)
                    onDismiss()
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                NameField(
                    name = schedule.name,
                    onNameChange = { name ->
                        // The BedJet stores 15 characters.
                        schedule = schedule.copy(name = name.prefixCharacters(Limits.NAME_LENGTH))
                    },
                    onDone = { focusManager.clearFocus() },
                )

                Card {
                    NightTimeline(schedule = schedule, selectionId = selection, height = 170.dp)
                }

                BedtimeRow(schedule = schedule, onClick = { showsBedtimePicker = true })

                Eyebrow("Phases")
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    schedule.phases.forEach { phase ->
                        PhaseRow(
                            phase = phase,
                            startsAt = schedule.startMinute(phase),
                            detail = phaseDetail(phase, adjustsTemperature, degrees),
                            onClick = { selection = phase.id },
                        )
                    }
                }
                if (schedule.phases.size < Limits.STEPS_PER_PROGRAM) {
                    PrimaryButton(
                        text = "Add Phase",
                        onClick = {
                            val phase = Phase.of(Mode.COOL, fan = 30, minutes = 60)
                            schedule = schedule.copy(phases = schedule.phases + phase)
                            selection = phase.id
                        },
                        prominent = false,
                        icon = Icons.Filled.Add,
                    )
                }
            }
        }

        // `.safeAreaInset(edge: .bottom)`: pinned over the scrolling content, on a translucent bar.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(LullColors.surface.copy(alpha = 0.94f))
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            PrimaryButton(
                text = "Use Tonight",
                onClick = {
                    model.save(schedule)
                    side.setTonight(schedule.id)
                    onDismiss()
                },
                enabled = isValid,
            )
        }
    }

    if (showsBedtimePicker) {
        BedtimePicker(
            minuteOfDay = schedule.bedtime,
            onConfirm = { minute ->
                schedule = schedule.copy(bedtime = minute)
                showsBedtimePicker = false
            },
            onDismiss = { showsBedtimePicker = false },
        )
    }

    val selected = schedule.phases.firstOrNull { it.id == selection }
    if (selected != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val sheetScope = rememberCoroutineScope()
        fun close(then: () -> Unit = {}) {
            sheetScope.launch { sheetState.hide() }.invokeOnCompletion {
                then()
                selection = null
            }
        }
        ModalBottomSheet(
            onDismissRequest = { selection = null },
            sheetState = sheetState,
            containerColor = LullColors.surface,
            contentColor = Color.White,
            dragHandle = null,
        ) {
            PhaseEditor(
                model = model,
                side = side,
                phase = selected,
                onChange = { updated ->
                    schedule = schedule.copy(phases = schedule.phases.map { if (it.id == updated.id) updated else it })
                },
                canDelete = schedule.phases.size > 1,
                onDelete = {
                    val id = selected.id
                    close { schedule = schedule.copy(phases = schedule.phases.filter { it.id != id }) }
                },
                onDone = { close() },
            )
        }
    }
}

/**
 * Swift's `String.prefix(_:)`: whole characters, so an emoji or accented letter is never cut in half.
 * Also used for side labels in the BedJet sheet.
 */
internal fun String.prefixCharacters(count: Int): String {
    val breaks = BreakIterator.getCharacterInstance()
    breaks.setText(this)
    var end = 0
    repeat(count) {
        val next = breaks.next()
        if (next == BreakIterator.DONE) return this
        end = next
    }
    return substring(0, end)
}

@Composable
private fun ToolbarButton(title: String, bold: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(LullColors.raised)
            .border(1.dp, LullColors.hairline, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, fontSize = 17.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun NameField(name: String, onNameChange: (String) -> Unit, onDone: () -> Unit) {
    val style = TextStyle(color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    BasicTextField(
        value = name,
        onValueChange = onNameChange,
        modifier = Modifier.fillMaxWidth(),
        textStyle = style,
        singleLine = true,
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        decorationBox = { field ->
            Box {
                if (name.isEmpty()) Text("Name", style = style.copy(color = LullColors.tertiary))
                field()
            }
        },
    )
}

@Composable
private fun BedtimeRow(schedule: Schedule, onClick: () -> Unit) {
    Card(padding = 16.dp) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Usual bedtime", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Wake around ${Format.clock(schedule.bedtime + schedule.totalMinutes)}",
                    color = LullColors.secondary,
                    fontSize = 13.sp,
                )
            }
            // The compact DatePicker's time chip.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(LullColors.raised)
                    .clickable(role = Role.Button, onClickLabel = "Change usual bedtime", onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(Format.clock(schedule.bedtime), fontSize = 17.sp, style = MonospacedDigits)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class) // TimePicker, rememberTimePickerState
@Composable
private fun BedtimePicker(minuteOfDay: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = minuteOfDay / 60,
        initialMinute = minuteOfDay % 60,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    // Material 3 1.3 has no TimePickerDialog, so the picker sits in an AlertDialog.
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                Text("OK", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Usual bedtime") },
        text = {
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors( // REVIEW: parameter names as in material3 1.2/1.3 TimePickerDefaults.colors
                    clockDialColor = LullColors.surface,
                    selectorColor = Color.White,
                    containerColor = LullColors.raised,
                    periodSelectorBorderColor = LullColors.hairline,
                    clockDialSelectedContentColor = Color.Black,
                    clockDialUnselectedContentColor = Color.White,
                    periodSelectorSelectedContainerColor = Color.White,
                    periodSelectorUnselectedContainerColor = LullColors.surface,
                    periodSelectorSelectedContentColor = Color.Black,
                    periodSelectorUnselectedContentColor = LullColors.secondary,
                    timeSelectorSelectedContainerColor = Color.White,
                    timeSelectorUnselectedContainerColor = LullColors.surface,
                    timeSelectorSelectedContentColor = Color.Black,
                    timeSelectorUnselectedContentColor = Color.White,
                ),
            )
        },
        containerColor = LullColors.raised,
        titleContentColor = Color.White,
        textContentColor = Color.White,
    )
}

private fun phaseDetail(phase: Phase, adjustsTemperature: (Mode) -> Boolean, degrees: (Int) -> String): String {
    val parts = mutableListOf<String>()
    if (adjustsTemperature(phase.mode)) parts.add(degrees(phase.temperature))
    if (phase.mode.usesFan) parts.add("Fan ${phase.fanPercent}%")
    return if (parts.isEmpty()) "Air off" else parts.joinToString(" · ")
}

private val PhaseRowShape = RoundedCornerShape(22.dp)

@Composable
private fun PhaseRow(phase: Phase, startsAt: Int, detail: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PhaseRowShape)
            .background(LullColors.surface)
            .border(1.dp, LullColors.hairline, PhaseRowShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(phase.mode.tint.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(phase.mode.icon, contentDescription = null, tint = phase.mode.tint, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(phase.mode.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = LullColors.secondary, fontSize = 13.sp)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(Format.duration(phase.minutes), fontSize = 15.sp, fontWeight = FontWeight.Medium, style = MonospacedDigits)
            Text(Format.clock(startsAt), color = LullColors.secondary, fontSize = 13.sp, style = MonospacedDigits)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = LullColors.tertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** The bottom sheet for one phase: its mode, temperature, fan and how long it lasts. */
@Composable
private fun PhaseEditor(
    model: AppModel,
    side: SideModel,
    phase: Phase,
    onChange: (Phase) -> Unit,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    val generation by side.generation.collectAsState()
    val modes = remember(side, generation) { side.phaseModes }
    val adjustsTemperature = rememberAdjustsTemperature(side)
    val isHeatLimited = phase.maxMinutes < phase.mode.maxMinutes

    /** Every edit keeps the phase within what the BedJet allows for its mode, temperature and fan. */
    fun update(changed: Phase) {
        onChange(changed.copy(minutes = min(changed.minutes, changed.maxMinutes)))
    }

    // `.onChange(of: phase.maxMinutes, initial: true)`: a phase saved before a limit applied is trimmed on opening.
    LaunchedEffect(phase.id, phase.maxMinutes) {
        if (phase.minutes > phase.maxMinutes) onChange(phase.copy(minutes = phase.maxMinutes))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Phase", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "Done",
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onDone)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        ModePicker(
            modes = modes,
            selection = phase.mode,
            onSelect = { mode ->
                val temperature = if (phase.temperature in mode.temperatureRange) phase.temperature else mode.defaultTemperature
                update(phase.copy(mode = mode, temperature = temperature))
            },
        )
        if (adjustsTemperature(phase.mode)) {
            TemperatureSlider(
                model = model,
                mode = phase.mode,
                temperature = phase.temperature,
                onChange = { update(phase.copy(temperature = it)) },
            )
        }
        if (phase.mode.usesFan) {
            LabeledSlider(
                title = "Fan",
                valueText = "${phase.fanPercent}%",
                value = phase.fanPercent.toFloat(),
                onValueChange = { update(phase.copy(fanPercent = it.toInt())) },
                range = 5f..100f,
                step = 5f,
                tint = phase.mode.tint,
            )
        }
        LabeledSlider(
            title = "Duration",
            valueText = Format.duration(phase.minutes),
            value = phase.minutes.toFloat(),
            onValueChange = { update(phase.copy(minutes = it.toInt())) },
            range = 5f..max(phase.maxMinutes / 5 * 5, 5).toFloat(),
            step = 5f,
            tint = Color.White,
        )
        if (isHeatLimited) {
            Text(
                "At this temperature and fan, BedJet heats for up to ${Format.duration(phase.maxMinutes)} before its safety cut-off." +
                    if (generation.supports(Mode.EXTENDED_HEAT)) " Use Gentle heat to run longer." else "",
                color = LullColors.secondary,
                fontSize = 13.sp,
            )
        }
        if (canDelete) {
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Remove Phase",
                    color = LullColors.destructive,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
