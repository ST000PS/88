package com.st000ps.powerstone.widget

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/** How one widget looks; set from its settings screen (long-press the widget → settings). */
data class WidgetOptions(
    /** What fills the big card: sampler pads drumming at the track's tempo, a spinning record, or the cover. */
    val hero: Hero = Hero.PADS,
    /** Animations while playing (pads, lamp, dial needle, record). Off = still, progress only. */
    val motion: Boolean = true,
    /** Dial backlight in the cover's colour, or 88.8 rose. */
    val coverColour: Boolean = true,
) {
    enum class Hero { PADS, RECORD, COVER }
}

/**
 * What the widget needs to survive the app being closed (last track, its cover and colour) and each
 * widget's options. The cover files live in private storage and reach the launcher through
 * [WidgetArtProvider], so widget updates carry a short link instead of the picture itself.
 */
object WidgetStore {
    private const val PREFS = "widget"

    fun dir(context: Context) = File(context.filesDir, "widget").apply { mkdirs() }

    fun options(context: Context, id: Int): WidgetOptions {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return WidgetOptions(
            hero = WidgetOptions.Hero.entries.getOrNull(p.getInt("hero_$id", 0)) ?: WidgetOptions.Hero.PADS,
            motion = p.getBoolean("motion_$id", true),
            coverColour = p.getBoolean("colour_$id", true),
        )
    }

    fun setOptions(context: Context, id: Int, o: WidgetOptions) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("hero_$id", o.hero.ordinal).putBoolean("motion_$id", o.motion).putBoolean("colour_$id", o.coverColour)
            .apply()
    }

    fun forget(context: Context, ids: IntArray) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        ids.forEach { e.remove("hero_$it").remove("motion_$it").remove("colour_$it") }
        e.apply()
    }

    /** The last thing that played, for when the app isn't running. */
    fun saveLast(context: Context, s: WidgetState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("title", s.title).putString("subtitle", s.subtitle)
            .putString("art", s.artName).putString("label", s.labelName).putInt("tint", s.tint)
            .putBoolean("hasTrack", s.hasTrack)
            .putInt("today", s.todayMinutes).putLong("todayDay", day())
            .apply()
    }

    fun loadLast(context: Context): WidgetState {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dir = dir(context)
        fun file(key: String) = p.getString(key, null)?.takeIf { File(dir, it).exists() }
        return WidgetState(
            title = p.getString("title", "") ?: "",
            subtitle = p.getString("subtitle", "") ?: "",
            artName = file("art"),
            labelName = file("label"),
            tint = p.getInt("tint", WidgetState.DEFAULT_TINT),
            hasTrack = p.getBoolean("hasTrack", false),
            todayMinutes = if (p.getLong("todayDay", 0) == day()) p.getInt("today", 0) else 0,
        )
    }

    private fun day(): Long = java.util.Calendar.getInstance().run { get(java.util.Calendar.YEAR) * 1000L + get(java.util.Calendar.DAY_OF_YEAR) }

    /** Saves this cover (and its record label) and returns their file names; keeps only the newest few. */
    fun saveArt(context: Context, key: String, art: Bitmap, label: Bitmap): Pair<String, String> {
        val dir = dir(context)
        val id = Integer.toHexString(key.hashCode())
        val names = "a_$id.png" to "l_$id.png"
        File(dir, names.first).outputStream().use { art.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, names.second).outputStream().use { label.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // The launcher may still be drawing the previous cover, so keep a few behind.
        dir.listFiles { f -> f.name.startsWith("a_") }?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { old ->
            old.delete()
            File(dir, "l_" + old.name.removePrefix("a_")).delete()
        }
        return names
    }
}

/**
 * Read-only access to the widget's cover files, granted to the home screen app only (see [grant]).
 * Not exported: the launcher gets per-file read grants.
 */
class WidgetArtProvider : ContentProvider() {
    companion object {
        fun authority(context: Context) = "${context.packageName}.widgetart"

        fun uri(context: Context, name: String): Uri =
            Uri.Builder().scheme("content").authority(authority(context)).appendPath(name).build()

        private val granted = HashSet<Uri>()

        /**
         * Lets the home screen app(s) read this file. Grants last until reboot, which also restarts
         * this process, so each link is granted once per process (renders happen every second).
         */
        @Synchronized fun grant(context: Context, uri: Uri) {
            if (!granted.add(uri)) return
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.queryIntentActivities(home, 0).forEach {
                context.grantUriPermission(it.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read-only")
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        val dir = WidgetStore.dir(ctx)
        val file = File(dir, name)
        // Only plain names inside the widget folder.
        if (name.contains('/') || file.parentFile?.canonicalPath != dir.canonicalPath || !file.exists()) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/png"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
