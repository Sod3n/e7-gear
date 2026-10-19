package e7.gearscan.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import e7.gearscan.data.Item
import e7.gearscan.data.Stat

/** One build to copy in the game, as the floating checklist shows it. */
class OverlayPiece(
    val item: Item,
    val setIcon: String?,
    val mainText: String,
    val subs: List<String>,
    /** "on Silk", "on now" or "in bag". */
    val owner: String,
    val onHero: Boolean,
    val find: String,
    /** Modification stone step, e.g. "Def 33 → Spd 12". */
    val mod: String? = null,
    /** The piece as it is in the game now (before any suggested mod). */
    val orig: Item = item,
    val reforged: Boolean = false,
    /** Pool checklists: whose piece this is. */
    val forHero: String? = null,
) {
    fun copyFor(hero: String) = OverlayPiece(item, setIcon, mainText, subs, owner, onHero, find, mod, orig, reforged, hero)
}

class OverlayBuild(
    val title: String,
    val face: String?,
    val pieces: List<OverlayPiece>,
    val stats: List<Triple<Stat, Double, Double>>,
    /** Shown under the title: a checklist from a past run, which may not match the gear any more. */
    val flag: String? = null,
)

/** Shared between the app and the overlay window; both run in the app process. */
object OverlayState {
    var build: OverlayBuild? by mutableStateOf(null)
    val done = mutableStateListOf<Boolean>()
    /** Pieces in the order they were ticked, for Undo. */
    private val doneOrder = mutableStateListOf<Int>()
    var expanded by mutableStateOf(false)
    var tab by mutableStateOf(0)
    var opacity by mutableStateOf(0.8f)
    var focus by mutableStateOf(-1)
    var running by mutableStateOf(false)
    /** The bubble is being dragged over the bottom edge, where letting go closes it. */
    var closeZone by mutableStateOf(false)
    /** Records the finished checklist in the app's box; set by whoever loaded the build. */
    var onFinish: (() -> Unit)? = null
    /** E7 Gear itself is on screen: the overlay hides, or it would sit on the app and take its taps. */
    var appInFront = false
        set(v) { field = v; onAppInFront?.invoke(v) }
    var onAppInFront: ((Boolean) -> Unit)? = null
    var recorded by mutableStateOf(false)

    fun load(b: OverlayBuild, onFinish: (() -> Unit)? = null) {
        build = b
        // a worn piece is done unless it needs a modification, which is its own step in the game
        done.clear(); done.addAll(b.pieces.map { it.onHero && it.mod == null })
        doneOrder.clear()
        expanded = false; tab = 0; focus = -1
        this.onFinish = onFinish; recorded = false
    }

    val current: Int get() = focus.takeIf { it >= 0 && it < done.size && !done[it] } ?: done.indexOfFirst { !it }
    val lastDone: Int? get() = doneOrder.lastOrNull()

    fun markCurrentDone() {
        val i = current.takeIf { it >= 0 } ?: return
        done[i] = true; doneOrder.add(i); focus = -1
    }

    fun undo() {
        val i = doneOrder.removeLastOrNull() ?: return
        done[i] = false; focus = i
    }

    fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)
}

/**
 * Hosts the bubble and checklist in a system overlay window over the game. Foreground, so it
 * survives while the game is in front; it only draws, never reads or taps the game.
 */
