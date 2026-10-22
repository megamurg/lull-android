package com.lull.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lull.app.model.AppModel
import com.lull.app.model.Format
import com.lull.app.model.SideModel
import com.lull.app.model.title
import com.lull.bedjetkit.LinkState
import com.lull.bedjetkit.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------------------------
// Small helpers shared by the screens
// ---------------------------------------------------------------------------------------------------------------

/** A plain mutable box, for values a screen keeps between compositions without redrawing when they change. */
internal class Ref<T>(var value: T)

/**
 * The scope that [RootScreen] provides for work a screen starts but shouldn't stop when it leaves the screen,
 * like Swift's unstructured `Task { }` (e.g. the Remote's slider value still on its way to the BedJet when the
 * person switches tabs).
 */
internal val LocalActionScope = staticCompositionLocalOf<CoroutineScope?> { null }

/** [LocalActionScope], or this composable's own scope outside [RootScreen] (previews, tests). */
@Composable
internal fun rememberActionScope(): CoroutineScope {
    val own = rememberCoroutineScope()
    return LocalActionScope.current ?: own
}

/**
 * Runs a model action the way Swift's `Task { await model.… }` does. Even the root scope ends when the activity
 * is recreated (rotation), and that must not cut a BedJet write off halfway, so the action itself is not
 * cancellable. The model lives in the Application, so finishing it later is safe.
 */
internal fun CoroutineScope.launchAction(action: suspend () -> Unit): Job =
    launch { withContext(NonCancellable) { action() } }

/**
 * `model.degrees`, refreshed whenever °F/°C changes. Composables only redraw for state they read, so anything
 * showing a temperature goes through this rather than calling `model.degrees` on its own.
 */
@Composable
internal fun rememberDegrees(model: AppModel): (Int) -> String {
    val fahrenheit by model.useFahrenheit.collectAsState()
    return remember(fahrenheit) { { halfCelsius: Int -> model.degrees(halfCelsius) } }
}

/** `side.adjustsTemperature`, refreshed whenever the kind of BedJet (V2 or V3) on that side changes. */
@Composable
internal fun rememberAdjustsTemperature(side: SideModel): (Mode) -> Boolean {
    val generation by side.generation.collectAsState()
    return remember(side, generation) { { mode: Mode -> side.adjustsTemperature(mode) } }
}

/** `side.isSetUp`, refreshed whenever the side's BedJet or demo mode changes. */
@Composable
internal fun rememberIsSetUp(model: AppModel, side: SideModel): Boolean {
    val unitName by side.unitName.collectAsState()
    val demoMode by model.demoMode.collectAsState()
    return remember(side, unitName, demoMode) { side.isSetUp }
}

/** The latest non-null value, so content that is animating out can still draw what it showed. */
@Composable
internal fun <T : Any> rememberLastNonNull(value: T?): T? {
    val last = remember { Ref<T?>(null) }
    if (value != null) last.value = value
    return value ?: last.value
}

// ---------------------------------------------------------------------------------------------------------------
// Port of Theme.swift's views
// ---------------------------------------------------------------------------------------------------------------

/** Small all-caps label used above groups of content. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = LullColors.secondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.6.sp,
    )
}

internal val CardShape = RoundedCornerShape(28.dp)

@Composable
fun Card(
    padding: Dp = 20.dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LullColors.surface)
            .border(1.dp, LullColors.hairline, CardShape)
            .padding(padding),
        content = content,
    )
}

/**
 * Port of `PrimaryButtonStyle`: a 56 dp capsule, white with black text when prominent, otherwise raised with a
 * hairline. Like the Swift style it shrinks a little while pressed instead of showing a ripple.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    prominent: Boolean = true,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, animationSpec = tween(200), label = "press")
    val contentColor = if (prominent) Color.Black else Color.White
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.4f
            }
            .clip(CircleShape)
            .background(if (prominent) Color.White else LullColors.raised)
            .then(if (prominent) Modifier else Modifier.border(1.dp, LullColors.hairline, CircleShape))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            // While loading the label is a spinner, as in Swift; the name stays for accessibility and tests.
            .semantics { if (loading) contentDescription = text },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = contentColor, strokeWidth = 2.dp)
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (icon != null) Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
                Text(text, color = contentColor, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** A slider with a tinted track that snaps to whole steps and shows its value inline. */
