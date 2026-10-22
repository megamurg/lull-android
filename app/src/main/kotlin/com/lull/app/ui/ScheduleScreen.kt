package com.lull.app.ui

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lull.app.model.ActiveRun
import com.lull.app.model.AppModel
import com.lull.app.model.Format
import com.lull.app.model.Phase
import com.lull.app.model.Schedule
import com.lull.app.model.SideModel
import com.lull.app.model.activity
import com.lull.bedjetkit.Codec
import com.lull.bedjetkit.LinkState
import com.lull.bedjetkit.Mode
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.max

/** What the large card at the top shows. Keyed so the card fades only when its kind or schedule changes. */
private sealed interface Featured {
    val key: String

    data class Running(val run: ActiveRun) : Featured {
        override val key: String get() = "running:${run.schedule.id}"
    }

    data class Elsewhere(val step: Int) : Featured {
        override val key: String get() = "elsewhere"
    }

    data class Planned(val schedule: Schedule) : Featured {
        override val key: String get() = "planned:${schedule.id}"
    }

    data object Empty : Featured {
        override val key: String get() = "empty"
    }
}

/** Plan the night and run it: tonight's schedule on top, the rest of the library below. */
@Composable
fun ScheduleScreen(model: AppModel) {
    val side by model.selected.collectAsState()
    val schedules by model.schedules.collectAsState()
    /** The schedule open in the editor, with the side selected when it was opened, which Use Tonight applies to. */
    var editing by remember { mutableStateOf<Pair<Schedule, SideModel>?>(null) }
    fun edit(schedule: Schedule) {
        editing = schedule to model.selected.value
    }

    Screen(title = "Schedule", subtitle = todayTitle(), model = model) {
        // Keyed so nothing collected or remembered for one side is shown for the other, not even for a frame.
        key(side.index) {
            SidePlan(model = model, side = side, schedules = schedules, onEdit = { schedule -> edit(schedule) })
        }
        PrimaryButton(
            text = "New Schedule",
            onClick = {
                edit(Schedule(name = "New schedule", phases = listOf(Phase.of(Mode.COOL, fan = 40, minutes = 240))))
            },
            prominent = false,
            icon = Icons.Filled.Add,
        )
    }

    // `.fullScreenCover(item: $editing)`. Kept in memory only: a Schedule isn't Parcelable, so after the
    // activity is recreated (rotation) the editor closes, as if cancelled.
    editing?.let { (schedule, editingSide) ->
        ScheduleEditor(model = model, side = editingSide, schedule = schedule, onDismiss = { editing = null })
    }
}

