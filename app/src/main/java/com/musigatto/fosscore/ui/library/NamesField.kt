package com.musigatto.fosscore.ui.library

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.distinctTagNames
import com.musigatto.fosscore.library.normalize
import com.musigatto.fosscore.library.sameTag
import com.musigatto.fosscore.library.tagName
import com.musigatto.fosscore.ui.viewer.LINE_ADD
import com.musigatto.fosscore.ui.viewer.LINE_CLOSE
import com.musigatto.fosscore.ui.viewer.LineIcon

/** Keeps a list of tags across a rotation; null (not read yet) stays null. */
internal val TagListSaver = Saver<List<String>?, ArrayList<String>>(
    save = { tags -> tags?.let { ArrayList(it) } },
    restore = { it }
)

private val TagNamesSaver = Saver<List<String>, ArrayList<String>>(
    save = { ArrayList(it) },
    restore = { it }
)

/** What the picker lists: the library's tags and the score's, each once. */
internal fun pickerTags(known: List<String>, chosen: List<String>): List<String> = distinctTagNames(known + chosen)

/** The tags of [all] holding [typed], case and accents ignored; all of them when nothing is typed. */
internal fun filterTags(all: List<String>, typed: String): List<String> {
    val wanted = normalize(tagName(typed))
    return all.filter { normalize(it).contains(wanted) }
}

/**
 * The tag that creating [typed] checks: the tag of [all] it names in any spelling ("rock" when
 * "Rock" exists), or a new one. Null when nothing is typed.
 */
internal fun tagToCreate(all: List<String>, typed: String): String? {
    val name = tagName(typed)
    if (name.isEmpty()) return null
    return all.firstOrNull { sameTag(it, name) } ?: name
}

/** [checked] with [tag] checked or unchecked. */
internal fun toggleTag(checked: List<String>, tag: String): List<String> =
    if (tag in checked) checked - tag else checked + tag

/**
 * What a field of names says: its label (the picker's title too), and the picker's words. [create]
 * null: a closed list (the keys), where nothing is created and the field only finds.
 */
class NamesTexts(
    @StringRes val label: Int,
    @StringRes val choose: Int,
    @StringRes val hint: Int,
    @StringRes val create: Int?,
    @StringRes val none: Int,
    @StringRes val noMatch: Int,
    @StringRes val remove: Int,
)

val TAG_NAMES = NamesTexts(
    R.string.field_tags, R.string.tag_choose, R.string.tag_hint, R.string.tag_create,
    R.string.tags_none, R.string.tags_no_match, R.string.tag_remove,
)

val COMPOSER_NAMES = NamesTexts(
    R.string.field_composer, R.string.composer_choose, R.string.person_hint, R.string.person_create,
    R.string.people_none_typed, R.string.people_no_match, R.string.person_remove,
)

val PERFORMER_NAMES = NamesTexts(
    R.string.field_performer, R.string.performer_choose, R.string.person_hint, R.string.person_create,
    R.string.people_none_typed, R.string.people_no_match, R.string.person_remove,
)

val GENRE_NAMES = NamesTexts(
    R.string.field_genre, R.string.genre_choose, R.string.genre_hint, R.string.genre_create,
    R.string.genres_none_typed, R.string.genres_no_match, R.string.genre_remove,
)

val KEY_NAMES = NamesTexts(
    R.string.field_key, R.string.key_choose, R.string.key_hint, null,
    R.string.key_no_match, R.string.key_no_match, R.string.key_remove,
)

/**
 * The score's names (its tags, its authors/composers, its performers) in an outlined box,
 * labelled like the other fields, each with ✕ to remove it. The + on the right (or a tap on the
 * box) opens the picker, where the library's names ([known]) are checked or unchecked and a new
 * one is created. [sorted]: the names come back in alphabetical order; else in the order they
 * were checked (the keys: the first is the one the score starts in).
 */
