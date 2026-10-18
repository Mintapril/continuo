package dev.dsh.pocket.data

import org.json.JSONObject

/** Merges one Session event sequence space; cached list hints never advance live watermarks. */
class ProjectionState {
    private val values = JSONObject()
    private val seqs = mutableMapOf<String, Long>()
    private var completeCut = -1L
    private var hasBaseline = false

    fun value(): JSONObject = JSONObject(values.toString())

    fun hint(block: JSONObject) {
        val incoming = block.getJSONObject("values")
        if (block.optString("kind") == "sequenced") {
            val cut = block.getLong("asOfSeq")
            incoming.keys().forEach { key -> update(key, incoming.opt(key), cut) }
        } else {
            incoming.keys().forEach { key ->
                if (!hasBaseline && !seqs.containsKey(key) && !values.has(key)) values.put(key, incoming.opt(key))
            }
        }
    }

    fun baseline(block: JSONObject) {
        val cut = block.getLong("asOfSeq")
        val incoming = block.getJSONObject("values")
        val keys = values.keys().asSequence().toSet() + seqs.keys + incoming.keys().asSequence().toSet()
        keys.forEach { key ->
            if (cut >= (seqs[key] ?: if (hasBaseline) completeCut else -1L)) {
                if (incoming.has(key)) values.put(key, incoming.opt(key)) else values.remove(key)
                seqs[key] = cut
            }
        }
        completeCut = maxOf(completeCut, cut)
        hasBaseline = true
    }

    fun update(key: String, value: Any?, seq: Long) {
        if (seq < (seqs[key] ?: if (hasBaseline) completeCut else -1L)) return
        values.put(key, value ?: JSONObject.NULL)
        seqs[key] = seq
    }
}
