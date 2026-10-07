# Algofeed

A personal feed reader with a ranked home stream, inspired by [feedi](https://github.com/facundoolano/feedi). Built with Kotlin Multiplatform and Compose Multiplatform. Nearly all the code lives in `shared/`, used by a desktop app and an Android app (in progress).

## Run

Requires a JDK (Gradle downloads JDK 21 for the build if needed).

```bash
./gradlew :desktopApp:run                                  # start the app
./gradlew :shared:desktopTest                              # tests
./gradlew :desktopApp:packageDistributionForCurrentOS      # .deb / .rpm / AppImage
```

The database lives at `$XDG_DATA_HOME/algofeed/algofeed.db` (default `~/.local/share/algofeed/algofeed.db`). Set `ALGOFEED_DB` to use another file.

### Android

Requires the Android SDK with platform 37, with its path in `local.properties` (`sdk.dir=…`) or `ANDROID_HOME`. The app targets API 36 and runs on Android 8.0 (API 26) and later.

```bash
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Release builds are shrunk with R8:

```bash
./gradlew :androidApp:assembleRelease   # APK, androidApp/build/outputs/apk/release/
./gradlew :androidApp:bundleRelease     # AAB for Play, androidApp/build/outputs/bundle/release/
```

Without a signing key they're signed with the debug key, which is only good for testing on your own devices. To sign with an upload key, add its path, passwords and alias to `~/.gradle/gradle.properties` as `algofeed.release.storeFile`, `algofeed.release.storePassword`, `algofeed.release.keyAlias` and `algofeed.release.keyPassword` (see `androidApp/build.gradle.kts`). Keep `androidApp/build/outputs/mapping/release/mapping.txt` from every build you ship; crash stack traces can only be decoded with it.

## Adding feeds

"Add feed" accepts:

| Input | Source |
| --- | --- |
| a site or feed URL | RSS, Atom or RDF; site pages are searched for a `<link rel="alternate">` feed |
| `r/kotlin`, `reddit.com/r/kotlin/top`, `u/someone/m/feed` | Reddit listing or public custom feed (JSON, RSS fallback); links go to the submitted URL, the thread is the discussion link |
| `hn`, an `hnrss.org` URL | Hacker News with discussion links |
| `kagi`, `kagi:tech`, `kagi technology`, a `news.kagi.com` URL | Kagi News category (World by default); the reader shows Kagi's summary |
| `lobsters`, `lobste.rs/t/rust` | Lobsters front page or tag |
| `@user@server`, `#tag@server`, `https://server/@user` | Mastodon account or hashtag |
| `youtube.com/@handle`, `/channel/UC…` | YouTube channel |

Hacker News stories show their comment thread under the article in the reader (from the Algolia HN API). Logging in under Settings > Hacker News account adds upvote buttons on stories and comments, replies, and a top-level comment box; favoriting an HN story in the app also favorites it on HN. HN has no write API, so this uses the website's own login form and links and can break when HN changes its pages. The session cookie is kept in the Android Keystore-encrypted store, or in the local database on desktop; the password isn't stored.

On Android, the "Top entries" home-screen widget lists the three entries at the top of Home; tapping one opens it in the reader. It updates after each background refresh and when you leave the app. Settings > Appearance has "Use wallpaper colors" on Android 12 and later.

Reddit posts show their comments under the post, read-only. Reddit often refuses its JSON API to apps without an account; the comments then come from the thread's RSS feed, which lists up to 100 comments without saying which one each replies to, so they appear as a flat list. Image posts show the image in the reader; galleries and videos show their preview with a link to the full post.

Each feed's Edit dialog has "Always show the feed version in the reader", which skips fetching and extracting the article page.

OPML import and export are in Settings. Pasting a URL into the search box opens it in the reader without subscribing.

## Ordering

Home has one ordering: a weighted sum whose weights are sliders in Settings.

- **Freshness**: exponential decay over about 36 hours, plus a bonus inside 72 hours. Raise it to lean towards newest first.
- **Quiet feeds**: feedi's frequency buckets (from monthly to more than 20 posts a day). Raise it to let rarely posting feeds rise above busy ones, as feedi does.
- **Feeds you engage with**: a smoothed rate of opens and favorites against entries seen for that feed, minus hides.
- **Topics you read**: similarity between the entry and an interest profile (TF-IDF over title and summary).

The profile is learned from what you do: opening an entry, reading it for 30 seconds or more, favoriting and bookmarking move it toward that entry's terms; "Show less like this" and scrolling past without opening move it away. Weights decay by 2% a day. After scoring, a pass pushes down duplicate stories and spreads busy feeds out, so one source never fills more than two consecutive rows.

The info button on each Home entry shows its score breakdown. Settings lists the terms the profile has learned and can reset them.

## Keyboard

| Key | Action |
| --- | --- |
| `j` / `k` | next / previous entry |
| `o`, `Enter` | open in reader |
| `v` | open link in browser |
| `c` | open discussion |
| `f` / `b` | favorite / bookmark |
| `x` | show less like this |
| `r` | check feeds now |
| `/` | search |
| `Esc` | close reader |

Middle-click in the stream, reader or sidebar to autoscroll as in a browser: the view scrolls toward the pointer, faster the further away it is, until the next click. Holding the middle button and dragging scrolls until you let go.

On desktop the mouse wheel scrolls smoothly, the way Firefox does by default: three lines (57 px at 100%) per notch, animated over 200 ms for a single notch and down to 50 ms when notches come quickly, with each new notch continuing from the current speed. Shift+wheel scrolls sideways as before.

## Layout

```
shared/src/commonMain/kotlin/algofeed/
  data/     Room entities, DAOs, database
  fetch/    source adapters (RSS, Reddit, HN, Kagi News, Lobsters, Mastodon, YouTube)
  rank/     tokenizer, frequency buckets, ranker, profile learning
  opml/     OPML import/export
  reader/   readable-article extraction interface
  ui/       Compose UI and view model
shared/src/jvmSharedMain/  JVM and Android implementations (Readability4J)
shared/src/desktopMain/    desktop actuals (RSS parser setup)
shared/src/androidMain/    Android actuals
desktopApp/                window, file dialogs, offscreen screenshot tool
androidApp/                Activity, Storage Access Framework pickers, launcher icon
```

`./gradlew :desktopApp:screenshot --args="<db> <out dir> [sources…]"` subscribes a scratch database to the given sources, prints the ranked stream with each score component, and renders the UI to PNG files at desktop and phone widths.

## Credits

Ordering and reading behavior follow [feedi](https://github.com/facundoolano/feedi) by Facundo Olano. Fonts: [Figtree](https://github.com/erikdkennedy/figtree) and [Literata](https://github.com/googlefonts/literata), both under the SIL Open Font License 1.1. The logo is the standard web feed icon, made for Mozilla Firefox and used under the Mozilla Public License 1.1; see `assets/README.md`.
