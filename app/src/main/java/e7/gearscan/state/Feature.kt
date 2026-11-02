package e7.gearscan.state

import e7.gearscan.data.Box

abstract class Feature(protected val ctx: FeatureContext, val id: String) {
    protected fun <T> saved(key: String, default: T, codec: Codec<T>, keepOnErase: Boolean = false) =
        ctx.store.field("$id.$key", default, codec, keepOnErase)

    protected fun savedFile(name: String, backup: Boolean = true, keepOnErase: Boolean = false, path: String = "$id/$name") =
        ctx.store.file(path, backup, keepOnErase)

    protected fun savedDir(name: String, keepOnErase: Boolean = false) = ctx.store.dir(name, keepOnErase)

    protected fun <R> work(name: String, weight: Weight = Weight.Light, progress: SavedFile? = null, foreground: Boolean = weight == Weight.Heavy, notice: String = name) =
        Work<R>(name, ctx.work, weight, progress, foreground, notice)

    protected fun <K, R> freshWork(name: String, weight: Weight = Weight.Light, inputs: () -> K) =
        FreshWork<K, R>(name, ctx.work, weight, inputs)

    // Box events, in the order ScanFeature raises them: replacing -> changed -> adopted (adopt), imported, restored, erased.
    open fun onBoxReplacing() {}

    open fun onBoxChanged(old: Box?, new: Box?) {}

    open fun onBoxAdopted(box: Box?) {}

    open fun onImported() {}

    open fun onRestored() {}

    open fun onErased() {}
}
