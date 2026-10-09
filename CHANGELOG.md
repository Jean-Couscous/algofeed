# Changelog

### Added
- Back-to-top button on touch devices, shown once the list is scrolled down a few rows.
- Source link in the full-screen media viewer: opens the post's discussion in the in-app reader when it has one, otherwise the source page in the browser.
- Arch Linux pacman repository: releases now carry a repo database, so Algofeed can be installed and updated with `pacman` (see the README).

### Changed
- Content ranking now uses an on-device multilingual embedding model (`multilingual-e5-small`) instead of TF-IDF term matching. Posts are matched by meaning rather than shared words, including across languages, so a French post can rank against an English interest. The model (~112 MB) is bundled in the app; the first build downloads it. Upgrading resets the learned interest profile, which relearns from use.
- A refresh no longer pops a "feeds failed to update" snackbar; a failing feed is shown only by the quiet error badge in the sidebar.

### Removed
- The "Learned interests" section in Settings, which listed the top learned terms and a reset button. The ranking still learns from use; it's just no longer surfaced there.
