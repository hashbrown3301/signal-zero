package com.itantra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.itantra.session.InputOrigin
import com.itantra.session.SourceDraft
import com.itantra.session.SessionManager
import com.itantra.translation.ReviewedPhrase
import com.itantra.ui.components.Rule
import com.itantra.ui.theme.Palette
import com.itantra.ui.theme.scriptFont

/** An explicit source decision. Dismissal discards; no text is sent merely by closing the dialog. */
@Composable
internal fun ReviewDraftDialog(
    draft: SourceDraft,
    networked: Boolean,
    notice: String?,
    onConfirm: (String) -> Unit,
    onDiscard: () -> Unit,
) {
    var text by rememberSaveable(draft) { mutableStateOf(draft.text) }
    val iso = isoOf(draft.langCode)
    val validationError = SessionManager.inputError(text)
    AlertDialog(
        onDismissRequest = onDiscard,
        title = { Text(if (networked) "Review before sending" else "Review before translating") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (draft.inputOrigin == InputOrigin.SPEECH) "Correct any words the microphone heard incorrectly."
                    else "Check the source text before continuing.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Source · ${englishName(iso)}") },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = scriptFont(iso)),
                    minLines = 2,
                    maxLines = 5,
                    isError = validationError != null,
                    supportingText = { validationError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Nothing is sent or translated until you confirm.", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                notice?.takeIf { it != validationError }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Mint) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = validationError == null) {
                Text(if (networked) "Send" else "Translate")
            }
        },
        dismissButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
    )
}

internal data class PhraseEditorSeed(
    val sourceIso: String,
    val targetIso: String,
    val sourceText: String = "",
    val targetText: String = "",
    val existing: Boolean = false,
)

/** Keep the editor mounted after Activity recreation so its saveable text fields can restore too. */
internal val PhraseEditorSeedSaver = listSaver<PhraseEditorSeed?, Any>(
    save = { seed ->
        seed?.let { listOf(it.sourceIso, it.targetIso, it.sourceText, it.targetText, it.existing) } ?: emptyList()
    },
    restore = { values ->
        if (values.isEmpty()) null else PhraseEditorSeed(
            sourceIso = values[0] as String,
            targetIso = values[1] as String,
            sourceText = values[2] as String,
            targetText = values[3] as String,
            existing = values[4] as Boolean,
        )
    },
)

