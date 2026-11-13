package com.musigatto.fosscore.ui.groups

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.GroupEntry
import com.musigatto.fosscore.library.GroupKind
import com.musigatto.fosscore.library.LibraryRepository
import com.musigatto.fosscore.library.ScoreGroups
import com.musigatto.fosscore.library.Sheet
import com.musigatto.fosscore.library.dragStep
import com.musigatto.fosscore.library.move
import com.musigatto.fosscore.library.moved
import com.musigatto.fosscore.ui.library.IndexedList
import com.musigatto.fosscore.ui.library.SheetText
import com.musigatto.fosscore.ui.viewer.LINE_CLOSE
import com.musigatto.fosscore.ui.viewer.LineIcon
import kotlinx.coroutines.launch

/** What the groups screen says about the groups of one kind; what it says on their scores is in [GroupFacet]. */
class GroupTexts(
    @StringRes val new: Int,
    @StringRes val none: Int,
    @StringRes val deleteTitle: Int,
    @StringRes val deleteText: Int,
    @StringRes val nameTaken: Int,
    /** What merging two groups says, for a kind that can ([ScoreGroups.canMerge]). */
    val merge: MergeTexts? = null,
)

class MergeTexts(
    @StringRes val action: Int,
    @StringRes val pick: Int,
    @StringRes val title: Int,
    @StringRes val text: Int,
    @StringRes val none: Int,
)

/** The words of the screen of [this] kind of group. */
fun GroupKind.texts(): GroupTexts = when (this) {
    GroupKind.SETLIST -> GroupTexts(
        new = R.string.setlist_new,
        none = R.string.setlist_none,
        deleteTitle = R.string.setlist_delete_title,
        deleteText = R.string.setlist_delete_text,
        nameTaken = R.string.group_name_taken,
    )
    GroupKind.TAG -> GroupTexts(
        new = R.string.tag_new,
        none = R.string.tag_list_none,
        deleteTitle = R.string.tag_delete_title,
        deleteText = R.string.tag_delete_text,
        nameTaken = R.string.tag_name_taken,
    )
    GroupKind.PERSON -> GroupTexts(
        new = R.string.person_new,
        none = R.string.people_none,
        deleteTitle = R.string.person_delete_title,
        deleteText = R.string.person_delete_text,
        nameTaken = R.string.person_name_taken,
        merge = MergeTexts(
            action = R.string.person_merge,
            pick = R.string.person_merge_pick,
            title = R.string.person_merge_title,
            text = R.string.person_merge_text,
            none = R.string.person_merge_none,
        ),
    )
    GroupKind.GENRE -> GroupTexts(
        new = R.string.genre_new,
        none = R.string.genre_list_none,
        deleteTitle = R.string.genre_delete_title,
        deleteText = R.string.genre_delete_text,
        nameTaken = R.string.genre_name_taken,
    )
}

/**
 * One way of looking at a group's scores, and of adding to them: a setlist or a tag has one; a
 * person has two, as author/composer and as performer, each its own [groups] (the scores in that
 * role). [label] names it on the switch (null: a single facet, no switch); [empty], [remove] and
 * [inGroup] are what the screen says on its scores.
 */
class GroupFacet(
    @StringRes val label: Int?,
    val groups: ScoreGroups,
    @StringRes val empty: Int,
    @StringRes val remove: Int,
    @StringRes val inGroup: Int,
)

fun setlistFacets(setlists: ScoreGroups) =
    listOf(GroupFacet(null, setlists, R.string.setlist_empty, R.string.setlist_remove, R.string.setlist_in))

fun tagFacets(tags: ScoreGroups) =
    listOf(GroupFacet(null, tags, R.string.tag_empty, R.string.tag_remove_score, R.string.tag_on_score))

fun genreFacets(genres: ScoreGroups) =
    listOf(GroupFacet(null, genres, R.string.genre_empty, R.string.genre_remove_score, R.string.genre_on_score))

/** A person's scores as author/composer (facet 0) and as performer (facet 1). */
fun personFacets(composers: ScoreGroups, performers: ScoreGroups) = listOf(
    GroupFacet(R.string.person_as_composer, composers, R.string.composer_empty, R.string.composer_remove, R.string.composer_on_score),
    GroupFacet(R.string.person_as_performer, performers, R.string.performer_empty, R.string.performer_remove, R.string.performer_on_score),
)

