package algofeed.fetch

/** A comment from a discussion thread (Hacker News or Reddit). */
data class Comment(
    val id: Long,
    /** Null for deleted comments. */
    val author: String?,
    val html: String?,
    val time: Long,
    val children: List<Comment> = emptyList(),
)

data class CommentThread(
    val comments: List<Comment>,
    /** Hacker News links for voting and replying, when logged in. */
    val page: HnItemPage? = null,
    /** Replies came without their parents (Reddit's RSS), so the list can't be nested. */
    val flat: Boolean = false,
)