/** The selected side's night: the large card, Start Both Sides, and the rest of the library. */
@Composable
private fun SidePlan(model: AppModel, side: SideModel, schedules: List<Schedule>, onEdit: (Schedule) -> Unit) {
    val run by side.run.collectAsState()
    val tonightId by side.tonightId.collectAsState()
    val status by side.status.collectAsState()
    // The side's derived getters, re-read whenever the state they are computed from changes.
    val tonight = remember(side, schedules, tonightId) { side.tonight }
    val runningElsewhereStep = remember(side, run, status) { side.runningElsewhereStep }

    val currentRun = run
    val featured: Featured = when {
        currentRun != null -> Featured.Running(currentRun)
        runningElsewhereStep != null -> Featured.Elsewhere(runningElsewhereStep)
        tonight != null -> Featured.Planned(tonight)
        else -> Featured.Empty
    }
    /** The schedule shown in the large card: the one running, otherwise the one picked for tonight. */
    val featuredId = currentRun?.schedule?.id ?: tonight?.id

    // `.animation(.smooth(duration: 0.35), value: featuredID)`
    AnimatedContent(
        targetState = featured,
        transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(350)) },
        contentKey = { it.key },
        label = "featured",
    ) { shown ->
        when (shown) {
            is Featured.Running -> RunningCard(side, shown.run)
            is Featured.Elsewhere -> RunningElsewhereCard(side, shown.step)
            is Featured.Planned -> PlannedCard(model, side, shown.schedule, onEdit = onEdit)
            Featured.Empty -> Card {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No schedules yet", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Create one to plan how your night feels.",
                        color = LullColors.secondary,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }

    StartBothSidesButton(model)

    val others = schedules.filter { it.id != featuredId }
    if (others.isNotEmpty()) {
        Eyebrow("More schedules", modifier = Modifier.padding(top = 8.dp))
        others.forEach { schedule ->
            ScheduleRowItem(
                model = model,
                side = side,
                schedule = schedule,
                showsUseTonight = run == null,
                onEdit = { onEdit(schedule) },
            )
        }
    }
}

/**
 * Starts tonight's schedule on side 0, then side 1. Shown only while both sides have a BedJet and a schedule for
 * tonight and neither is running one, from this phone or elsewhere. Every side's flows behind those are collected,
 * so it appears and goes as soon as either side changes, whichever side is selected.
 */
@Composable
private fun StartBothSidesButton(model: AppModel) {
    val schedules by model.schedules.collectAsState()
    var ready = true
    var usable = true
    model.sides.forEach { side ->
        val tonightId by side.tonightId.collectAsState()
        val run by side.run.collectAsState()
        val status by side.status.collectAsState()
        val linkState by side.linkState.collectAsState()
        val isBusy by side.isBusy.collectAsState()
        val isSetUp = rememberIsSetUp(model, side)
        // The side's derived getters, re-read whenever the state they are computed from changes.
        val tonight = remember(side, schedules, tonightId) { side.tonight }
        val runningElsewhereStep = remember(side, run, status) { side.runningElsewhereStep }
        val canControl = remember(side, linkState) { side.canControl }
        // With one BedJet the other side has nothing to start: not offered rather than failing there. A schedule
        // started from a partner's phone or the BedJet's remote isn't replaced from here either.
        if (!isSetUp || tonight == null || run != null || runningElsewhereStep != null) ready = false
        if (!canControl || isBusy) usable = false
    }
    if (!ready) return
    val scope = rememberActionScope()
    PrimaryButton(
        text = "Start Both Sides",
        onClick = { scope.launchAction { model.startBothSides() } },
        prominent = false,
        enabled = usable,
    )
}

/** Today as "Monday, Oct 12", like Swift's `.dateTime.weekday(.wide).month().day()`. */
private fun todayTitle(): String {
    val locale = Locale.getDefault()
    val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEMMMd")
    return LocalDate.now().format(DateTimeFormatter.ofPattern(pattern, locale))
}

private fun minuteOfDay(epochMillis: Long): Int {
    val time = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    return time.hour * 60 + time.minute
}

private fun minuteOfDayNow(): Int {
    val now = LocalTime.now()
    return now.hour * 60 + now.minute
}

/** The running card, redrawn every 30 seconds like Swift's `TimelineView(.periodic(from: .now, by: 30))`. */
@Composable
private fun RunningCard(side: SideModel, run: ActiveRun) {
    val isBusy by side.isBusy.collectAsState()
    val scope = rememberActionScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(run) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val current = run.phase(now)
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Eyebrow(current?.let { "Phase ${it.index + 1} of ${run.schedule.phases.size}" } ?: "Finishing")
                Text(run.schedule.name, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                if (current != null) {
                    IconLabel(
                        text = "${current.phase.mode.activity} · ${Format.duration(current.minutesLeft)} left",
                        icon = current.phase.mode.icon,
                        color = current.phase.mode.tint,
                    )
                }
            }
            NightTimeline(
                schedule = run.schedule,
                startMinute = minuteOfDay(run.startedAt),
                progress = run.progress(now),
            )
            PrimaryButton(
                text = "End Night",
                onClick = { scope.launchAction { side.endNight() } },
                prominent = false,
                enabled = !isBusy,
            )
        }
    }
}

/** Shown instead of the planned card when the BedJet is already running a schedule this phone didn't start. */
@Composable
private fun RunningElsewhereCard(side: SideModel, step: Int) {
    val status by side.status.collectAsState()
    val isBusy by side.isBusy.collectAsState()
    val scope = rememberActionScope()
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Eyebrow("Running now")
                Text("Phase $step", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                status?.let { current ->
                    val minutes = max((current.remainingSeconds + 59) / 60, 1)
                    IconLabel(
                        text = "${current.mode.activity} · ${Format.duration(minutes)} left in this phase",
                        icon = current.mode.icon,
                        color = current.mode.tint,
                    )
                }
                Text(
                    "This schedule was started from another phone or the BedJet's own remote.",
                    color = LullColors.tertiary,
                    fontSize = 13.sp,
                )
            }
            PrimaryButton(
                text = "End Night",
                onClick = { scope.launchAction { side.endNight() } },
                prominent = false,
                enabled = !isBusy,
            )
        }
    }
}