@OptIn(ExperimentalMaterial3Api::class) // Slider overload with custom `thumb`/`track` slots
@Composable
fun LabeledSlider(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    step: Float = 1f,
    tint: Color = Color.White,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    // A one-value range (e.g. a phase whose cut-off leaves only 5 min) still needs a span to draw; the value stays
    // pinned to that one value.
    val pinned = range.endInclusive <= range.start
    val safeRange = if (!pinned) range else range.start..(range.start + max(step, 1f))
    val span = safeRange.endInclusive - safeRange.start
    val intervals = if (step > 0f) max((span / step).roundToInt(), 1) else 1
    val shown = value.coerceIn(safeRange.start, safeRange.endInclusive)
    val fraction = ((shown - safeRange.start) / span).coerceIn(0f, 1f)

    fun snap(raw: Float): Float {
        if (step <= 0f) return raw
        val index = ((raw - safeRange.start) / step).roundToInt()
        return (safeRange.start + index * step).coerceIn(safeRange.start, safeRange.endInclusive)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                title,
                modifier = Modifier.alignByBaseline(),
                color = LullColors.secondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                valueText,
                modifier = Modifier.alignByBaseline(),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                style = MonospacedDigits,
            )
        }
        Slider( // REVIEW: thumb/track slot overload (material3 ≥ 1.2, lambdas take SliderState; only `it` is unused)
            value = shown,
            onValueChange = { raw ->
                val snapped = if (pinned) range.start else snap(raw)
                if (snapped != value) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onValueChange(snapped)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = title
                    stateDescription = valueText
                },
            enabled = enabled,
            valueRange = safeRange,
            steps = intervals - 1,
            thumb = { SliderThumb(enabled) },
            track = { SliderTrack(fraction = fraction, tint = tint, enabled = enabled) },
        )
    }
}

@Composable
private fun SliderThumb(enabled: Boolean) {
    Box(
        Modifier
            .size(28.dp)
            .shadow(elevation = 3.dp, shape = CircleShape)
            .background(if (enabled) Color.White else Color(0xFF8E8E93), CircleShape),
    )
}

@Composable
private fun SliderTrack(fraction: Float, tint: Color, enabled: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(if (enabled) tint else tint.copy(alpha = 0.4f)),
        )
    }
}

/**
 * Temperature in °F or °C as the person prefers, kept in half-degrees Celsius.
 * [limits] (half-degrees Celsius): the Remote passes what the BedJet reports for the mode it is in, which can be
 * narrower than the mode's general range.
 */
@Composable
fun TemperatureSlider(
    model: AppModel,
    mode: Mode,
    temperature: Int,
    onChange: (Int) -> Unit,
    limits: IntRange? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val fahrenheit by model.useFahrenheit.collectAsState()
    val degrees = rememberDegrees(model)
    val bounds = limits ?: mode.temperatureRange
    val low = Format.degrees(bounds.first, fahrenheit)
    val high = max(Format.degrees(bounds.last, fahrenheit), low + 1)
    LabeledSlider(
        title = "Temperature",
        valueText = degrees(temperature),
        value = Format.degrees(temperature, fahrenheit).toFloat(),
        onValueChange = { value ->
            val halves = Format.halfCelsius(value.roundToInt(), fahrenheit)
            onChange(halves.coerceIn(bounds.first, bounds.last))
        },
        range = low.toFloat()..high.toFloat(),
        modifier = modifier,
        tint = mode.tint,
        enabled = enabled,
    )
}

private val TileShape = RoundedCornerShape(18.dp)

