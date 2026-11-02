package e7.gearscan.features.tracker

import e7.gearscan.AppGraph
import e7.gearscan.Screen
import e7.gearscan.opt.Build

class OverGame(private val g: AppGraph) {
    // false = E7 Gear's own overlay (any phone), true = the phone's floating window showing the app's panel
    private fun native() {
        g.platform.stopOverlay()
        g.overlay.expanded = true
        g.overlay.floatingPanel = true
        if (!g.settings.nativeSeen) {
            g.nav.go(Screen.OverlayIntro)
            return
        }
        g.scan.openGame()
    }

    fun open(afterStart: () -> Unit = {}) {
        if (g.settings.overGameNative) {
            native()
            afterStart()
            return
        }
        if (g.platform.pillAllowed) {
            g.platform.startOverlay()
            afterStart()
            g.scan.openGame()
        } else {
            g.nav.go(Screen.OverlayIntro)
        }
    }

    fun show(heroId: Long, b: Build, title: String, flag: String? = null): Boolean {
        g.checklist.loadBuild(heroId, b, title, flag)
        return showChecklist()
    }

    fun showChecklist(): Boolean {
        if (g.settings.overGameNative) {
            native()
            return true
        }
        if (!g.platform.pillAllowed) return false
        g.platform.stopOverlay()
        g.platform.startOverlay()
        g.settings.overlaySeen = true
        g.store.saveNow()
        g.scan.openGame()
        return true
    }

    fun closeFloatingPanel() {
        g.tracker.hide()
        g.checklist.unload()
        g.overlay.floatingPanel = true
    }
}
