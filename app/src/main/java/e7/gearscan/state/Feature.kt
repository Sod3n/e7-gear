package e7.gearscan.state

import e7.gearscan.AppGraph
import e7.gearscan.data.Box

abstract class Feature(protected val g: AppGraph, val id: String) {
    protected fun <T> saved(key: String, default: T, codec: Codec<T>, keepOnErase: Boolean = false) =
        g.store.field("$id.$key", default, codec, keepOnErase)

    protected fun savedFile(name: String, backup: Boolean = true) = g.store.file("$id/$name", backup)

    protected fun <R> work(name: String, weight: Weight = Weight.Light, progress: SavedFile? = null) =
        Work<R>(name, g.work, weight, progress)

    protected fun <K, R> freshWork(name: String, weight: Weight = Weight.Light, inputs: () -> K) =
        FreshWork<K, R>(name, g.work, weight, inputs)

    open fun onBoxChanged(old: Box?, new: Box?) {}
}
