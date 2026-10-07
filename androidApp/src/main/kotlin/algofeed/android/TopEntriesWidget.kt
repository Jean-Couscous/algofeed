package algofeed.android

import algofeed.Repository
import algofeed.StreamView
import algofeed.util.Html
import algofeed.util.relativeTime
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.flow.first

/** One widget line: the entry to open and what to show for it. */
data class WidgetEntry(val id: Long, val title: String, val source: String)

/** The top three Home entries, as ranked when the widget last updated. */
class TopEntriesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as AlgofeedApplication
        val entries = runCatching { topEntries(app.repository) }.getOrDefault(emptyList())
        provideContent { GlanceTheme { WidgetContent(entries) } }
    }

    companion object {
        suspend fun topEntries(repo: Repository, count: Int = 3): List<WidgetEntry> {
            val feeds = repo.feeds.first().associateBy { it.id }
            return repo.stream(StreamView.Home, repo.settings()).take(count).map { ranked ->
                val e = ranked.entry
                WidgetEntry(
                    id = e.id,
                    title = e.title ?: Html.snippet(e.summaryHtml ?: e.contentHtml, 140),
                    source = listOfNotNull(feeds[e.feedId]?.title, relativeTime(e.sortDate)).joinToString(" · "),
                )
            }
        }

        /** Re-ranks after a refresh or once entries were viewed; a no-op without placed widgets. */
        suspend fun update(context: Context) = runCatching { TopEntriesWidget().updateAll(context) }
    }
}

class TopEntriesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TopEntriesWidget()
}

@Composable
fun WidgetContent(entries: List<WidgetEntry>) {
    val context = androidx.glance.LocalContext.current
    fun open(entryId: Long?) = actionStartActivity(
        Intent(context, MainActivity::class.java)
            .setAction(if (entryId == null) Intent.ACTION_MAIN else MainActivity.ACTION_OPEN_ENTRY)
            .putExtra(MainActivity.EXTRA_ENTRY_ID, entryId ?: -1L)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
    Column(
        GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(12.dp),
    ) {
        Text(
            "Algofeed",
            style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp),
            modifier = GlanceModifier.fillMaxWidth().clickable(open(null)),
        )
        if (entries.isEmpty()) {
            Spacer(GlanceModifier.height(8.dp))
            Text("Nothing new", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp))
        }
        for (entry in entries) {
            Spacer(GlanceModifier.height(8.dp))
            Column(GlanceModifier.fillMaxWidth().clickable(open(entry.id))) {
                Text(
                    entry.title,
                    maxLines = 2,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp),
                )
                Text(entry.source, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            }
        }
    }
}
