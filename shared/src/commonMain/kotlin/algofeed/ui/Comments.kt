package algofeed.ui

import algofeed.fetch.HackerNews
import algofeed.fetch.Comment
import algofeed.util.relativeTime
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** What the comment thread can do; [loggedIn] gates voting and replying. */
class CommentActions(
    val loggedIn: Boolean,
    val voted: (Long) -> Boolean,
    val onVote: (Long) -> Unit,
    /** Returns an error message, or null when posted. */
    val onReply: suspend (parentId: Long, text: String) -> String?,
    val onRetry: () -> Unit,
)

private const val PAGE = 40

@Composable
fun CommentsSection(state: CommentsState, actions: CommentActions, onOpenThread: () -> Unit) {
    // Voting and replying exist only for Hacker News; Reddit threads are read-only.
    val hn = state.source == CommentSource.HackerNews
    val canWrite = hn && actions.loggedIn
    var collapsed by remember(state.storyId) { mutableStateOf(emptySet<Long>()) }
    var shown by remember(state.storyId) { mutableStateOf(PAGE) }
    var replyTo by remember { mutableStateOf<ReplyTarget?>(null) }
    val comments = state.thread?.comments.orEmpty()
    val rows = remember(comments, collapsed) { flatten(comments, collapsed) }

    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = LocalExtraColors.current.divider)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.thread == null) "Comments" else "Comments (${countAll(comments)})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenThread) { Text("Open on ${state.source.siteName}") }
            if (canWrite) TextButton(onClick = { replyTo = ReplyTarget(state.storyId, null) }) { Text("Add comment") }
        }
        if (state.thread?.flat == true) {
            Text(
                "Reddit only lets apps without an account read this thread as a list, so replies aren't nested under their parents.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hn && !actions.loggedIn) {
            Text(
                "Log in to Hacker News in Settings to vote and reply.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = actions.onRetry) { Text("Try again") }
        }
        if (state.thread != null && comments.isEmpty()) {
            Text("No comments yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for ((comment, depth) in rows.take(shown)) {
            CommentRow(
                comment = comment,
                depth = depth,
                collapsed = comment.id in collapsed,
                actions = actions,
                onToggle = { collapsed = if (comment.id in collapsed) collapsed - comment.id else collapsed + comment.id },
                onReply = { replyTo = ReplyTarget(comment.id, comment.author) },
            )
        }
        if (rows.size > shown) {
            OutlinedButton(onClick = { shown += PAGE }) { Text("Show more comments (${rows.size - shown} left)") }
        }
    }

    replyTo?.let { target -> ReplyDialog(target, actions.onReply) { replyTo = null } }
}

private data class ReplyTarget(val parentId: Long, val author: String?)

private fun flatten(comments: List<Comment>, collapsed: Set<Long>, depth: Int = 0): List<Pair<Comment, Int>> =
    comments.flatMap { c -> listOf(c to depth) + if (c.id in collapsed) emptyList() else flatten(c.children, collapsed, depth + 1) }

private fun countAll(comments: List<Comment>): Int = comments.sumOf { 1 + countAll(it.children) }

@Composable
private fun CommentRow(
    comment: Comment,
    depth: Int,
    collapsed: Boolean,
    actions: CommentActions,
    onToggle: () -> Unit,
    onReply: () -> Unit,
) {
    // Deep threads stop indenting so text keeps a readable width on phones.
    Row(Modifier.padding(start = (depth.coerceAtMost(6) * 12).dp).height(IntrinsicSize.Min)) {
        if (depth > 0) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 40.dp)) {
                Text(
                    comment.author ?: "[deleted]",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "  ${relativeTime(comment.time)} ago",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (collapsed) "  [+${1 + countAll(comment.children)}]" else "  [–]",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onToggle).padding(horizontal = 4.dp, vertical = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                if (actions.loggedIn && comment.author != null && !collapsed) {
                    val voted = actions.voted(comment.id)
                    Action(
                        if (voted) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                        if (voted) "Take back upvote" else "Upvote",
                        tint = if (voted) MaterialTheme.colorScheme.primary else null,
                    ) { actions.onVote(comment.id) }
                    TextButton(onClick = onReply) { Text("Reply") }
                }
            }
            if (!collapsed) {
                comment.html?.let { HtmlContent(it, "${HackerNews.BASE}/item?id=${comment.id}", compact = true) }
                    ?: Text("[deleted]", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReplyDialog(target: ReplyTarget, onReply: suspend (Long, String) -> String?, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var posting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!posting) onDismiss() },
        modifier = Modifier.imePadding(),
        title = { Text(target.author?.let { "Reply to $it" } ?: "Comment on Hacker News") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    text, { text = it },
                    minLines = 4,
                    maxLines = 12,
                    enabled = !posting,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Posted publicly under your Hacker News account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && !posting,
                onClick = {
                    posting = true
                    scope.launch {
                        error = onReply(target.parentId, text.trim())
                        posting = false
                        if (error == null) onDismiss()
                    }
                },
            ) { Text(if (posting) "Posting…" else "Post") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !posting) { Text("Cancel") } },
    )
}
