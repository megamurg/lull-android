package com.lull.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lull.app.model.AppModel
import com.lull.app.model.Format
import com.lull.app.model.SideModel
import com.lull.app.model.activity
import com.lull.app.model.maxMinutes
import com.lull.bedjetkit.DeviceStatus
import com.lull.bedjetkit.LinkState
import com.lull.bedjetkit.Mode
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** Direct control, for when you want air right now rather than a schedule. */
@Composable
fun RemoteScreen(model: AppModel) {
    val side by model.selected.collectAsState()
    Screen(title = "Remote", model = model) { openDevice ->
        // Keyed so the sliders and the mode just tapped start over from the other side's BedJet, never carrying
        // one side's values to the other.
        key(side.index) {
            if (rememberIsSetUp(model, side)) {
                RemoteControls(model = model, side = side)
            } else {
                ChooseBedJetCard(onChoose = openDevice)
            }
        }
    }
}

/** Shown instead of the orb and controls while this side has no BedJet. */
@Composable
private fun ChooseBedJetCard(onChoose: () -> Unit) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Choose this side's BedJet", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Lull controls one BedJet per side of the bed.",
                    color = LullColors.secondary,
                    fontSize = 15.sp,
                )
            }
            PrimaryButton(text = "Choose BedJet", onClick = onChoose)
        }
    }
}

