package e7.gearscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.toArgb
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import e7.gearscan.features.feedback.CRASH_FILE
import e7.gearscan.ui.App
import e7.gearscan.ui.C
import e7.gearscan.ui.Themes
import e7.gearscan.ui.installImageLoader
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val graph get() = (application as E7GearApp).graph

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ViewModelProvider(this)[Session::class.java]
        installCrashCatcher()
        installImageLoader(this, graph.http, graph.content.mirrorSwitch)
        Themes.load(this)
        applySystemBars(Themes.dark)
        lifecycleScope.launch { snapshotFlow { Themes.dark }.collect { applySystemBars(it) } }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { App(graph) { finish() } }
        updateOrientation()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        updateOrientation()
    }

    /** In a floating or split window the app follows the window; full screen it stays portrait. */
    private fun updateOrientation() {
        requestedOrientation = if (isInMultiWindowMode) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun onResume() {
        super.onResume()
        graph.overlay.appInFront = true
        updateOrientation()
        graph.appUpdate.resumed()
    }

    override fun onPause() {
        super.onPause()
        graph.overlay.appInFront = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CAPTURE, false)) graph.scan.showCapture()
    }

    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(style, style)
        window.setBackgroundDrawable(ColorDrawable(C.paper.toArgb()))
    }

    private fun installCrashCatcher() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is CrashCatcher) return
        Thread.setDefaultUncaughtExceptionHandler(CrashCatcher(File(filesDir, CRASH_FILE), prev))
    }

    companion object {
        const val EXTRA_CAPTURE = "capture"
    }
}

/** Runs the app's startup once per activity and stops the work when the player leaves for good. */
class Session(app: Application) : AndroidViewModel(app) {
    private val graph = getApplication<E7GearApp>().graph
    private val startup = graph.start()

    // swiped away from recents: nothing is left to show the result, so stop rather than leave the service running
    override fun onCleared() {
        startup.cancel()
        graph.work.cancelAll()
        super.onCleared()
    }
}

/** Keeps the last crash so the next launch can show it and offer to report it. */
private class CrashCatcher(private val file: File, private val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        runCatching {
            file.writeText("${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}\n" +
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${java.util.Date()}\n" +
                e.stackTraceToString().lineSequence().take(60).joinToString("\n"))
        }
        prev?.uncaughtException(t, e)
    }
}
