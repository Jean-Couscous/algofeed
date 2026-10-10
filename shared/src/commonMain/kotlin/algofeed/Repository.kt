package algofeed

import algofeed.data.AppDatabase
import algofeed.data.Entry
import algofeed.data.EntryEmbedding
import algofeed.data.Feed
import algofeed.data.Folder
import algofeed.data.Setting
import algofeed.fetch.EntryDraft
import algofeed.fetch.FetchException
import algofeed.fetch.FetchResult
import algofeed.fetch.HackerNews
import algofeed.fetch.HackerNewsException
import algofeed.fetch.HnItemPage
import algofeed.fetch.CommentThread
import algofeed.fetch.FourChanAdapter
import algofeed.fetch.RateLimitStore
import algofeed.fetch.RateLimiter
import algofeed.fetch.Sources
import algofeed.opml.Opml
import algofeed.rank.Buckets
import algofeed.rank.DisabledEmbedder
import algofeed.rank.Embedder
import algofeed.rank.ProfileLearner
import algofeed.rank.RankInput
import algofeed.rank.Ranked
import algofeed.rank.Ranker
import algofeed.rank.Signal
import algofeed.rank.Vectors
import algofeed.reader.Article
import algofeed.reader.ReaderExtractor
import algofeed.util.DAY_MS
import algofeed.util.Html
import algofeed.util.Urls
import algofeed.util.nowMillis
import algofeed.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

sealed interface StreamView {
    data object Home : StreamView
    data object Bookmarks : StreamView
    data object Media : StreamView
    data class OfFeed(val feedId: Long) : StreamView
    data class OfFolder(val folderId: Long) : StreamView
    data class Search(val query: String) : StreamView
}

/** Backs the rate limiter's per-host quota with the settings table (no schema migration needed). */
fun rateLimitStore(db: AppDatabase): RateLimitStore = object : RateLimitStore {
    override suspend fun load(host: String): String? = db.settings().get("ratelimit.$host")?.ifBlank { null }
    override suspend fun save(host: String, json: String) = db.settings().put(Setting("ratelimit.$host", json))
}

/** Refresh progress: [done] of [total] feeds fetched. */
data class RefreshProgress(val done: Int, val total: Int)

data class RefreshResult(val newEntries: Int, val failures: Map<Long, String>)

