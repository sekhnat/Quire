package com.quire.reader.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.updateTransition
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
import com.quire.reader.ui.settings.SettingsScreen

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun QuireApp(
  vm: QuireViewModel = viewModel(
    factory = viewModelFactory {
      initializer { QuireViewModel(this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as QuireApplication) }
    },
  ),
) {
  androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) { vm.onForeground() }
  androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { vm.onAppStop() }
  val libState by vm.library.state.collectAsStateWithLifecycle()
  val lib by vm.library.data.collectAsStateWithLifecycle()
  val destination by vm.destination.collectAsStateWithLifecycle()
  val toast by vm.toastText.collectAsStateWithLifecycle()
  Box(Modifier.fillMaxSize().background(Nq.bg)) {
    // Keyed by kind, so moving between two books' pages swaps the content without a fade.
    updateTransition(destination, label = "screen").Crossfade(Modifier.fillMaxSize(), animationSpec = tween(180), contentKey = { it::class }) { d ->
      when (d) {
        Destination.Splash -> Box(Modifier.fillMaxSize().background(Nq.bg))
        is Destination.Onboard -> OnboardingScreen(d.state)
        Destination.Library -> LibraryScreen(libState, lib, vm.library)
        is Destination.Detail -> DetailScreen(d.state, lib)
        is Destination.Reader -> ReaderScreen(d.state)
        is Destination.Settings -> SettingsScreen(d.state)
      }
    }
    Toast(toast, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 120.dp))
  }
}
