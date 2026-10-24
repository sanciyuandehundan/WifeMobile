package io.wife.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One line in the conversation with her (protocol.md 消息): his words, voice messages and photos, and hers. His wait
 *  as ``pending`` until the computer says it has them; ``phone``: said by her brain on this phone. */
data class Item(
    val id: String,
    val his: Boolean,
    val t: Long,
    val kind: String,                       // text, voice or photo
    val text: String,                       // typed or said; under his voice what she heard, with hers what she said
    val ms: Int = 0,
    val pending: Boolean = false,
    val heard: Boolean? = null,             // his voice message: null not heard yet, false not his voice
    val phone: Boolean = false,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("his", his).put("t", t).put("kind", kind).put("text", text)
        .put("ms", ms).put("pending", pending).put("heard", heard ?: JSONObject.NULL).put("phone", phone)

    companion object {
        fun of(o: JSONObject) = Item(o.getString("id"), o.getBoolean("his"), o.getLong("t"), o.getString("kind"),
                                     o.optString("text"), o.optInt("ms"), o.optBoolean("pending"),
                                     if (o.isNull("heard")) null else o.getBoolean("heard"), o.optBoolean("phone"))
    }
}

/** The conversation on this phone: chat.json beside one file per recording (.opus) or photo (.jpg), in the app's
 *  own storage. */
class Chat(private val folder: File) {
    private val list = mutableListOf<Item>()

    init {
        folder.mkdirs()
        val saved = File(folder, "chat.json")
        if (saved.exists()) {
            val array = JSONArray(saved.readText())
            for (i in 0 until array.length()) list += Item.of(array.getJSONObject(i))
        }
    }

    @get:Synchronized val items: List<Item> get() = list.toList()

    @Synchronized fun has(id: String) = list.any { it.id == id }

    @Synchronized
    fun add(item: Item, voice: ByteArray? = null, photo: ByteArray? = null) {
        voice?.let { File(folder, "${item.id}.opus").writeBytes(it) }
        photo?.let { File(folder, "${item.id}.jpg").writeBytes(it) }
        list += item
        list.sortBy { it.t }
        save()
    }

    @Synchronized
    fun update(id: String, voice: ByteArray? = null, change: (Item) -> Item) {
        val at = list.indexOfFirst { it.id == id }
        if (at < 0) return
        voice?.let { File(folder, "$id.opus").writeBytes(it) }
        list[at] = change(list[at])
        save()
    }

    fun voice(id: String): ByteArray? = File(folder, "$id.opus").takeIf { it.exists() }?.readBytes()
    fun photo(id: String): ByteArray? = File(folder, "$id.jpg").takeIf { it.exists() }?.readBytes()

    private fun save() {
        val file = File(folder, "chat.json.new")
        file.writeText(JSONArray(list.map { it.json() }).toString())
        file.renameTo(File(folder, "chat.json"))                   // replaces it in one step
    }
}