class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    private lateinit var wm: WindowManager
    private var view: ComposeView? = null
    private lateinit var params: WindowManager.LayoutParams
    /** The panel's list, under the controls: not touchable and at most 80% opaque, so the game gets the taps. */
    private var body: ComposeView? = null
    private lateinit var bodyParams: WindowManager.LayoutParams
    /** The bubble's place down the right edge (0 top, 1 bottom): low, clear of the game's menus and filter rows, and a share so it stays put when the game turns the screen. */
    private var bubbleAt = 0.75f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        wm = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || OverlayState.build == null) { stopSelf(); return START_NOT_STICKY }
        foreground()
        show()
        return START_NOT_STICKY
    }

    private fun show() {
        if (view != null) return
        val d = resources.displayMetrics.density
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = (110 * d).toInt()
        }
        bodyParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; alpha = OverlayState.opacity }
        body = composeView {
            OverlayBody()
            androidx.compose.runtime.LaunchedEffect(OverlayState.opacity) { setBodyAlpha(OverlayState.opacity) }
        }
        view = composeView { OverlayTop(onMove = ::moveBy, onDock = ::dock, onClose = { stopSelf() }) }.apply {
            // the list hangs under the controls, whatever height they take
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeBody() }
        }
        if (OverlayState.expanded) params.width = (338 * d).toInt()
        wm.addView(body, bodyParams)
        wm.addView(view, params)
        OverlayState.onAppInFront = ::hideForApp
        hideForApp(OverlayState.appInFront)
        if (OverlayState.expanded) dock(true) else placeBubble()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        OverlayState.running = true
    }

    private fun composeView(content: @androidx.compose.runtime.Composable () -> Unit) = ComposeView(this).apply {
        setViewTreeLifecycleOwner(this@OverlayService)
        setViewTreeSavedStateRegistryOwner(this@OverlayService)
        setContent(content)
    }

    private fun setBodyAlpha(a: Float) {
        val b = body ?: return
        bodyParams.alpha = a
        wm.updateViewLayout(b, bodyParams)
    }

    private fun hideForApp(hide: Boolean) {
        val v = view ?: return
        v.visibility = if (hide) android.view.View.GONE else android.view.View.VISIBLE
        params.flags = if (hide) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        wm.updateViewLayout(v, params)
        // the app may have been in portrait; the game turns the screen while the overlay is hidden
        if (!hide && !OverlayState.expanded) placeBubble() else placeBody()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!OverlayState.expanded) placeBubble()
    }

    private fun placeBubble() {
        val v = view ?: return
        val h = if (v.height > 0) v.height else (80 * resources.displayMetrics.density).toInt()
        params.y = ((screen().height() - h) * bubbleAt).toInt().coerceAtLeast(0)
        wm.updateViewLayout(v, params)
        placeBody()
    }

    private fun placeBody() {
        val v = view ?: return
        val b = body ?: return
        b.visibility = if (OverlayState.expanded && !OverlayState.appInFront) android.view.View.VISIBLE else android.view.View.GONE
        bodyParams.width = params.width
        bodyParams.gravity = params.gravity
        bodyParams.x = params.x
        bodyParams.y = params.y + v.height + (PANEL_GAP * resources.displayMetrics.density).toInt()
        wm.updateViewLayout(b, bodyParams)
    }

    /**
     * Where the overlay can sit now, in its own coordinates: between the system bars, which is where the window
     * manager places it. displayMetrics can keep the portrait size after the game turns the screen.
     */
    private fun screen(): android.graphics.Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = wm.currentWindowMetrics
            val i = m.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or
                android.view.WindowInsets.Type.displayCutout())
            android.graphics.Rect(0, 0, m.bounds.width() - i.left - i.right, m.bounds.height() - i.top - i.bottom)
        } else android.util.DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(it) }
            .let { android.graphics.Rect(0, 0, it.widthPixels, it.heightPixels) }

    /** With top-end gravity x grows to the left. */
    private fun moveBy(dx: Float, dy: Float) {
        val v = view ?: return
        val r = screen()
        val maxY = maxOf(0, r.height() - v.height)
        val fromEnd = (params.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == Gravity.END
        params.x = (params.x + if (fromEnd) -dx.toInt() else dx.toInt()).coerceIn(0, maxOf(0, r.width() - v.width))
        params.y = (params.y + dy.toInt()).coerceIn(0, maxY)
        if (!OverlayState.expanded && maxY > 0) bubbleAt = params.y.toFloat() / maxY
        wm.updateViewLayout(v, params)
        placeBody()
        // only a bubble pushed against the bottom edge closes; anywhere else on a short landscape screen is a move
        OverlayState.closeZone = !OverlayState.expanded && params.y >= maxY - (12 * resources.displayMetrics.density).toInt()
    }

    private fun dock(expanded: Boolean) {
        val v = view ?: return
        val d = resources.displayMetrics.density
        // wrap-content overlays are first measured at dialog width (~320 dp), which would squeeze the panel
        params.width = if (expanded) (338 * d).toInt() else WindowManager.LayoutParams.WRAP_CONTENT
        // open on the left: in the game's gear screens the list to pick from is on the right
        params.gravity = Gravity.TOP or if (expanded) Gravity.START else Gravity.END
        params.x = if (expanded) (14 * d).toInt() else 0
        if (expanded) { params.y = (6 * d).toInt(); wm.updateViewLayout(v, params); placeBody() } else placeBubble()
    }

    private fun foreground() {
        val channel = "overlay"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "Build over the game", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, channel)
            .setContentTitle("Build checklist over the game")
            .setContentText("Tap to close it.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(stop)
            .build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startForeground(3, n)
        else startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    override fun onDestroy() {
        OverlayState.onAppInFront = null
        view?.let { runCatching { wm.removeView(it) } }
        body?.let { runCatching { wm.removeView(it) } }
        view = null; body = null
        OverlayState.running = false
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "e7.gearscan.OVERLAY_STOP"

        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, OverlayService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, OverlayService::class.java)) }
    }
}