/**
 * Groups of scores (the setlists, the tags, the people): their list ([groups]), or one of them
 * ([shownId]) with its scores, in its order for a setlist ([ScoreGroups.ordered]), by title under
 * the letters otherwise. A person's scores show in one of its [facets] ([facet]: as
 * author/composer or as performer), two buttons switching. A tap on a score opens it in the group:
 * turning the page past its end opens the next one.
 *
 * [playingKey] is the entry open in the viewer, marked in the list.
 */
@Composable
fun ScoreGroupsScreen(
    groups: ScoreGroups,
    texts: GroupTexts,
    facets: List<GroupFacet>,
    facet: Int,
    library: LibraryRepository,
    shownId: Long?,
    playingKey: Long?,
    onShow: (Long?) -> Unit,
    onFacet: (Int) -> Unit,
    onPlay: (groupId: Long, entry: GroupEntry) -> Unit,
    onClose: () -> Unit,
    /** The left of the header of the list: ☰ and the name, or the views' icons (MainNavigation). */
    navigation: @Composable () -> Unit,
) {
    if (shownId == null) {
        GroupList(groups, texts, onShow, onClose, navigation)
    } else {
        GroupDetail(
            groups, texts, facets, facet.coerceIn(facets.indices), library, shownId, playingKey,
            onFacet = onFacet, onBack = { onShow(null) }, onPlay = { onPlay(shownId, it) },
        )
    }
}

/** The buttons between the facets of a group (a person: as author/composer, as performer); none for one facet. */
@Composable
private fun FacetSwitch(facets: List<GroupFacet>, facet: Int, onFacet: (Int) -> Unit) {
    if (facets.size < 2) return
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        facets.forEachIndexed { index, f ->
            SegmentedButton(
                selected = index == facet,
                onClick = { onFacet(index) },
                shape = SegmentedButtonDefaults.itemShape(index, facets.size),
            ) { Text(f.label?.let { stringResource(it) }.orEmpty()) }
        }
    }
}

