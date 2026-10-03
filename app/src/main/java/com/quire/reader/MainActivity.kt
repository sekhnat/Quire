package com.quire.reader

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.quire.reader.theme.QuireTheme
import com.quire.reader.ui.QuireApp

class MainActivity : FragmentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    // The reader's vendor fork of EpubNavigatorFragment has only a parameterized `internal`
    // constructor. When Android restores the activity with saved state (process death),
    // FragmentManager tries to re-instantiate that fragment through reflection and crashes with
    // "could not find Fragment constructor". The app rebuilds the reader itself from persisted
    // position, so the framework-saved fragment list is not needed — drop it before the
    // FragmentManager sees it.
    if (savedInstanceState != null) {
      savedInstanceState.keySet().remove("android:fragments")
      savedInstanceState.keySet().remove("android:viewHierarchyState")
    }
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
    setContent {
      QuireTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { QuireApp() } }
    }
  }
}