package com.quire.reader.ui.settings

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.quire.reader.ui.QButton

/**
 * Settings → Library search: whether Quire is exempt from battery optimization, and a button to ask for it. Exempt,
 * indexing that starts while the app is closed (after a background scan, say) runs in the foreground at full speed, as
 * it does when started from the app; otherwise it runs in short batches that Android throttles.
 */
@Composable
internal fun BackgroundIndexing(indexing: Boolean) {
  val context = LocalContext.current
  var exempt by remember { mutableStateOf(isExempt(context)) }
  // The choice is made in a system dialog or screen; read it again on coming back.
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exempt = isExempt(context) }
  Value(
    "Background indexing",
    if (exempt) "Unrestricted" else "Optimized",
    if (exempt) {
      "Indexing runs at full speed with its notification, even when it starts while Quire is closed."
    } else {
      "Indexing that starts while Quire is closed runs in short batches that Android slows down. Allow unrestricted battery use to let it run at full speed with its notification."
    },
  )
  Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (exempt) {
      QButton("Open battery settings", { openBatterySettings(context) }, Modifier.fillMaxWidth(), size = 12.5f)
    } else {
      QButton("Allow unrestricted battery use", { requestExemption(context) }, Modifier.fillMaxWidth(), size = 12.5f, enabled = indexing)
    }
  }
}

private fun isExempt(context: Context): Boolean =
  context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

/**
 * Asks directly, in the system's one-tap dialog. Play restricts this request to a few kinds of app, but Quire is not
 * distributed there (see the manifest). Some builds lack the dialog; the list of apps in settings does the same job.
 */
@SuppressLint("BatteryLife")
private fun requestExemption(context: Context) {
  val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
  try {
    context.startActivity(request)
  } catch (e: ActivityNotFoundException) {
    openBatterySettings(context)
  }
}

/** The system list of battery-optimized apps, where the exemption is also turned off again. */
private fun openBatterySettings(context: Context) {
  runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}
