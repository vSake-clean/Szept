package pl.szept.nostr

import org.json.JSONArray
import org.json.JSONObject
import pl.szept.crypto.Crypto
import pl.szept.crypto.hex
import pl.szept.crypto.unhex

/** Zdarzenie Nostr (NIP-01). */
class Event(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    fun toJson(): String = StringBuilder().apply {
        append("{\"id\":\"").append(id)
        append("\",\"pubkey\":\"").append(pubkey)
        append("\",\"created_at\":").append(createdAt)
        append(",\"kind\":").append(kind)
        append(",\"tags\":"); appendTags(this, tags)
        append(",\"content\":"); appendString(this, content)
        append(",\"sig\":\"").append(sig).append("\"}")
    }.toString()

    /** Sprawdza identyfikator i podpis. */
    fun verify(): Boolean = try {
        computeId(pubkey, createdAt, kind, tags, content) == id &&
            Crypto.verify(sig.unhex(), id.unhex(), pubkey.unhex())
    } catch (e: Throwable) { false }

    companion object {
        fun create(priv: ByteArray, kind: Int, tags: List<List<String>>, content: String,
                   createdAt: Long = Clock.nowSec()): Event {
            val pub = Crypto.publicKey(priv).hex()
            val id = computeId(pub, createdAt, kind, tags, content)
            val sig = Crypto.sign(id.unhex(), priv).hex()
            return Event(id, pub, createdAt, kind, tags, content, sig)
        }

        fun computeId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
            val sb = StringBuilder()
            sb.append("[0,\"").append(pubkey).append("\",").append(createdAt).append(',').append(kind).append(',')
            appendTags(sb, tags)
            sb.append(',')
            appendString(sb, content)
            sb.append(']')
            return Crypto.sha256(sb.toString().toByteArray(Charsets.UTF_8)).hex()
        }

        fun fromJson(o: JSONObject): Event {
            val tagsArr = o.getJSONArray("tags")
            val tags = ArrayList<List<String>>(tagsArr.length())
            for (i in 0 until tagsArr.length()) {
                val t = tagsArr.getJSONArray(i)
                tags.add(List(t.length()) { j -> t.getString(j) })
            }
            return Event(
                o.getString("id"), o.getString("pubkey"), o.getLong("created_at"), o.getInt("kind"),
                tags, o.getString("content"), o.getString("sig"),
            )
        }

        fun fromJson(s: String): Event = fromJson(JSONObject(s))

        private fun appendTags(sb: StringBuilder, tags: List<List<String>>) {
            sb.append('[')
            tags.forEachIndexed { i, t ->
                if (i > 0) sb.append(',')
                sb.append('[')
                t.forEachIndexed { j, v -> if (j > 0) sb.append(','); appendString(sb, v) }
                sb.append(']')
            }
            sb.append(']')
        }

        /** Serializacja łańcucha zgodna z NIP-01. */
        fun appendString(sb: StringBuilder, s: String) {
            sb.append('"')
            for (ch in s) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000c' -> sb.append("\\f")
                    else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
                }
            }
            sb.append('"')
        }
    }
}

fun jsonArrayOf(vararg items: Any?): JSONArray = JSONArray().apply { items.forEach { put(it) } }
