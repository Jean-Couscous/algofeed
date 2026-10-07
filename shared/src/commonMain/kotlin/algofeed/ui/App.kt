package algofeed.ui

import algofeed.StreamView
import algofeed.data.Feed
import algofeed.data.Folder
import algofeed.fetch.HackerNews
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun AlgofeedApp(
    vm: AlgofeedViewModel,
    platform: PlatformActions,
    sharedUrl: String? = null,
    onSharedUrlHandled: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    AlgofeedTheme(state.settings.theme, state.settings.dynamicColor) {
        Surface(color = MaterialTheme.colorScheme.background) {
            AppContent(vm, state, platform, sharedUrl, onSharedUrlHandled)
        }
    }
}

private sealed interface DialogState {
    data class AddFeed(val initial: String = "") : DialogState
    data class Shared(val url: String) : DialogState
    data object Settings : DialogState
    data class EditFeed(val feed: Feed) : DialogState
    data class EditFolder(val folder: Folder) : DialogState
}

@Composable
private fun AppContent(
    vm: AlgofeedViewModel,
    state: UiState,
    platform: PlatformActions,
    sharedUrl: String?,
    onSharedUrlHandled: () -> Unit,
) {
    val feeds by vm.feeds.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val feedMap = remember(feeds) { feeds.associateBy { it.id } }
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    var typing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val drawer = rememberDrawerState(DrawerValue.Closed)

    LaunchedEffect(sharedUrl) {
        if (sharedUrl == null) return@LaunchedEffect
        dialog = DialogState.Shared(sharedUrl)
        onSharedUrlHandled()
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val undo = vm.hasUndo()
        // Clearing the message restarts this effect, so the snackbar runs in the outer scope. A newer
        // message replaces the one on screen instead of queueing behind it.
        vm.messageShown()
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            val result = snackbar.showSnackbar(message, actionLabel = if (undo) "Undo" else null, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) vm.undo()
        }
    }

    // feedi's auto-mark: entries scrolled above the viewport count as seen.
    LaunchedEffect(listState, state.view) {
        if (state.view != StreamView.Home) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { first ->
                val items = vm.state.value.items
                if (first > 0) vm.scrolledPast(items.take(first.coerceAtMost(items.size)).map { it.entry })
            }
    }

    LaunchedEffect(state.view, state.loading) { if (!state.loading) listState.scrollToItem(0) }

    LaunchedEffect(state.focused) {
        if (state.focused < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val fullyVisible = visible.any { it.index == state.focused && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }
        if (!fullyVisible) listState.animateScrollToItem(state.focused)
    }

    LaunchedEffect(Unit) { rootFocus.requestFocus() }

    // Dialogs handle back themselves, ahead of this.
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = drawer.isOpen || state.reader.open || state.view != StreamView.Home,
        onBackCompleted = {
            when {
                drawer.isOpen -> scope.launch { drawer.close() }
                vm.state.value.reader.open -> vm.closeReader()
                else -> vm.show(StreamView.Home)
            }
        },
    )

    fun handleKey(key: Key): Boolean {
        val entry = vm.focusedEntry()
        when (key) {
            Key.J -> vm.moveFocus(1)
            Key.K -> vm.moveFocus(-1)
            Key.O, Key.Enter -> entry?.let(vm::open) ?: return false
            Key.V -> entry?.url?.let { vm.openedExternally(entry); uri.openUri(it) } ?: return false
            Key.C -> entry?.commentsUrl?.let { vm.openedExternally(entry); uri.openUri(it) } ?: return false
            Key.F -> entry?.let(vm::toggleFavorite) ?: return false
            Key.B -> entry?.let(vm::toggleBookmark) ?: return false
            Key.X -> entry?.let(vm::dismiss) ?: return false
            Key.R -> vm.refresh(manual = true)
            Key.Slash -> searchFocus.requestFocus()
            Key.Escape -> if (state.reader.open) vm.closeReader() else return false
            else -> return false
        }
        return true
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || typing || dialog != null) return@onPreviewKeyEvent false
                if (e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return@onPreviewKeyEvent false
                handleKey(e.key)
            }
    ) {
        val wide = maxWidth >= 1000.dp

        val sidebar: @Composable (Modifier) -> Unit = { modifier ->
            // The modal drawer closes behind a dialog opened from it.
            fun open(d: DialogState) {
                dialog = d
                scope.launch { drawer.close() }
            }
            Sidebar(
                view = state.view,
                feeds = feeds,
                folders = folders,
                onSelect = { vm.show(it); scope.launch { drawer.close() } },
                onAddFeed = { open(DialogState.AddFeed()) },
                onEditFeed = { open(DialogState.EditFeed(it)) },
                onEditFolder = { open(DialogState.EditFolder(it)) },
                onSettings = { open(DialogState.Settings) },
                modifier = modifier,
            )
        }

        // Alone in a wide window the stream spans the pane, so the wheel and middle-click autoscroll work
        // over the margins too, while its content stays a readable width in the middle.
        val streamMaxWidth = if (wide && !state.reader.open) 760.dp else Dp.Unspecified
        val stream: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = streamMaxWidth)) {
                        StreamHeader(
                            state = state,
                            title = title(state.view, feedMap, folders),
                            showMenu = !wide,
                            searchFocus = searchFocus,
                            onMenu = { scope.launch { drawer.open() } },
                            onSearch = vm::search,
                            onTyping = { typing = it },
                            onRefresh = { vm.refresh(manual = true) },
                            onShowNew = vm::reload,
                        )
                    }
                }
                when {
                    state.loading && state.items.isEmpty() -> Box(Modifier.fillMaxSize())
                    state.items.isEmpty() && state.view == StreamView.Bookmarks -> EmptyState(
                        title = "Save entries for later",
                        body = "Bookmark entries to find them again here. Removing a bookmark doesn't affect the entry.",
                    )
                    state.items.isEmpty() -> EmptyState(
                        title = if (feeds.isEmpty()) "Nothing to read yet" else "You're all caught up",
                        body = when {
                            feeds.isEmpty() -> "Add a feed from the sidebar, or import an OPML file in Settings."
                            state.view == StreamView.Home -> "Entries you've scrolled past are hidden. New ones arrive on the next update."
                            else -> "No entries here."
                        },
                    )
                    else -> PullToRefresh(refreshing = state.refreshing, onRefresh = { vm.refresh(manual = true) }) {
                        EntryList(
                            items = state.items,
                            feeds = feedMap,
                            listState = listState,
                            focused = state.focused,
                            openId = state.reader.entry?.id,
                            showWhy = state.view == StreamView.Home,
                            onOpen = vm::open,
                            onExternal = vm::openedExternally,
                            onFavorite = { vm.toggleFavorite(it) },
                            onBookmark = vm::toggleBookmark,
                            onDismiss = vm::dismiss,
                            hnVoted = { e -> if (state.hnUser == null) null else HackerNews.itemId(e.commentsUrl)?.let { state.hnVoted(it) } },
                            onUpvote = { e -> HackerNews.itemId(e.commentsUrl)?.let(vm::hnToggleVote) },
                            modifier = Modifier.fillMaxSize(),
                            maxItemWidth = streamMaxWidth,
                            // The list scrolls behind the navigation bar and stops just above it.
                            contentPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues(),
                        )
                    }
                }
            }
        }

        val reader: @Composable (Modifier) -> Unit = { modifier ->
            ReaderPane(
                reader = state.reader,
                feed = state.reader.entry?.let { feedMap[it.feedId] },
                narrow = !wide,
                onClose = vm::closeReader,
                onFavorite = { state.reader.entry?.let { vm.toggleFavorite(it) } },
                onBookmark = { state.reader.entry?.let(vm::toggleBookmark) },
                onExternal = { state.reader.entry?.let(vm::openedExternally) },
                onToggleSource = vm::toggleReaderSource,
                comments = CommentActions(
                    loggedIn = state.hnUser != null && state.reader.comments?.source == CommentSource.HackerNews,
                    voted = { state.hnVoted(it, state.reader.comments?.thread?.page) },
                    onVote = vm::hnToggleVote,
                    onReply = vm::hnReply,
                    onRetry = vm::loadComments,
                ),
                modifier = modifier,
            )
        }

        // Each pane applies the system bar insets it needs, so content can draw behind the bars.
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar, Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0),
        ) { padding ->
            Box(Modifier.padding(padding).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        sidebar(Modifier.width(260.dp))
                        VerticalDivider(color = LocalExtraColors.current.divider)
                        if (state.reader.open) {
                            stream(Modifier.widthIn(min = 380.dp, max = 560.dp).weight(0.42f).fillMaxHeight())
                            VerticalDivider(color = LocalExtraColors.current.divider)
                            reader(Modifier.weight(0.58f))
                        } else {
                            stream(Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                } else {
                    // Dragging right anywhere opens the drawer and dragging left closes it.
                    ModalNavigationDrawer(
                        drawerState = drawer,
                        gesturesEnabled = true,
                        drawerContent = { ModalDrawerSheet(Modifier.width(300.dp)) { sidebar(Modifier.fillMaxHeight()) } },
                    ) {
                        if (state.reader.open) reader(Modifier.fillMaxSize()) else stream(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        is DialogState.AddFeed -> AddFeedDialog(folders, vm::addFeed, d.initial) { dialog = null }
        is DialogState.Shared -> SharedLinkDialog(
            url = d.url,
            onRead = { dialog = null; vm.openUrl(d.url) },
            onSubscribe = { dialog = DialogState.AddFeed(d.url) },
            onDismiss = { dialog = null },
        )
        DialogState.Settings -> SettingsDialog(
            settings = state.settings,
            options = vm.settingsOptions,
            loadInterests = vm::interests,
            onSave = vm::saveSettings,
            onResetInterests = vm::resetInterests,
            onImport = { vm.importOpml(platform) },
            onExport = { vm.exportOpml(platform) },
            onRemoveAllAndReset = vm::removeAllAndReset,
            hn = if (vm.hasHackerNews) HnAccountActions(state.hnUser, vm::hnLogin, vm::hnLogout) else null,
            onDismiss = { dialog = null },
        )
        is DialogState.EditFeed -> EditFeedDialog(d.feed, folders, vm::updateFeed, vm::deleteFeed) { dialog = null }
        is DialogState.EditFolder -> EditFolderDialog(
            d.folder,
            onRename = { vm.renameFolder(d.folder, it) },
            onDelete = { vm.deleteFolder(d.folder) },
            onDismiss = { dialog = null },
        )
        null -> LaunchedEffect(Unit) { rootFocus.requestFocus() }
    }
}

/** Pull down to refresh on touch hosts; the indicator shows only for a refresh started by pulling. */
@Composable
private fun PullToRefresh(refreshing: Boolean, onRefresh: () -> Unit, content: @Composable () -> Unit) {
    if (!LocalTouchUi.current) {
        content()
        return
    }
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) { if (!refreshing) pulled = false }
    PullToRefreshBox(isRefreshing = pulled, onRefresh = { pulled = true; onRefresh() }, modifier = Modifier.fillMaxSize()) {
        content()
    }
}

@Composable
private fun StreamHeader(
    state: UiState,
    title: String,
    showMenu: Boolean,
    searchFocus: FocusRequester,
    onMenu: () -> Unit,
    onSearch: (String) -> Unit,
    onTyping: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onShowNew: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    LaunchedEffect(state.view) { if (state.view !is StreamView.Search) query = "" }
    Column {
        Row(Modifier.fillMaxWidth().padding(start = if (showMenu) 4.dp else 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showMenu) IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, "Open navigation") }
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh, enabled = !state.refreshing) { Icon(Icons.Outlined.Refresh, "Check feeds now (r)") }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search, or paste a link to read it") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { query = ""; onSearch("") }) { Icon(Icons.Outlined.Close, "Clear search") } }
            } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .focusRequester(searchFocus)
                .onFocusChanged { onTyping(it.isFocused) }
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) {
                        onSearch(query); true
                    } else false
                },
        )
        if (state.newAvailable > 0) {
            TextButton(onClick = onShowNew, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("Show ${state.newAvailable} new ${if (state.newAvailable == 1) "entry" else "entries"}")
            }
        }
        state.progress?.let { ProgressBar(it) }
        if (state.loading && state.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

private fun title(view: StreamView, feeds: Map<Long, Feed>, folders: List<Folder>): String = when (view) {
    StreamView.Home -> "Home"
    StreamView.Favorites -> "Favorites"
    StreamView.Bookmarks -> "Bookmarks"
    is StreamView.OfFeed -> feeds[view.feedId]?.title ?: "Feed"
    is StreamView.OfFolder -> folders.firstOrNull { it.id == view.folderId }?.name ?: "Folder"
    is StreamView.Search -> "Results for \"${view.query}\""
}

@Composable
private fun ProgressBar(progress: Progress) {
    val shown by animateFloatAsState(progress.fraction, tween(300, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row {
            Text(progress.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(
                "${progress.remainingPercent}% remaining",
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(progress = { shown }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}
