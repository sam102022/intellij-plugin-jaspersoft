package com.samos.jasperreports.intellij.structure

import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.samos.jasperreports.intellij.inspections.DeclarationInserter
import com.samos.jasperreports.intellij.references.JrxmlSymbols
import com.samos.jasperreports.intellij.references.PsiTagNode
import com.samos.jasperreports.intellij.symbols.ExpressionSyntax
import com.samos.jasperreports.intellij.symbols.SymbolKind
import com.samos.jasperreports.intellij.symbols.TagNode
import java.util.UUID

/**
 * Modifications de structure depuis l'arborescence (EF-15, EF-16). Le texte est inséré, déplacé ou supprimé
 * dans le document, avec l'indentation des lignes voisines : le reste du fichier n'est pas reformaté (EF-28).
 * Chaque fonction s'appelle dans une commande d'écriture ; elle valide le document avant de rendre la main.
 */
object StructureEditing {

    /** Éléments simples proposés par « Add » (EF-15). */
    enum class NewElement(val kind: String, val body: String) {
        STATIC_TEXT("staticText", "<text><![CDATA[Static Text]]></text>"),
        TEXT_FIELD("textField", "<expression><![CDATA[\"\"]]></expression>"),
        IMAGE("image", "<expression><![CDATA[\"\"]]></expression>"),
        RECTANGLE("rectangle", ""),
        ELLIPSE("ellipse", ""),
        LINE("line", ""),
        FRAME("frame", ""),
    }

    private val SINGLE_BAND_SECTIONS = setOf("background", "title", "pageHeader", "columnHeader", "columnFooter",
        "pageFooter", "lastPageFooter", "summary", "noData")
    private val MULTI_BAND_SECTIONS = setOf("detail", "groupHeader", "groupFooter")

    // --- Où ajouter ------------------------------------------------------------------------------------------------

    /** Conteneur d'un nouvel élément : la bande ou le cadre sélectionné, ou celui qui contient l'élément sélectionné. */
    fun elementContainer(selected: XmlTag): XmlTag? = when {
        selected.localName == "element" && selected.getAttributeValue("kind") == "frame" -> selected
        selected.localName == "element" -> selected.parentTag?.let { if (it.localName == "element" || isBand(it)) it else null }
        isBand(selected) -> selected
        selected.localName in MULTI_BAND_SECTIONS -> selected.findSubTags("band").lastOrNull()
        else -> null
    }

    /** Une nouvelle bande s'ajoute après la bande sélectionnée, ou à la fin de la section detail / groupHeader / groupFooter. */
    fun bandTarget(selected: XmlTag): XmlTag? = when {
        selected.localName == "band" && selected.parentTag?.localName in MULTI_BAND_SECTIONS -> selected
        selected.localName in MULTI_BAND_SECTIONS -> selected
        else -> null
    }

    private fun isBand(tag: XmlTag) = tag.localName == "band" || (tag.localName in SINGLE_BAND_SECTIONS && tag.parentTag?.parentTag == null)

    // --- Ajouts ----------------------------------------------------------------------------------------------------

    fun addElement(container: XmlTag, element: NewElement): XmlTag? {
        val body = element.body
        val xml = "<element kind=\"${element.kind}\" uuid=\"${UUID.randomUUID()}\" x=\"0\" y=\"0\" width=\"100\" height=\"20\"" +
            if (body.isEmpty()) "/>" else ">$body</element>"
        return appendChild(container, xml)
    }

    fun addBand(target: XmlTag): XmlTag? {
        val xml = "<band height=\"30\"/>"
        return if (target.localName == "band") insertAfter(target, xml) else appendChild(target, xml)
    }

