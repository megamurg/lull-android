// Round-trips the app's saved state through kotlinx.serialization with the same Json settings as AndroidJsonCodec.
// Run by tools/verify.sh against the real serialization plugin and runtime, which the Android build also uses.
package com.lull.tools

import com.lull.app.model.ActiveRun
import com.lull.app.model.HistoryEntry
import com.lull.app.model.Saved
import com.lull.app.model.SavedHistory
import com.lull.app.model.Schedule
import com.lull.app.model.Seen
import com.lull.bedjetkit.Mode
import kotlinx.serialization.json.Json

fun main() {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }
    val now = 1_760_000_000_000L
    val saved = Saved(
        schedules = Schedule.templates,
        tonightId = Schedule.templates[1].id,
        run = ActiveRun(schedule = Schedule.templates[0], startedAt = now),
        slot = 2,
    )
    val savedText = json.encodeToString(Saved.serializer(), saved)
    val savedBack = json.decodeFromString(Saved.serializer(), savedText)
    check(savedBack == saved) { "Saved did not round-trip:\n$savedText" }
    check("\"tonightID\"" in savedText) { "tonightID key missing" }

    val history = SavedHistory(
        entries = HistoryEntry.samples(now) + listOf(
            HistoryEntry(date = now, event = HistoryEntry.Event.ScheduleEnded("Cool Drift", HistoryEntry.Ending.Early(12)), room = 44, noticedLater = true),
            HistoryEntry(date = now + 1, event = HistoryEntry.Event.ScheduleEnded(null, HistoryEntry.Ending.ByYou)),
            HistoryEntry(date = now + 2, event = HistoryEntry.Event.ScheduleStarted(null, null)),
            HistoryEntry(date = now + 3, event = HistoryEntry.Event.Off(timerRanOut = true)),
        ),
        lastSeen = Seen(Mode.HEAT, 66, 40, 0, 3600, now),
    )
    val historyText = json.encodeToString(SavedHistory.serializer(), history)
    val historyBack = json.decodeFromString(SavedHistory.serializer(), historyText)
    check(historyBack == history) { "SavedHistory did not round-trip:\n$historyText" }
    for (tag in listOf("\"air\"", "\"off\"", "\"scheduleStarted\"", "\"scheduleEnded\"", "\"byYou\"", "\"finished\"", "\"early\"")) {
        check(tag in historyText) { "discriminator $tag missing from:\n$historyText" }
    }
    // A file from a newer version with a field this version doesn't know still opens.
    val future = savedText.replaceFirst("{", "{\"futureField\":123,")
    check(json.decodeFromString(Saved.serializer(), future) == saved) { "unknown keys not ignored" }
    println("json round-trip OK (${savedText.length} + ${historyText.length} bytes)")
}
