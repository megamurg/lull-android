package com.lull.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lull.app.model.AppModel
import com.lull.app.model.Format
import com.lull.app.model.HistoryEntry
import com.lull.app.model.Night
import com.lull.app.model.SideModel
import com.lull.app.model.title

/** What the BedJet did each night, so the morning after you can see what you picked at 3 a.m. */
@Composable
fun HistoryScreen(model: AppModel) {
    val side by model.selected.collectAsState()
    Screen(title = "History", model = model) {
        // Keyed so nothing collected or remembered for one side (the nights, which are open, their animations) is
        // shown for the other, not even for a frame.
        key(side.index) {
            SideHistory(model = model, side = side)
        }
    }
}

/** One side's nights. Emitted straight into the screen's column, so its spacing applies. */
@Composable
private fun SideHistory(model: AppModel, side: SideModel) {
    val history by side.history.collectAsState()
    val nights = remember(side, history) { side.nights }
    // Ids ("yyyy-MM-dd") of the nights shown open. Kotlin's empty set and the LinkedHashSet that `+`/`-` return
    // are java.io.Serializable, so this survives tab switches and rotation. Night ids repeat across sides, so
    // switching sides starts with every night closed rather than opening the same dates on the other side.
    var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }

    if (nights.isEmpty()) {
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("No nights yet", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Every change to your BedJet shows up here with the time and room temperature, so in the morning you can see what you picked overnight.",
                    color = LullColors.secondary,
                    fontSize = 15.sp,
                )
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        nights.forEach { night ->
            NightRow(
                model = model,
                side = side,
                night = night,
                isExpanded = night.id in expanded,
                toggle = { expanded = if (night.id in expanded) expanded - night.id else expanded + night.id },
            )
        }
    }
}

/** One night at a glance, opening up to everything that happened in it. */
@Composable
private fun NightRow(model: AppModel, side: SideModel, night: Night, isExpanded: Boolean, toggle: () -> Unit) {
    val title = Format.nightTitle(night.evening)
    val summary = summary(night)
    val chevron by animateFloatAsState(if (isExpanded) 180f else 0f, animationSpec = tween(350), label = "chevron")
    Card(padding = 18.dp) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // `.accessibilityIdentifier("Night")`
                    .testTag("Night")
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        // `.accessibilityHint(…)`
                        onClickLabel = if (isExpanded) "Hides this night" else "Shows everything that happened this night",
                        onClick = toggle,
                    )
                    // `.accessibilityLabel("\(title), \(summary)")`; the texts below are hidden so it's read once.
                    .semantics { contentDescription = "$title, $summary" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f).clearAndSetSemantics {},
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        title,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        summary,
                        color = LullColors.secondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        style = MonospacedDigits,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp)) // `Spacer(minLength: 8)`
                NightCurve(phases = night.phases, lineWidth = 2.dp, modifier = Modifier.size(width = 88.dp, height = 40.dp))
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = LullColors.tertiary,
                    modifier = Modifier.size(18.dp).rotate(chevron),
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(tween(350)) + expandVertically(tween(350)),
                exit = fadeOut(tween(350)) + shrinkVertically(tween(350)),
            ) {
                // The Swift VStack's 20 pt spacing sits above the entries only while they are shown.
                Column(modifier = Modifier.padding(top = 20.dp)) {
                    night.entries.forEachIndexed { index, entry ->
                        EntryRow(model = model, side = side, entry = entry, isLast = index == night.entries.size - 1)
                    }
                }
            }
        }
    }
}

/** The schedule that ran, or else when the first and last changes happened. */
private fun summary(night: Night): String {
    val names = night.scheduleNames
    if (names.isNotEmpty()) return names.joinToString(", ")
    val first = night.entries.firstOrNull()?.date ?: return ""
    val last = night.entries.lastOrNull()?.date ?: return ""
    val start = Format.time(first)
    val end = Format.time(last)
    return if (start == end) start else "$start – $end"
}

@Composable
private fun EntryRow(model: AppModel, side: SideModel, entry: HistoryEntry, isLast: Boolean) {
    val degrees = rememberDegrees(model)
    val adjustsTemperature = rememberAdjustsTemperature(side)
    val event = entry.event
    val title = when (event) {
        is HistoryEntry.Event.Air -> if (adjustsTemperature(event.mode)) {
            "${event.mode.title} · ${degrees(event.temperature)} · fan ${event.fanPercent}%"
        } else {
            "${event.mode.title} · fan ${event.fanPercent}%"
        }
        is HistoryEntry.Event.Off -> "Turned off"
        is HistoryEntry.Event.ScheduleStarted -> "Started ${event.name ?: "a schedule"}"
        is HistoryEntry.Event.ScheduleEnded -> {
            val name = event.name ?: "Schedule"
            when (val ending = event.ending) {
                is HistoryEntry.Ending.ByYou -> "Ended $name"
                is HistoryEntry.Ending.Finished -> "$name finished"
                is HistoryEntry.Ending.Early -> "$name stopped ${Format.duration(ending.minutes)} early"
            }
        }
    }
    val detail = run {
        val parts = mutableListOf<String>()
        if (event is HistoryEntry.Event.Off && event.timerRanOut) parts.add("Timer ran out")
        entry.room?.let { parts.add("Room ${degrees(it)}") }
        if (entry.noticedLater) parts.add("Seen when Lull reopened")
        if (parts.isEmpty()) null else parts.joinToString(" · ")
    }
    val symbol: ImageVector = when (event) {
        is HistoryEntry.Event.Air -> event.mode.icon
        is HistoryEntry.Event.Off -> Icons.Outlined.PowerSettingsNew
        is HistoryEntry.Event.ScheduleStarted -> Icons.Filled.Bedtime
        is HistoryEntry.Event.ScheduleEnded ->
            if (event.ending is HistoryEntry.Ending.Early) Icons.Outlined.ErrorOutline else Icons.Filled.WbTwilight
    }
    val tint: Color = when (event) {
        is HistoryEntry.Event.Air -> event.mode.tint
        is HistoryEntry.Event.ScheduleEnded ->
            if (event.ending is HistoryEntry.Ending.Early) LullColors.warm else Color.White
        is HistoryEntry.Event.Off -> LullColors.neutral
        is HistoryEntry.Event.ScheduleStarted -> Color.White
    }
    val time = Format.time(entry.date)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            // `.accessibilityElement(children: .combine)`
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            time,
            modifier = Modifier.width(64.dp).padding(top = 6.dp),
            color = LullColors.secondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            style = MonospacedDigits,
            textAlign = TextAlign.End,
        )
        Column(modifier = Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(tint.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(symbol, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            }
            // Joins the entries into one line through the night.
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .weight(1f)
                        .heightIn(min = 14.dp)
                        .background(LullColors.hairline),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 4.dp, bottom = if (isLast) 0.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (detail != null) Text(detail, color = LullColors.secondary, fontSize = 13.sp)
        }
    }
}
