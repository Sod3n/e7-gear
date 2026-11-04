package e7.gearscan.ui

@RequiresOptIn("Link-style text is rare: prefer a row, a pill or a button", RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
annotation class LinkStyle

@RequiresOptIn("Typing on the keyboard is allowed only in the optimizer's stat controls, on tap of a slider's value (Sell review and Find gear targets) and in name searches", RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
annotation class KeyboardInput
