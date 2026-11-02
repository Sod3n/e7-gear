package e7.gearscan.state

import e7.gearscan.data.Http
import e7.gearscan.overlay.OverlayWindow
import e7.gearscan.ui.Messages
import kotlinx.coroutines.CoroutineScope

class FeatureContext(
    val disk: Disk,
    val scope: CoroutineScope,
    val platform: Platform,
    val store: Store,
    val http: Http,
    val rev: Revision,
    val work: WorkHost,
    val messages: Messages,
    val nav: Nav,
    val overlay: OverlayWindow,
)
