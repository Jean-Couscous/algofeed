package algofeed.android

import algofeed.ui.AlgofeedApp
import algofeed.ui.AlgofeedViewModel
import algofeed.ui.LocalTouchUi
import algofeed.util.Urls
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as AlgofeedApplication

    private val vm: AlgofeedViewModel by viewModels {
        viewModelFactory { initializer { AlgofeedViewModel(app.repository, app.backgroundRefresh) } }
    }

    /** A link shared into the app, waiting for the UI to ask what to do with it. */
    private var sharedUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        app.platform.attach(this)
        // On recreation the intent was already handled.
        if (savedInstanceState == null) handleIntent(intent)
        val uriHandler = CustomTabsUriHandler(this)
        setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler, LocalTouchUi provides true) {
                AlgofeedApp(vm, app.platform, sharedUrl, onSharedUrlHandled = { sharedUrl = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == ACTION_OPEN_ENTRY) {
            intent.getLongExtra(EXTRA_ENTRY_ID, -1L).takeIf { it > 0 }?.let(vm::openEntry)
            return
        }
        if (intent.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Urls.firstUrl(text)
        if (url == null) {
            Toast.makeText(this, "No link in the shared text", Toast.LENGTH_SHORT).show()
            return
        }
        sharedUrl = url
    }

    override fun onStart() {
        super.onStart()
        if (app.inBackground) {
            app.inBackground = false
            vm.onForeground()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) app.inBackground = true
        // The process can be killed any time after this without further callbacks.
        app.scope.launch {
            vm.persistPending()
            TopEntriesWidget.update(applicationContext)
        }
    }

    companion object {
        /** From the widget: open [EXTRA_ENTRY_ID] in the reader. */
        const val ACTION_OPEN_ENTRY = "algofeed.android.OPEN_ENTRY"
        const val EXTRA_ENTRY_ID = "entryId"
    }
}
