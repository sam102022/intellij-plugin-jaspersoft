package com.samos.jasperreports.intellij.properties

import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag

/**
 * Lecture et écriture des propriétés dans le XML (EF-23, EF-25, EF-28). L'écriture se fait dans une action
 * d'écriture : seuls l'attribut ou la balise concernés changent, le reste du fichier (ordre des attributs,
 * indentation, commentaires) n'est pas touché. Une valeur vide supprime l'attribut ou la balise enfant.
 */
object PropertyAccess {

    fun read(tag: XmlTag, def: PropertyDef): String? = when (def.type) {
        PropertyType.EXPRESSION, PropertyType.CHILD_TEXT -> tag.findFirstSubTag(def.name)?.value?.trimmedText
        PropertyType.OWN_TEXT -> tag.value.trimmedText
        else -> tag.getAttributeValue(def.name)
    }

    fun write(tag: XmlTag, def: PropertyDef, value: String?) {
        val text = value?.let { if (def.isAttribute) it.trim() else it.trim('\n', '\r') }?.takeIf { it.isNotBlank() }
        when (def.type) {
            PropertyType.EXPRESSION, PropertyType.CHILD_TEXT -> {
                val child = tag.findFirstSubTag(def.name)
                if (text == null) {
                    child?.delete()
                    return
                }
                if (child != null) {
                    child.value.setEscapedText(PropertySchema.cdata(text))
                } else {
                    appendChild(tag, "<${def.name}>${PropertySchema.cdata(text)}</${def.name}>")
                }
            }
            PropertyType.OWN_TEXT -> tag.value.setEscapedText(text?.let(PropertySchema::cdata).orEmpty())
            else -> tag.setAttribute(def.name, text)
        }
    }

    /**
     * Ajoute une balise enfant après la dernière, avec la même indentation, en passant par le document : le
     * formateur XML, appliqué aux nœuds créés par le PSI, couperait la section CDATA (EF-28).
     */
    private fun appendChild(tag: XmlTag, xml: String) {
        val last = tag.subTags.lastOrNull()
        val manager = PsiDocumentManager.getInstance(tag.project)
        val document = manager.getDocument(tag.containingFile)
        if (last == null || document == null) {
            tag.addSubTag(tag.createChildTag(xml.substringAfter('<').substringBefore('>'), tag.namespace, xml.substringAfter('>').substringBeforeLast("</"), false), false)
            return
        }
        manager.doPostponedOperationsAndUnblockDocument(document)
        val start = last.textRange.startOffset
        val lineStart = document.getLineStartOffset(document.getLineNumber(start))
        val indent = document.charsSequence.subSequence(lineStart, start).takeWhile { it == ' ' || it == '\t' }
        document.insertString(last.textRange.endOffset, "\n$indent$xml")
        manager.commitDocument(document)
    }

    /** EF-26 : attributs présents dans le XML mais inconnus du panneau. */
    fun unknownAttributes(tag: XmlTag, definitions: List<PropertyDef>): List<XmlAttribute> {
        val known = PropertySchema.knownAttributes(definitions)
        return tag.attributes.filter { it.localName !in known }
    }

    /** EF-25 : propriétés personnalisées `<property name value/>`, dans l'ordre du fichier. */
    fun customProperties(tag: XmlTag): List<Pair<String, String>> =
        tag.findSubTags("property").map { (it.getAttributeValue("name").orEmpty()) to (it.getAttributeValue("value").orEmpty()) }

    /** Remplace les propriétés personnalisées par [properties] : mises à jour sur place, ajouts après la dernière. */
    fun writeCustomProperties(tag: XmlTag, properties: List<Pair<String, String>>) {
        val existing = tag.findSubTags("property").toMutableList()
        properties.filter { it.first.isNotBlank() }.forEachIndexed { index, (name, value) ->
            val current = existing.getOrNull(index)
            if (current != null) {
                if (current.getAttributeValue("name") != name) current.setAttribute("name", name)
                if (current.getAttributeValue("value") != value) current.setAttribute("value", value)
            } else {
                val created = tag.createChildTag("property", tag.namespace, null, false)
                created.setAttribute("name", name)
                created.setAttribute("value", value)
                val anchor = tag.findSubTags("property").lastOrNull()
                val first = tag.subTags.firstOrNull()
                when {
                    anchor != null -> tag.addAfter(created, anchor)
                    first != null -> tag.addBefore(created, first)
                    else -> tag.addSubTag(created, false)
                }
            }
        }
        existing.drop(properties.count { it.first.isNotBlank() }).forEach { it.delete() }
    }
}
