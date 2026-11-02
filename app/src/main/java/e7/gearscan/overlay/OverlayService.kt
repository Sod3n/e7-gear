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
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.AndroidUiDispatcher
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One build to copy in the game, as the floating checklist shows it. */
class OverlayPiece(
    val item: Item,
    val setIcon: String?,
    val mainText: String,
    val subs: List<String>,
    /** "on Silk", "on now" or "in bag". */
    val owner: String,
    val onHero: Boolean,
    /** Modification stone step, e.g. "Def 33 → Spd 12"; set on mod steps only. */
    val mod: String? = null,
    /** The piece as it is in the game now (before any suggested mod). */
    val orig: Item = item,
    val reforged: Boolean = false,
    /** Pool checklists: whose piece this is. */
    val forHero: String? = null,
    /** A reforge step: the build counts this finished level 85 piece as reforged and it isn't yet. */
    val reforgeStep: Boolean = false,
) {
    /** The game modifies gear in another menu than it equips, so a mod is its own step, after all the equipping. */
    val modStep get() = mod != null
    /** Steps in another menu than equipping: reforge, then modify. */
    val otherMenu get() = modStep || reforgeStep
    /** The menu this step happens in: 0 equip, 1 reforge, 2 modify. */
    val menu get() = when { modStep -> 2; reforgeStep -> 1; else -> 0 }

    fun copyFor(hero: String) = OverlayPiece(item, setIcon, mainText, subs, owner, onHero, mod, orig, reforged, hero, reforgeStep)
}

class OverlayBuild(
    val title: String,
    val face: String?,
    val pieces: List<OverlayPiece>,
    val stats: List<Triple<Stat, Double, Double>>,
    /** Shown under the title: a checklist from a past run, which may not match the gear any more. */
    val flag: String? = null,
) {
    val key get() = pieces.joinToString(",") { "${it.forHero.orEmpty()}:${it.item.id}:${it.mod.orEmpty()}${if (it.reforgeStep) ":reforge" else ""}" }
}

