package e7.gearscan.features.settings

import e7.gearscan.state.FeatureContext
import e7.gearscan.calc.Rating
import e7.gearscan.state.Codecs
import e7.gearscan.state.Feature

class SettingsFeature(ctx: FeatureContext) : Feature(ctx, "settings") {
    var consent by saved("consent", false, Codecs.bool)
    var proxyOn by saved("proxyOn", false, Codecs.bool, keepOnErase = true)
    var proxy by saved("proxy", "", Codecs.string, keepOnErase = true)
    var showRating by saved("showRating", Rating.DMG, Codecs.enum(Rating.entries.toTypedArray()))
    var overlaySeen by saved("overlaySeen", false, Codecs.bool, keepOnErase = true)
    var overGameNative by saved("overGameNative", false, Codecs.bool, keepOnErase = true)
    var nativeSeen by saved("nativeSeen", false, Codecs.bool, keepOnErase = true)

    fun useOverGameNative(on: Boolean) {
        overGameNative = on
        if (on) ctx.platform.stopOverlay()
        ctx.rev.bump()
    }
}
