package dev.dsh.pocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dsh.pocket.ui.PocketApp
import dev.dsh.pocket.ui.PocketTheme

class MainActivity : ComponentActivity() {
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean =
        if (dev.dsh.pocket.island.IslandTouchGuard.isObscured(event)) true else super.dispatchTouchEvent(event)
    private val model: PocketViewModel by lazy { androidx.lifecycle.ViewModelProvider(this)[PocketViewModel::class.java] }
    private val request = androidx.compose.runtime.mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        request.value = intent?.getStringExtra("open")
        setContent {
            val model: PocketViewModel = viewModel()
            PocketTheme(model.theme) { PocketApp(model, request.value) { request.value = null } }
        }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        request.value = intent.getStringExtra("open")
    }
    /** The Cowork executor connects by itself whenever the app is visible; no button is needed. */
    override fun onStart() {
        super.onStart()
        model.resumeConnection()
        dev.dsh.pocket.island.IslandRuntime.restore(applicationContext)
        dev.dsh.pocket.cowork.CoworkControllerService.ensureRunning(applicationContext)
    }
}
