package com.samos.jasperreports.intellij.references

import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.samos.jasperreports.intellij.format.JrxmlFormat
import com.samos.jasperreports.intellij.format.JrxmlFormatDetector
import com.samos.jasperreports.intellij.symbols.ReportSymbols
import com.samos.jasperreports.intellij.symbols.TagNode

/** TagNode sur le PSI XML. */
class PsiTagNode(val tag: XmlTag) : TagNode {
    override val tagName: String get() = tag.localName
    override fun attribute(name: String): String? = tag.getAttributeValue(name)
    override val parentTag: TagNode? get() = tag.parentTag?.let(::PsiTagNode)
    override val subTags: List<TagNode> get() = tag.subTags.map(::PsiTagNode)
    override val textContent: String get() = tag.value.text

    override fun equals(other: Any?): Boolean = other is PsiTagNode && other.tag == tag
    override fun hashCode(): Int = tag.hashCode()
}

val TagNode.xmlTag: XmlTag get() = (this as PsiTagNode).tag

/** Symboles d'un .jrxml au format 7, recalculés à chaque modification du fichier ; null pour tout autre fichier. */
object JrxmlSymbols {
    fun of(file: PsiFile?): ReportSymbols? {
        val xml = file as? XmlFile ?: return null
        if (!xml.name.endsWith(".jrxml", ignoreCase = true)) return null
        return CachedValuesManager.getCachedValue(xml) {
            val root = xml.rootTag
            val symbols = if (root != null && JrxmlFormatDetector.detect(xml) == JrxmlFormat.V7) {
                ReportSymbols.of(PsiTagNode(root))
            } else {
                null
            }
            CachedValueProvider.Result.create(symbols, xml)
        }
    }
}