/** The orb and the controls for one side. Emitted straight into the screen's column, so its spacing applies. */
@Composable
private fun ColumnScope.RemoteControls(model: AppModel, side: SideModel) {
    val status by side.status.collectAsState()
    val linkState by side.linkState.collectAsState()
    val run by side.run.collectAsState()
    val generation by side.generation.collectAsState()
    val modes = remember(side, generation) { side.remoteModes + Mode.STANDBY }
    val adjustsTemperature = rememberAdjustsTemperature(side)
    val canControl = side.canControl // derived from linkState, collected above

    var temperature by remember { mutableIntStateOf(60) }
    var fan by remember { mutableFloatStateOf(40f) }
    var runtime by remember { mutableFloatStateOf(60f) }
    /** While a slider is in use, and briefly after, it leads and the BedJet's reports are not applied to it. */
    val followAfter = remember { Ref(0L) }
    val pending = remember { mutableMapOf<String, Job>() }
    /** The mode just tapped, shown as selected until the BedJet reports it. */
    var chosenMode by remember { mutableStateOf<Mode?>(null) }
    val scope = rememberActionScope()

    /**
     * Forwards what the person sets to the BedJet shortly after they stop moving the slider. Only their input
     * passes through here, so values applied from the BedJet are never echoed back.
     */
    fun sending(name: String, send: suspend () -> Unit) {
        followAfter.value = System.currentTimeMillis() + 1_500
        pending[name]?.cancel()
        pending[name] = scope.launch {
            delay(350)
            withContext(NonCancellable) { send() }
        }
    }

    LaunchedEffect(status) {
        // Follow the unit, which can also be changed from its own remote, but never
        // while a slider is being moved or its new value is still on the way there.
        val current = status ?: return@LaunchedEffect
        if (side.isBusy.value || System.currentTimeMillis() <= followAfter.value) return@LaunchedEffect
        temperature = current.targetTemperature
        fan = current.fanPercent.toFloat()
        val limit = runtimeLimit(current)
        runtime = min(max(minutesLeft(current).toFloat(), limit.range.start), limit.range.endInclusive)
    }

    ClimateOrb(model = model, side = side, status = status, modifier = Modifier.align(Alignment.CenterHorizontally))

    if (linkState == LinkState.YIELDED) {
        Text(
            "In use on another phone. Touch a control to take over.",
            modifier = Modifier.fillMaxWidth(),
            color = LullColors.secondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }

    ModePicker(
        modes = modes,
        selection = chosenMode ?: status?.mode,
        onSelect = { picked ->
            chosenMode = picked
            scope.launchAction {
                side.setMode(picked)
                chosenMode = null
            }
        },
        enabled = canControl,
    )

    // Kept while the card animates out, so it doesn't empty first.
    val running = rememberLastNonNull(status?.takeIf { it.isRunning })
    AnimatedVisibility(
        visible = status?.isRunning == true,
        // `.transition(.opacity.combined(with: .move(edge: .bottom)))` with a 0.3 s animation.
        enter = fadeIn(tween(300)) + expandVertically(tween(300)) + slideInVertically(tween(300)) { it / 2 },
        exit = fadeOut(tween(300)) + shrinkVertically(tween(300)) + slideOutVertically(tween(300)) { it / 2 },
    ) {
        val shown = running ?: return@AnimatedVisibility
        val shownMode = shown.mode
        Card(padding = 18.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (adjustsTemperature(shownMode)) {
                    TemperatureSlider(
                        model = model,
                        mode = shownMode,
                        temperature = temperature,
                        onChange = { value ->
                            temperature = value
                            sending("temperature") { side.setTemperature(value) }
                        },
                        limits = if (shown.minTemperature < shown.maxTemperature) {
                            shown.minTemperature..shown.maxTemperature
                        } else {
                            null
                        },
                        enabled = canControl,
                    )
                }
                LabeledSlider(
                    title = "Fan",
                    valueText = "${fan.toInt()}%",
                    value = fan,
                    onValueChange = { value ->
                        fan = value
                        sending("fan") { side.setFan(value.toInt()) }
                    },
                    range = 5f..100f,
                    step = 5f,
                    tint = shownMode.tint,
                    enabled = canControl,
                )
                if (run == null) {
                    val limit = runtimeLimit(shown)
                    LabeledSlider(
                        title = "Turns off in",
                        valueText = Format.duration(runtime.toInt()),
                        value = runtime,
                        onValueChange = { value ->
                            runtime = value
                            sending("runtime") { side.setRuntime(value.toInt()) }
                        },
                        range = limit.range,
                        step = limit.step,
                        enabled = canControl,
                    )
                } else {
                    // A schedule sets its own timing, so this is read-only until it ends.
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Phase ends in",
                            modifier = Modifier.alignByBaseline(),
                            color = LullColors.secondary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            Format.duration(minutesLeft(shown)),
                            modifier = Modifier.alignByBaseline(),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            style = MonospacedDigits,
                        )
                    }
                }
            }
        }
    }

    if (run != null) {
        Text(
            "Choosing a mode here ends the schedule that's running.",
            modifier = Modifier.fillMaxWidth(),
            color = LullColors.tertiary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

private fun minutesLeft(status: DeviceStatus): Int = max((status.remainingSeconds + 59) / 60, 1)

private class RuntimeLimit(val range: ClosedFloatingPointRange<Float>, val step: Float)

/** How long the current mode may run, as the BedJet reports it, with a step size that suits the span. */
private fun runtimeLimit(status: DeviceStatus): RuntimeLimit {
    val longest = if (status.maxRuntimeMinutes > 0) status.maxRuntimeMinutes else status.mode.maxMinutes
    val step = if (longest <= 15) 1 else if (longest <= 240) 5 else 15
    return RuntimeLimit(step.toFloat()..max(longest / step * step, step * 2).toFloat(), step.toFloat())
}

/** The glowing live readout: what the BedJet is doing this second. */
@Composable
private fun ClimateOrb(model: AppModel, side: SideModel, status: DeviceStatus?, modifier: Modifier = Modifier) {
    val mode = status?.mode ?: Mode.STANDBY
    val isOn = status?.isRunning ?: false
    val degrees = rememberDegrees(model)
    val adjustsTemperature = rememberAdjustsTemperature(side)
    // `.animation(.smooth(duration: 0.6), value: mode)`
    val tint by animateColorAsState(mode.tint, animationSpec = tween(600), label = "orbTint")
    val glowAlpha by animateFloatAsState(if (isOn) 0.55f else 0.12f, animationSpec = tween(600), label = "orbGlow")
    val ringWidth by animateFloatAsState(if (isOn) 2.5f else 1f, animationSpec = tween(600), label = "orbRing")

    Box(
        modifier = modifier
            .size(204.dp)
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Swift fills the circle with a radial gradient from 20 pt (solid) to 112 pt (clear), then blurs it by
            // 24 pt. Modifier.blur only works from API 31, so the blurred falloff is drawn into the gradient instead,
            // reaching a little past the orb the way the blur spreads it.
            val glowRadius = 132.dp.toPx()
            drawCircle(
                brush = Brush.radialGradient(
                    0f to tint.copy(alpha = glowAlpha),
                    0.15f to tint.copy(alpha = glowAlpha * 0.95f),
                    0.45f to tint.copy(alpha = glowAlpha * 0.55f),
                    0.75f to tint.copy(alpha = glowAlpha * 0.18f),
                    1f to Color.Transparent,
                    center = center,
                    radius = glowRadius,
                ),
                radius = glowRadius,
                center = center,
            )
            // `.strokeBorder(AngularGradient(…), lineWidth:)` inset by 8 pt: the stroke sits inside the circle.
            val stroke = ringWidth.dp.toPx()
            val ringRadius = size.minDimension / 2 - 8.dp.toPx() - stroke / 2
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(tint.copy(alpha = 0.9f), tint.copy(alpha = 0.15f), tint.copy(alpha = 0.9f)),
                    center = center,
                ),
                radius = ringRadius,
                center = center,
                style = Stroke(width = stroke),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (status != null) {
                Eyebrow(mode.activity)
                Text(
                    when {
                        isOn && adjustsTemperature(mode) -> degrees(status.targetTemperature)
                        isOn -> "${status.fanPercent}%"
                        else -> "Off"
                    },
                    fontSize = 60.sp,
                    fontWeight = FontWeight.Thin,
                    style = MonospacedDigits,
                )
                Text(
                    if (isOn) {
                        "Fan ${status.fanPercent}% · Air ${degrees(status.actualTemperature)}"
                    } else {
                        "Room ${degrees(status.ambientTemperature)}"
                    },
                    color = LullColors.secondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    style = MonospacedDigits,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = LullColors.secondary,
                    strokeWidth = 2.dp,
                )
                Text(
                    "Looking for your BedJet",
                    modifier = Modifier.padding(top = 8.dp),
                    color = LullColors.secondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