    /** Paramètre, champ, variable ou groupe au nom libre, à sa place dans l'ordre des balises. */
    fun addDeclaration(root: XmlTag, kind: SymbolKind): XmlTag {
        val base = kind.name.lowercase().replaceFirstChar { it.uppercase() }
        val existing = root.findSubTags(kind.tagName).mapNotNull { it.getAttributeValue("name") }.toSet()
        val name = generateSequence(1) { it + 1 }.map { "$base$it" }.first { it !in existing }
        val tag = DeclarationInserter.insert(root, kind.tagName, name)
        if (kind == SymbolKind.GROUP) tag.setAttribute("class", null)
        return tag
    }

    // --- Dupliquer, déplacer, supprimer ----------------------------------------------------------------------------

    /** Copie placée juste après : nouveaux uuid, et nom libre pour une déclaration. */
    fun duplicate(tag: XmlTag): XmlTag? {
        var text = tag.text.replace(Regex("uuid=\"[^\"]*\"")) { "uuid=\"${UUID.randomUUID()}\"" }
        val name = tag.getAttributeValue("name")
        if (name != null && tag.localName in setOf("parameter", "field", "variable", "group", "style", "dataset")) {
            val existing = tag.parentTag?.findSubTags(tag.localName)?.mapNotNull { it.getAttributeValue("name") }?.toSet().orEmpty()
            val copy = generateSequence(2) { it + 1 }.map { "${name}_$it" }.first { it !in existing }
            text = text.replaceFirst("name=\"$name\"", "name=\"$copy\"")
        }
        return insertAfter(tag, text)
    }

    /** Sœur précédente ou suivante de même nom de balise (ordre d'impression des éléments, des bandes…). */
    fun sibling(tag: XmlTag, up: Boolean): XmlTag? {
        val siblings = tag.parentTag?.subTags?.filter { it.localName == tag.localName } ?: return null
        val index = siblings.indexOf(tag)
        return siblings.getOrNull(if (up) index - 1 else index + 1)
    }

    /** Échange la balise avec sa voisine : le texte entre les deux (commentaires, autres balises) ne bouge pas. */
    fun move(tag: XmlTag, up: Boolean): Boolean {
        val other = sibling(tag, up) ?: return false
        val (first, second) = if (up) other to tag else tag to other
        val document = document(tag) ?: return false
        val start = first.textRange.startOffset
        val end = second.textRange.endOffset
        val between = document.charsSequence.subSequence(first.textRange.endOffset, second.textRange.startOffset)
        document.replaceString(start, end, second.text + between + first.text)
        commit(document, tag)
        return true
    }

    /** Supprime la balise et sa ligne quand elle est seule sur la ligne. */
    fun delete(tag: XmlTag) {
        val document = document(tag) ?: run { tag.delete(); return }
        val chars = document.charsSequence
        var start = tag.textRange.startOffset
        val end = tag.textRange.endOffset
        var lineStart = start
        while (lineStart > 0 && (chars[lineStart - 1] == ' ' || chars[lineStart - 1] == '\t')) lineStart--
        if (lineStart > 0 && chars[lineStart - 1] == '\n') {
            start = lineStart - 1
            if (start > 0 && chars[start - 1] == '\r') start--
        }
        document.deleteString(start, end)
        commit(document, tag)
    }

    // --- Usages (EF-16) --------------------------------------------------------------------------------------------

    /** Expressions et attributs du fichier qui utilisent la déclaration : une ligne lisible par usage. */
    fun usages(declaration: XmlTag): List<String> {
        val kind = when (declaration.localName) {
            "parameter" -> SymbolKind.PARAMETER
            "field" -> SymbolKind.FIELD
            "variable" -> SymbolKind.VARIABLE
            "group" -> SymbolKind.GROUP
            "style" -> SymbolKind.STYLE
            else -> return emptyList()
        }
        val name = declaration.getAttributeValue("name") ?: return emptyList()
        val symbols = JrxmlSymbols.of(declaration.containingFile) ?: return emptyList()
        val result = mutableListOf<String>()
        for ((tag, occurrences) in symbols.expressionOccurrences()) {
            val used = occurrences.any {
                (it.kind == kind && it.name == name) || (kind == SymbolKind.GROUP && it.kind == SymbolKind.VARIABLE && it.name == "${name}_COUNT")
            }
            if (used) result += describe(tag) + ": " + tag.textContent.trim().removePrefix("<![CDATA[").removeSuffix("]]>").trim()
        }
        val attributes = when (kind) {
            SymbolKind.GROUP -> setOf("resetGroup", "incrementGroup", "evaluationGroup", "printWhenGroupChanges")
            SymbolKind.STYLE -> setOf("style")
            else -> emptySet()
        }
        if (attributes.isNotEmpty()) {
            fun visit(tag: TagNode) {
                attributes.filter { tag.attribute(it) == name && (tag as? PsiTagNode)?.tag != declaration }
                    .forEach { result += describe(tag) + ": $it=\"$name\"" }
                tag.subTags.forEach(::visit)
            }
            visit(symbols.root)
        }
        return result
    }

