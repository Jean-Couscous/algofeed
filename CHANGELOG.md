# Changelog

All notable changes to Algofeed are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Each push to `main`
publishes a release; binaries are on the
[Releases](https://github.com/Jean-Couscous/algofeed/releases) page.

## [Unreleased]

### Added
- Nexus Mods support through the official API: subscribe with `nexus:skyrimspecialedition` (optionally `/updated` or `/trending`) or a `nexusmods.com/<game>` URL to follow a game's newest, updated or trending mods. Needs a personal API key (from nexusmods.com/users/myaccount?tab=api; a free account's key works), set in Settings → Source keys. The RSS feeds stay blocked by an interactive Cloudflare challenge; the API is not.

### Fixed
- Opening a Reddit crosspost from the RSS fallback no longer fails with "Failed to connect to localhost". Reddit's RSS gives a crosspost's `[link]` as a site-relative path, which was stored verbatim and then fetched against `localhost:80`; it is now resolved against the thread permalink.

## [0.1.6] - 2026-10-10

### Added
- Tapping an image or video attached to a comment now opens it in the full-screen viewer, instead of opening the raw file in a browser.
- Deleted or pruned 4chan threads now show a notice linking to a third-party archive (desuarchive, 4plebs, b4k, archiveofsins) that covers the board, instead of failing to load comments.

### Changed
- On Linux, secrets (Hacker News session, Tumblr key, MangaDex tokens) are now stored in the OS keyring through the Secret Service API (GNOME Keyring or KWallet, via `secret-tool`) instead of as plaintext in the database; existing secrets are migrated on first run. If no keyring is available they fall back to the database as before. Android already encrypts them with the Android Keystore.
- The Android APK is now split per ABI. The release carries an arm64-v8a APK (every current phone) and an x86_64 APK (emulators), each roughly half the former universal APK since it ships only its own native libraries.
- 4chan threads with no subject are titled `/<board>/ thread <number>` instead of repeating the opening comment as both title and body.

### Fixed
- The Android app no longer crashes while updating feeds. R8 was renaming ONNX Runtime classes that its native code looks up by name, so the embedding step aborted the process; a keep rule for `ai.onnxruntime.**` prevents it.

## [0.1.5] - 2026-10-09

### Added
- Back-to-top button on touch devices, shown once the list is scrolled down a few rows.
- Source link in the full-screen media viewer: opens the post's discussion in the in-app reader when it has one, otherwise the source page in the browser.
- Arch Linux pacman repository: releases now carry a repo database, so Algofeed can be installed and updated with `pacman` (see the README).

### Changed
- Content ranking now uses an on-device multilingual embedding model (`multilingual-e5-small`) instead of TF-IDF term matching. Posts are matched by meaning rather than shared words, including across languages, so a French post can rank against an English interest. The model (~112 MB) is bundled in the app; the first build downloads it. Upgrading resets the learned interest profile, which relearns from use.
- A refresh no longer pops a "feeds failed to update" snackbar; a failing feed is shown only by the quiet error badge in the sidebar.

### Removed
- The "Learned interests" section in Settings, which listed the top learned terms and a reset button. The ranking still learns from use; it's just no longer surfaced there.

## [0.1.4] - 2026-10-09

### Added
- Full-screen media viewer: swipe through a post's gallery, pinch or scroll to zoom, double-tap or drag down to dismiss, tap to close. Opens from images in the thread page and from the new Media tab.
- Media tab in the sidebar: a grid of everything with an image or video.
- Desktop: right-click images (in the feed, reader and viewer) for Open in browser, Copy link and Save image. Text selection and these menus follow the app's theme.
- Desktop: videos in the viewer offer "Play in browser", since the desktop build has no inline player.

### Changed
- Home is now a single ranked feed. The "Newest first" toggle and the ranking weight sliders are gone; the ranking is tuned so the freshest posts lead until it has learned what you open, favorite and hide.
- Ranking now follows the authors you engage with within a feed, not just the feed, so posters you open and favorite rise and ones you hide sink.

### Fixed
- More reliable Reddit updates: fall back to the RSS listing when the JSON API times out, back off instead of re-requesting while rate-limited, and retry transient 429 and 5xx responses.

Releases 0.1.1–0.1.3 predate this changelog.
