package com.lull.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lull.app.model.AppModel
import com.lull.app.model.SideModel

private enum class Tab(val title: String, val icon: ImageVector) {
    REMOTE("Remote", Icons.Filled.Tune),
    SCHEDULE("Schedule", Icons.Filled.Bedtime),
    HISTORY("History", Icons.Filled.History),
}

/**
 * Port of RootView.swift: the three tabs, the error alert and the full-slots chooser.
 * Moving to the background and back is reported to the model by MainActivity (onStart/onStop), not here.
 */
@Composable
fun RootScreen(model: AppModel) {
    LullTheme {
        // Work the screens start outlives a tab switch, like Swift's unstructured `Task { }`.
        val actionScope = rememberCoroutineScope()
        CompositionLocalProvider(LocalActionScope provides actionScope) {
            var selectedTab by rememberSaveable { mutableIntStateOf(Tab.REMOTE.ordinal) }
            // Like a SwiftUI TabView, each tab keeps its state (e.g. which nights are open) while another is shown.
            val tabStates = rememberSaveableStateHolder()
            Scaffold(
                containerColor = LullColors.background,
                contentColor = Color.White,
                bottomBar = {
                    NavigationBar(containerColor = LullColors.surface, contentColor = Color.White) {
                        Tab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab.ordinal,
                                onClick = { selectedTab = tab.ordinal },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.title) },
                                modifier = Modifier.testTag("${tab.title} tab"),
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color.White,
                                    selectedTextColor = Color.White,
                                    indicatorColor = LullColors.raised,
                                    unselectedIconColor = LullColors.secondary,
                                    unselectedTextColor = LullColors.secondary,
                                ),
                            )
                        }
                    }
                },
            ) { innerPadding ->
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    val tab = Tab.entries[selectedTab]
                    tabStates.SaveableStateProvider(tab.name) {
                        when (tab) {
                            Tab.REMOTE -> RemoteScreen(model)
                            Tab.SCHEDULE -> ScheduleScreen(model)
                            Tab.HISTORY -> HistoryScreen(model)
                        }
                    }
                }
            }
            ErrorAlert(model)
            SlotChooser(model)
        }
    }
}

/**
 * "Something went wrong (Left)": names the side when both have a BedJet, since either may be the one in trouble.
 * Collects every side's label and BedJet, so the title follows renames and set-up.
 */
@Composable
private fun rememberSideTitle(model: AppModel): (SideModel, String) -> String {
    val labels = model.sides.map { it.label.collectAsState().value }
    val setUp = model.sides.map { rememberIsSetUp(model, it) }
    val bothSetUp = setUp.all { it }
    return remember(labels, bothSetUp) {
        { side: SideModel, title: String -> if (bothSetUp) "$title (${labels[side.index]})" else title }
    }
}

/** The first side with an error to report; the other side's, if any, shows once this one is dismissed. */
@Composable
private fun ErrorAlert(model: AppModel) {
    val messages = model.sides.map { it.errorMessage.collectAsState().value }
    val sideTitle = rememberSideTitle(model)
    val index = messages.indexOfFirst { it != null }
    if (index < 0) return
    val side = model.sides[index]
    val message = messages[index] ?: return
    AlertDialog(
        onDismissRequest = { side.clearError() },
        confirmButton = {
            TextButton(onClick = { side.clearError() }) { Text("OK", fontWeight = FontWeight.SemiBold) }
        },
        title = { Text(sideTitle(side, "Something went wrong")) },
        text = { Text(message) },
        containerColor = LullColors.raised,
        titleContentColor = Color.White,
        textContentColor = LullColors.secondary,
    )
}

/** Port of the `confirmationDialog` shown when every slot on the BedJet is taken, for the first side that asks. */
@Composable
private fun SlotChooser(model: AppModel) {
    val choices = model.sides.map { it.needsSlotChoice.collectAsState().value }
    val sideTitle = rememberSideTitle(model)
    val index = choices.indexOf(true)
    if (index < 0) return
    val side = model.sides[index]
    // Keyed so the slots listed are always this side's, even when the other side asked first.
    key(side.index) {
        SlotChooserDialog(side = side, title = sideTitle(side, "Your BedJet's three slots are full"))
    }
}

@Composable
private fun SlotChooserDialog(side: SideModel, title: String) {
    val deviceSlots by side.deviceSlots.collectAsState()
    val scope = rememberActionScope()
    AlertDialog(
        onDismissRequest = { side.dismissSlotChoice() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Lull keeps tonight's schedule in one slot on the BedJet. Pick one to reuse. " +
                        "Everything on the BedJet is already copied into your schedules.",
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                deviceSlots.forEachIndexed { index, program ->
                    TextButton(
                        onClick = {
                            // A confirmation dialog closes as soon as one of its buttons is tapped.
                            side.dismissSlotChoice()
                            scope.launchAction { side.startTonight(replacingSlot = index) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Replace “${program?.name ?: "Slot ${index + 1}"}”",
                            color = Color.White,
                            fontSize = 17.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { side.dismissSlotChoice() }) { Text("Cancel", fontWeight = FontWeight.SemiBold) }
        },
        containerColor = LullColors.raised,
        titleContentColor = Color.White,
        textContentColor = LullColors.secondary,
    )
}