/** A drag on the bubble, as the bubble reports it: screen deltas, then the finger's speed (px/s) as it lets go. */
interface BubbleDrag {
    fun start()
    fun move(dx: Float, dy: Float)
    fun end(vx: Float, vy: Float)
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
    /** The bubble's place down its edge (0 top, 1 bottom): low, clear of the game's menus and filter rows, and a share so it stays put when the game turns the screen. */
    private var bubbleAt = 0.75f
    /** Shown only while the bubble is dragged; under the bubble's window, so the held bubble sits on top of it. */
    private var target: ComposeView? = null
    private lateinit var targetParams: WindowManager.LayoutParams
    /** The next-step hint beside the bubble, a window of its own so the bubble's window keeps one size wherever it docks. */
    private var hint: ComposeView? = null
    private lateinit var hintParams: WindowManager.LayoutParams
    private val frames = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)
    private var glide: Job? = null
    private val glideX = Animatable(0f)
    private val glideY = Animatable(0f)
    /** Where the finger would put the bubble's top-left; the bubble leaves it only while the target holds it. */
    private var finger = Offset.Zero

    private val graph get() = (application as e7.gearscan.E7GearApp).graph
    private val w get() = graph.overlay

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        e7.gearscan.ui.Themes.load(this)
        saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        wm = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || (graph.checklist.build == null && !graph.tracker.shown)) { stopSelf(); return START_NOT_STICKY }
        foreground()
        show()
        return START_NOT_STICKY
    }

    private fun show() {
        w.usableHeightDp = screen().height() / resources.displayMetrics.density
        if (view != null) { dock(w.expanded); return }
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
        ).apply { gravity = Gravity.TOP or Gravity.END; alpha = w.opacity }
        // not touchable; 0.8 is the most opaque a window can be and still let taps through to the game (Android 12+)
        fun passThrough() = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; alpha = 0.8f }
        targetParams = passThrough()
        hintParams = passThrough()
        target = composeView { DismissTarget(w) }.apply { visibility = android.view.View.GONE }
        hint = composeView { BubbleHint(graph) }
        w.closing = false; w.dragging = false; w.captured = false
        w.bubbleDrag = drag
        body = composeView { OverlayBody(graph) }
        view = composeView {
            OverlayTop(graph, onMove = ::moveBy, onDock = ::dock, onClose = { stopSelf() })
            // here: the list's window is gone while it is hidden
            androidx.compose.runtime.LaunchedEffect(w.opacity) { setBodyAlpha(w.opacity) }
        }.apply {
            // the list hangs under the controls, whatever height they take
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeBody() }
        }
        if (w.expanded) params.width = (338 * d).toInt()
        wm.addView(body, bodyParams)
        wm.addView(target, targetParams)
        wm.addView(hint, hintParams)
        wm.addView(view, params)
        w.onAppInFront = ::hideForApp
        hideForApp(w.appInFront)
        if (w.expanded) dock(true) else placeBubble()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        w.running = true
    }

    private fun composeView(content: @androidx.compose.runtime.Composable () -> Unit) = ComposeView(this).apply {
        setViewTreeLifecycleOwner(this@OverlayService)
        setViewTreeSavedStateRegistryOwner(this@OverlayService)
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(e7.gearscan.ui.LocalImageBase provides graph.content.imageBase) { content() }
        }
    }

    private fun setBodyAlpha(a: Float) {
        if (body == null) return
        if (a > 0f) bodyParams.alpha = a
        placeBody()
    }

    private fun hideForApp(hide: Boolean) {
        val v = view ?: return
        v.visibility = if (hide) android.view.View.GONE else android.view.View.VISIBLE
        params.flags = if (hide) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        wm.updateViewLayout(v, params)
        // the app may have been in portrait; the game turns the screen while the overlay is hidden
        if (!hide && !w.expanded) placeBubble() else { placeBody(); placeHint() }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        e7.gearscan.ui.Themes.load(this)
        w.usableHeightDp = screen().height() / resources.displayMetrics.density
        if (!w.expanded) placeBubble()
    }

    private fun placeBubble() {
        val v = view ?: return
        glide?.cancel()
        val h = bubbleSize(v)
        params.gravity = Gravity.TOP or if (w.bubbleLeft) Gravity.START else Gravity.END
        params.x = 0
        params.y = ((screen().height() - h) * bubbleAt).toInt().coerceAtLeast(0)
        wm.updateViewLayout(v, params)
        placeBody()
        placeHint()
        reportBubble(v)
    }

    private fun bubbleSize(v: android.view.View) = if (v.width > 0) v.width else (BUBBLE_BOX * resources.displayMetrics.density).toInt()

    /** The bubble's left edge in [screen] coordinates, whichever edge its gravity counts from. */
    private fun bubbleLeftEdge(v: android.view.View): Float =
        if ((params.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == Gravity.END) (screen().width() - params.x - bubbleSize(v)).toFloat()
        else params.x.toFloat()

    /** Puts the bubble's top-left at [x], [y]; while it moves it counts from the left, so it can cross the screen. */
    private fun putBubble(x: Float, y: Float) {
        val v = view ?: return
        params.gravity = Gravity.TOP or Gravity.START
        params.x = x.roundToInt(); params.y = y.roundToInt()
        wm.updateViewLayout(v, params)
        reportBubble(v)
    }

    private fun reportBubble(v: android.view.View) {
        val o = origin()
        val size = bubbleSize(v)
        val left = bubbleLeftEdge(v).toInt()
        w.bubbleOnScreen = android.graphics.Rect(o.x + left, o.y + params.y, o.x + left + size, o.y + params.y + size)
    }

    private fun placeHint() {
        val h = hint ?: return
        val v = view ?: return
        h.visibility = if (w.expanded || w.appInFront || graph.tracker.shown) android.view.View.GONE else android.view.View.VISIBLE
        hintParams.gravity = params.gravity
        hintParams.x = params.x + bubbleSize(v)
        hintParams.y = params.y
        wm.updateViewLayout(h, hintParams)
    }

    private fun targetCenter(): Offset {
        val r = screen()
        val d = resources.displayMetrics.density
        return Offset(r.width() / 2f, r.height() - (TARGET_MARGIN + TARGET_BOX / 2f) * d)
    }

    /** The bubble's top-left when the target holds it: centred on the target. */
    private fun targetSpot(v: android.view.View) = targetCenter() - Offset(bubbleSize(v) / 2f, bubbleSize(v) / 2f)

    private fun showTarget(on: Boolean) {
        val t = target ?: return
        if (!on) {
            // after its fade-out; hidden, its window can't keep taps from the game
            frames.launch { delay(300); if (!w.dragging) t.visibility = android.view.View.GONE }
            return
        }
        val box = (TARGET_BOX * resources.displayMetrics.density).toInt()
        val c = targetCenter()
        targetParams.x = (c.x - box / 2f).roundToInt(); targetParams.y = (c.y - box / 2f).roundToInt()
        wm.updateViewLayout(t, targetParams)
        t.visibility = android.view.View.VISIBLE
    }

    /** Springs the bubble to [x], [y], keeping the speed it has (or [vx], [vy]), then runs [then]. */
    private fun glideTo(x: Float, y: Float, sx: SpringSpec<Float>, sy: SpringSpec<Float>, vx: Float? = null, vy: Float? = null, then: () -> Unit = {}) {
        val v = view ?: return
        val from = Offset(bubbleLeftEdge(v), params.y.toFloat())
        val ivx = vx ?: if (glideX.isRunning) glideX.velocity else 0f
        val ivy = vy ?: if (glideY.isRunning) glideY.velocity else 0f
        glide?.cancel()
        glide = frames.launch {
            glideX.snapTo(from.x); glideY.snapTo(from.y)
            coroutineScope {
                launch { glideX.animateTo(x, sx, ivx) { putBubble(value, glideY.value) } }
                launch { glideY.animateTo(y, sy, ivy) { putBubble(glideX.value, value) } }
            }
            then()
        }
    }

    /**
     * Leaving the target, the bubble closes the gap to the moving finger in a fixed time, then follows it exactly. A spring
     * restarted on every move lagged: each restart spends its first frame only taking the start time.
     */
    private fun catchUp() {
        val v = view ?: return
        val from = Offset(bubbleLeftEdge(v), params.y.toFloat())
        glide?.cancel()
        glide = frames.launch {
            val t0 = withFrameNanos { it }
            do {
                val t = ((withFrameNanos { it } - t0) / 1e6f / CATCH_UP_MS).coerceAtMost(1f)
                val k = 1f - (1f - t) * (1f - t) * (1f - t)
                putBubble(from.x + (finger.x - from.x) * k, from.y + (finger.y - from.y) * k)
            } while (t < 1f)
        }
    }

    private fun tick(on: Boolean) {
        val c = if (Build.VERSION.SDK_INT >= 34) {
            if (on) android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
        } else if (on) android.view.HapticFeedbackConstants.VIRTUAL_KEY else android.view.HapticFeedbackConstants.CLOCK_TICK
        view?.performHapticFeedback(c)
    }

    /** Like the phone's chat bubbles: follows the finger, a dismiss target pulls it in, let go it springs to a side edge. */
    private val drag = object : BubbleDrag {
        override fun start() {
            val v = view ?: return
            finger = Offset(bubbleLeftEdge(v), params.y.toFloat())
            glide?.cancel()
            putBubble(finger.x, finger.y)
            w.dragging = true; w.captured = false
            placeHint(); showTarget(true)
        }

        override fun move(dx: Float, dy: Float) {
            val v = view ?: return
            val r = screen()
            val size = bubbleSize(v)
            finger = Offset((finger.x + dx).coerceIn(0f, (r.width() - size).toFloat()), (finger.y + dy).coerceIn(0f, (r.height() - size).toFloat()))
            val held = (finger + Offset(size / 2f, size / 2f) - targetCenter()).getDistance() < MAGNET * resources.displayMetrics.density
            when {
                held != w.captured -> {
                    w.captured = held
                    tick(held)
                    if (held) targetSpot(v).let { glideTo(it.x, it.y, PULL, PULL) } else catchUp()
                }
                held -> {}
                // catching up: it reads the finger every frame
                glide?.isActive == true -> {}
                else -> putBubble(finger.x, finger.y)
            }
        }

        override fun end(vx: Float, vy: Float) {
            val v = view ?: return
            w.dragging = false
            showTarget(false)
            if (w.captured || flungAtTarget(v, vx, vy)) { dismiss(v); return }
            val r = screen()
            val d = resources.displayMetrics.density
            val size = bubbleSize(v)
            val x0 = bubbleLeftEdge(v)
            val y0 = params.y.toFloat()
            val right = if (abs(vx) > FLING_SIDE * d) vx > 0 else x0 + size / 2f > r.width() / 2f
            // a critically damped spring stops by itself after v/ω, so that is how far a fling glides
            val y = (y0 + vy / sqrt(GLIDE.stiffness)).coerceIn(0f, (r.height() - size).toFloat())
            glideTo(if (right) (r.width() - size).toFloat() else 0f, y, SNAP, GLIDE, vx, vy) {
                w.bubbleLeft = !right
                val maxY = screen().height() - size
                if (maxY > 0) bubbleAt = (params.y.toFloat() / maxY).coerceIn(0f, 1f)
                placeBubble()
            }
        }
    }

    /** A hard fling down whose path crosses the target closes, as if dropped on it. */
    private fun flungAtTarget(v: android.view.View, vx: Float, vy: Float): Boolean {
        if (vy < FLING_DISMISS * resources.displayMetrics.density) return false
        val c = Offset(bubbleLeftEdge(v), params.y.toFloat()) + Offset(bubbleSize(v) / 2f, bubbleSize(v) / 2f)
        val t = targetCenter()
        if (t.y <= c.y) return false
        return abs(c.x + vx * (t.y - c.y) / vy - t.x) < MAGNET * resources.displayMetrics.density
    }

    private fun dismiss(v: android.view.View) {
        if (!w.captured) tick(true)
        w.closing = true
        val to = targetSpot(v)
        glideTo(to.x, to.y, PULL, PULL)
        frames.launch { delay(260); stopSelf() }
    }

    private fun placeBody() {
        val v = view ?: return
        val b = body ?: return
        b.visibility = if (!graph.tracker.shown && w.expanded && !w.appInFront && !w.listHidden) android.view.View.VISIBLE else android.view.View.GONE
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

    /** Where [screen]'s top-left is on the display. */
    private fun origin(): android.graphics.Point =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()).let { android.graphics.Point(it.left, it.top) }
        else android.graphics.Point(0, 0)

    /** Drags the open panel. With top-end gravity x grows to the left. */
    private fun moveBy(dx: Float, dy: Float) {
        val v = view ?: return
        val r = screen()
        val fromEnd = (params.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == Gravity.END
        params.x = (params.x + if (fromEnd) -dx.toInt() else dx.toInt()).coerceIn(0, maxOf(0, r.width() - v.width))
        params.y = (params.y + dy.toInt()).coerceIn(0, maxOf(0, r.height() - v.height))
        wm.updateViewLayout(v, params)
        placeBody()
    }

    private fun dock(expanded: Boolean) {
        val v = view ?: return
        glide?.cancel()
        val d = resources.displayMetrics.density
        // wrap-content overlays are first measured at dialog width (~320 dp), which would squeeze the panel
        params.width = if (expanded) (338 * d).toInt() else WindowManager.LayoutParams.WRAP_CONTENT
        // open on the left: in the game's gear screens the list to pick from is on the right
        params.gravity = Gravity.TOP or if (expanded) Gravity.START else Gravity.END
        params.x = if (expanded) (14 * d).toInt() else 0
        if (expanded) { params.y = (6 * d).toInt(); wm.updateViewLayout(v, params); placeBody(); placeHint() } else placeBubble()
    }

    private fun foreground() {
        val channel = "overlay"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "Build over the game", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, channel)
            .setContentTitle(if (graph.tracker.shown) "Upgrade tracker over the game" else "Build checklist over the game")
            .setContentText("Tap to close it.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(stop)
            .build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startForeground(3, n)
        else startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    override fun onDestroy() {
        w.usableHeightDp = 0f
        w.onAppInFront = null
        w.expanded = false
        frames.cancel()
        w.bubbleDrag = null; w.bubbleOnScreen = null
        w.dragging = false; w.captured = false; w.closing = false
        listOfNotNull(view, body, target, hint).forEach { runCatching { wm.removeView(it) } }
        view = null; body = null; target = null; hint = null
        w.running = false
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "e7.gearscan.OVERLAY_STOP"
        /** dp from the target's centre at which it pulls the bubble in. */
        private const val MAGNET = 90f
        /** dp/s: a sideways fling this fast picks the side; slower, the nearer side wins. */
        private const val FLING_SIDE = 700f
        /** dp/s: a fling down this fast at the target closes. */
        private const val FLING_DISMISS = 2500f
        private val SNAP = spring<Float>(0.75f, 750f)
        private val GLIDE = spring<Float>(1f, 200f)
        private val PULL = spring<Float>(0.8f, Spring.StiffnessMedium)
        private const val CATCH_UP_MS = 120f

        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, OverlayService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, OverlayService::class.java)) }
    }
}
