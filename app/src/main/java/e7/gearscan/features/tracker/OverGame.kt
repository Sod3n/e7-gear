package e7.gearscan.features.tracker

import e7.gearscan.Screen
import e7.gearscan.features.scan.ScanFeature
import e7.gearscan.features.settings.SettingsFeature
import e7.gearscan.opt.Build
import e7.gearscan.state.FeatureContext

class OverGameLauncher(private val ctx: FeatureContext, private val settings: SettingsFeature, private val scan: ScanFeature) {
    // false = E7 Gear's own overlay (any phone), true = the phone's floating window showing the app's panel
    private fun native() {
        ctx.platform.stopOverlay()
        ctx.overlay.expanded = true
        ctx.overlay.floatingPanel = true
        if (!settings.nativeSeen) {
            ctx.nav.go(Screen.OverlayIntro)
            return
        }
        scan.openGame()
    }

    fun open(afterStart: () -> Unit = {}) {
        if (settings.overGameNative) {
            native()
            afterStart()
            return
        }
        if (ctx.platform.pillAllowed) {
            ctx.platform.startOverlay()
            afterStart()
            scan.openGame()
        } else {
            ctx.nav.go(Screen.OverlayIntro)
        }
    }

    fun showChecklist(): Boolean {
        if (settings.overGameNative) {
            native()
            return true
        }
        if (!ctx.platform.pillAllowed) return false
        ctx.platform.stopOverlay()
        ctx.platform.startOverlay()
        settings.overlaySeen = true
        ctx.store.saveNow()
        scan.openGame()
        return true
    }
}

class OverGame(
    private val ctx: FeatureContext,
    private val launcher: OverGameLauncher,
    private val tracker: TrackerFeature,
    private val checklist: ChecklistFeature,
) {
    fun open(afterStart: () -> Unit = {}) = launcher.open(afterStart)

    fun show(heroId: Long, b: Build, title: String, flag: String? = null): Boolean {
        checklist.loadBuild(heroId, b, title, flag)
        return showChecklist()
    }

    fun showChecklist(): Boolean = launcher.showChecklist()

    fun closeFloatingPanel() {
        tracker.hide()
        checklist.unload()
        ctx.overlay.floatingPanel = true
    }
}
