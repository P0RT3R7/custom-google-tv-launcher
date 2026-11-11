package com.custom.hisenselauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.custom.hisenselauncher.data.Place
import com.custom.hisenselauncher.data.Places
import com.custom.hisenselauncher.data.Prefs
import com.custom.hisenselauncher.dream.ScreensaverTheme
import com.custom.hisenselauncher.ui.NightSetting

/**
 * Change launcher settings over adb without opening anything on screen.
 * Protected by android.permission.DUMP, which the adb shell has and normal apps don't.
 *
 *   adb shell am broadcast -n com.custom.hisenselauncher/.ConfigReceiver \
 *     --es screensaver_theme NIGHT_SKY        # ROTATE | NIGHT_SKY | TOPO | JELLYFISH
 *     --es night_mode AUTO                    # AUTO | ON | OFF
 *     --es set_place "Denver, Colorado" --ef lat 39.7392 --ef lon -104.9903
 */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val prefs = Prefs(ctx)
        val done = ArrayList<String>()
        i.getStringExtra("screensaver_theme")?.let { name ->
            ScreensaverTheme.entries.firstOrNull { it.name == name }?.let { prefs.screensaverTheme = it.name; done += "screensaver_theme=${it.name}" }
        }
        i.getStringExtra("night_mode")?.let { name ->
            NightSetting.entries.firstOrNull { it.name == name }?.let { prefs.nightMode = it.name; done += "night_mode=${it.name}" }
        }
        val place = i.getStringExtra("set_place")
        if (place != null && i.hasExtra("lat") && i.hasExtra("lon")) {
            Places.save(prefs, Place(place, i.getFloatExtra("lat", 0f).toDouble(), i.getFloatExtra("lon", 0f).toDouble()))
            done += "place=$place"
        }
        // --es open weather | search | ski | preview_NIGHT_SKY | preview_TOPO | preview_JELLYFISH  (for screenshots)
        i.getStringExtra("open")?.let { what ->
            val target = when {
                what == "weather" -> Intent(ctx, com.custom.hisenselauncher.weather.WeatherActivity::class.java)
                what == "ski" -> Intent(ctx, com.custom.hisenselauncher.weather.SkiActivity::class.java)
                what == "search" -> Intent(ctx, com.custom.hisenselauncher.search.SearchActivity::class.java)
                what.startsWith("preview_") -> Intent(ctx, com.custom.hisenselauncher.dream.ScreensaverPreviewActivity::class.java)
                    .putExtra(com.custom.hisenselauncher.dream.ScreensaverPreviewActivity.EXTRA_THEME, what.removePrefix("preview_"))
                else -> null
            }
            target?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); done += "open=$what" }
        }
        val msg = if (done.isEmpty()) "nothing changed" else done.joinToString(", ")
        Log.i("ConfigReceiver", msg)
        resultData = msg
    }
}
