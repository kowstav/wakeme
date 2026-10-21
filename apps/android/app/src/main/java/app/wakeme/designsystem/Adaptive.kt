package app.wakeme.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Forms and detail screens keep a readable column on wide windows instead of stretching controls. */
val ReadableWidth = 640.dp

/** Card lists gain a column for every 320 dp: one on phones and in a list pane, more on wide windows. */
val CardMinWidth = 320.dp

/** Wide lists stop growing here; a desktop window doesn't need five columns of cards. */
private val ListMaxWidth = 1200.dp

/**
 * Centres [content] in a column no wider than [maxWidth] (API 37 resizability: large screens,
 * split screen, foldables and desktop windows; no orientation lock).
 */
@Composable
fun Readable(maxWidth: Dp = ReadableWidth, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxWidth)) { content() }
    }
}

/**
 * android/skills adaptive, step 4: a vertical list of cards in a grid whose column count follows
 * the available width. Headings, forms and buttons go in [fullWidth] rows.
 */
@Composable
fun AdaptiveList(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(0.dp), content: LazyGridScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(CardMinWidth),
            modifier = modifier.widthIn(max = ListMaxWidth).fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm),
            horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm),
            content = content,
        )
    }
}

/** A row across every column, kept to a readable width. Children stack as they did in a list item. */
fun LazyGridScope.fullWidth(key: Any? = null, content: @Composable ColumnScope.() -> Unit) {
    // Grid items get an exact width; wrapContentWidth lets the column be narrower than the row.
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        Column(Modifier.fillMaxWidth().wrapContentWidth(Alignment.Start).widthIn(max = ReadableWidth), content = content)
    }
}

/**
 * Shown in the detail pane of a list-detail layout before anything is chosen (two panes on
 * tablets, unfolded foldables and desktop windows).
 */
@Composable
fun DetailPlaceholder(text: String) {
    Box(Modifier.fillMaxSize().padding(WakeMeTokens.spaceXl), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = WM.colors.textSecondary, textAlign = TextAlign.Center)
    }
}

/**
 * In a two-pane layout the list stays on screen while its detail pane changes things (a friend
 * removed, a challenge published). Reloads the list when it's back on top; a single-pane list
 * is recomposed on return and loads by itself.
 */
@Composable
fun ReloadWhenBackOnTop(onTop: Boolean, reload: () -> Unit) {
    var wasOnTop by remember { mutableStateOf(onTop) }
    LaunchedEffect(onTop) {
        if (onTop && !wasOnTop) reload()
        wasOnTop = onTop
    }
}
