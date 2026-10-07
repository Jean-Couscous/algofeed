package algofeed.android

import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = FakeRefreshApp::class)
class TopEntriesWidgetTest {
    @Test fun showsEntries() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        provideComposable {
            WidgetContent(listOf(WidgetEntry(1, "First story", "Blog · 2h"), WidgetEntry(2, "Second story", "HN · 5h")))
        }
        onNode(hasText("First story")).assertExists()
        onNode(hasText("HN · 5h")).assertExists()
        onNode(hasText("Nothing new")).assertDoesNotExist()
    }

    @Test fun showsEmptyState() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setContext(ApplicationProvider.getApplicationContext())
        provideComposable { WidgetContent(emptyList()) }
        onNode(hasText("Nothing new")).assertExists()
    }
}
