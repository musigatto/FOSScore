package com.musigatto.fosscore.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.ui.viewer.LINE_ABOUT
import com.musigatto.fosscore.ui.viewer.LINE_LIBRARY
import com.musigatto.fosscore.ui.viewer.LINE_PRACTICE
import com.musigatto.fosscore.ui.viewer.LINE_SETLIST
import com.musigatto.fosscore.ui.viewer.LINE_SETTINGS
import com.musigatto.fosscore.ui.viewer.LINE_TAG
import com.musigatto.fosscore.ui.viewer.LINE_PEOPLE
import com.musigatto.fosscore.ui.viewer.LINE_GENRE
import com.musigatto.fosscore.ui.viewer.LineIcon
import kotlinx.coroutines.launch

/**
 * The side menu, opened by the ☰ button of the library or setlists header or by swiping from the
 * left edge. It holds what concerns the whole app: the views (the library, the setlists, then,
 * greyed out until they are built, tags and practice), the preferences (language, keyboard and
 * pedals, MIDI, the export and import of the library) and "About" (roadmap 3.6). [current] is the
 * view shown, marked in the menu.
 */
@Composable
fun LibraryMenuDrawer(
    drawerState: DrawerState,
    current: MenuView,
    onOpenPreferences: () -> Unit,
    onOpenView: (MenuView) -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp)
                )
                MenuView.entries.forEach { view ->
                    if (view.ready) {
                        DrawerRow(stringResource(view.label), view.icon, selected = view == current) {
                            scope.launch { drawerState.close() }
                            onOpenView(view)
                        }
                    } else {
                        SoonItem(view.label, view.icon)
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 4.dp))
                DrawerRow(stringResource(R.string.library_menu_settings), LINE_SETTINGS) {
                    scope.launch { drawerState.close() }
                    onOpenPreferences()
                }
                SoonItem(R.string.library_menu_about, LINE_ABOUT)
            }
        },
        content = content
    )
}

/**
 * The views of the app, in the side menu (and in the icon bar that can take its place): their
 * name, their icon, and whether they are built yet ([ready]; the others show greyed out).
 */
enum class MenuView(@StringRes val label: Int, val icon: String, val ready: Boolean) {
    LIBRARY(R.string.library_title, LINE_LIBRARY, true),
    SETLISTS(R.string.library_menu_setlists, LINE_SETLIST, true),
    TAGS(R.string.library_menu_tags, LINE_TAG, true),
    PEOPLE(R.string.library_menu_people, LINE_PEOPLE, true),
    GENRES(R.string.library_menu_genres, LINE_GENRE, true),
    PRACTICE(R.string.library_menu_practice, LINE_PRACTICE, false),
}

// an entry that is not built yet: greyed out ("Coming soon" for a screen reader), and a tap does nothing
@Composable
private fun SoonItem(label: Int, icon: String) {
    DrawerRow(stringResource(label), icon, soon = stringResource(R.string.library_menu_soon))
}

// An entry of the menu, on one line: the whole menu stays in sight on a phone. [soon]: not built
// yet, greyed out and not clickable. [selected]: the view shown, marked as in the viewer's menu.
// [icon]: one of the line icons, before the label.
@Composable
private fun DrawerRow(label: String, icon: String, soon: String? = null, selected: Boolean = false, onClick: () -> Unit = {}) {
    val color = when {
        soon != null -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .heightIn(min = DRAWER_ROW_HEIGHT)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(enabled = soon == null, role = Role.Button, onClick = onClick)
            .semantics {
                if (soon != null) stateDescription = soon
                this.selected = selected
            }
            .padding(horizontal = 16.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides color) { LineIcon(icon) }
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            fontWeight = if (selected) FontWeight.Bold else null,
        )
    }
}

// a row of the menu: as the viewer's menu, low enough for the whole list, high enough for a finger
private val DRAWER_ROW_HEIGHT = 40.dp
