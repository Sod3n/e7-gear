package e7.gearscan.state

import org.json.JSONArray
import org.json.JSONObject

interface Codec<T> {
    fun write(value: T): Any?
    fun read(json: Any?): T?
}

object Codecs {
    private fun <T> codec(write: (T) -> Any?, read: (Any?) -> T?) = object : Codec<T> {
        override fun write(value: T) = write(value)
        override fun read(json: Any?) = read(json)
    }

    val bool: Codec<Boolean> = codec({ it }, { it as? Boolean })
    val int: Codec<Int> = codec({ it }, { (it as? Number)?.toInt() })
    val long: Codec<Long> = codec({ it }, { (it as? Number)?.toLong() })
    val double: Codec<Double> = codec({ it }, { (it as? Number)?.toDouble() })
    val string: Codec<String> = codec({ it }, { it as? String })

    fun <E : Enum<E>> enum(values: Array<E>): Codec<E> =
        codec({ it.name }, { n -> values.firstOrNull { it.name == n } })

    val longSet: Codec<Set<Long>> = codec({ s -> JSONArray().also { a -> s.forEach { a.put(it) } } }, { j -> numbers(j)?.map { it.toLong() }?.toSet() })
    val intSet: Codec<Set<Int>> = codec({ s -> JSONArray().also { a -> s.forEach { a.put(it) } } }, { j -> numbers(j)?.map { it.toInt() }?.toSet() })
    val longList: Codec<List<Long>> = codec({ s -> JSONArray().also { a -> s.forEach { a.put(it) } } }, { j -> numbers(j)?.map { it.toLong() } })

    fun <T> obj(write: (T) -> JSONObject, read: (JSONObject) -> T): Codec<T> =
        codec(write, { j -> (j as? JSONObject)?.let { runCatching { read(it) }.getOrNull() } })

    private fun numbers(j: Any?): List<Number>? {
        val a = j as? JSONArray ?: return null
        return (0 until a.length()).map { a.get(it) as? Number ?: return null }
    }
}
