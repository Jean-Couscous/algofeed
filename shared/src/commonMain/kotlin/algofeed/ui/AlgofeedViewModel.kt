package algofeed.ui

import algofeed.BackgroundRefresh
import algofeed.Repository
import algofeed.Settings
import algofeed.StreamView
import algofeed.data.Entry
import algofeed.data.Feed
import algofeed.data.Folder
import algofeed.fetch.HackerNews
import algofeed.fetch.HnItemPage
import algofeed.fetch.CommentThread
import algofeed.rank.Ranked
import algofeed.reader.Article
import algofeed.util.Html
import algofeed.util.Urls
import algofeed.util.nowMillis
import algofeed.util.runCatchingCancellable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReaderState(
    val entry: Entry? = null,
    /** Ad-hoc URL opened from the search box (not stored). */
    val url: String? = null,
    val article: Article? = null,
    /** Showing the feed-provided HTML rather than the extracted article. */
    val showingFeedVersion: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /** The discussion thread, for Hacker News stories and Reddit posts. */
    val comments: CommentsState? = null,
) {
    val open get() = entry != null || url != null
}

enum class CommentSource(val siteName: String) { HackerNews("HN"), Reddit("Reddit") }

data class CommentsState(
    /** The HN item id, or the Reddit post id read as base 36. */
    val storyId: Long,
    val source: CommentSource,
    /** The thread's web page. */
    val threadUrl: String,
    val loading: Boolean = true,
    val thread: CommentThread? = null,
    val error: String? = null,
)

/** A long task with known size, shown as a progress bar. */
data class Progress(val label: String, val done: Int, val total: Int) {
    val fraction get() = if (total == 0) 1f else done.toFloat() / total
    val remainingPercent get() = ((1 - fraction) * 100).toInt().coerceIn(0, 100)
}

data class UiState(
    val view: StreamView = StreamView.Home,
    val items: List<Ranked> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val progress: Progress? = null,
    val newAvailable: Int = 0,
    val focused: Int = -1,
    val reader: ReaderState = ReaderState(),
    val settings: Settings = Settings(),
    val message: String? = null,
    /** Logged-in Hacker News user. */
    val hnUser: String? = null,
    /** Votes made in this session by HN item id, over what the item pages said. */
    val hnVotes: Map<Long, Boolean> = emptyMap(),
) {
    /** Whether the logged-in HN user has upvoted [itemId], as far as the app knows. */
    fun hnVoted(itemId: Long, page: HnItemPage? = null) = hnVotes[itemId] ?: page?.voted(itemId) ?: false
}