/** Three columns of modes; the selected one is filled with its colour. */
@Composable
fun ModePicker(
    modes: List<Mode>,
    selection: Mode?,
    onSelect: (Mode) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    // `.sensoryFeedback(.selection, trigger: selection)`: a tick whenever the selection changes, not on first show.
    val previous = remember { Ref(selection) }
    LaunchedEffect(selection) {
        if (previous.value != selection) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            previous.value = selection
        }
    }
    // Rows rather than a lazy grid: the pickers sit inside scrolling columns, where a lazy grid can't measure.
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        modes.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { mode ->
                    ModeTile(
                        mode = mode,
                        isSelected = mode == selection,
                        enabled = enabled,
                        onClick = { onSelect(mode) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ModeTile(mode: Mode, isSelected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val contentColor = if (isSelected) Color.Black else mode.tint
    Column(
        modifier = modifier
            .heightIn(min = 62.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(TileShape)
            .background(if (isSelected) mode.tint else LullColors.raised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { selected = isSelected }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(mode.icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
        Text(mode.title, color = contentColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Port of RootView.swift's Screen and ConnectionPill
// ---------------------------------------------------------------------------------------------------------------

/**
 * Screen scaffold shared by the tabs: large title on the left, the selected side's BedJet connection on the right,
 * and the side switcher underneath. [content] receives the function that opens the BedJet sheet, so a screen can
 * offer it too (the Remote does while its side has no BedJet).
 */
@Composable
fun Screen(
    title: String,
    subtitle: String? = null,
    model: AppModel,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(openDevice: () -> Unit) -> Unit,
) {
    val side by model.selected.collectAsState()
    var showsDevice by rememberSaveable { mutableStateOf(false) }
    val openDevice: () -> Unit = { showsDevice = true }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LullColors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (subtitle != null) Eyebrow(subtitle)
                Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
            // Keyed: collectAsState keeps showing a flow's last value for a frame after the flow is swapped, so
            // without this the other side's state would flash in the pill on switching.
            key(side.index) {
                ConnectionPill(model = model, side = side, onClick = openDevice)
            }
        }
        SideSwitcher(model = model)
        content(openDevice)
    }
    if (showsDevice) DeviceSheet(model = model, onDismiss = { showsDevice = false })
}

/** "Left | Right": which half of the bed the screens show and control. Same look as the °F/°C picker. */
@OptIn(ExperimentalMaterial3Api::class) // SingleChoiceSegmentedButtonRow, SegmentedButton
@Composable
fun SideSwitcher(model: AppModel, modifier: Modifier = Modifier) {
    val selectedSide by model.selectedSide.collectAsState()
    val sides = model.sides
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        sides.forEachIndexed { index, side ->
            val label by side.label.collectAsState()
            SegmentedButton(
                selected = selectedSide == index,
                onClick = { model.selectSide(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = sides.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Color.White,
                    activeContentColor = Color.Black,
                    activeBorderColor = LullColors.hairline,
                    inactiveContainerColor = LullColors.surface,
                    inactiveContentColor = Color.White,
                    inactiveBorderColor = LullColors.hairline,
                ),
                icon = {},
            ) {
                Text(
                    label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The selected side's connection; [model] supplies demo mode and the screenshot label flag. */
@Composable
fun ConnectionPill(model: AppModel, side: SideModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val linkState by side.linkState.collectAsState()
    val isSlowToFind by side.isSlowToFind.collectAsState()
    val demoMode by model.demoMode.collectAsState()
    val isSetUp = rememberIsSetUp(model, side)
    // Derived from linkState, which is collected above, so this recomposes whenever it changes.
    val isConnected = side.isConnected
    // Invites a tap through to the things worth checking.
    val label = when {
        !isSetUp -> "Not set up"
        isSlowToFind -> "Not found"
        else -> when (linkState) {
            // `labelsDemoAsConnected`: Swift's static AppModel.labelsDemoAsConnected (launch flag), not listed in §4.2.
            LinkState.READY -> if (demoMode && !model.labelsDemoAsConnected) "Demo" else "Connected"
            LinkState.CONNECTING -> "Connecting"
            LinkState.SEARCHING -> "Searching"
            LinkState.YIELDED -> "Other phone"
            LinkState.BLUETOOTH_OFF -> "Bluetooth off"
            LinkState.UNAUTHORIZED -> "No access"
        }
    }
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(LullColors.raised)
            .border(1.dp, LullColors.hairline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "BedJet, $label" }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // A side with no BedJet isn't reaching for one, so its dot stays still.
        StatusDot(
            color = if (isConnected) LullColors.connected else LullColors.neutral,
            pulsing = !isConnected && isSetUp,
        )
        Text(
            label,
            modifier = Modifier.clearAndSetSemantics {},
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** The pill's 8 dp dot, which pulses while Lull is still reaching for the BedJet (`.symbolEffect(.pulse)`). */
@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val pulse by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
            label = "pulseAlpha",
        )
        pulse
    } else {
        1f
    }
    Box(
        Modifier
            .size(8.dp)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}
