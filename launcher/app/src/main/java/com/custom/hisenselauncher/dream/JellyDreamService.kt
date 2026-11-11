package com.custom.hisenselauncher.dream

import android.service.dreams.DreamService
import com.custom.hisenselauncher.data.Prefs

/** The launcher's screensaver: shows the theme picked in ☰ → Screensaver theme (default: rotate). */
class JellyDreamService : DreamService() {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        setContentView(ScreensaverHost(this, ScreensaverTheme.from(Prefs(this).screensaverTheme)))
    }
}
