package com.litepptx.viewer.pptx

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

/** Tiny immutable-ish XML tree. Names are stored without namespace prefix ("a:t" -> "t"). */
class Node(val name: String, val attrs: Map<String, String>) {
    val kids = ArrayList<Node>(4)
    var text: String = ""

    fun a(key: String): String? = attrs[key]
    fun f(key: String): Float? = attrs[key]?.toFloatOrNull()
    fun i(key: String): Int? = attrs[key]?.toIntOrNull()
    fun bool(key: String): Boolean? = attrs[key]?.let { it == "1" || it == "true" }

    /** Relationship-namespace attribute (r:embed, r:id ...). */
    fun ra(local: String): String? {
        attrs["r:$local"]?.let { return it }
        for ((k, v) in attrs) if (k.endsWith(":$local")) return v
        return null
    }

    fun child(n: String): Node? {
        for (k in kids) if (k.name == n) return k
        return null
    }

    fun children(n: String): List<Node> = kids.filter { it.name == n }

    fun path(vararg p: String): Node? {
        var c: Node? = this
        for (x in p) c = c?.child(x) ?: return null
        return c
    }
}

object Dom {
    fun parse(bytes: ByteArray): Node? = try {
        parseStream(ByteArrayInputStream(bytes))
    } catch (e: Exception) {
        null
    }

    private fun parseStream(input: ByteArrayInputStream): Node? {
        val p = Xml.newPullParser()
        p.setInput(input, null)
        val stack = ArrayList<Node>()
        var root: Node? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val n = p.attributeCount
                    val attrs: Map<String, String> = if (n == 0) emptyMap() else {
                        val m = HashMap<String, String>(n * 2)
                        for (i in 0 until n) {
                            val an = p.getAttributeName(i)
                            // keep prefixed attributes as-is (r:embed); unprefixed ones as-is
                            m[an] = p.getAttributeValue(i)
                        }
                        m
                    }
                    val node = Node(p.name.substringAfter(':'), attrs)
                    if (stack.isEmpty()) root = node else stack[stack.size - 1].kids.add(node)
                    stack.add(node)
                }
                XmlPullParser.TEXT -> {
                    if (stack.isNotEmpty()) {
                        val top = stack[stack.size - 1]
                        if (top.name == "t" || top.name == "tableStyleId") top.text += p.text
                    }
                }
                XmlPullParser.END_TAG -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            }
            ev = p.next()
        }
        return root
    }
}
