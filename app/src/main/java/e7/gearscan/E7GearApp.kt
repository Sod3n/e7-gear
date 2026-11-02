package e7.gearscan

import android.app.Application
import e7.gearscan.state.Disk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class E7GearApp : Application() {
    val graph by lazy { AppGraph(Disk(filesDir), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
}