class AlgofeedViewModel(
    private val repo: Repository,
    /** Null runs refreshes on a timer while the view model lives (desktop). */
    private val background: BackgroundRefresh? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val feeds: StateFlow<List<Feed>> = repo.feeds.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val folders: StateFlow<List<Folder>> = repo.folders.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val pendingViewed = LinkedHashMap<Long, Entry>()
    private var flushJob: Job? = null
    private var loadJob: Job? = null
    private var readerJob: Job? = null
    private var commentsJob: Job? = null
    private var readStartedAt: Long? = null
    private var autoRefreshJob: Job? = null
    private var loadedAt = 0L

    val settingsOptions = SettingsOptions(
        minRefreshMinutes = background?.minIntervalMinutes ?: 5,
        refreshConstraints = background != null,
    )

    init {
        viewModelScope.launch {
            _state.update { it.copy(settings = repo.settings(), hnUser = repo.hnUser()) }
            reload()
            refresh(reloadAfter = true)
            scheduleAutoRefresh()
        }
    }

    // --- stream

    fun show(view: StreamView) {
        closeReader()
        _state.update { it.copy(view = view, focused = -1) }
        reload()
    }

    fun reload() {
        flushViewed()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loadedAt = nowMillis()
            _state.update { it.copy(loading = true) }
            val s = _state.value
            val items = runCatchingCancellable { repo.stream(s.view, s.settings) }
            _state.update {
                it.copy(
                    items = items.getOrDefault(it.items),
                    loading = false,
                    newAvailable = 0,
                    focused = -1,
                    message = items.exceptionOrNull()?.let { e -> "Could not load entries: ${e.message}" } ?: it.message,
                )
            }
        }
    }

    /** [manual] refreshes skip only feeds fetched in the last minute instead of the last five. */
    fun refresh(
        reloadAfter: Boolean = false,
        manual: Boolean = false,
        minIntervalMs: Long = if (manual) 60_000L else 5 * 60_000L,
    ) {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            val result = runCatchingCancellable {
                repo.refreshAll(minIntervalMs = minIntervalMs) { p -> showProgress("Updating feeds", p.done, p.total) }
            }
            _state.update { it.copy(refreshing = false, progress = null) }
            result.onFailure { e -> notify("Refresh failed: ${e.message}") }
            result.onSuccess { r ->
                if (reloadAfter || _state.value.items.isEmpty()) reload()
                else if (r.newEntries > 0) _state.update { it.copy(newAvailable = it.newAvailable + r.newEntries) }
                if (r.failures.isNotEmpty()) notify("${r.failures.size} feed(s) failed to update")
            }
        }
    }

    /**
     * The app came back to the foreground. Counts what a background refresh added since the stream was
     * loaded, then fetches the feeds that are older than the refresh interval.
     */
    fun onForeground() {
        if (background == null) return
        viewModelScope.launch {
            val added = repo.countFetchedSince(loadedAt)
            if (added > 0) _state.update { it.copy(newAvailable = added) }
            refresh(minIntervalMs = _state.value.settings.refreshMinutes * 60_000L)
        }
    }

    private fun scheduleAutoRefresh() {
        background?.let {
            it.schedule(_state.value.settings)
            return
        }
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (true) {
                delay(_state.value.settings.refreshMinutes * 60_000L)
                refresh()
            }
        }
    }

    fun search(query: String) {
        val q = query.trim()
        when {
            q.isEmpty() -> show(StreamView.Home)
            Urls.looksLikeUrl(q) -> openUrl(q)
            else -> show(StreamView.Search(q))
        }
    }

    fun setFocus(index: Int) = _state.update { it.copy(focused = index.coerceIn(-1, it.items.lastIndex)) }

    fun moveFocus(delta: Int) = _state.update {
        if (it.items.isEmpty()) it else it.copy(focused = (it.focused + delta).coerceIn(0, it.items.lastIndex))
    }

    fun focusedEntry(): Entry? = _state.value.let { s -> s.items.getOrNull(s.focused)?.entry }

    // --- signals

    /** Called with entries the user has scrolled past in the home stream; written in batches. */
    fun scrolledPast(entries: List<Entry>) {
        val fresh = entries.filter { it.viewedAt == null && it.id !in pendingViewed }
        if (fresh.isEmpty()) return
        fresh.forEach { pendingViewed[it.id] = it }
        if (flushJob?.isActive != true) {
            flushJob = viewModelScope.launch {
                delay(2000)
                flushViewed()
            }
        }
    }

    private fun flushViewed() {
        if (pendingViewed.isEmpty()) return
        val batch = pendingViewed.values.toList()
        pendingViewed.clear()
        val now = nowMillis()
        updateEntries(batch.map { it.id }.toSet()) { it.copy(viewedAt = it.viewedAt ?: now) }
        viewModelScope.launch { repo.markViewed(batch) }
    }

    fun toggleFavorite(entry: Entry) {
        val on = entry.favoritedAt == null
        val toggled = entry.copy(favoritedAt = if (on) nowMillis() else null)
        updateEntries(setOf(entry.id)) { it.copy(favoritedAt = toggled.favoritedAt) }
        viewModelScope.launch {
            repo.setFavorite(entry, on)
            if (_state.value.hnUser != null) {
                runCatchingCancellable { repo.syncHnFavorite(entry, on) }.onFailure { notify("Hacker News: ${it.message}") }
            }
        }
    }

    fun toggleBookmark(entry: Entry) {
        val on = entry.bookmarkedAt == null
        updateEntries(setOf(entry.id)) { it.copy(bookmarkedAt = if (on) nowMillis() else null) }
        // As on Twitter, an entry un-bookmarked from the Bookmarks list leaves it at once.
        if (!on && _state.value.view == StreamView.Bookmarks) {
            _state.update { s -> s.copy(items = s.items.filterNot { it.entry.id == entry.id }) }
        }
        viewModelScope.launch { repo.setBookmarked(entry, on) }
        notify(if (on) "Added to your Bookmarks" else "Removed from your Bookmarks")
    }

    fun dismiss(entry: Entry) {
        _state.update { s ->
            val index = s.items.indexOfFirst { it.entry.id == entry.id }
            s.copy(
                items = s.items.filterNot { it.entry.id == entry.id },
                focused = if (index in 0..<s.focused) s.focused - 1 else s.focused.coerceAtMost(s.items.size - 2),
            )
        }
        if (_state.value.reader.entry?.id == entry.id) closeReader()
        viewModelScope.launch { repo.setDismissed(entry, true) }
        notify("Hidden. Similar entries will rank lower.", undo = { undismiss(entry) })
    }

    private fun undismiss(entry: Entry) {
        viewModelScope.launch {
            repo.setDismissed(entry, false)
            reload()
        }
    }

    /** Records that the user followed a link out of the app (browser, comments). */
    fun openedExternally(entry: Entry) {
        updateEntries(setOf(entry.id)) { it.copy(openedAt = it.openedAt ?: nowMillis()) }
        viewModelScope.launch { repo.markOpened(entry) }
    }

    private fun updateEntries(ids: Set<Long>, transform: (Entry) -> Entry) {
        _state.update { s ->
            s.copy(
                items = s.items.map { if (it.entry.id in ids) it.copy(entry = transform(it.entry)) else it },
                reader = s.reader.entry?.takeIf { it.id in ids }?.let { s.reader.copy(entry = transform(it)) } ?: s.reader,
            )
        }
    }

    // --- reader

    /** Opens a stored entry by id, for example from the home-screen widget. */
    fun openEntry(id: Long) {
        viewModelScope.launch { repo.entry(id)?.let(::open) }
    }

    fun open(entry: Entry) {
        stopReadTimer()
        readerJob?.cancel()
        val index = _state.value.items.indexOfFirst { it.entry.id == entry.id }
        val feed = feeds.value.firstOrNull { it.id == entry.feedId }
        val feedHtml = entry.contentHtml ?: entry.summaryHtml
        // Short posts (toots, self posts), feeds that ship full articles and feeds set to it are shown as-is.
        val feedIsEnough = feed?.type == "mastodon" || (feed?.preferFeedVersion == true && feedHtml != null) || feed?.type == "kagi" || entry.url == null ||
            (entry.extractedHtml == null && Html.toText(entry.contentHtml).length > 1500) ||
            (feed?.type == "reddit" && entry.url == entry.commentsUrl) ||
            // Image, gallery and video posts: the reader shows the picture; there is no article to extract.
            Urls.isImage(entry.url) || Urls.isMediaPage(entry.url)
        _state.update {
            it.copy(
                focused = if (index >= 0) index else it.focused,
                reader = ReaderState(
                    entry = entry,
                    article = if (feedIsEnough) Article(entry.title, entry.author, feedHtml.orEmpty()) else null,
                    showingFeedVersion = feedIsEnough,
                    loading = !feedIsEnough,
                    comments = commentsFor(entry, feed),
                ),
            )
        }
        loadComments()
        updateEntries(setOf(entry.id)) { it.copy(openedAt = it.openedAt ?: nowMillis()) }
        viewModelScope.launch { repo.markOpened(entry) }
        readStartedAt = nowMillis()
        if (!feedIsEnough) loadArticle(entry)
    }

    private fun loadArticle(entry: Entry) {
        readerJob = viewModelScope.launch {
            _state.update { it.copy(reader = it.reader.copy(loading = true, error = null, showingFeedVersion = false)) }
            val result = runCatchingCancellable { repo.readable(entry) }
            _state.update { s ->
                if (s.reader.entry?.id != entry.id) return@update s
                result.fold(
                    onSuccess = { s.copy(reader = s.reader.copy(article = it, loading = false)) },
                    onFailure = { e ->
                        val fallback = entry.contentHtml ?: entry.summaryHtml
                        s.copy(
                            reader = s.reader.copy(
                                loading = false,
                                article = fallback?.let { Article(entry.title, entry.author, it) },
                                showingFeedVersion = fallback != null,
                                error = "Couldn't extract the article (${e.message}).",
                            ),
                        )
                    },
                )
            }
        }
    }

    // --- comments and Hacker News

    private fun commentsFor(entry: Entry, feed: Feed?): CommentsState? {
        val url = entry.commentsUrl ?: return null
        HackerNews.itemId(url)?.takeIf { repo.hasHackerNews }?.let { return CommentsState(it, CommentSource.HackerNews, url) }
        if (feed?.type == "reddit") {
            Repository.redditPostId(url)?.let { return CommentsState(it, CommentSource.Reddit, url) }
        }
        return null
    }

    fun loadComments() {
        val comments = _state.value.reader.comments ?: return
        commentsJob?.cancel()
        commentsJob = viewModelScope.launch {
            _state.update { it.copy(reader = it.reader.copy(comments = comments.copy(loading = true, error = null))) }
            val result = runCatchingCancellable {
                when (comments.source) {
                    CommentSource.HackerNews -> repo.hnThread(comments.storyId)
                    CommentSource.Reddit -> repo.redditThread(comments.threadUrl)
                }
            }
            _state.update { s ->
                if (s.reader.comments?.storyId != comments.storyId) return@update s
                s.copy(
                    reader = s.reader.copy(
                        comments = comments.copy(
                            loading = false,
                            thread = result.getOrNull() ?: comments.thread,
                            error = result.exceptionOrNull()?.let { "Couldn't load the comments (${it.message})." },
                        ),
                    ),
                )
            }
        }
    }

    /** Upvotes [itemId] (a story or comment), or takes the vote back when there is one. */
    fun hnToggleVote(itemId: Long) {
        val page = _state.value.reader.comments?.thread?.page
        val up = !_state.value.hnVoted(itemId, page)
        _state.update { it.copy(hnVotes = it.hnVotes + (itemId to up)) }
        viewModelScope.launch {
            runCatchingCancellable { repo.hnVote(itemId, up, page) }.onFailure { e ->
                _state.update { it.copy(hnVotes = it.hnVotes + (itemId to !up)) }
                notify("Hacker News: ${e.message}")
            }
        }
    }

    /** Posts a reply; returns an error message, or null after reloading the thread. */
    suspend fun hnReply(parentId: Long, text: String): String? {
        val comments = _state.value.reader.comments ?: return "No thread open"
        val hmac = comments.thread?.page?.hmac?.takeIf { parentId == comments.storyId }
        return runCatchingCancellable { repo.hnReply(parentId, comments.storyId, text, hmac) }
            .fold(onSuccess = { loadComments(); null }, onFailure = { it.message ?: "Posting failed" })
    }

    /** Returns an error message, or null once logged in. */
    suspend fun hnLogin(user: String, password: String): String? =
        runCatchingCancellable { repo.hnLogin(user, password) }.fold(
            onSuccess = { name -> _state.update { it.copy(hnUser = name, hnVotes = emptyMap()) }; loadComments(); null },
            onFailure = { it.message ?: "Login failed" },
        )

    fun hnLogout() {
        viewModelScope.launch {
            repo.hnLogout()
            _state.update { it.copy(hnUser = null, hnVotes = emptyMap()) }
        }
    }

    val hasHackerNews get() = repo.hasHackerNews

    /** Switches between the feed's own HTML and the extracted article. */
    fun toggleReaderSource() {
        val reader = _state.value.reader
        val entry = reader.entry ?: return
        if (reader.showingFeedVersion) {
            loadArticle(entry)
        } else {
            val html = entry.contentHtml ?: entry.summaryHtml ?: return
            _state.update { it.copy(reader = it.reader.copy(article = Article(entry.title, entry.author, html), showingFeedVersion = true, error = null)) }
        }
    }

    fun openUrl(url: String) {
        stopReadTimer()
        readerJob?.cancel()
        _state.update { it.copy(reader = ReaderState(url = url, loading = true)) }
        readerJob = viewModelScope.launch {
            val result = runCatchingCancellable { repo.readableUrl(url) }
            _state.update { s ->
                if (s.reader.url != url) return@update s
                s.copy(reader = s.reader.copy(article = result.getOrNull(), loading = false, error = result.exceptionOrNull()?.let { "Couldn't load $url (${it.message})." }))
            }
        }
    }

    fun closeReader() {
        stopReadTimer()
        readerJob?.cancel()
        _state.update { it.copy(reader = ReaderState()) }
    }

    private fun stopReadTimer() {
        val entry = _state.value.reader.entry
        val started = readStartedAt
        readStartedAt = null
        if (entry != null && started != null) {
            val seconds = ((nowMillis() - started) / 1000).toInt().coerceAtMost(30 * 60)
            viewModelScope.launch { repo.addReadTime(entry, seconds) }
        }
    }

    // --- subscriptions

    suspend fun addFeed(input: String, folderName: String?): Result<Feed> = runCatchingCancellable {
        val folderId = folderName?.trim()?.ifEmpty { null }?.let { repo.createFolder(it).id }
        repo.addFeed(input, folderId)
    }.onSuccess { if (_state.value.view == StreamView.Home) reload() }

    fun updateFeed(feed: Feed, folderName: String?) {
        viewModelScope.launch {
            val folderId = folderName?.trim()?.ifEmpty { null }?.let { repo.createFolder(it).id }
            repo.updateFeed(feed.copy(folderId = folderId))
            reload()
        }
    }

    fun deleteFeed(feed: Feed) {
        viewModelScope.launch {
            repo.deleteFeed(feed)
            if (_state.value.view == StreamView.OfFeed(feed.id)) show(StreamView.Home) else reload()
        }
    }

    fun renameFolder(folder: Folder, name: String) {
        viewModelScope.launch { repo.renameFolder(folder, name) }
    }

    fun deleteFolder(folder: Folder) {
        viewModelScope.launch {
            repo.deleteFolder(folder)
            if (_state.value.view == StreamView.OfFolder(folder.id)) show(StreamView.Home)
        }
    }

    // --- settings

    fun saveSettings(settings: Settings) {
        val old = _state.value.settings
        _state.update { it.copy(settings = settings) }
        viewModelScope.launch {
            repo.saveSettings(settings)
            val scheduling = settings.copy(
                refreshMinutes = old.refreshMinutes,
                refreshUnmeteredOnly = old.refreshUnmeteredOnly,
                refreshWhileChargingOnly = old.refreshWhileChargingOnly,
            )
            if (scheduling != settings) scheduleAutoRefresh()
            if (scheduling.copy(theme = old.theme, dynamicColor = old.dynamicColor) != old) reload()
        }
    }

    /** Strongest learned likes and dislikes, as word stems. */
    suspend fun interests(): Pair<List<Pair<String, Double>>, List<Pair<String, Double>>> {
        val profile = repo.profile().entries.map { it.key to it.value }
        return profile.filter { it.second > 0 }.sortedByDescending { it.second }.take(15) to
            profile.filter { it.second < 0 }.sortedBy { it.second }.take(10)
    }

    /** Removes every subscription, entry and learned preference; settings are untouched. */
    suspend fun removeAllAndReset() {
        closeReader()
        pendingViewed.clear()
        repo.removeAllAndReset()
        _state.update { it.copy(view = StreamView.Home, items = emptyList(), newAvailable = 0, focused = -1) }
        notify("Removed all subscriptions and reset the ranking")
        reload()
    }

    fun resetInterests() {
        viewModelScope.launch {
            repo.resetProfile()
            notify("Learned interests cleared")
            reload()
        }
    }

    fun importOpml(platform: PlatformActions) {
        viewModelScope.launch {
            val xml = platform.pickOpml() ?: return@launch
            showProgress("Importing subscriptions", 0, 1)
            val result = runCatchingCancellable { repo.importOpml(xml) { done, total -> showProgress("Importing subscriptions", done, total) } }
            _state.update { it.copy(progress = null) }
            result.onSuccess { (added, failed) ->
                notify(if (failed.isEmpty()) "Imported $added feeds" else "Imported $added feeds, ${failed.size} failed")
                reload()
            }.onFailure { notify("Import failed: ${it.message}") }
        }
    }

    fun exportOpml(platform: PlatformActions) {
        viewModelScope.launch {
            val saved = platform.saveOpml(repo.exportOpml())
            if (saved) notify("Subscriptions exported")
        }
    }

    private fun showProgress(label: String, done: Int, total: Int) =
        _state.update { it.copy(progress = if (total > 0) Progress(label, done, total) else null) }

    // --- messages

    private var undoAction: (() -> Unit)? = null

    private fun notify(message: String, undo: (() -> Unit)? = null) {
        undoAction = undo
        _state.update { it.copy(message = message) }
    }

    fun undo() {
        undoAction?.invoke()
        undoAction = null
    }

    fun hasUndo() = undoAction != null

    fun messageShown() = _state.update { it.copy(message = null) }

    /** Writes buffered signals; called before the app exits. */
    suspend fun persistPending() {
        val batch = pendingViewed.values.toList()
        pendingViewed.clear()
        repo.markViewed(batch)
        val entry = _state.value.reader.entry
        val started = readStartedAt
        readStartedAt = null
        if (entry != null && started != null) repo.addReadTime(entry, ((nowMillis() - started) / 1000).toInt().coerceAtMost(30 * 60))
    }
}
