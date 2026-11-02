package e7.gearscan

import e7.gearscan.features.tracker.TrackerFeature
import e7.gearscan.state.Disk
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

fun reopenTracker(
    graph: AppGraph,
    dir: File,
): TrackerFeature {
    graph.disk.flush()
    return AppGraph(Disk(dir), CoroutineScope(Job())).tracker
}