    private fun describe(tag: TagNode): String {
        val owner = generateSequence(tag) { it.parentTag }.firstOrNull { !ExpressionSyntax.isExpressionTag(it.tagName) } ?: tag
        return owner.attribute("kind")?.let { "${owner.tagName} $it" } ?: owner.attribute("name")?.let { "${owner.tagName} $it" } ?: owner.tagName
    }

    // --- Texte -----------------------------------------------------------------------------------------------------

    private fun document(tag: XmlTag): Document? {
        val manager = PsiDocumentManager.getInstance(tag.project)
        val document = manager.getDocument(tag.containingFile) ?: return null
        manager.doPostponedOperationsAndUnblockDocument(document)
        return document
    }

    private fun commit(document: Document, tag: XmlTag) = PsiDocumentManager.getInstance(tag.project).commitDocument(document)

    private fun indentAt(document: Document, offset: Int): String {
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        return document.charsSequence.subSequence(lineStart, offset).takeWhile { it == ' ' || it == '\t' }.toString()
    }

    /** Unité d'indentation du fichier : tabulation (Jaspersoft Studio) ou espaces. */
    private fun indentUnit(document: Document): String {
        val line = document.charsSequence.lineSequence().firstOrNull { it.isNotEmpty() && (it[0] == '\t' || it[0] == ' ') }
        return if (line == null || line[0] == '\t') "\t" else line.takeWhile { it == ' ' }
    }

    private fun tagAt(file: PsiFile, offset: Int): XmlTag? {
        return PsiTreeUtil.getParentOfType(file.findElementAt(offset), XmlTag::class.java, false)
    }

    fun insertAfter(anchor: XmlTag, xml: String): XmlTag? {
        val file = anchor.containingFile
        val document = document(anchor) ?: return null
        val offset = anchor.textRange.endOffset
        val text = "\n" + indentAt(document, anchor.textRange.startOffset) + xml
        document.insertString(offset, text)
        commit(document, anchor)
        return tagAt(file, offset + text.length - xml.length)
    }

    /** Ajoute une balise enfant en dernière position, y compris dans une balise vide `<band height="20"/>`. */
    fun appendChild(parent: XmlTag, xml: String): XmlTag? {
        val last = parent.subTags.lastOrNull()
        if (last != null) return insertAfter(last, xml)
        val file = parent.containingFile
        val document = document(parent) ?: return null
        val indent = indentAt(document, parent.textRange.startOffset)
        val childIndent = indent + indentUnit(document)
        val range = parent.textRange
        val text = parent.text
        val start: Int
        val replacement: String
        val emptyEnd = Regex("\\s*/>$").find(text)
        if (emptyEnd != null) {
            start = range.startOffset + emptyEnd.range.first
            replacement = ">\n$childIndent$xml\n$indent</${parent.name}>"
            document.replaceString(start, range.endOffset, replacement)
        } else {
            val value = parent.value.textRange
            start = value.startOffset
            replacement = "\n$childIndent$xml\n$indent"
            document.replaceString(value.startOffset, value.endOffset, replacement)
        }
        commit(document, parent)
        return tagAt(file, start + replacement.indexOf(xml))
    }
}
