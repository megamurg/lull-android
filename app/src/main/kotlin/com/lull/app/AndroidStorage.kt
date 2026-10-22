package com.lull.app

import android.content.Context
import android.content.SharedPreferences
import com.lull.app.model.Storage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * [Storage] on Android: SharedPreferences "lull" for settings (UserDefaults on iOS) and files in `filesDir`
 * for the JSON documents (Application Support on iOS).
 */
class AndroidStorage(context: Context) : Storage {
    private val preferences: SharedPreferences = context.getSharedPreferences("lull", Context.MODE_PRIVATE)
    private val directory: File = context.filesDir

    // A value saved under another type reads as missing, like `UserDefaults.object(forKey:) as? Bool`.
    override fun getBoolean(key: String): Boolean? =
        if (!preferences.contains(key)) null else try { preferences.getBoolean(key, false) } catch (e: ClassCastException) { null }

    override fun putBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    override fun getString(key: String): String? =
        try { preferences.getString(key, null) } catch (e: ClassCastException) { null }

    override fun putString(key: String, value: String?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }

    override fun getInt(key: String): Int? =
        if (!preferences.contains(key)) null else try { preferences.getInt(key, 0) } catch (e: ClassCastException) { null }

    override fun putInt(key: String, value: Int) {
        preferences.edit().putInt(key, value).apply()
    }

    override fun readFile(name: String): String? =
        try {
            File(directory, name).takeIf { it.isFile }?.readText()
        } catch (e: IOException) {
            null
        }

    /** Written beside the file and renamed over it, so a crash mid-write never leaves half a file. */
    override fun writeFile(name: String, contents: String) {
        val target = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(contents.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            // rename(2): atomic within one directory, and replaces the old file.
            if (!temporary.renameTo(target)) temporary.delete()
        } catch (e: IOException) {
            // Like Swift's `try? data.write(to:options: .atomic)`: the next change saves again.
            temporary.delete()
        }
    }
}
