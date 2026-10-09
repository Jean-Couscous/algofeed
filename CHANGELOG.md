# Changelog

Each push to `main` publishes a release; binaries are on the
[Releases](https://github.com/Jean-Couscous/algofeed/releases) page.

## 0.1.4 — 2026-10-09

### Home and ranking
- Home is now a single ranked feed. The "Newest first" toggle and the ranking
  weight sliders are gone; the ranking is tuned so the freshest posts lead until
  it has learned what you open, favorite and hide.
- Ranking now follows the authors you engage with within a feed, not just the
  feed, so posters you open and favorite rise and ones you hide sink.

### Media
- Full-screen media viewer: swipe through a post's gallery, pinch or scroll to
  zoom, double-tap or drag down to dismiss, tap to close. Opens from images in
  the thread page and from the new Media tab.
- New Media tab in the sidebar: a grid of everything with an image or video.

### Desktop
- Right-click images (in the feed, reader and viewer) for Open in browser, Copy
  link and Save image. Text selection and these menus follow the app's theme.
- Videos in the viewer offer "Play in browser", since the desktop build has no
  inline player.

### Reddit
- More reliable updates: fall back to the RSS listing when the JSON API times
  out, back off instead of re-requesting while rate-limited, and retry transient
  429 and 5xx responses.