/** Saving is a user review action; generated text is editable and never silently trusted. */
@Composable
internal fun ReviewedPhraseEditor(
    seed: PhraseEditorSeed,
    storageError: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, (Boolean) -> Unit) -> Unit,
) {
    var source by rememberSaveable(seed) { mutableStateOf(seed.sourceText) }
    var target by rememberSaveable(seed) { mutableStateOf(seed.targetText) }
    var reviewed by rememberSaveable(seed) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Save a reviewed phrase") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Edit both texts. Only this exact source and language pair will use your saved translation.", style = MaterialTheme.typography.bodyMedium)
                if (seed.existing) Text("Changing the source saves a separate phrase; the old phrase remains.", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it; reviewed = false },
                    label = { Text("Source · ${englishName(seed.sourceIso)}") },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = scriptFont(seed.sourceIso)),
                    enabled = !saving,
                    minLines = 2,
                    maxLines = 4,
                    isError = source.length > MAX_PHRASE_CHARS,
                    supportingText = { if (source.length > MAX_PHRASE_CHARS) Text("Keep the source within $MAX_PHRASE_CHARS characters.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it; reviewed = false },
                    label = { Text("Translation · ${englishName(seed.targetIso)}") },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = scriptFont(seed.targetIso)),
                    enabled = !saving,
                    minLines = 2,
                    maxLines = 4,
                    isError = target.length > MAX_PHRASE_CHARS,
                    supportingText = { if (target.length > MAX_PHRASE_CHARS) Text("Keep the translation within $MAX_PHRASE_CHARS characters.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = reviewed, onCheckedChange = { reviewed = it }, enabled = !saving, modifier = Modifier.semantics { contentDescription = "I reviewed this translation" })
                    Text("I reviewed this translation", style = MaterialTheme.typography.bodyMedium)
                }
                Text("Saved on this phone. Reviewing it yourself does not certify its accuracy.", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                if (failed) Text(storageError ?: "Could not save this phrase. Try again.", color = Palette.Mint, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && reviewed && source.isNotBlank() && target.isNotBlank() &&
                    source.length <= MAX_PHRASE_CHARS && target.length <= MAX_PHRASE_CHARS && seed.sourceIso != seed.targetIso,
                onClick = {
                    saving = true
                    failed = false
                    onSave(seed.sourceIso, seed.targetIso, source, target) { success ->
                        saving = false
                        if (success) onDismiss() else failed = true
                    }
                },
            ) { Text(if (saving) "Saving…" else "Save reviewed phrase") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )
}

/** Directional local entries. A peer receives source text, not this phone's saved target. */
@Composable
internal fun PhrasebookDialog(
    entries: List<ReviewedPhrase>,
    error: String?,
    sourceIso: String,
    targetIso: String,
    networked: Boolean,
    hasSession: Boolean,
    canUse: Boolean,
    onDismiss: () -> Unit,
    onEdit: (PhraseEditorSeed) -> Unit,
    onRemove: (String) -> Unit,
    onUse: (ReviewedPhrase) -> Boolean,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    var useError by remember { mutableStateOf<String?>(null) }
    val visible = entries.filter { showAll || (it.sourceIso == sourceIso && it.targetIso == targetIso) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reviewed phrases") },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("${englishName(sourceIso)} → ${englishName(targetIso)} · this phone", style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
                Text(
                    if (networked) "Send source text to the other phone. It needs its own saved phrase or translation pack."
                    else "Use an exact reviewed pair without loading the translation pack. A voice pack is needed for speech.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextMuted,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Show all language pairs", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = showAll, onCheckedChange = { showAll = it }, modifier = Modifier.semantics { contentDescription = "Show all language pairs" })
                }
                (useError ?: error)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Mint) }
                if (sourceIso == targetIso) Text("Choose different source and listening languages in Languages to add or use a translated phrase.", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                if (visible.isEmpty() && sourceIso != targetIso) Text("No reviewed phrases for this pair yet. Add your own source and translation.", style = MaterialTheme.typography.bodyMedium)
                visible.forEach { phrase ->
                    val pairMatches = phrase.sourceIso == sourceIso && phrase.targetIso == targetIso
                    Rule()
                    Text("${englishName(phrase.sourceIso)} → ${englishName(phrase.targetIso)}", style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
                    Text(phrase.sourceText, fontFamily = scriptFont(phrase.sourceIso), style = MaterialTheme.typography.bodyLarge)
                    Text(phrase.targetText, fontFamily = scriptFont(phrase.targetIso), style = MaterialTheme.typography.bodyLarge, color = Palette.Mint)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            enabled = pairMatches && canUse,
                            onClick = {
                                if (onUse(phrase)) onDismiss()
                                else useError = "Could not use this phrase yet. Wait for the current task or link, then try again."
                            },
                        ) { Text(if (networked) "Send source" else if (hasSession) "Use phrase" else "Use in Solo") }
                        TextButton(onClick = { onEdit(PhraseEditorSeed(phrase.sourceIso, phrase.targetIso, phrase.sourceText, phrase.targetText, existing = true)) }) { Text("Edit") }
                        TextButton(onClick = { onRemove(phrase.id) }) { Text("Delete") }
                    }
                    if (!pairMatches) Text("Select this language pair in Languages to use it.", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onEdit(PhraseEditorSeed(sourceIso, targetIso)) }, enabled = sourceIso != targetIso) { Text("Add phrase") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

internal const val MAX_PHRASE_CHARS = 1_000
