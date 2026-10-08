package algofeed.fetch

/** A comment from a discussion thread. */
data class Comment(
    val id: Long,
    /** Null for deleted comments. */
    val author: String?,
    val html: String?,
    val time: Long,
    val children: List<Comment> = emptyList(),
    /** An attachment (4chan): [thumbnailUrl] previews it; [imageUrl] or [videoUrl] is the full file. */
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val thumbnailUrl: String? = null,
)

data class CommentThread(
    val comments: List<Comment>,
    /** Hacker News links for voting and replying, when logged in. */
    val page: HnItemPage? = null,
)
