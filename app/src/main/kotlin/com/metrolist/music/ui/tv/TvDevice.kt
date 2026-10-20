/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** True on Android TV / Google TV and Fire TV, where the app shows the 10-foot UI. */
fun Context.isTvDevice(): Boolean {
    val modeType = getSystemService(UiModeManager::class.java)?.currentModeType
    return modeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        packageManager.hasSystemFeature("amazon.hardware.fire_tv")
}
