package com.quire.reader.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.quire.reader.QuireApplication
import com.quire.reader.theme.Nq
import com.quire.reader.ui.detail.DetailScreen
import com.quire.reader.ui.library.LibraryScreen
import com.quire.reader.ui.onboarding.OnboardingScreen
import com.quire.reader.ui.reader.ReaderScreen

@Composable
fun QuireApp(
  vm: QuireViewModel = viewModel(
    factory = viewModelFactory {
      initializer { QuireViewModel(this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as QuireApplication) }
    },
  ),
) {
  androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) { vm.onForeground() }
  val s by vm.state.collectAsStateWithLifecycle()
  val lib by vm.library.collectAsStateWithLifecycle()
  Box(Modifier.fillMaxSize().background(Nq.bg)) {
    Crossfade(s.screen, Modifier.fillMaxSize(), animationSpec = tween(180), label = "screen") { screen ->
      when (screen) {
        Screen.Splash -> Box(Modifier.fillMaxSize().background(Nq.bg))
        Screen.Onboard -> OnboardingScreen(s, vm)
        Screen.Library -> LibraryScreen(s, lib, vm)
        Screen.Detail -> DetailScreen(s, lib, vm)
        Screen.Reader -> ReaderScreen(s, lib, vm)
      }
    }
    Toast(s.toast, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 120.dp))
  }
}
