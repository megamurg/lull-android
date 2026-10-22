package com.lull.app

import android.app.Application
import com.lull.app.ble.BleLink
import com.lull.app.ble.BleUnitFinder
import com.lull.app.model.AppModel
import com.lull.app.model.Format
import com.lull.app.model.LaunchOptions
import com.lull.app.model.PrefixedStorage
import com.lull.app.model.Schedule
import com.lull.bedjetkit.DemoLink
import com.lull.bedjetkit.StoredProgram
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Owns the one [AppModel] for the life of the process, as `LullApp` does with its `@State` model.
 *
 * The model is created by the first [MainActivity] to start, through [obtainModel] with the [LaunchOptions] it read
 * from its launching Intent (the iOS launch arguments). Nothing else in the app runs before an activity, so
 * this is the moment iOS creates its model too. Later calls, including from a recreated activity, get the same
 * model and ignore their options.
 */
class LullApplication : Application() {
    /** The model's scope: the main thread, for as long as the process lives. */
    private val mainScope: CoroutineScope = MainScope()
    private var created: AppModel? = null

    /** The model, once [MainActivity] has created it with [obtainModel]. */
    val model: AppModel
        get() = checkNotNull(created) { "MainActivity creates the model in onCreate" }

    /** Creates the model with [options] the first time; afterwards returns the same model and ignores them. */
    fun obtainModel(options: LaunchOptions): AppModel = created ?: create(options).also { created = it }

    override fun onCreate() {
        super.onCreate()
        // Times follow the phone's 12/24-hour setting and dates the locale's own order, as they do on iOS.
        Format.platformTime = { time: LocalTime ->
            // Android: on UTC, which has no daylight-saving gap, so 2:30 isn't shown as 3:30 on the day clocks go forward.
            val utc = TimeZone.getTimeZone("UTC")
            val calendar = Calendar.getInstance(utc).apply {
                set(Calendar.HOUR_OF_DAY, time.hour)
                set(Calendar.MINUTE, time.minute)
                set(Calendar.SECOND, 0)
            }
            android.text.format.DateFormat.getTimeFormat(this).apply { timeZone = utc }.format(calendar.time)
        }
        Format.platformNightTitle = { evening: LocalDate ->
            // The skeleton Swift's `.weekday(.abbreviated).month(.abbreviated).day()` stands for, in the locale's order.
            val date = Date.from(evening.atStartOfDay(ZoneId.systemDefault()).toInstant())
            android.icu.text.DateFormat.getInstanceForSkeleton("EEEMMMd", Locale.getDefault()).format(date)
        }
    }

    private fun create(options: LaunchOptions): AppModel {
        val storage = AndroidStorage(this)
        return AppModel(
            storage = storage,
            scope = mainScope,
            linkFactory = { demo, index ->
                if (demo) {
                    // One simulated BedJet per side, both assigned, so the emulator shows two sides. The second starts empty.
                    DemoLink(
                        name = if (index == 0) "BEDJET-DEMO-L" else "BEDJET-DEMO-R",
                        slots = if (index == 0) {
                            listOf(StoredProgram("Summer nights", Schedule.templates[2].program.steps), null, null)
                        } else {
                            listOf(null, null, null)
                        },
                        scope = mainScope,
                    )
                } else {
                    // AppModel sets the link's `canYield` to "not busy" as soon as it has it, as the Swift model does.
                    // Each side's link remembers its own BedJet under the side's prefix, where the side keeps its settings.
                    BleLink(this, PrefixedStorage(storage, "side$index."))
                }
            },
            finderFactory = { BleUnitFinder(this) },
            options = options,
            json = AndroidJsonCodec(),
        )
    }
}
