package com.chaman.browser

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

class ChamanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Make sure every setting starts at its default (all toggles OFF).
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
        // The ad blocker is not a setting: it always loads.
        AdBlocker.init(this)
        applyNightMode(this)
    }

    companion object {
        fun applyNightMode(context: Context) {
            AppCompatDelegate.setDefaultNightMode(
                if (Prefs(context).darkMode) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }
}
