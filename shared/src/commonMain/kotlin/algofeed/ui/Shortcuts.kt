package algofeed.ui

/** A keyboard shortcut for the desktop settings listing. The behavior lives in App's handleKey. */
data class Shortcut(val keys: String, val description: String)

val KEYBOARD_SHORTCUTS = listOf(
    Shortcut("J / K", "Focus the next / previous entry"),
    Shortcut("O or Enter", "Open the focused entry"),
    Shortcut("V", "Open its link in the browser"),
    Shortcut("C", "Open its comments in the browser"),
    Shortcut("F", "Like"),
    Shortcut("B", "Bookmark"),
    Shortcut("X", "Dismiss"),
    Shortcut("R", "Refresh feeds"),
    Shortcut("/", "Jump to search"),
    Shortcut("Esc", "Close the reader"),
)