class Repository(
    private val db: AppDatabase,
    private val sources: Sources,
    private val extractor: ReaderExtractor,
    private val clock: () -> Long = ::nowMillis,
    /** Where the Hacker News session lives; null keeps it in the settings table (desktop). */
    private val secrets: SecretStore? = null,
    /** Settings before the user saves any. */
    private val defaults: Settings = Settings(),
    /** Hacker News account actions; null hides them. */
    private val hackerNews: HackerNews? = null,
    /** MangaDex OAuth; null hides the login and the followed feed can't authenticate. */
    private val mangadexAuth: algofeed.fetch.MangadexAuth? = null,
    /** On-device content embedder; [DisabledEmbedder] (the default) leaves the content signal at 0. */
    private val embedder: Embedder = DisabledEmbedder,
    /** Proactive per-host rate limiting; null skips the check (feeds still rely on the 429 retry). */
    private val rateLimiter: RateLimiter? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val profileMutex = Mutex()
    private var profileCache: FloatArray? = null

    val feeds: Flow<List<Feed>> = db.feeds().observeAll()
    val folders: Flow<List<Folder>> = db.folders().observeAll()

    // --- settings

    suspend fun settings(): Settings {
        val raw = db.settings().get(SETTINGS_KEY) ?: return defaults
        val stored = runCatching { json.decodeFromString<Settings>(raw) }.getOrNull() ?: return defaults
        // Settings saved before AI ranking was removed hold an "ai" block, possibly with an API key; drop it.
        if ("\"ai\"" in raw) saveSettings(stored)
        return stored
    }

    suspend fun saveSettings(settings: Settings) {
        db.settings().put(Setting(SETTINGS_KEY, json.encodeToString(Settings.serializer(), settings)))
    }

    // --- Hacker News account

    val hasHackerNews: Boolean get() = hackerNews != null

    /** The session cookie: in [secrets] when there is a store, else in the settings table (desktop). */
    private suspend fun hnSession(): String? =
        if (secrets != null) secrets.hnSession() else db.settings().get(HN_SESSION_KEY)?.ifBlank { null }

    private suspend fun setHnSession(session: String) {
        if (secrets != null) secrets.setHnSession(session) else db.settings().put(Setting(HN_SESSION_KEY, session))
    }

    suspend fun hnUser(): String? = hnSession()?.let(HackerNews::userOf)

    /** A user-provided secret (API key, token): in [secrets] when there is a store, else the settings table. */
    suspend fun secret(key: String): String? =
        if (secrets != null) secrets.secret(key) else db.settings().get(key)?.ifBlank { null }

    suspend fun setSecret(key: String, value: String) {
        if (secrets != null) secrets.setSecret(key, value) else db.settings().put(Setting(key, value.trim()))
    }

    suspend fun hnLogin(user: String, password: String): String {
        val hn = hackerNews ?: error("Hacker News isn't available")
        setHnSession(hn.login(user.trim(), password))
        return HackerNews.userOf(hnSession()!!)
    }

    suspend fun hnLogout() = setHnSession("")

    // --- MangaDex account

    val hasMangadex: Boolean get() = mangadexAuth != null

    suspend fun mangadexUser(): String? =
        secret(SecretStore.MANGADEX_REFRESH_TOKEN)?.let { secret(SecretStore.MANGADEX_USER) ?: "MangaDex" }

    /** Returns an error message, or null once logged in. */
    suspend fun mangadexLogin(clientId: String, clientSecret: String, user: String, password: String): String? {
        val auth = mangadexAuth ?: return "MangaDex isn't available"
        return runCatchingCancellable {
            val refresh = auth.login(clientId.trim(), clientSecret.trim(), user.trim(), password)
            setSecret(SecretStore.MANGADEX_CLIENT_ID, clientId.trim())
            setSecret(SecretStore.MANGADEX_CLIENT_SECRET, clientSecret.trim())
            setSecret(SecretStore.MANGADEX_REFRESH_TOKEN, refresh)
            setSecret(SecretStore.MANGADEX_USER, user.trim())
        }.fold(onSuccess = { null }, onFailure = { it.message ?: "Login failed" })
    }

    suspend fun mangadexLogout() {
        mangadexAuth?.forget()
        setSecret(SecretStore.MANGADEX_CLIENT_ID, "")
        setSecret(SecretStore.MANGADEX_CLIENT_SECRET, "")
        setSecret(SecretStore.MANGADEX_REFRESH_TOKEN, "")
        setSecret(SecretStore.MANGADEX_USER, "")
    }

    suspend fun hnThread(itemId: Long): CommentThread = hackerNews!!.thread(itemId, hnSession())

    /** The replies to a 4chan thread, from its web URL. */
    suspend fun fourchanThread(url: String): CommentThread {
        val adapter = sources.adapters.firstOrNull { it is FourChanAdapter } as? FourChanAdapter
            ?: throw FetchException("4chan isn't available")
        return adapter.thread(url)
    }

    private suspend fun requireHnSession() = hnSession() ?: throw HackerNewsException("Log in to Hacker News in Settings first")

    /** Upvotes or unvotes a story or comment; a [page] that has the link saves fetching the item page. */
    suspend fun hnVote(itemId: Long, up: Boolean, page: HnItemPage? = null) {
        val session = requireHnSession()
        val hn = hackerNews!!
        fun link(p: HnItemPage) = if (up) p.upvoteUrls[itemId] else p.unvoteUrls[itemId]
        val url = page?.let(::link) ?: link(hn.itemPage(itemId, session))
            ?: throw HackerNewsException("Hacker News didn't offer that vote. Are you still logged in?")
        hn.follow(url, session)
    }

    suspend fun hnReply(parentId: Long, storyId: Long, text: String, hmac: String? = null) =
        hackerNews!!.reply(parentId, storyId, text, requireHnSession(), hmac)

    // --- subscriptions

    suspend fun addFeed(input: String, folderId: Long? = null): Feed {
        val info = sources.resolve(input)
        db.feeds().byUrl(info.url)?.let { return it }
        val feed = Feed(
            folderId = folderId,
            type = info.type,
            url = info.url,
            title = info.title,
            siteUrl = info.siteUrl,
            iconUrl = info.iconUrl,
            createdAt = clock(),
        )
        val saved = feed.copy(id = db.feeds().insert(feed))
        refreshFeed(saved)
        return db.feeds().get(saved.id) ?: saved
    }

    suspend fun updateFeed(feed: Feed) = db.feeds().update(feed)
    suspend fun deleteFeed(feed: Feed) = db.feeds().delete(feed)

    suspend fun createFolder(name: String): Folder {
        db.folders().byName(name)?.let { return it }
        val folder = Folder(name = name)
        return folder.copy(id = db.folders().insert(folder))
    }

    suspend fun renameFolder(folder: Folder, name: String) = db.folders().update(folder.copy(name = name))
    suspend fun deleteFolder(folder: Folder) = db.folders().delete(folder)

    // --- fetching

    /**
     * Fetches every enabled feed not fetched in the last [minIntervalMs] (some hosts, Reddit in
     * particular, rate-limit quick refetches), then prunes old entries and decays the profile.
     */
    suspend fun refreshAll(
        concurrency: Int = 10,
        minIntervalMs: Long = 5 * 60_000L,
        onProgress: (RefreshProgress) -> Unit = {},
    ): RefreshResult = coroutineScope {
        val gate = Semaphore(concurrency)
        val now = clock()
        val feeds = db.feeds().all().filter { it.enabled && now - (it.lastFetch ?: 0L) >= minIntervalMs }
        val total = feeds.size
        val counter = Mutex()
        var done = 0
        if (total > 0) onProgress(RefreshProgress(0, total))
        val results = feeds.map { feed ->
            async {
                gate.withPermit {
                    // A host near its quota is skipped this cycle (no error badge); it retries next cycle.
                    val r = if (rateLimiter?.allow(Urls.host(feed.url)) == false) feed.id to Result.success(0)
                    else feed.id to runCatchingCancellable { refreshFeed(feed) }
                    counter.withLock { onProgress(RefreshProgress(++done, total)) }
                    r
                }
            }
        }.awaitAll()
        val settings = settings()
        db.entries().prune(clock() - settings.retentionDays * DAY_MS)
        decayProfileIfDue()
        RefreshResult(
            newEntries = results.sumOf { it.second.getOrDefault(0) },
            failures = results.mapNotNull { (id, r) -> r.exceptionOrNull()?.let { id to (it.message ?: it.toString()) } }.toMap(),
        )
    }

    /** Fetches one feed, stores new entries and their terms, and recomputes the frequency bucket. */
    suspend fun refreshFeed(feed: Feed): Int {
        val now = clock()
        val result = try {
            withContext(Dispatchers.IO) { sources.forFeed(feed).fetch(feed) }
        } catch (e: Exception) {
            db.feeds().update(feed.copy(lastFetch = now, lastError = e.message ?: e.toString()))
            throw if (e is FetchException) e else FetchException(e.message ?: e.toString(), e)
        }
        var inserted = 0
        var updated = feed.copy(lastFetch = now, lastError = null)
        if (result is FetchResult.Fetched) {
            inserted = store(feed, result.entries, now)
            updated = updated.copy(etag = result.etag, lastModified = result.lastModified)
        }
        val volume = db.entries().volume(feed.id)
        db.feeds().update(updated.copy(bucket = Buckets.compute(volume.count, volume.oldest, now)))
        return inserted
    }

    private suspend fun store(feed: Feed, drafts: List<EntryDraft>, now: Long): Int {
        val retentionStart = now - settings().retentionDays * DAY_MS
        val fresh = drafts.filter { it.sortDate >= retentionStart }.distinctBy { it.remoteId }
        if (fresh.isEmpty()) return 0
        val entries = fresh.map {
            Entry(
                feedId = feed.id,
                remoteId = it.remoteId,
                url = it.url,
                commentsUrl = it.commentsUrl,
                title = it.title,
                author = it.author,
                summaryHtml = it.summaryHtml,
                contentHtml = it.contentHtml,
                thumbnailUrl = it.thumbnailUrl,
                media = algofeed.data.MediaCodec.encode(it.media),
                sortDate = it.sortDate,
                fetchedAt = now,
            )
        }
        val ids = db.entries().insertIgnore(entries)
        val toEmbed = ArrayList<Pair<Long, String>>()
        ids.forEachIndexed { i, id ->
            if (id <= 0) return@forEachIndexed
            val e = entries[i]
            val body = Html.toText(e.summaryHtml ?: e.contentHtml).take(2000)
            toEmbed += id to embeddingText(e.title, body)
        }
        embedAndStore(toEmbed)
        return ids.count { it > 0 }
    }

    /** e5 expects a "query: " prefix; the title leads since many sources give little or no body. */
    private fun embeddingText(title: String?, body: String): String =
        "query: " + listOfNotNull(title?.trim()?.ifEmpty { null }, body.trim().ifEmpty { null }).joinToString(". ")

    /** Embeds entries and stores their vectors; a model failure leaves them unembedded (content = 0). */
    private suspend fun embedAndStore(items: List<Pair<Long, String>>) {
        if (items.isEmpty()) return
        val vectors = runCatchingCancellable { embedder.embed(items.map { it.second }) }.getOrNull() ?: return
        val rows = ArrayList<EntryEmbedding>(items.size)
        items.forEachIndexed { i, (id, _) ->
            val v = vectors.getOrNull(i) ?: return@forEachIndexed
            if (v.isNotEmpty()) rows += EntryEmbedding(id, Vectors.toBytes(v))
        }
        if (rows.isNotEmpty()) db.embeddings().upsert(rows)
    }

    // --- streams

    suspend fun stream(view: StreamView, settings: Settings): List<Ranked> {
        val entries = db.entries()
        return when (view) {
            StreamView.Home -> {
                val since = clock() - settings.retentionDays * DAY_MS
                val candidates = if (settings.includeSeen) entries.candidatesIncludingSeen(since) else entries.candidates(since)
                rank(candidates, settings)
            }
            StreamView.Bookmarks -> entries.bookmarks().unranked()
            StreamView.Media -> entries.withMedia().unranked()
            is StreamView.OfFeed -> entries.byFeed(view.feedId).unranked()
            is StreamView.OfFolder -> entries.byFolder(view.folderId).unranked()
            is StreamView.Search -> if (view.query.isBlank()) emptyList() else entries.search(view.query).unranked()
        }
    }

    private suspend fun rank(candidates: List<Entry>, settings: Settings): List<Ranked> {
        val feeds = db.feeds().all().associateBy { it.id }
        val authorStats = db.authorStats().forFeeds(candidates.map { it.feedId }.distinct())
            .associateBy { it.feedId to it.author }
        val input = RankInput(
            candidates = candidates,
            feeds = feeds,
            embeddings = embeddingsFor(candidates.map { it.id }),
            profile = profile(),
            now = clock(),
            authorStats = authorStats,
        )
        return withContext(Dispatchers.Default) { Ranker().rank(input) }
    }

    private fun List<Entry>.unranked() = map { Ranked(it, 0.0, algofeed.rank.Breakdown()) }

    private suspend fun embeddingsFor(ids: List<Long>): Map<Long, FloatArray> =
        ids.chunked(900).flatMap { db.embeddings().forEntries(it) }
            .associate { it.entryId to Vectors.fromBytes(it.vector) }

    suspend fun entry(id: Long): Entry? = db.entries().get(id)

    // --- signals

    /** The entry's author for per-author affinity, trimmed; null when the source gives no author. */
    private fun Entry.authorKey(): String? = author?.trim()?.takeIf { it.isNotEmpty() }

    /** Entries scrolled past: they count as impressions and nudge the profile away from skipped topics. */
    suspend fun markViewed(entries: List<Entry>) {
        if (entries.isEmpty()) return
        val now = clock()
        val changed = db.entries().markViewed(entries.map { it.id }, now)
        if (changed == 0) return
        entries.groupBy { it.feedId }.forEach { (feedId, list) -> db.feeds().addStats(feedId, impressions = list.size) }
        entries.groupBy { it.feedId to it.authorKey() }.forEach { (key, list) ->
            key.second?.let { db.authorStats().addStats(key.first, it, impressions = list.size) }
        }
        val skipped = entries.filter { it.openedAt == null && it.favoritedAt == null && it.bookmarkedAt == null }
        learn(skipped, Signal.SkippedPast)
    }

    suspend fun markOpened(entry: Entry) {
        if (entry.openedAt != null) return
        val now = clock()
        db.entries().setOpened(entry.id, now)
        // Opening implies seeing: it counts as an impression and leaves Home on the next load.
        val impressions = db.entries().markViewed(listOf(entry.id), now)
        db.feeds().addStats(entry.feedId, impressions = impressions, opens = 1)
        entry.authorKey()?.let { db.authorStats().addStats(entry.feedId, it, impressions = impressions, opens = 1) }
        learn(listOf(entry), Signal.Open)
    }

    suspend fun addReadTime(entry: Entry, seconds: Int) {
        if (seconds <= 0) return
        val before = db.entries().get(entry.id)?.readSeconds ?: return
        db.entries().addReadSeconds(entry.id, seconds)
        if (before < LONG_READ_SECONDS && before + seconds >= LONG_READ_SECONDS) learn(listOf(entry), Signal.LongRead)
    }

    // Liking an entry. The `favoritedAt` column and the `favorites` affinity counters back likes; the
    // name is kept so no DB migration is needed. A like trains the ranker and nothing else.
    suspend fun setLike(entry: Entry, on: Boolean) {
        db.entries().setFavorited(entry.id, if (on) clock() else null)
        db.feeds().addStats(entry.feedId, favorites = if (on) 1 else -1)
        entry.authorKey()?.let { db.authorStats().addStats(entry.feedId, it, favorites = if (on) 1 else -1) }
        learn(listOf(entry), if (on) Signal.Favorite else Signal.Dismiss, scale = if (on) 1.0 else 3.0)
    }

    suspend fun setBookmarked(entry: Entry, on: Boolean) {
        db.entries().setBookmarked(entry.id, if (on) clock() else null)
        if (on) learn(listOf(entry), Signal.Bookmark)
    }

    suspend fun setDismissed(entry: Entry, on: Boolean) {
        db.entries().setDismissed(entry.id, if (on) clock() else null)
        db.feeds().addStats(entry.feedId, dismissals = if (on) 1 else -1)
        entry.authorKey()?.let { db.authorStats().addStats(entry.feedId, it, dismissals = if (on) 1 else -1) }
        learn(listOf(entry), Signal.Dismiss, scale = if (on) 1.0 else -1.0)
    }

    private suspend fun learn(entries: List<Entry>, signal: Signal, scale: Double = 1.0) {
        if (entries.isEmpty()) return
        val vectors = embeddingsFor(entries.map { it.id })
        if (vectors.isEmpty()) return
        profileMutex.withLock {
            val current = loadProfileLocked()
            val dim = current?.size ?: vectors.values.firstOrNull { it.isNotEmpty() }?.size ?: return@withLock
            val profile = current?.copyOf() ?: FloatArray(dim)
            for (e in entries) {
                val v = vectors[e.id] ?: continue
                if (v.size != dim) continue
                ProfileLearner.apply(profile, v, signal, scale)
            }
            db.profileVector().put(Vectors.toBytes(profile))
            profileCache = profile
        }
    }

    private suspend fun loadProfileLocked(): FloatArray? =
        profileCache ?: db.profileVector().get()?.let { Vectors.fromBytes(it) }?.also { profileCache = it }

    suspend fun profile(): FloatArray? = profileMutex.withLock { loadProfileLocked()?.copyOf() }

    /** Unsubscribes from everything and forgets all learning; settings stay. */
    suspend fun removeAllAndReset() = profileMutex.withLock {
        db.reset().clearSubscriptionsAndLearning()
        profileCache = null
    }

    private suspend fun decayProfileIfDue() {
        val now = clock()
        val last = db.settings().get(DECAY_KEY)?.toLongOrNull()
        if (last == null) {
            db.settings().put(Setting(DECAY_KEY, now.toString()))
            return
        }
        val days = (now - last).toDouble() / DAY_MS
        if (days < 1) return
        profileMutex.withLock {
            loadProfileLocked()?.let { current ->
                val decayed = ProfileLearner.decay(current, days)
                db.profileVector().put(Vectors.toBytes(decayed))
                profileCache = decayed
            }
        }
        db.settings().put(Setting(DECAY_KEY, now.toString()))
    }

    // --- reader

    suspend fun readable(entry: Entry): Article {
        // Image links were once extracted as if they were pages; those cached results are gibberish.
        entry.extractedHtml?.takeUnless { Urls.isImage(entry.url) }?.let { return Article(entry.title, entry.author, it) }
        val url = entry.url ?: throw FetchException("Entry has no link")
        val article = withContext(Dispatchers.IO) { extractor.extract(url) }
        db.entries().setExtracted(entry.id, article.html)
        return article
    }

    suspend fun readableUrl(url: String): Article = withContext(Dispatchers.IO) { extractor.extract(url) }

    // --- OPML

    suspend fun exportOpml(): String = Opml.export(db.folders().all(), db.feeds().all())

    /** Subscribes to every feed in the document. Returns (added, failed inputs with reasons). */
    suspend fun importOpml(xml: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Pair<Int, Map<String, String>> =
        coroutineScope {
            val items = Opml.parse(xml)
            val folderIds = items.mapNotNull { it.folder }.distinct().associateWith { createFolder(it).id }
            val gate = Semaphore(6)
            var done = 0
            val results = items.map { item ->
                async {
                    gate.withPermit {
                        val r = runCatchingCancellable {
                            val existing = db.feeds().byUrl(item.url)
                            if (existing != null) return@runCatchingCancellable false
                            val feed = Feed(
                                folderId = item.folder?.let(folderIds::get),
                                type = sources.forInput(item.url).type,
                                url = item.url,
                                title = item.title ?: item.url,
                                createdAt = clock(),
                            )
                            val saved = feed.copy(id = db.feeds().insert(feed))
                            runCatchingCancellable { refreshFeed(saved) }
                            true
                        }
                        done++
                        onProgress(done, items.size)
                        item.url to r
                    }
                }
            }.awaitAll()
            results.count { it.second.getOrNull() == true } to
                results.mapNotNull { (url, r) -> r.exceptionOrNull()?.let { url to (it.message ?: it.toString()) } }.toMap()
        }

    // --- backup

    /** A portable snapshot of subscriptions, settings and learned ranking as JSON. */
    suspend fun exportBackup(): String {
        val folders = db.folders().all()
        val nameOf = folders.associate { it.id to it.name }
        val backup = Backup(
            settings = settings(),
            folders = folders.map { BackupFolder(it.name) },
            feeds = db.feeds().all().map { f ->
                BackupFeed(
                    type = f.type, url = f.url, title = f.title, siteUrl = f.siteUrl, iconUrl = f.iconUrl,
                    folder = f.folderId?.let(nameOf::get), bucket = f.bucket,
                    impressions = f.impressions, opens = f.opens, favorites = f.favorites, dismissals = f.dismissals,
                    preferFeedVersion = f.preferFeedVersion,
                    authors = db.authorStats().forFeed(f.id).map {
                        BackupAuthor(it.author, it.impressions, it.opens, it.favorites, it.dismissals)
                    },
                )
            },
            profileVector = profile()?.toList() ?: emptyList(),
        )
        return json.encodeToString(Backup.serializer(), backup)
    }

    /** Restores a [exportBackup] snapshot, merging into whatever is already here. Returns feeds added. */
    suspend fun importBackup(raw: String): Int {
        val backup = json.decodeFromString(Backup.serializer(), raw)
        saveSettings(backup.settings)
        val folderIds = backup.folders.associate { it.name to createFolder(it.name).id }
        var added = 0
        for (bf in backup.feeds) {
            if (db.feeds().byUrl(bf.url) != null) continue
            val feed = Feed(
                folderId = bf.folder?.let(folderIds::get),
                type = bf.type, url = bf.url, title = bf.title, siteUrl = bf.siteUrl, iconUrl = bf.iconUrl,
                bucket = bf.bucket, impressions = bf.impressions, opens = bf.opens, favorites = bf.favorites,
                dismissals = bf.dismissals, preferFeedVersion = bf.preferFeedVersion, createdAt = clock(),
            )
            val saved = feed.copy(id = db.feeds().insert(feed))
            for (a in bf.authors) {
                db.authorStats().addStats(saved.id, a.author, a.impressions, a.opens, a.favorites, a.dismissals)
            }
            runCatchingCancellable { refreshFeed(saved) }
            added++
        }
        if (backup.profileVector.isNotEmpty()) profileMutex.withLock {
            db.profileVector().put(Vectors.toBytes(backup.profileVector.toFloatArray()))
            profileCache = null
        }
        return added
    }

    /** Unseen entries stored since [since], including ones a background refresh added. */
    suspend fun countFetchedSince(since: Long): Int = db.entries().countFetchedSince(since)

    companion object {
        private const val SETTINGS_KEY = "settings"
        private const val DECAY_KEY = "profile.lastDecay"
        private const val HN_SESSION_KEY = "hn.session"
        const val LONG_READ_SECONDS = 30
    }
}
