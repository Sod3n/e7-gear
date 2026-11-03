package e7.gearscan

import android.content.Context
import e7.gearscan.state.Disk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun startedGraph(ctx: Context): AppGraph = (ctx.applicationContext as E7GearApp).graph.also { it.start() }

fun restartedGraph(ctx: Context): AppGraph =
    AppGraph(Disk(ctx.filesDir), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), platform = AndroidPlatform(ctx.applicationContext as android.app.Application))
        .also { it.start() }

// a game-data update landing mid-test marks every list out of date
fun AppGraph.contentSettled() = System.currentTimeMillis() - content.checkedAt < 24 * 3600 * 1000L
