package com.quire.reader.reader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import kotlinx.coroutines.launch
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.shared.publication.Link
import com.quire.reader.navigator.epub.EpubNavigatorFactory
import com.quire.reader.navigator.epub.EpubNavigatorFragment
import com.quire.reader.navigator.epub.EpubPreferences
import com.quire.reader.navigator.epub.ReaderLinkPolicy
import com.quire.reader.navigator.epub.ReaderMedia
import com.quire.reader.navigator.epub.css.FontStyle
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.util.BaseActionModeCallback
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.AbsoluteUrl

import timber.log.Timber

/** What the user picked from the text-selection menu. */
enum class SelectionAction(val label: String) { Highlight("Highlight"), Note("Note"), Copy("Copy"), LookUp("Look up"), Share("Share"), ReadAloud("Read from here") }

private class SelectionMenu(
  private val actions: List<SelectionAction>,
  private val onStarted: () -> Unit,
  private val onEnded: () -> Unit,
  private val onAction: (SelectionAction) -> Unit,
) : BaseActionModeCallback() {
  override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
    actions.forEachIndexed { i, a -> menu.add(Menu.NONE, i, i, a.label).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM) }
    // A long press made a selection: the definition card may open beside the menu (the menu stays).
    onStarted()
    return true
  }

  override fun onDestroyActionMode(mode: ActionMode) {
    onEnded()
  }

  override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
    onAction(actions[item.itemId])
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
  selectionActions: List<SelectionAction>,
  onTap: (xFraction: Float) -> Unit,
  onSelectionAction: (SelectionAction) -> Unit,
  onHighlightTapped: (id: Long) -> Unit,
  /** A link to another place in the book was tapped; returns whether to follow it. */
  onInternalLink: (Link) -> Boolean,
  /** An image, SVG or table the reader asked to see full screen. */
  onMedia: (ReaderMedia) -> Unit,
  /** A selection was made, and later cleared (the definition card follows it). */
  onSelectionStarted: () -> Unit = {},
  onSelectionEnded: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val activity = LocalActivity.current as FragmentActivity
  val containerId = remember(session) { View.generateViewId() }
  val tag = remember(session) { "epub-navigator-$containerId" }
  val tap by rememberUpdatedState(onTap)
  val selection by rememberUpdatedState(onSelectionAction)
  val tapped by rememberUpdatedState(onHighlightTapped)
  val internalLink by rememberUpdatedState(onInternalLink)
  val media by rememberUpdatedState(onMedia)
  val selectionStarted by rememberUpdatedState(onSelectionStarted)
  val selectionEnded by rememberUpdatedState(onSelectionEnded)

  DisposableEffect(session) {
    onDispose {
      val fm = activity.supportFragmentManager
      fm.findFragmentByTag(tag)?.let { f -> if (!activity.isFinishing) fm.commitNow(allowStateLoss = true) { remove(f) } }
      session.detach()
    }
  }

  // The container's id and the fragment's tag belong to this session, but AndroidView builds its view only once per place in
  // the tree. When one session replaces another with no composition in between (the activity was stopped, a frame was
  // missed) the old view, still carrying the old id, would be reused and the new fragment would have no container to attach to.
  key(session) {
    AndroidView(
      modifier = modifier,
      factory = { ctx -> FrameLayout(ctx).apply { addView(FragmentContainerView(ctx).apply { id = containerId }, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT) } },
      update = { container ->
        val fm = activity.supportFragmentManager
        if (fm.findFragmentByTag(tag) == null) {
          val factory = EpubNavigatorFactory(session.publication)
          fm.fragmentFactory = factory.createFragmentFactory(
            initialLocator = session.current.value,
            initialPreferences = preferences,
            listener = object : EpubNavigatorFragment.Listener {
              override fun shouldFollowInternalLink(link: Link, context: HyperlinkNavigator.LinkContext?): Boolean =
                internalLink(link)

              override fun onExternalLinkActivated(url: AbsoluteUrl) {
                // Only web links may leave the reader. The navigator's policy already
                // filters; this re-check is defense in depth before the ACTION_VIEW launch.
                if (!ReaderLinkPolicy.isSupportedScheme(url.scheme.value)) {
                  Timber.w("Rejected external link with unsupported scheme: $url")
                  return
                }
                try {
                  activity.startActivity(Intent(Intent.ACTION_VIEW, url.toString().toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                  Toast.makeText(activity, "No app can open this link", Toast.LENGTH_SHORT).show()
                } catch (e: RuntimeException) {
                  Timber.w(e, "Could not open external link")
                }
              }
            },
            configuration = EpubNavigatorFragment.Configuration {
              selectionActionModeCallback = SelectionMenu(selectionActions, { selectionStarted() }, { selectionEnded() }) { selection(it) }
              // Chapter changes come from the vertical scroll column; sideways swipes
              // must not turn pages.
              disablePageTurnsWhileScrolling = true
              // The reading fonts. The continuous-scroll shell and its script are served
              // on the publication origin by WebViewServer's reserved route, so they do
              // not need an assets-host entry.
              servedAssets = listOf("fonts/.*")
              decorationTemplates = HtmlDecorationTemplates.defaultTemplates()
              // A font with no files of ours is one Readium declares itself.
              ReaderFontList.filter { it.files.isNotEmpty() }.forEach { font ->
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
          nav.onMedia = { media(it) }
          session.attach(nav)
          session.scope.launch { nav.currentLocator.collect { session.onLocator(it) } }
          // Preference submissions belong to the session: ReaderScreen submits mapped
          // preferences through it, deduplicated and latest-wins, so no update-pass
          // dedup happens here and detached navigators never miss a change.
        }
      },
    )
  }
}
