package com.samos.jasperreports.intellij.symbols

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** TagNode sur le DOM W3C, pour tester l'analyse sans IntelliJ. */
class W3cTagNode(private val element: Element) : TagNode {
    override val tagName: String get() = element.tagName
    override fun attribute(name: String): String? = if (element.hasAttribute(name)) element.getAttribute(name) else null
    override val parentTag: TagNode? get() = (element.parentNode as? Element)?.let(::W3cTagNode)
    override val subTags: List<TagNode>
        get() = (0 until element.childNodes.length).map { element.childNodes.item(it) }
            .filterIsInstance<Element>().map(::W3cTagNode)
    override val textContent: String
        get() = (0 until element.childNodes.length).map { element.childNodes.item(it) }
            .filter { it.nodeType == Node.TEXT_NODE || it.nodeType == Node.CDATA_SECTION_NODE }
            .joinToString("") { it.nodeValue }

    override fun equals(other: Any?): Boolean = other is W3cTagNode && other.element === element
    override fun hashCode(): Int = System.identityHashCode(element)

    companion object {
        fun parse(text: String): TagNode = W3cTagNode(builder().parse(text.byteInputStream()).documentElement)
        fun parse(file: File): TagNode = W3cTagNode(builder().parse(file).documentElement)

        private fun builder() = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder()
    }
}
