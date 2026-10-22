package com.lull.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bed
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lull.app.model.AppModel
import com.lull.app.model.FoundUnit
import com.lull.app.model.SideModel
import com.lull.bedjetkit.LinkState
import kotlinx.coroutines.launch

/** Side labels are short: they fit the switcher's half of the screen (SIDES_PLAN §1). */
private const val SIDE_LABEL_LENGTH = 12

/**
 * The BedJet sheet behind the connection pill: which BedJet each side uses, then the selected side's status and
 * the unit's own settings, and the app's.
 */
@OptIn(ExperimentalMaterial3Api::class) // ModalBottomSheet, rememberModalBottomSheetState
@Composable
fun DeviceSheet(model: AppModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetScope = rememberCoroutineScope()
    val side by model.selected.collectAsState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = LullColors.background,
        contentColor = Color.White,
        dragHandle = null,
    ) {
        // Keyed so a change of selected side never shows the previous side's state, not even for a frame.
        key(side.index) {
            DeviceSheetContent(
                model = model,
                side = side,
                onDone = { sheetScope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } },
            )
        }
    }
}

/** [side] is the selected side, which everything below the Sides section is about. */
@Composable
private fun DeviceSheetContent(model: AppModel, side: SideModel, onDone: () -> Unit) {
    val label by side.label.collectAsState()
    val linkState by side.linkState.collectAsState()
    val demoMode by model.demoMode.collectAsState()
    val deviceName by side.deviceName.collectAsState()
    val firmware by side.firmware.collectAsState()
    val isSlowToFind by side.isSlowToFind.collectAsState()
    val status by side.status.collectAsState()
    val deviceSlots by side.deviceSlots.collectAsState()
    val slot by side.slot.collectAsState()
    val useFahrenheit by model.useFahrenheit.collectAsState()
    val isSetUp = rememberIsSetUp(model, side)
    val isConnected = side.isConnected // derived from linkState, collected above
    val scope = rememberActionScope()
    // Indices into `model.sides`: whose BedJet is being chosen, and which side is being renamed.
    var picking by rememberSaveable { mutableStateOf<Int?>(null) }
    var renaming by rememberSaveable { mutableStateOf<Int?>(null) }

    // A side without a BedJet stays SEARCHING without looking (SIDES_PLAN §1), so it says what to do instead.
    val statusText = if (!isSetUp) {
        "Not set up"
    } else {
        when (linkState) {
            LinkState.READY -> if (demoMode) "Demo BedJet" else "Connected"
            LinkState.CONNECTING -> "Connecting…"
            LinkState.SEARCHING -> "Searching…"
            LinkState.YIELDED -> "In use on another phone"
            LinkState.BLUETOOTH_OFF -> "Bluetooth is off"
            LinkState.UNAUTHORIZED -> "Bluetooth not allowed"
        }
    }
    val help = if (!isSetUp) {
        "Choose this side's BedJet under Sides."
    } else {
        when (linkState) {
            LinkState.READY -> if (demoMode) {
                "Demo mode uses a pretend BedJet so you can explore the app."
            } else {
                "Everything stays between this phone and your BedJet. No account, no cloud."
            }
            LinkState.CONNECTING, LinkState.SEARCHING ->
                "Stand near your BedJet and force-quit the official BedJet app. The BedJet talks to one phone at a time; phones with Lull hand it over to each other automatically."
            LinkState.YIELDED ->
                "The BedJet talks to one phone at a time, and another phone with Lull asked for it. Use any control here to take it back."
            // PORT_PLAN §5.1: Android wording.
            LinkState.BLUETOOTH_OFF -> "Turn on Bluetooth in Quick Settings to connect."
            LinkState.UNAUTHORIZED -> "Allow Nearby devices for Lull in Settings to connect."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        // Inline navigation title with Done as the confirmation action.
        SheetTitle(title = "BedJet", action = "Done", onAction = onDone)

        FormSection(header = "Sides") {
            model.sides.forEach { each ->
                row {
                    SideRow(
                        model = model,
                        side = each,
                        onRename = { renaming = each.index },
                        onChoose = { picking = each.index },
                    )
                }
            }
        }

        // Everything below is the selected side's BedJet.
        FormSection(header = label, footer = help) {
            row { ValueRow("Status", statusText) }
            deviceName?.let { name -> row { ValueRow("Name", name) } }
            firmware?.let { version -> row { ValueRow("Firmware", version) } }
        }

        if (isSetUp && isSlowToFind) {
            FormSection(header = "Can't find your BedJet?", footer = "Lull keeps looking while it's open.") {
                row { TipRow(Icons.Outlined.Bed, "Lull works with BedJet 3 and BedJet V2. The original BedJet isn't supported.") }
                // Android wording for the iOS force-quit tip.
                row { TipRow(Icons.Outlined.Cancel, "Close the BedJet app from Recents, or force stop it in Settings.") }
                row { TipRow(Icons.Outlined.Smartphone, "Close the BedJet app on any other phone or tablet nearby.") }
                row { TipRow(Icons.Outlined.Power, "Unplug the BedJet for 10 seconds, then plug it back in.") }
            }
        }

        val extra = status?.extra
        if (isConnected && extra != null) {
            FormSection(header = "BedJet") {
                row { ToggleRow("Light ring", checked = extra.ledEnabled) { on -> scope.launchAction { side.setLed(on) } } }
                row { ToggleRow("Mute beeps", checked = extra.beepsMuted) { muted -> scope.launchAction { side.setMuted(muted) } } }
            }
        }

        if (deviceSlots.isNotEmpty()) {
            FormSection(
                header = "Saved on the BedJet",
                footer = "Lull keeps tonight's schedule in one slot and leaves the others alone.",
            ) {
                deviceSlots.forEachIndexed { index, program ->
                    val name = program?.name ?: "Empty"
                    row { ValueRow("Slot ${index + 1}", if (index == slot) "Lull · $name" else name) }
                }
            }
        }

        FormSection(header = "App") {
            row { UnitRow(useFahrenheit = useFahrenheit, onChange = { model.setUseFahrenheit(it) }) }
            row { ToggleRow("Demo mode", checked = demoMode) { model.setDemoMode(it) } }
            // Forgets the selected side's BedJet; a side without one has nothing to forget.
            if (!demoMode && isSetUp) {
                row {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { side.forgetDevice() }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text("Forget This BedJet", color = LullColors.destructive, fontSize = 17.sp)
                    }
                }
            }
        }
    }

    picking?.let { index ->
        model.sides.getOrNull(index)?.let { chosen ->
            key(index) {
                UnitPicker(model = model, side = chosen, onDismiss = { picking = null })
            }
        }
    }
    renaming?.let { index ->
        model.sides.getOrNull(index)?.let { chosen ->
            key(index) {
                RenameSideDialog(side = chosen, onDismiss = { renaming = null })
            }
        }
    }
}

