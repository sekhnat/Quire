package com.quire.reader

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commitNow
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.quire.reader.navigator.epub.EpubNavigatorFragment
import com.quire.reader.theme.QuireTheme
import com.quire.reader.ui.QuireApp

class MainActivity : FragmentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    // Android restores saved fragments inside super.onCreate, instantiating them through the
    // FragmentManager's factory. The navigator's only constructor is internal and parameterized
    // (the vendored Readium fragment), so the default reflection factory crashes with "could
    // not find Fragment constructor". Readium's supported path: give the framework a factory
    // that builds a dummy navigator — the real publication opens asynchronously in the
    // reader's ReaderState, so no real factory can exist this early.
    supportFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
    super.onCreate(savedInstanceState)
    // Anything restored is a stale dummy: it would throw RestorationNotSupportedException on
    // resume, and its page fragments are just as dead. The reader rebuilds itself — from the
    // live ReaderSession after recreation, or from the persisted position after process death —
    // so drop the whole restored set now, long before anything can resume.
    val restored = supportFragmentManager.fragments
    if (restored.isNotEmpty()) {
      supportFragmentManager.commitNow(allowStateLoss = true) { restored.forEach { remove(it) } }
    }
    enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
    setContent {
      QuireTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { QuireApp() } }
    }
  }
}