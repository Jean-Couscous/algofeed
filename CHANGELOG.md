# Changelog

### Added
- Tapping an image or video attached to a comment now opens it in the full-screen viewer, instead of opening the raw file in a browser.
- Deleted or pruned 4chan threads now show a notice linking to a third-party archive (desuarchive, 4plebs, b4k, archiveofsins) that covers the board, instead of failing to load comments.
- Back-to-top button on touch devices, shown once the list is scrolled down a few rows.
- Source link in the full-screen media viewer: opens the post's discussion in the in-app reader when it has one, otherwise the source page in the browser.
- Arch Linux pacman repository: releases now carry a repo database, so Algofeed can be installed and updated with `pacman` (see the README).

### Changed
- On Linux, secrets (Hacker News session, Tumblr key, MangaDex tokens) are now stored in the OS keyring through the Secret Service API (GNOME Keyring or KWallet, via `secret-tool`) instead of as plaintext in the database; existing secrets are migrated on first run. If no keyring is available they fall back to the database as before. Android already encrypts them with the Android Keystore.
- The Android APK is now split per ABI. The release carries an arm64-v8a APK (every current phone) and an x86_64 APK (emulators), each roughly half the former universal APK since it ships only its own native libraries.
- 4chan threads with no subject are titled `/<board>/ thread <number>` instead of repeating the opening comment as both title and body.
- Content ranking now uses an on-device multilingual embedding model (`multilingual-e5-small`) instead of TF-IDF term matching. Posts are matched by meaning rather than shared words, including across languages, so a French post can rank against an English interest. The model (~112 MB) is bundled in the app; the first build downloads it. Upgrading resets the learned interest profile, which relearns from use.
- A refresh no longer pops a "feeds failed to update" snackbar; a failing feed is shown only by the quiet error badge in the sidebar.

### Removed
- The "Learned interests" section in Settings, which listed the top learned terms and a reset button. The ranking still learns from use; it's just no longer surfaced there.

### Fixed
- The Android app no longer crashes while updating feeds. R8 was renaming ONNX Runtime classes that its native code looks up by name, so the embedding step aborted the process; a keep rule for `ai.onnxruntime.**` prevents it.
