package algofeed.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.platform.UriHandler
import androidx.core.net.toUri

/** Opens web links in a Custom Tab, and other schemes (mailto:, …) in whatever app handles them. */
class CustomTabsUriHandler(private val activity: Activity) : UriHandler {
    override fun openUri(uri: String) {
        val parsed = uri.toUri()
        try {
            if (parsed.scheme == "http" || parsed.scheme == "https") {
                // Falls back to a plain ACTION_VIEW when the browser doesn't support Custom Tabs.
                CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, parsed)
            } else {
                activity.startActivity(Intent(Intent.ACTION_VIEW, parsed))
            }
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }
}
