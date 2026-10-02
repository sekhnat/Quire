package com.quire.reader.reader

import android.content.Intent
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.view.View
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import kotlinx.coroutines.launch
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.util.BaseActionModeCallback
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.AbsoluteUrl

/** What the user picked from the text-selection menu. */
enum class SelectionAction(val label: String) { Highlight("Highlight"), Note("Note"), Copy("Copy") }

private class SelectionMenu(private val onAction: (SelectionAction) -> Unit) : BaseActionModeCallback() {
  override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
    SelectionAction.entries.forEachIndexed { i, a -> menu.add(Menu.NONE, i, i, a.label).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM) }
    return true
  }

  override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
    onAction(SelectionAction.entries[item.itemId])
    mode.finish()
    return true
  }
}

/**
 * Embeds Readium's navigator in Compose and wires the app's behaviour into it: tap handling, text
 * selection menu, tapping a highlight, external links, and the bundled reading fonts.
 */
@OptIn(ExperimentalReadiumApi::class)
@Composable
fun EpubHost(
  session: ReaderSession,
  preferences: EpubPreferences,
  onTap: (xFraction: Float) -> Unit,
  onSelectionAction: (SelectionAction) -> Unit,
  onHighlightTapped: (id: Long) -> Unit,
  /** In scroll mode, scrolling past the end/start of a chapter calls this (true = forward). */
  continuousScroll: Boolean,
  onEdgeScroll: (forward: Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val activity = LocalActivity.current as FragmentActivity
  val containerId = remember(session) { View.generateViewId() }
  val tag = remember(session) { "epub-navigator-$containerId" }
  val tap by rememberUpdatedState(onTap)
  val selection by rememberUpdatedState(onSelectionAction)
  val tapped by rememberUpdatedState(onHighlightTapped)
  val edge by rememberUpdatedState(onEdgeScroll)
  var lastPrefs = remember(session) { arrayOfNulls<EpubPreferences>(1) }

  DisposableEffect(session) {
    onDispose {
      val fm = activity.supportFragmentManager
      fm.findFragmentByTag(tag)?.let { f -> if (!activity.isFinishing) fm.commitNow(allowStateLoss = true) { remove(f) } }
      session.detach()
    }
  }

  AndroidView(
    modifier = modifier,
    factory = { ctx -> EdgeScrollLayout(ctx).apply { addView(FragmentContainerView(ctx).apply { id = containerId }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } },
    update = { container ->
      container.continuous = continuousScroll
      container.onEdgeScroll = { edge(it) }
      val fm = activity.supportFragmentManager
      if (fm.findFragmentByTag(tag) == null) {
        val factory = EpubNavigatorFactory(session.publication)
        fm.fragmentFactory = factory.createFragmentFactory(
          initialLocator = session.current.value,
          initialPreferences = preferences,
          listener = object : EpubNavigatorFragment.Listener {
            override fun onExternalLinkActivated(url: AbsoluteUrl) {
              runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, url.toString().toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
          },
          configuration = EpubNavigatorFragment.Configuration {
            selectionActionModeCallback = SelectionMenu { selection(it) }
            // Chapters change by scrolling (see EdgeScrollLayout), so accidental sideways swipes must not.
            disablePageTurnsWhileScrolling = true
            servedAssets = listOf("fonts/.*")
            decorationTemplates = HtmlDecorationTemplates.defaultTemplates()
            ReaderFontList.forEach { font ->
              addFontFamilyDeclaration(FontFamily(font.name)) {
                font.files.forEach { file ->
                  addFontFace {
                    addSource(file.asset)
                    setFontStyle(if (file.italic) FontStyle.ITALIC else FontStyle.NORMAL)
                    setFontWeight(file.weights)
                  }
                }
              }
            }
          },
        )
        fm.commitNow(allowStateLoss = true) { add(containerId, EpubNavigatorFragment::class.java, null, tag) }
        val nav = fm.findFragmentByTag(tag) as EpubNavigatorFragment
        nav.addInputListener(object : InputListener {
          override fun onTap(event: TapEvent): Boolean {
            val w = container.width.takeIf { it > 0 } ?: return false
            tap(event.point.x / w)
            return true
          }
        })
        nav.addDecorationListener(ReaderSession.HIGHLIGHTS, object : DecorableNavigator.Listener {
          override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
            val id = (event.decoration.extras["id"] as? Number)?.toLong() ?: return false
            tapped(id)
            return true
          }
        })
        session.attach(nav)
        session.scope.launch { nav.currentLocator.collect { session.onLocator(it) } }
        lastPrefs[0] = preferences
      } else if (lastPrefs[0] != preferences) {
        (fm.findFragmentByTag(tag) as? EpubNavigatorFragment)?.submitPreferences(preferences)
        lastPrefs[0] = preferences
      }
    },
  )
}
