package algofeed.android

import algofeed.ui.PlatformActions
import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Storage Access Framework pickers for OPML import and export.
 *
 * Lives as long as the process, because the view model awaits it from `viewModelScope`. Each Activity
 * instance calls [attach] in `onCreate`; after a rotation the new instance's launchers receive the
 * pending result and complete the same deferred.
 */
class AndroidPlatform(private val context: Context) : PlatformActions {
    private var open: ActivityResultLauncher<Array<String>>? = null
    private var create: ActivityResultLauncher<String>? = null
    private var pendingOpen: CompletableDeferred<Uri?>? = null
    private var pendingCreate: CompletableDeferred<Uri?>? = null

    fun attach(activity: ComponentActivity, registry: ActivityResultRegistry = activity.activityResultRegistry) {
        open = activity.registerForActivityResult(ActivityResultContracts.OpenDocument(), registry) { uri ->
            pendingOpen?.complete(uri)
            pendingOpen = null
        }
        create = activity.registerForActivityResult(ActivityResultContracts.CreateDocument(OPML_MIME), registry) { uri ->
            pendingCreate?.complete(uri)
            pendingCreate = null
        }
    }

    override suspend fun pickOpml(): String? {
        val launcher = open ?: return null
        val result = CompletableDeferred<Uri?>().also { pendingOpen = it }
        // OPML files rarely carry a registered MIME type, so filtering by type would hide them.
        withContext(Dispatchers.Main) { launcher.launch(arrayOf("*/*")) }
        val uri = result.await() ?: return null
        return withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }
    }

    override suspend fun saveOpml(content: String): Boolean {
        val launcher = create ?: return false
        val result = CompletableDeferred<Uri?>().also { pendingCreate = it }
        withContext(Dispatchers.Main) { launcher.launch("algofeed-subscriptions.opml") }
        val uri = result.await() ?: return false
        return withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.encodeToByteArray()) } != null
        }
    }

    private companion object {
        const val OPML_MIME = "text/x-opml"
    }
}
