package algofeed.ui

import algofeed.StreamView
import algofeed.data.Feed
import algofeed.data.Folder
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage

@Composable
fun Sidebar(
    view: StreamView,
    feeds: List<Feed>,
    folders: List<Folder>,
    onSelect: (StreamView) -> Unit,
    onAddFeed: () -> Unit,
    onEditFeed: (Feed) -> Unit,
    onEditFolder: (Folder) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }
    val byFolder = feeds.groupBy { it.folderId }
    Column(
        modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)
            // No-op inside the modal drawer, whose sheet has already applied these.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 12.dp),
        ) {
            Image(AppIcon, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text("Algofeed", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
        val listState = rememberLazyListState()
        LazyColumn(Modifier.weight(1f).mouseScrolling(listState), state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { NavRow(Icons.Outlined.Home, "Home", view == StreamView.Home) { onSelect(StreamView.Home) } }
            item { NavRow(Icons.Outlined.StarOutline, "Favorites", view == StreamView.Favorites) { onSelect(StreamView.Favorites) } }
            item { NavRow(Icons.Outlined.BookmarkBorder, "Bookmarks", view == StreamView.Bookmarks) { onSelect(StreamView.Bookmarks) } }
            item { NavRow(Icons.Outlined.PermMedia, "Media", view == StreamView.Media) { onSelect(StreamView.Media) } }
            item {
                Text(
                    "Feeds",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp),
                )
            }
            if (feeds.isEmpty()) {
                item {
                    Text(
                        "No subscriptions yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
            for (folder in folders) {
                val members = byFolder[folder.id].orEmpty()
                val open = expanded[folder.id] ?: false
                item(key = "folder-${folder.id}") {
                    NavRow(
                        Icons.Outlined.Folder, folder.name, view == StreamView.OfFolder(folder.id),
                        trailing = {
                            IconButton(onClick = { expanded[folder.id] = !open }, modifier = Modifier.size(32.dp)) {
                                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) "Collapse" else "Expand", Modifier.size(18.dp))
                            }
                        },
                        onMore = { onEditFolder(folder) },
                    ) { onSelect(StreamView.OfFolder(folder.id)) }
                }
                if (open) {
                    items(members, key = { "feed-${it.id}" }) { feed ->
                        FeedRow(feed, view == StreamView.OfFeed(feed.id), indent = 16, onEdit = { onEditFeed(feed) }) { onSelect(StreamView.OfFeed(feed.id)) }
                    }
                }
            }
            items(byFolder[null].orEmpty(), key = { "feed-${it.id}" }) { feed ->
                FeedRow(feed, view == StreamView.OfFeed(feed.id), indent = 0, onEdit = { onEditFeed(feed) }) { onSelect(StreamView.OfFeed(feed.id)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onAddFeed) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add feed")
            }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
        }
    }
}

@Composable
private fun NavRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    trailing: (@Composable () -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    SidebarRow(selected, indent = 0, onClick = onClick, onMore = onMore, trailing = trailing) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FeedRow(feed: Feed, selected: Boolean, indent: Int, onEdit: () -> Unit, onClick: () -> Unit) {
    SidebarRow(selected, indent, onClick = onClick, onMore = onEdit) {
        FeedIcon(feed, 18)
        Spacer(Modifier.width(12.dp))
        Text(
            feed.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (feed.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (feed.lastError != null) {
            Icon(Icons.Outlined.ErrorOutline, "Last update failed: ${feed.lastError}", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SidebarRow(
    selected: Boolean,
    indent: Int,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val touch = LocalTouchUi.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) LocalExtraColors.current.selection else androidx.compose.ui.graphics.Color.Transparent)
            // Long-press opens the same menu as the edit button.
            .combinedClickable(onLongClick = onMore, onClick = onClick)
            .heightIn(min = if (touch) 48.dp else 40.dp)
            .padding(start = (12 + indent).dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        trailing?.invoke()
        if (onMore != null) {
            IconButton(onClick = onMore, modifier = Modifier.size(if (touch) 48.dp else 32.dp)) {
                Icon(Icons.Outlined.MoreHoriz, "Edit", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Feed favicon, falling back to the first letter of the title. */
@Composable
fun FeedIcon(feed: Feed?, size: Int) {
    val letter = feed?.title?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    val fallback: @Composable () -> Unit = {
        Box(
            Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
    if (feed?.iconUrl == null) {
        fallback()
        return
    }
    SubcomposeAsyncImage(
        model = feed.iconUrl,
        contentDescription = null,
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape(4.dp)),
        loading = { fallback() },
        error = { fallback() },
    )
}

