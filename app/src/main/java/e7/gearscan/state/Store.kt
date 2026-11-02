package e7.gearscan.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class Disk(val dir: File) {
    internal var beforeRename: (() -> Unit)? = null

    fun file(name: String) = File(dir, name)

    fun read(name: String): String? = file(name).takeIf { it.isFile }?.readText()

    fun write(name: String, text: String) {
        val target = file(name)
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.writeText(text)
        beforeRename?.invoke()
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun delete(name: String) { file(name).delete() }
}

class Migration(val from: Int, val apply: (JSONObject) -> JSONObject)

class SavedField<T> internal constructor(
    val key: String,
    val default: T,
    private val codec: Codec<T>,
    val keepOnErase: Boolean,
    private val store: Store,
) : ReadWriteProperty<Any?, T> {
    private var value by mutableStateOf(default)

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = value

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        this.value = value
        store.saveSoon()
    }

    internal fun write(): Any? = codec.write(value)
    internal fun load(json: Any?) { value = json?.let(codec::read) ?: default }
    internal fun reset() { value = default }
}

class SavedFile internal constructor(val name: String, val backup: Boolean, val keepOnErase: Boolean, private val disk: Disk) {
    val exists: Boolean get() = disk.file(name).isFile
    fun read(): String? = disk.read(name)
    fun write(text: String) = disk.write(name, text)
    fun delete() = disk.delete(name)
}

class Store(
    private val disk: Disk,
    val version: Int,
    private val migrations: List<Migration>,
    private val scope: CoroutineScope,
    private val fileName: String = "store.json",
) {
    private val fields = LinkedHashMap<String, SavedField<*>>()
    private val files = LinkedHashMap<String, SavedFile>()
    private var pending: Job? = null

    fun <T> field(key: String, default: T, codec: Codec<T>, keepOnErase: Boolean = false): SavedField<T> {
        require(key !in fields) { "field $key is registered twice" }
        return SavedField(key, default, codec, keepOnErase, this).also { fields[key] = it }
    }

    fun file(name: String, backup: Boolean, keepOnErase: Boolean = false): SavedFile {
        require(name !in files) { "file $name is registered twice" }
        return SavedFile(name, backup, keepOnErase, disk).also { files[name] = it }
    }

    fun load() {
        val text = disk.read(fileName)
        val json = text?.let { runCatching { JSONObject(it) }.getOrNull() }
        applyFields(json?.let(::migrate)?.optJSONObject("fields"))
    }

    fun backup(): JSONObject {
        val f = JSONObject()
        fields.values.forEach { f.put(it.key, it.write()) }
        val fs = JSONObject()
        files.values.filter { it.backup }.forEach { file -> file.read()?.let { fs.put(file.name, it) } }
        return JSONObject().put("app", "e7gear").put("version", version).put("fields", f).put("files", fs)
    }

    fun restore(json: JSONObject) {
        val m = migrate(json)
        applyFields(m.optJSONObject("fields"))
        val fs = m.optJSONObject("files")
        files.values.filter { it.backup }.forEach { file ->
            val text = fs?.optString(file.name, "")?.takeIf { it.isNotEmpty() }
            if (text != null) file.write(text) else file.delete()
        }
        saveNow()
    }

    fun erase() {
        pending?.cancel()
        fields.values.filter { !it.keepOnErase }.forEach { it.reset() }
        files.values.filter { !it.keepOnErase }.forEach { it.delete() }
        saveNow()
    }

    internal fun saveSoon() {
        pending?.cancel()
        pending = scope.launch { delay(SAVE_DELAY_MS); saveNow() }
    }

    fun saveNow() {
        pending?.cancel()
        val f = JSONObject()
        fields.values.forEach { f.put(it.key, it.write()) }
        disk.write(fileName, JSONObject().put("version", version).put("fields", f).toString())
    }

    private fun applyFields(json: JSONObject?) {
        fields.values.forEach { it.load(json?.opt(it.key)) }
    }

    private fun migrate(json: JSONObject): JSONObject {
        var m = json
        var v = m.optInt("version", 1)
        while (v < version) {
            val step = migrations.firstOrNull { it.from == v } ?: break
            m = step.apply(m)
            v++
            m.put("version", v)
        }
        return m
    }

    private companion object { const val SAVE_DELAY_MS = 500L }
}