/** A sheet's inline navigation title, with its one action (Done, Cancel) on the trailing side. */
@Composable
private fun SheetTitle(title: String, action: String, onAction: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            action,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onAction)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** One side in the Sides section: its label (tap to rename) and its BedJet (tap to choose). */
@Composable
private fun SideRow(model: AppModel, side: SideModel, onRename: () -> Unit, onChoose: () -> Unit) {
    val label by side.label.collectAsState()
    val unitName by side.unitName.collectAsState()
    val isSetUp = rememberIsSetUp(model, side)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clickable(role = Role.Button, onClickLabel = "Rename", onClick = onRename)
                .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, fontSize = 17.sp, maxLines = 1)
            Icon(
                Icons.Outlined.Edit,
                contentDescription = null,
                tint = LullColors.tertiary,
                modifier = Modifier.size(16.dp),
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClickLabel = "Choose BedJet", onClick = onChoose)
                .padding(start = 6.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        ) {
            Text(
                // Demo mode counts as set up even before the pretend unit has a name.
                if (isSetUp) (unitName ?: "Demo BedJet") else "Not set up",
                modifier = Modifier.weight(1f, fill = false),
                color = LullColors.secondary,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = LullColors.tertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Renames a side. Labels are capped at [SIDE_LABEL_LENGTH] whole characters and can't be blank. */
@Composable
private fun RenameSideDialog(side: SideModel, onDismiss: () -> Unit) {
    var text by remember(side) { mutableStateOf(side.label.value) }
    val canSave = text.isNotBlank()
    // Reads the field when tapped, not when composed.
    fun save() {
        val name = text.trim()
        if (name.isEmpty()) return
        side.setLabel(name)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { save() }, enabled = canSave) { Text("Save", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Rename side") },
        text = {
            TextField(
                value = text,
                onValueChange = { changed -> text = changed.prefixCharacters(SIDE_LABEL_LENGTH) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { save() }),
                // REVIEW: parameter names as in material3 1.2/1.3 TextFieldDefaults.colors
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = LullColors.surface,
                    unfocusedContainerColor = LullColors.surface,
                    cursorColor = Color.White,
                    focusedIndicatorColor = Color.White,
                    unfocusedIndicatorColor = LullColors.hairline,
                ),
            )
        },
        containerColor = LullColors.raised,
        titleContentColor = Color.White,
        textContentColor = Color.White,
    )
}

/**
 * Chooses [side]'s BedJet from the ones this phone can see. Lull looks for BedJets only while this is open.
 * A BedJet that is connected stops advertising, so the model lists the connected ones from their links too.
 */
@OptIn(ExperimentalMaterial3Api::class) // ModalBottomSheet, rememberModalBottomSheetState
@Composable
private fun UnitPicker(model: AppModel, side: SideModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetScope = rememberCoroutineScope()
    fun close() {
        sheetScope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    DisposableEffect(model) {
        model.startFinding()
        onDispose { model.stopFinding() }
    }
    val units by model.foundUnits.collectAsState()
    val isFinding by model.isFinding.collectAsState()
    val label by side.label.collectAsState()
    val labels = model.sides.map { it.label.collectAsState().value }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = LullColors.background,
        contentColor = Color.White,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            SheetTitle(title = "BedJet for $label", action = "Cancel", onAction = { close() })

            FormSection(header = "BedJets nearby") {
                units.forEach { unit ->
                    // "Right's": choosing the other side's BedJet moves it to this side.
                    val tag = unit.connectedToSide?.let { owner ->
                        val ownerLabel = labels.getOrNull(owner)
                        if (owner == side.index || ownerLabel == null) "connected" else "$ownerLabel's"
                    }
                    row {
                        FoundUnitRow(
                            unit = unit,
                            // Two BedJets with one name are told apart by address.
                            showsAddress = units.count { it.name == unit.name } > 1,
                            tag = tag,
                            onClick = {
                                side.assign(unit)
                                close()
                            },
                        )
                    }
                }
                // Keeps the list from being an empty card: the scanning indicator, or that nothing turned up.
                if (units.isEmpty()) {
                    row {
                        MessageRow(
                            text = if (isFinding) "Looking for BedJets…" else "No BedJets found.",
                            spinning = isFinding,
                        )
                    }
                }
            }

            // Swaps only the two BedJets; each side keeps its name, tonight's schedule and history.
            TextButton(
                onClick = { model.swapSides() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text("Swap sides", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Not sure which is which? Assign one, tap Heat on the Remote, and feel which side warms. " +
                    "Swap them here if it's the wrong one.",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                color = LullColors.secondary,
                fontSize = 13.sp,
            )
        }
    }
}

/** A BedJet in the picker: its name, its address when another listed BedJet shares the name, and whose it is. */
@Composable
private fun FoundUnitRow(unit: FoundUnit, showsAddress: Boolean, tag: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(unit.name, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showsAddress) {
                Text(unit.address, color = LullColors.secondary, fontSize = 13.sp, style = MonospacedDigits)
            }
        }
        if (tag != null) {
            Spacer(Modifier.width(12.dp))
            Text(tag, color = LullColors.secondary, fontSize = 15.sp, maxLines = 1)
        }
    }
}

/** A row that only says something, optionally with a spinner in front. */
@Composable
private fun MessageRow(text: String, spinning: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (spinning) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = LullColors.secondary, strokeWidth = 2.dp)
        }
        Text(text, color = LullColors.secondary, fontSize = 17.sp)
    }
}

