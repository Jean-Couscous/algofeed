package algofeed.ui

import algofeed.Settings
import algofeed.ThemeMode
import algofeed.data.Feed
import algofeed.data.Folder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

@Composable
fun AddFeedDialog(
    folders: List<Folder>,
    onAdd: suspend (input: String, folder: String?) -> Result<Feed>,
    initial: String = "",
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(initial) }
    var folder by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun submit() {
        if (input.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            onAdd(input, folder).fold(
                onSuccess = { onDismiss() },
                onFailure = { error = it.message ?: "Couldn't add this feed"; busy = false },
            )
        }
    }
    FormDialog(
        title = "Add a feed",
        onDismiss = onDismiss,
        confirmButton = { TextButton(onClick = ::submit, enabled = input.isNotBlank() && !busy) { Text(if (busy) "Adding…" else "Subscribe") } },
    ) {
        Text(
            "Paste a site or feed URL, a subreddit (r/kotlin) or custom feed (u/name/m/feed), a Mastodon account (@user@server), a YouTube channel, a Kagi News category (kagi:tech), or type hn or lobsters.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(input, { input = it }, label = { Text("Address") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(folder, { folder = it }, label = { Text("Folder (optional)") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        if (folders.isNotEmpty()) FolderChips(folders) { folder = it }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

/**
 * A dialog with a form: an [AlertDialog] on wide windows, full screen with a top bar on compact ones,
 * where an alert dialog leaves the fields cramped and the keyboard covers them.
 */
@Composable
private fun FormDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmButton: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val compact = LocalWindowInfo.current.containerDpSize.width < 600.dp
    if (!compact) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column(Modifier.heightIn(max = 560.dp).mouseScrolling(scroll).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            },
            confirmButton = confirmButton,
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        return
    }
    Dialog(onDismissRequest = onDismiss, properties = fullScreenDialogProperties()) {
        SystemBarIcons(LocalDarkTheme.current)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            // safeDrawing includes the keyboard, so the form shrinks above it.
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Cancel") }
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    confirmButton()
                }
                HorizontalDivider()
                Column(
                    Modifier.weight(1f).mouseScrolling(scroll).verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}

/** Asks what to do with a link shared into the app from elsewhere. */
@Composable
fun SharedLinkDialog(url: String, onRead: () -> Unit, onSubscribe: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shared link") },
        text = { Text(url, style = MaterialTheme.typography.bodyMedium, maxLines = 4) },
        confirmButton = { TextButton(onClick = onRead) { Text("Read now") } },
        dismissButton = { TextButton(onClick = onSubscribe) { Text("Subscribe") } },
    )
}

@Composable
private fun FolderChips(folders: List<Folder>, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        folders.take(6).forEach { TextButton(onClick = { onPick(it.name) }) { Text(it.name) } }
    }
}

