package app.gamenative.ui.screen.settings

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.gamenative.R
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsSwitch

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@Composable
fun SettingsGroupDownloads() {
    SettingsGroup {
        val context = LocalContext.current
        var backgroundEnabled by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

        // The switch mirrors the real system state: re-check when returning
        // from the system battery dialog / settings screen.
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    backgroundEnabled = isIgnoringBatteryOptimizations(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            state = backgroundEnabled,
            title = { Text(stringResource(R.string.settings_downloads_battery_title)) },
            subtitle = {
                Text(
                    stringResource(
                        if (backgroundEnabled) {
                            R.string.settings_downloads_battery_subtitle_exempt
                        } else {
                            R.string.settings_downloads_battery_subtitle_restricted
                        },
                    ),
                )
            },
            icon = { Icon(imageVector = Icons.Filled.BatterySaver, contentDescription = null) },
            onCheckedChange = { enabled ->
                if (enabled) {
                    // Ask the system to exempt GameNative from battery optimization
                    // so downloads keep running while the device sleeps.
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                } else {
                    // There is no API to re-enable optimization: send the user to the
                    // system battery-optimization list to revert it themselves.
                    context.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }
                // State stays as-is until ON_RESUME re-reads the system value
                // (the user can deny the request dialog).
            },
        )
    }
}