private val GroupShape = RoundedCornerShape(12.dp)

/** Collects a section's rows, so hairlines can go between them and not after the last. */
private class SectionRows {
    val items = mutableListOf<@Composable () -> Unit>()
    fun row(content: @Composable () -> Unit) {
        items += content
    }
}

/** An inset grouped `Form` section: optional header, rows on a raised card separated by hairlines, optional footer. */
@Composable
private fun FormSection(
    header: String? = null,
    footer: String? = null,
    rows: SectionRows.() -> Unit,
) {
    val items = SectionRows().apply(rows).items
    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        if (header != null) {
            Text(
                header.uppercase(),
                modifier = Modifier.padding(start = 16.dp, bottom = 7.dp),
                color = LullColors.secondary,
                fontSize = 13.sp,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GroupShape)
                .background(LullColors.raised),
        ) {
            items.forEachIndexed { index, item ->
                if (index > 0) RowDivider()
                item()
            }
        }
        if (footer != null) {
            Text(
                footer,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp),
                color = LullColors.secondary,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = LullColors.hairline)
}

/** `LabeledContent(title, value:)`. */
@Composable
private fun ValueRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 17.sp)
        Spacer(Modifier.width(16.dp))
        Text(value, modifier = Modifier.weight(1f), color = LullColors.secondary, fontSize = 17.sp, textAlign = TextAlign.End)
    }
}

/** A `Label` row in the "Can't find your BedJet?" section. */
@Composable
private fun TipRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = LullColors.connected, modifier = Modifier.size(22.dp))
        Text(text, fontSize = 17.sp)
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), fontSize = 17.sp)
        // The row handles the tap (and accessibility); the switch only shows the state. `.tint(.green)`.
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = LullColors.connected,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color.White.copy(alpha = 0.16f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** `Picker("Temperature", selection: $model.useFahrenheit)` with °F and °C. */
@OptIn(ExperimentalMaterial3Api::class) // SingleChoiceSegmentedButtonRow, SegmentedButton
@Composable
private fun UnitRow(useFahrenheit: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Temperature", modifier = Modifier.weight(1f), fontSize = 17.sp)
        val options = listOf(true to "°F", false to "°C")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.width(140.dp)) {
            options.forEachIndexed { index, (fahrenheit, label) ->
                SegmentedButton(
                    selected = useFahrenheit == fahrenheit,
                    onClick = { onChange(fahrenheit) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
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
                    Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