@Composable
fun EditFeedDialog(
    feed: Feed,
    folders: List<Folder>,
    onSave: (Feed, String?) -> Unit,
    onDelete: (Feed) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(feed.title) }
    var folder by remember { mutableStateOf(folders.firstOrNull { it.id == feed.folderId }?.name.orEmpty()) }
    var enabled by remember { mutableStateOf(feed.enabled) }
    var preferFeedVersion by remember { mutableStateOf(feed.preferFeedVersion) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit feed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(feed.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(folder, { folder = it }, label = { Text("Folder") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (folders.isNotEmpty()) FolderChips(folders) { folder = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Include in updates and Home", modifier = Modifier.weight(1f))
                    Switch(enabled, { enabled = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Always show the feed version in the reader", modifier = Modifier.weight(1f))
                    Switch(preferFeedVersion, { preferFeedVersion = it })
                }
                feed.lastError?.let {
                    Text("Last update failed: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Seen ${feed.impressions}, opened ${feed.opens}, favorited ${feed.favorites}, hidden ${feed.dismissals}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { if (confirmDelete) { onDelete(feed); onDismiss() } else confirmDelete = true }) {
                    Text(if (confirmDelete) "Click again to unsubscribe" else "Unsubscribe", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(feed.copy(title = title.ifBlank { feed.title }, enabled = enabled, preferFeedVersion = preferFeedVersion), folder); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun EditFolderDialog(folder: Folder, onRename: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(folder.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Deleting a folder keeps its feeds; they move out of the folder.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { onDelete(); onDismiss() }) { Text("Delete folder", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onRename(name.trim()); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Settings that only apply on some platforms. */
data class SettingsOptions(
    val minRefreshMinutes: Int = 5,
    /** Background refresh can wait for an unmetered network or the charger. */
    val refreshConstraints: Boolean = false,
)

/** Hacker News login for the Settings dialog. */
class HnAccountActions(
    val user: String?,
    /** Returns an error message, or null once logged in. */
    val login: suspend (user: String, password: String) -> String?,
    val logout: () -> Unit,
)

@Composable
private fun HnAccountSection(hn: HnAccountActions) {
    Section("Hacker News account")
    if (hn.user != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Logged in as ${hn.user}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = hn.logout) { Text("Log out") }
        }
        return
    }
    Text(
        "Log in to upvote, favorite and comment on Hacker News stories from the reader. Favorites you set here are " +
            "also set on HN. The password is sent only to news.ycombinator.com and isn't stored.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        password, { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    OutlinedButton(
        enabled = user.isNotBlank() && password.isNotEmpty() && !working,
        onClick = {
            working = true
            scope.launch {
                error = hn.login(user, password)
                working = false
                if (error == null) password = ""
            }
        },
    ) { Text(if (working) "Logging in…" else "Log in") }
}

@Composable
fun SettingsDialog(
    settings: Settings,
    options: SettingsOptions,
    loadInterests: suspend () -> Pair<List<Pair<String, Double>>, List<Pair<String, Double>>>,
    onSave: (Settings) -> Unit,
    onResetInterests: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onRemoveAllAndReset: suspend () -> Unit,
    hn: HnAccountActions?,
    onDismiss: () -> Unit,
) {
    var s by remember { mutableStateOf(settings) }
    var confirmReset by remember { mutableStateOf(false) }
    var interests by remember { mutableStateOf<Pair<List<Pair<String, Double>>, List<Pair<String, Double>>>?>(null) }
    LaunchedEffect(Unit) { interests = loadInterests() }
    val scope = rememberCoroutineScope()
    FormDialog(
        title = "Settings",
        onDismiss = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(s); onDismiss() }) { Text("Save") } },
    ) {
        Section("Home ordering")
        Text(
            "One mix of four things. Raise Freshness to lean towards newest first, Quiet feeds to let rarely " +
                "posting feeds rise above busy ones, and the last two to follow what you open, favorite and hide.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val w = s.weights
        WeightSlider("Freshness", w.recency) { s = s.copy(weights = w.copy(recency = it)) }
        WeightSlider("Quiet feeds", w.rarity) { s = s.copy(weights = w.copy(rarity = it)) }
        WeightSlider("Feeds you engage with", w.source) { s = s.copy(weights = w.copy(source = it)) }
        WeightSlider("Topics you read", w.content) { s = s.copy(weights = w.copy(content = it)) }
        TextButton(onClick = { s = s.copy(weights = algofeed.rank.Weights()) }) { Text("Reset to defaults") }
        LabeledSwitch("Show entries already scrolled past", s.includeSeen) { s = s.copy(includeSeen = it) }

        HorizontalDivider()
        Section("Updates")
        val minRefresh = options.minRefreshMinutes
        IntSlider("Check feeds every", s.refreshMinutes.coerceAtLeast(minRefresh), minRefresh..180, "min") { s = s.copy(refreshMinutes = it) }
        if (options.refreshConstraints) {
            LabeledSwitch("In the background, only on Wi-Fi", s.refreshUnmeteredOnly) { s = s.copy(refreshUnmeteredOnly = it) }
            LabeledSwitch("In the background, only while charging", s.refreshWhileChargingOnly) { s = s.copy(refreshWhileChargingOnly = it) }
        }
        IntSlider("Keep entries for", s.retentionDays, 7..90, "days") { s = s.copy(retentionDays = it) }

        HorizontalDivider()
        Section("Appearance")
        Segmented(listOf(ThemeMode.System to "System", ThemeMode.Light to "Light", ThemeMode.Dark to "Dark"), s.theme) { s = s.copy(theme = it) }
        if (dynamicColorSupported) LabeledSwitch("Use wallpaper colors", s.dynamicColor) { s = s.copy(dynamicColor = it) }

        HorizontalDivider()
        Section("Learned interests")
        val (likes, dislikes) = interests ?: (emptyList<Pair<String, Double>>() to emptyList())
        if (likes.isEmpty() && dislikes.isEmpty()) {
            Text("Nothing learned yet. Open, favorite or hide entries to teach the ranking.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            if (likes.isNotEmpty()) Text("More of: " + likes.joinToString(", ") { it.first }, style = MaterialTheme.typography.bodySmall)
            if (dislikes.isNotEmpty()) Text("Less of: " + dislikes.joinToString(", ") { it.first }, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { onResetInterests(); interests = emptyList<Pair<String, Double>>() to emptyList() }) { Text("Forget learned interests") }

        hn?.let {
            HorizontalDivider()
            HnAccountSection(it)
        }

        HorizontalDivider()
        Section("Subscriptions")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImport) { Text("Import OPML") }
            OutlinedButton(onClick = onExport) { Text("Export OPML") }
        }

        HorizontalDivider()
        Section("Start over")
        Text(
            "Unsubscribe from every feed and forget everything the ranking has learned. Settings on this page stay as they are.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { confirmReset = true }) {
            Text("Remove all subscriptions and reset", color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirmReset) {
        var working by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!working) confirmReset = false },
            title = { Text("Remove all subscriptions and reset?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This deletes every feed and folder, all stored entries including bookmarks and favorites, and the interests learned from what you read. It can't be undone.")
                    Text("Your settings and Hacker News login are kept. Export your subscriptions first if you may want them back.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onExport, enabled = !working) { Text("Export OPML first") }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !working,
                    onClick = {
                        working = true
                        scope.launch {
                            onRemoveAllAndReset()
                            interests = emptyList<Pair<String, Double>>() to emptyList()
                            working = false
                            confirmReset = false
                        }
                    },
                ) { Text(if (working) "Removing…" else "Remove and reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }, enabled = !working) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleSmall)

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun WeightSlider(label: String, value: Double, onChange: (Double) -> Unit) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(((value * 10).toInt() / 10.0).toString(), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value.toFloat(), { onChange(((it * 10).toInt() / 10.0)) }, valueRange = 0f..3f)
    }
}

@Composable
private fun IntSlider(label: String, value: Int, range: IntRange, unit: String, onChange: (Int) -> Unit) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("$value $unit", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value.toFloat(), { onChange(it.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat())
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