@Composable
private fun Header(
    title: String,
    onBack: () -> Unit,
    backLabel: String,
    backSymbol: String = "←",
    actions: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = backLabel }) {
            Text(backSymbol, style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
private fun GroupList(
    groups: ScoreGroups,
    texts: GroupTexts,
    onShow: (Long?) -> Unit,
    onClose: () -> Unit,
    navigation: @Composable () -> Unit,
) {
    val all by remember(groups) { groups.groups() }.collectAsState(initial = null)
    var naming by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        // as the library (same margins, so the icon bar stays in place from one view to the other):
        // ☰ and the name (or the views' icons) on the left, ✕ on the right
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { navigation() }
            Button(onClick = { naming = true }, modifier = Modifier.padding(end = 8.dp)) {
                Text(stringResource(texts.new))
            }
            val closeLabel = stringResource(R.string.action_close)
            TextButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = closeLabel }) {
                LineIcon(LINE_CLOSE)
            }
        }
        val list = all
        when {
            list == null -> Unit
            list.isEmpty() -> Text(
                stringResource(texts.none),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            // as every list: by name under a bar for each letter, the letters on the right
            else -> IndexedList(list, name = { it.name }, key = { it.id }, modifier = Modifier.fillMaxSize()) { group ->
                Column(
                    Modifier.fillMaxWidth().clickable { onShow(group.id) }.padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        pluralStringResource(R.plurals.setlist_size, group.size, group.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    if (naming) {
        NameDialog(
            title = stringResource(texts.new),
            initial = "",
            takenMessage = stringResource(texts.nameTaken),
            onDismiss = { naming = false },
            onDone = { name ->
                if (groups.nameTaken(name, null)) {
                    false
                } else {
                    naming = false
                    scope.launch { onShow(groups.create(name)) }
                    true
                }
            },
        )
    }
}

@Composable
private fun GroupDetail(
    groups: ScoreGroups,
    texts: GroupTexts,
    facets: List<GroupFacet>,
    facet: Int,
    library: LibraryRepository,
    id: Long,
    playingKey: Long?,
    onFacet: (Int) -> Unit,
    onBack: () -> Unit,
    onPlay: (GroupEntry) -> Unit,
) {
    val shown = facets[facet]
    val name by remember(groups, id) { groups.name(id) }.collectAsState(initial = null)
    val entries by remember(shown, id) { shown.groups.entries(id) }.collectAsState(initial = emptyList())
    var editing by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    // merging: picking the other group, then the other group picked (0: none yet)
    var mergePicking by rememberSaveable { mutableStateOf(false) }
    var mergeWith by rememberSaveable { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val merge = texts.merge?.takeIf { groups.canMerge }

    if (editing) {
        GroupEditor(facets, facet, library, id, name.orEmpty(), entries, onFacet = onFacet, onDone = { editing = false })
        return
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Header(name.orEmpty(), onBack, stringResource(R.string.action_back)) {
            TextButton(onClick = { renaming = true }) { Text(stringResource(R.string.setlist_rename)) }
            merge?.let { TextButton(onClick = { mergePicking = true }) { Text(stringResource(it.action)) } }
            TextButton(onClick = { deleting = true }) { Text(stringResource(R.string.action_delete)) }
            Button(onClick = { editing = true }, modifier = Modifier.padding(end = 8.dp)) {
                Text(stringResource(R.string.setlist_edit))
            }
        }
        FacetSwitch(facets, facet, onFacet)
        if (entries.isEmpty()) {
            Text(stringResource(shown.empty), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
        }
        val row: @Composable (Int?, GroupEntry) -> Unit = { number, entry ->
            val playing = entry.entryKey == playingKey
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (playing) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                    .clickable { onPlay(entry) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                number?.let {
                    Text("$it.", style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(min = 32.dp))
                }
                SheetText(entry.sheet, Modifier.weight(1f), bold = playing)
            }
        }
        if (shown.groups.ordered) {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(entries, key = { _, e -> e.entryKey }) { index, entry ->
                    row(index + 1, entry)
                    HorizontalDivider()
                }
            }
        } else {
            // each score once: by title under the letters, as the library
            IndexedList(entries, name = { it.sheet.title }, key = { it.entryKey }, modifier = Modifier.fillMaxSize()) { entry ->
                row(null, entry)
            }
        }
    }

    if (renaming) {
        NameDialog(
            title = stringResource(R.string.setlist_rename),
            initial = name.orEmpty(),
            takenMessage = stringResource(texts.nameTaken),
            onDismiss = { renaming = false },
            onDone = { newName ->
                if (groups.nameTaken(newName, id)) {
                    false
                } else {
                    // renamed before the dialog goes: its scope ends with it
                    groups.rename(id, newName)
                    renaming = false
                    true
                }
            },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(texts.deleteTitle, name.orEmpty())) },
            text = { Text(stringResource(texts.deleteText)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onBack()
                    scope.launch { groups.delete(id) }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    merge?.let { words ->
        if (mergePicking) {
            MergePickDialog(groups, words, id, name.orEmpty(), onDismiss = { mergePicking = false }) { other ->
                mergePicking = false
                mergeWith = other
            }
        }
        if (mergeWith != 0L) {
            MergeConfirmDialog(groups, words, id, name.orEmpty(), mergeWith, onDismiss = { mergeWith = 0L }) { keptName ->
                val other = mergeWith
                mergeWith = 0L
                // this group stays (the screen shows it), with the name chosen; the other joins it
                scope.launch { groups.merge(into = id, from = other, name = keptName) }
            }
        }
    }
}

/** The other groups to merge [name] ([id]) with, as every list: by name under the letters. */
@Composable
private fun MergePickDialog(
    groups: ScoreGroups,
    texts: MergeTexts,
    id: Long,
    name: String,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val all by remember(groups) { groups.groups() }.collectAsState(initial = emptyList())
    val others = all.filter { it.id != id }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.9f).widthIn(max = 560.dp).fillMaxHeight(0.85f),
        ) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(texts.pick, name),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                }
                if (others.isEmpty()) {
                    Text(stringResource(texts.none), modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
                } else {
                    IndexedList(others, name = { it.name }, key = { it.id }, modifier = Modifier.weight(1f).fillMaxWidth()) { group ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(group.id) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(group.name, style = MaterialTheme.typography.titleMedium)
                            Text(pluralStringResource(R.plurals.setlist_size, group.size, group.size), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

/** Merging [name] ([id]) and the group [other]: which of the two names stays. */
@Composable
private fun MergeConfirmDialog(
    groups: ScoreGroups,
    texts: MergeTexts,
    id: Long,
    name: String,
    other: Long,
    onDismiss: () -> Unit,
    onConfirm: (keptName: String) -> Unit,
) {
    val otherName by remember(groups, other) { groups.name(other) }.collectAsState(initial = null)
    val names = listOfNotNull(name, otherName)
    var kept by rememberSaveable { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(texts.title, name, otherName.orEmpty())) },
        text = {
            Column {
                Text(stringResource(texts.text))
                names.forEachIndexed { index, candidate ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = kept == index, role = Role.RadioButton, onClick = { kept = index })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = kept == index, onClick = null)
                        Text(candidate, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { names.getOrNull(kept)?.let(onConfirm) },
                enabled = otherName != null,
            ) { Text(stringResource(texts.action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Editing a group, with both sides in sight: the group (for a setlist in its order, drag ≡ to move
 * a score; for a tag by title) with ✕ to take a score out, and the library, grouped by letter with
 * an index to jump. A tap on a score of the library puts it in a setlist, at the end even if it is
 * already in it; for a tag it puts the tag on the score, or takes it off; for a person, the same
 * in the role chosen with the two buttons on top (as author/composer, as performer). The scores
 * already in the group show on a pale blue background. Side by side on a tablet, one above the
 * other on a phone.
 */
@Composable
private fun GroupEditor(
    facets: List<GroupFacet>,
    facet: Int,
    library: LibraryRepository,
    id: Long,
    name: String,
    entries: List<GroupEntry>,
    onFacet: (Int) -> Unit,
    onDone: () -> Unit,
) {
    val shown = facets[facet]
    val groups = shown.groups
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onDone)
    Column(Modifier.fillMaxSize()) {
        Header(name, onDone, stringResource(R.string.action_back)) {
            Button(onClick = onDone, modifier = Modifier.padding(end = 8.dp)) { Text(stringResource(R.string.setlist_done)) }
        }
        FacetSwitch(facets, facet, onFacet)
        HorizontalDivider()
        val members: @Composable (Modifier) -> Unit = { modifier ->
            val onRemove: (Long) -> Unit = { key -> scope.launch { groups.remove(id, key) } }
            if (groups.ordered) {
                EditedOrder(
                    entries,
                    shown,
                    onReorder = { keys -> scope.launch { groups.reorder(keys) } },
                    onMove = { from, to -> scope.launch { groups.move(entries, from, to) } },
                    onRemove = onRemove,
                    modifier = modifier,
                )
            } else {
                EditedMembers(entries, shown, onRemove, modifier)
            }
        }
        val picker: @Composable (Modifier) -> Unit = { modifier ->
            val times = entries.groupingBy { it.sheet.id }.eachCount()
            LibraryPicker(
                library,
                present = times,
                mark = { count -> if (groups.ordered) stringResource(R.string.setlist_times, count) else "✓" },
                inGroup = stringResource(shown.inGroup),
                onTap = { sheet ->
                    scope.launch {
                        // a tag is on a score or not: a second tap takes it off
                        if (!groups.ordered && (times[sheet.id] ?: 0) > 0) groups.remove(id, sheet.id)
                        else groups.add(id, sheet.id)
                    }
                },
                modifier = modifier,
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= TWO_COLUMNS) {
                Row(Modifier.fillMaxSize()) {
                    members(Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider()
                    picker(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    members(Modifier.weight(1f).fillMaxWidth())
                    HorizontalDivider(thickness = 2.dp)
                    picker(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

// from this width the group and the library stand side by side
private val TWO_COLUMNS = 560.dp

/** The setlist being edited: number, ≡ to drag, title and composer, ✕ to take the score out. */
@Composable
private fun EditedOrder(
    entries: List<GroupEntry>,
    texts: GroupFacet,
    onReorder: (List<Long>) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (entryKey: Long) -> Unit,
    modifier: Modifier,
) {
    // the order on screen: follows the setlist, except while a row is dragged
    var order by remember { mutableStateOf(entries) }
    var dragged by remember { mutableStateOf<Long?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(entries) { if (dragged == null) order = entries }

    // a score just added shows at the end of the list
    val state = rememberLazyListState()
    var shownSize by remember { mutableIntStateOf(entries.size) }
    LaunchedEffect(entries.size) {
        if (entries.size > shownSize) state.animateScrollToItem(entries.size - 1)
        shownSize = entries.size
    }

    fun drop() {
        val keys = order.map { it.entryKey }
        if (dragged != null && keys != entries.map { it.entryKey }) onReorder(keys)
        dragged = null
        offset = 0f
    }

    Column(modifier) {
        ColumnTitle(pluralStringResource(R.plurals.setlist_size, entries.size, entries.size))
        if (entries.isEmpty()) {
            Text(stringResource(texts.empty), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
        }
        val moveUp = stringResource(R.string.setlist_move_up)
        val moveDown = stringResource(R.string.setlist_move_down)
        val moveLabel = stringResource(R.string.setlist_move)
        val removeLabel = stringResource(texts.remove)
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            itemsIndexed(order, key = { _, e -> e.entryKey }) { index, entry ->
                val isDragged = entry.entryKey == dragged
                var height by remember { mutableIntStateOf(0) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) offset else 0f }
                        .background(if (isDragged) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                        .onSizeChanged { height = it.height }
                        // the drag has no meaning for a screen reader: up and down instead
                        .semantics {
                            customActions = listOfNotNull(
                                CustomAccessibilityAction(moveUp) { onMove(index, index - 1); true }.takeIf { index > 0 },
                                CustomAccessibilityAction(moveDown) { onMove(index, index + 1); true }.takeIf { index < order.size - 1 },
                            )
                        }
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.widthIn(min = 28.dp),
                    )
                    Text(
                        "≡",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier
                            .semantics { contentDescription = moveLabel }
                            .pointerInput(entry.entryKey) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragged = entry.entryKey
                                        offset = 0f
                                    },
                                    onDragEnd = { drop() },
                                    onDragCancel = { drop() },
                                ) { change, amount ->
                                    change.consume()
                                    offset += amount.y
                                    val at = order.indexOfFirst { it.entryKey == entry.entryKey }
                                    val step = dragStep(offset, height.toFloat())
                                    if (step != 0 && at + step in order.indices) {
                                        order = order.moved(at, at + step)
                                        offset -= step * height
                                    }
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    SheetText(entry.sheet, Modifier.weight(1f).padding(vertical = 6.dp))
                    RemoveButton(removeLabel) { onRemove(entry.entryKey) }
                }
                HorizontalDivider()
            }
        }
    }
}

/** The scores of a group with no order (a tag), by title under the letters, ✕ to take one out. */
@Composable
private fun EditedMembers(entries: List<GroupEntry>, texts: GroupFacet, onRemove: (entryKey: Long) -> Unit, modifier: Modifier) {
    Column(modifier) {
        ColumnTitle(pluralStringResource(R.plurals.setlist_size, entries.size, entries.size))
        if (entries.isEmpty()) {
            Text(stringResource(texts.empty), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
        }
        val removeLabel = stringResource(texts.remove)
        IndexedList(entries, name = { it.sheet.title }, key = { it.entryKey }, modifier = Modifier.fillMaxSize()) { entry ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                SheetText(entry.sheet, Modifier.weight(1f).padding(vertical = 6.dp))
                RemoveButton(removeLabel) { onRemove(entry.entryKey) }
            }
        }
    }
}

@Composable
private fun RemoveButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        Text("✕", style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * The library to pick from, as the library shows it (IndexedList). A score already in the group is
 * on pale blue, marked in its top right corner ([mark] of how many times it is in it, [present]:
 * times by score id; [inGroup] says it to a screen reader).
 */
@Composable
private fun LibraryPicker(
    library: LibraryRepository,
    present: Map<Long, Int>,
    mark: @Composable (Int) -> String,
    inGroup: String,
    onTap: (Sheet) -> Unit,
    modifier: Modifier,
) {
    val sheets by remember { library.sheets() }.collectAsState(initial = emptyList())
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val inGroupColor = if (dark) Color(0xFF1E3A5C) else Color(0xFFDCEBFF)

    Column(modifier) {
        ColumnTitle(stringResource(R.string.library_title))
        IndexedList(sheets, name = { it.title }, key = { it.id }, modifier = Modifier.fillMaxSize()) { sheet ->
            val times = present[sheet.id] ?: 0
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(if (times > 0) inGroupColor else MaterialTheme.colorScheme.surface)
                    .clickable { onTap(sheet) }
                    .semantics { if (times > 0) stateDescription = inGroup },
            ) {
                SheetText(sheet, Modifier.fillMaxWidth().padding(start = 16.dp, end = 40.dp, top = 8.dp, bottom = 8.dp))
                // a score played twice in a setlist, a score with the tag: seen at a glance
                if (times > 0) {
                    Text(
                        mark(times),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 8.dp),
                    )
                }
            }
        }
    }
}

// the small title over each side of the editor
@Composable
private fun ColumnTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * The name of a new group, or a new name. [onDone] says whether the name is taken: then the dialog
 * stays, with [takenMessage] under the field.
 */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    takenMessage: String,
    onDismiss: () -> Unit,
    onDone: suspend (String) -> Boolean,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    var taken by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    taken = false
                },
                label = { Text(stringResource(R.string.setlist_name)) },
                singleLine = true,
                isError = taken,
                supportingText = if (taken) ({ Text(takenMessage) }) else null,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { scope.launch { taken = !onDone(name) } },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
