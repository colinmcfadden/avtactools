package app.ezpztac.formats

import app.ezpztac.model.JsNumber
import org.w3c.dom.Comment
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.ProcessingInstruction
import org.w3c.dom.Text
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/**
 * The few things the `.msnx` writer does to a parsed XML part, named as the web's `mutateMsnx.js` names them. They are not a general XML toolkit: they
 * do what the web's DOM calls do on the template, so the same steps give the same document.
 */
internal object MsnxXml {
    const val MSNX_NS: String = "http://www.xplan.com/MSNX/v1.0"
    const val BOM: String = "﻿"

    /**
     * Parses one part. A DOCTYPE is refused by *text* before anything is built, as the reader does: an AMPS file can come from anyone, and an external
     * entity could read a file off the device. (The parser's own flags are best effort: Android's does not know all of them.)
     */
    fun parse(text: String, label: String): Document {
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) {
            throw MsnxException("$label has a DTD, which a mission file does not.")
        }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        for ((feature, value) in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            XMLConstants.FEATURE_SECURE_PROCESSING to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        )) {
            try {
                factory.setFeature(feature, value)
            } catch (_: Exception) {
                // This parser does not know it; the text check above is the one that must hold.
            }
        }
        try {
            // A byte order mark in front of the text is not part of the document.
            return factory.newDocumentBuilder().parse(InputSource(StringReader(text.removePrefix(BOM))))
        } catch (e: SAXException) {
            throw MsnxException("Failed to parse $label in the mission template.", e)
        }
    }

    /** The element children, in order (the DOM's `children`). */
    fun children(el: Element): List<Element> {
        val out = ArrayList<Element>()
        var node: Node? = el.firstChild
        while (node != null) {
            if (node is Element) out += node
            node = node.nextSibling
        }
        return out
    }

    fun directChildren(el: Element, tagName: String): List<Element> = children(el).filter { it.tagName == tagName }

    fun findDirectChild(el: Element, tagName: String): Element? = children(el).firstOrNull { it.tagName == tagName }

    fun requireChild(el: Element, tagName: String): Element =
        findDirectChild(el, tagName) ?: throw MsnxException("The mission template has no <$tagName> where one is expected (in <${el.tagName}>).")

    /** Every element below [el] with this name, in document order, as a list that does not change when the tree does. */
    fun descendants(el: Element, tagName: String): List<Element> {
        val nodes = el.getElementsByTagName(tagName)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    fun clearChildren(el: Element) {
        while (el.firstChild != null) el.removeChild(el.firstChild)
    }

    /** `textContent = text`: whatever was inside is gone, and an empty text leaves nothing at all. */
    fun setText(el: Element, text: String) {
        clearChildren(el)
        if (text.isNotEmpty()) el.appendChild(el.ownerDocument.createTextNode(text))
    }

    fun setDirectChildText(el: Element, tagName: String, text: String) {
        findDirectChild(el, tagName)?.let { setText(it, text) }
    }

    fun clone(el: Element): Element = el.cloneNode(true) as Element

    /** The value of the first `item` below [el] whose key is [key], or null. */
    fun getItemValue(el: Element, key: String): String? {
        for (item in descendants(el, "item")) {
            val keyEl = findDirectChild(item, "key")
            if (keyEl != null && keyEl.textContent == key) {
                val attribute = findDirectChild(item, "attribute")
                return attribute?.let { findDirectChild(it, "value") }?.textContent
            }
        }
        return null
    }

    /** Sets the first `item` below [el] with this key; an element with no such item is left alone. */
    fun setItemValue(el: Element, key: String, value: String) {
        for (item in descendants(el, "item")) {
            val keyEl = findDirectChild(item, "key")
            if (keyEl != null && keyEl.textContent == key) {
                val attribute = findDirectChild(item, "attribute")
                val valueEl = attribute?.let { findDirectChild(it, "value") }
                if (valueEl != null) setText(valueEl, value)
                return
            }
        }
    }

    fun findCoordinateValueEl(pointEl: Element): Element? {
        for (item in descendants(pointEl, "item")) {
            val keyEl = findDirectChild(item, "key")
            if (keyEl != null && keyEl.textContent == "Coordinate") {
                val attribute = findDirectChild(item, "attribute")
                return attribute?.let { findDirectChild(it, "value") }
            }
        }
        return null
    }

    /** `34.5N/84.2W`: the positive numbers JavaScript writes, then the hemispheres (a zero is north and east). */
    fun formatCoordinate(lat: Double, lon: Double): String =
        "${JsNumber.toText(abs(lat))}${if (lat >= 0) "N" else "S"}/${JsNumber.toText(abs(lon))}${if (lon >= 0) "E" else "W"}"

    // -- Writing ---------------------------------------------------------------------------------------------------

    /**
     * The document as text with exactly one XML declaration, [decl], and a byte order mark in front if asked (the mission's XML parts carry one,
     * the GPX does not). Written by hand rather than through a transformer, which sorts attributes and reflows the text: the document must come out
     * as it went in, apart from what the writer changed.
     */
    fun serialize(doc: Document, decl: String, bom: Boolean): String {
        val out = StringBuilder()
        if (bom) out.append(BOM)
        out.append(decl)
        write(doc.documentElement, out)
        return out.toString()
    }

    private fun write(node: Node, out: StringBuilder) {
        when (node) {
            is Element -> {
                out.append('<').append(node.tagName)
                val attributes = node.attributes
                for (i in 0 until attributes.length) {
                    val a = attributes.item(i)
                    out.append(' ').append(a.nodeName).append("=\"").append(escapeAttribute(a.nodeValue)).append('"')
                }
                if (node.firstChild == null) {
                    out.append("/>")
                } else {
                    out.append('>')
                    var child: Node? = node.firstChild
                    while (child != null) {
                        write(child, out)
                        child = child.nextSibling
                    }
                    out.append("</").append(node.tagName).append('>')
                }
            }
            is Text -> out.append(if (node.nodeType == Node.CDATA_SECTION_NODE) "<![CDATA[${node.data}]]>" else escapeText(node.data))
            is Comment -> out.append("<!--").append(node.data).append("-->")
            is ProcessingInstruction -> out.append("<?").append(node.target).append(' ').append(node.data).append("?>")
            else -> Unit
        }
    }

    private fun escapeText(text: String): String = buildString {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '\r' -> append("&#13;")
            else -> append(c)
        }
    }

    private fun escapeAttribute(text: String): String = buildString {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\t' -> append("&#9;")
            '\n' -> append("&#10;")
            '\r' -> append("&#13;")
            else -> append(c)
        }
    }
}
