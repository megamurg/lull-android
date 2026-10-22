package com.lull.app

import com.lull.app.model.JsonCodec
import com.lull.app.model.Saved
import com.lull.app.model.SavedHistory
import com.lull.app.model.SavedSide
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** [JsonCodec] with kotlinx.serialization, which the model can't use directly (see PORT_PLAN §2). */
class AndroidJsonCodec : JsonCodec {
    private val json = Json {
        // Files written by a newer version still open in an older one.
        ignoreUnknownKeys = true
        encodeDefaults = true
        // History events and endings are sealed classes, told apart by their @SerialName.
        classDiscriminator = "type"
    }

    override fun encodeSaved(saved: Saved): String = json.encodeToString(Saved.serializer(), saved)

    override fun decodeSaved(text: String): Saved? = decode { json.decodeFromString(Saved.serializer(), text) }

    override fun encodeSide(side: SavedSide): String = json.encodeToString(SavedSide.serializer(), side)

    override fun decodeSide(text: String): SavedSide? = decode { json.decodeFromString(SavedSide.serializer(), text) }

    override fun encodeHistory(history: SavedHistory): String = json.encodeToString(SavedHistory.serializer(), history)

    override fun decodeHistory(text: String): SavedHistory? = decode { json.decodeFromString(SavedHistory.serializer(), text) }

    /** Like Swift's `try? JSONDecoder().decode`: anything unreadable is treated as missing. */
    private inline fun <T> decode(block: () -> T): T? =
        try {
            block()
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
}