@Composable
private fun PlannedCard(model: AppModel, side: SideModel, schedule: Schedule, onEdit: (Schedule) -> Unit) {
    val linkState by side.linkState.collectAsState()
    val isBusy by side.isBusy.collectAsState()
    val canControl = side.canControl // derived from linkState, collected above
    val scope = rememberActionScope()
    val startHint = when (linkState) {
        LinkState.READY -> "Saved to your BedJet, which runs it all night. Your phone can sleep too."
        LinkState.YIELDED -> "Your BedJet is in use on another phone. Starting here takes over."
        else -> "Waiting for your BedJet. Keep your phone within a few feet of it."
    }
    val longPressSource = remember { MutableInteractionSource() }
    WithScheduleMenu(model = model, schedule = schedule, onEdit = onEdit) { openMenu ->
        Card(modifier = Modifier.longPress(longPressSource, onLongPress = openMenu)) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Eyebrow("Tonight")
                        Text(schedule.name, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Edit",
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { onEdit(schedule) }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        color = LullColors.secondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                NightTimeline(schedule = schedule, startMinute = minuteOfDayNow())
                PrimaryButton(
                    text = "Start Night",
                    onClick = { scope.launchAction { side.startTonight() } },
                    enabled = canControl && !isBusy,
                    loading = isBusy,
                )
                Text(
                    startHint,
                    modifier = Modifier.fillMaxWidth(),
                    color = LullColors.tertiary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ScheduleRowItem(
    model: AppModel,
    side: SideModel,
    schedule: Schedule,
    showsUseTonight: Boolean,
    onEdit: () -> Unit,
) {
    WithScheduleMenu(model = model, schedule = schedule, onEdit = { onEdit() }) { openMenu ->
        Box {
            ScheduleRow(
                schedule = schedule,
                modifier = Modifier
                    .clip(CardShape)
                    .rowClickable(onClick = onEdit, onLongClick = openMenu),
            )
            // Swapping schedules waits until the one that is running has ended.
            if (showsUseTonight) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null, // `.buttonStyle(.plain)`
                            role = Role.Button,
                        ) { side.setTonight(schedule.id) }
                        // `.accessibilityLabel("Use \(schedule.name) tonight")` replaces the visible "Use Tonight".
                        .clearAndSetSemantics { contentDescription = "Use ${schedule.name} tonight" }
                        .padding(10.dp)
                        .height(32.dp)
                        .clip(CircleShape)
                        .background(LullColors.raised)
                        .border(1.dp, LullColors.hairline, CircleShape)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Use Tonight", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(schedule: Schedule, modifier: Modifier = Modifier) {
    Card(padding = 18.dp, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                schedule.name,
                // Leaves room for the Use Tonight button laid over this corner.
                modifier = Modifier.padding(end = 116.dp),
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            NightCurve(
                phases = schedule.phases,
                lineWidth = 2.5.dp,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            )
            val count = schedule.phases.size
            Text(
                "${Format.duration(schedule.totalMinutes)} · $count ${if (count == 1) "phase" else "phases"}",
                color = LullColors.secondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Port of `.contextMenu { menu(schedule) }`: a long press opens Edit / Duplicate / Delete, anchored to the content.
 * [content] receives the function that opens the menu.
 */
@Composable
private fun WithScheduleMenu(
    model: AppModel,
    schedule: Schedule,
    onEdit: (Schedule) -> Unit,
    content: @Composable (openMenu: () -> Unit) -> Unit,
) {
    var showsMenu by remember { mutableStateOf(false) }
    Box {
        content { showsMenu = true }
        // The menu's container is the theme's surfaceContainer, which LullTheme sets to `raised`.
        DropdownMenu(expanded = showsMenu, onDismissRequest = { showsMenu = false }) {
            MenuItem("Edit", Icons.Outlined.Tune) {
                showsMenu = false
                onEdit(schedule)
            }
            MenuItem("Duplicate", Icons.Outlined.ContentCopy) {
                showsMenu = false
                model.save(
                    schedule.copy(
                        id = UUID.randomUUID().toString().uppercase(),
                        name = Codec.sanitize("${schedule.name} copy"),
                    ),
                )
            }
            MenuItem("Delete", Icons.Outlined.Delete, color = LullColors.destructive) {
                showsMenu = false
                model.delete(schedule)
            }
        }
    }
}

@Composable
private fun MenuItem(title: String, icon: ImageVector, color: Color = Color.White, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title, color = color, fontSize = 17.sp) },
        onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null, tint = color) },
    )
}

/** SwiftUI's `Label`: an icon before the text, tinted alike. */
@Composable
private fun IconLabel(text: String, icon: ImageVector, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** A tap and a long press on a schedule row. */
@OptIn(ExperimentalFoundationApi::class) // combinedClickable was experimental before Compose Foundation 1.7
private fun Modifier.rowClickable(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick)

/** A long press with no tap action or ripple, for the planned card, which isn't a button itself. */
@OptIn(ExperimentalFoundationApi::class) // combinedClickable was experimental before Compose Foundation 1.7
private fun Modifier.longPress(interactionSource: MutableInteractionSource, onLongPress: () -> Unit): Modifier =
    combinedClickable(
        interactionSource = interactionSource,
        indication = null,
        onLongClick = onLongPress,
        onClick = {},
    )