@Composable
fun NamesField(
    texts: NamesTexts,
    tags: List<String>,
    known: List<String>,
    onTagsChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    sorted: Boolean = true,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val chooseLabel = stringResource(texts.choose)
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier
            .defaultMinSize(minHeight = OutlinedTextFieldDefaults.MinHeight)
            .clickable(onClickLabel = chooseLabel) { picking = true }
    ) {
        OutlinedTextFieldDefaults.DecorationBox(
            // a value keeps the label on the border, top left, as soon as there is a tag
            value = tags.joinToString(" "),
            innerTextField = {
                // the full width: the box is as wide as the other fields, the + on its right
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tags.forEach { tag -> TagChip(tag, texts.remove) { onTagsChange(tags - tag) } }
                }
            },
            enabled = true,
            singleLine = false,
            visualTransformation = VisualTransformation.None,
            interactionSource = interactionSource,
            label = { Text(stringResource(texts.label)) },
            trailingIcon = {
                IconButton(
                    onClick = { picking = true },
                    modifier = Modifier.semantics { contentDescription = chooseLabel }
                ) { LineIcon(LINE_ADD) }
            }
        )
    }

    if (picking) {
        TagPickerDialog(
            texts = texts,
            sorted = sorted,
            chosen = tags,
            known = known,
            onDismiss = { picking = false },
            onConfirm = {
                onTagsChange(it)
                picking = false
            }
        )
    }
}

/**
 * The library's tags in alphabetical order, with the letters on the right, each with a box to
 * check; the score's are checked. The field on top finds a tag, or creates it with + (a tag
 * typed in another spelling checks the existing one).
 */
@Composable
private fun TagPickerDialog(
    texts: NamesTexts,
    sorted: Boolean,
    chosen: List<String>,
    known: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    var checked by rememberSaveable(stateSaver = TagNamesSaver) { mutableStateOf(chosen) }
    // created here, still listed if unchecked again
    var created by rememberSaveable(stateSaver = TagNamesSaver) { mutableStateOf(emptyList<String>()) }
    var draft by rememberSaveable { mutableStateOf("") }
    val all = pickerTags(known, chosen + created)
    // the list first: the field cannot take the focus until it is tapped, so the keyboard does not
    // open with the picker (Android 7 focuses a new window's first field; in landscape the
    // keyboard would cover most of the list)
    var typing by rememberSaveable { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(typing) { if (typing) fieldFocus.requestFocus() }

    fun create(): List<String> {
        val tag = tagToCreate(all, draft) ?: return checked
        if (all.none { it == tag }) {
            // a closed list: only what it holds
            if (texts.create == null) return checked
            created = created + tag
        }
        if (tag !in checked) checked = checked + tag
        draft = ""
        return checked
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 560.dp)
                .fillMaxHeight(0.85f)
        ) {
            Column(Modifier.padding(vertical = 16.dp)) {
                // the buttons beside the title: the keyboard, when it shows, never hides them
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(texts.label),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    // a name typed but not added yet is not lost
                    TextButton(onClick = {
                        val collator = nameCollator()
                        val names = create()
                        onConfirm(if (sorted) names.sortedWith { a, b -> collator.compare(a, b) } else names)
                    }) { Text(stringResource(R.string.action_ok)) }
                }
                val createLabel = texts.create?.let { stringResource(it, tagName(draft)) }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text(stringResource(texts.hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { create() }),
                    trailingIcon = createLabel?.let { label ->
                        {
                            IconButton(
                                onClick = { create() },
                                enabled = tagName(draft).isNotEmpty(),
                                modifier = Modifier.semantics { contentDescription = label }
                            ) { LineIcon(LINE_ADD) }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(pass = PointerEventPass.Initial)
                                typing = true
                            }
                        }
                        .focusProperties { canFocus = typing }
                        .focusRequester(fieldFocus)
                )
                val shown = filterTags(all, draft)
                if (shown.isEmpty()) {
                    Text(
                        stringResource(if (draft.isBlank()) texts.none else texts.noMatch),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 24.dp)
                    )
                } else {
                    IndexedList(
                        shown,
                        name = { it },
                        key = { it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) { tag ->
                        TagRow(tag, tag in checked) { checked = toggleTag(checked, tag) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagRow(tag: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // the row takes the tap and reads the state: the box only shows it
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(tag, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TagChip(tag: String, @StringRes remove: Int, onRemove: () -> Unit) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(tag, style = MaterialTheme.typography.labelLarge)
            val removeLabel = stringResource(remove)
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .size(32.dp)
                    .semantics { contentDescription = removeLabel }
            ) {
                LineIcon(LINE_CLOSE, size = 14.dp)
            }
        }
    }
}
