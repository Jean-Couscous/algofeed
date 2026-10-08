package algofeed.fetch

/** A comment from a Hacker News discussion thread. */
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
)
