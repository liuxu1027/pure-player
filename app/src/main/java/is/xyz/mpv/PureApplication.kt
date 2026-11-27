package `is`.xyz.mpv

import android.app.Application
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager

/**
 * Pure Player application: applies the saved day/night theme before any
 * Activity is created, so the first frame already uses the right theme.
 */
class PureApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PureTheme.applyFromPrefs(PreferenceManager.getDefaultSharedPreferences(this))
    }
}

/** Day/night theme handling for the "app_theme" preference (system/dark/light). */
object PureTheme {
    const val KEY_APP_THEME = "app_theme"
    const val DEFAULT = "system"

    fun apply(mode: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun applyFromPrefs(prefs: SharedPreferences) {
        apply(prefs.getString(KEY_APP_THEME, DEFAULT) ?: DEFAULT)
    }
}

/**
 * Edge-to-edge with a transparent status bar; the status-bar icon shade
 * follows the current theme (dark icons on a light theme, light icons on
 * a dark theme). Activities recreate on theme change (none of them declares
 * uiMode in configChanges), so the shade is re-evaluated in onCreate.
 */
internal fun AppCompatActivity.applyEdgeToEdge() {
    enableEdgeToEdge()
    window.statusBarColor = Color.TRANSPARENT
    val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !night
}
