package e7.gearscan

import android.app.Application
import e7.gearscan.state.Disk
import e7.gearscan.state.Nav
import e7.gearscan.state.Store
import e7.gearscan.state.WorkHost
import e7.gearscan.ui.Messages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val disk = Disk(app.filesDir)
    val store = Store(disk, version = 2, migrations = emptyList(), scope = scope)
    val work = WorkHost(scope)
    val messages = Messages()
    val nav = Nav(Screen.Optimize, messages::dismissConfirm)
}
