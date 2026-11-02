package e7.gearscan

import e7.gearscan.state.Disk
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

class TrackerEnv(private val dir: File) {
    val g = AppGraph(Disk(dir), CoroutineScope(Job()))
    val t = g.tracker

    fun reopen(): TrackerEnv {
        g.disk.flush()
        return TrackerEnv(dir)
    }
}
