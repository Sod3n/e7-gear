package e7.gearscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import e7.gearscan.ui.App
import e7.gearscan.ui.installImageLoader
import java.io.File

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        installCrashCatcher()
        installImageLoader(this)
        enableEdgeToEdge(SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { App(model) { finish() } }
    }

    override fun onResume() {
        super.onResume()
        model.resumed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CAPTURE, false)) model.showCapture()
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
