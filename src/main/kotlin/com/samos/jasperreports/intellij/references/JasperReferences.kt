package com.samos.jasperreports.intellij.references

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.XmlPatterns
import com.intellij.pom.references.PomService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.XmlElementFactory
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlText
import com.intellij.util.ProcessingContext
import com.intellij.util.xml.DomManager
import com.intellij.util.xml.DomTarget
import com.samos.jasperreports.intellij.symbols.Declaration
import com.samos.jasperreports.intellij.symbols.ExpressionSyntax
import com.samos.jasperreports.intellij.symbols.SymbolKind
import javax.swing.Icon

/*
 * Références du rapport (section 7) : $F{} $P{} $V{} $R{} dans les expressions (EF-31 à EF-34, EF-38) et
 * attributs qui nomment un style, un groupe ou un sous-jeu de données (EF-35). Toutes sont « soft » : la
 * plateforme ne les souligne pas, l'inspection JasperUnresolvedSymbol s'en charge avec des règles prudentes.
 */

/** Cible PSI d'une déclaration : la cible DOM (renommage et Find Usages complets) ou, à défaut, l'attribut name. */
fun declarationTarget(declaration: Declaration): PsiElement? {
    val tag = declaration.tag.xmlTag
    val dom = DomManager.getDomManager(tag.project).getDomElement(tag)
    if (dom != null) {
        DomTarget.getTarget(dom)?.let { return PomService.convertToPsi(tag.project, it) }
    }
    return tag.getAttribute("name")?.valueElement ?: tag
}

fun symbolIcon(kind: SymbolKind): Icon = when (kind) {
    SymbolKind.PARAMETER -> AllIcons.Nodes.Parameter
    SymbolKind.FIELD -> AllIcons.Nodes.Field
    SymbolKind.VARIABLE -> AllIcons.Nodes.Variable
    SymbolKind.RESOURCE -> AllIcons.FileTypes.Properties
    SymbolKind.GROUP -> AllIcons.Nodes.Folder
    SymbolKind.STYLE -> AllIcons.Actions.Colors
    SymbolKind.DATASET -> AllIcons.Nodes.DataTables
}

/** `$F{nom}` dans le texte d'une balise d'expression ; [rangeInElement] couvre le seul nom. */
class JasperSymbolReference(text: XmlText, range: TextRange, val kind: SymbolKind, val name: String) :
    PsiReferenceBase<XmlText>(text, range, true) {

    override fun resolve(): PsiElement? {
        val file = element.containingFile
        val symbols = JrxmlSymbols.of(file) ?: return null
        if (kind == SymbolKind.RESOURCE) return ResourceBundles.findProperty(file, symbols.resourceBundle, name)
        val tag = element.parentTag ?: return null
        return symbols.resolve(kind, name, PsiTagNode(tag))?.let(::declarationTarget)
    }

    /** La complétion passe par JasperCompletionContributor, qui marche aussi avant la fermeture de l'accolade. */
    override fun getVariants(): Array<Any> = emptyArray()

    /**
     * Le manipulateur standard de XmlText travaille sur le texte affiché et perdrait la section CDATA : on
     * reconstruit le texte physique et on remplace le nœud.
     */
    override fun handleElementRename(newElementName: String): PsiElement {
        val old = element.text
        val updated = old.substring(0, rangeInElement.startOffset) + newElementName + old.substring(rangeInElement.endOffset)
        val holder = XmlElementFactory.getInstance(element.project).createTagFromText("<e>$updated</e>")
        val replacement = holder.value.textElements.firstOrNull() ?: return element
        return element.replace(replacement)
    }
}

/** Valeur d'attribut qui nomme un style, un groupe ou un sous-jeu de données. */
class JasperAttributeReference(value: XmlAttributeValue, val kind: SymbolKind) :
    PsiReferenceBase<XmlAttributeValue>(value, true) {

    val name: String get() = element.value

    override fun resolve(): PsiElement? {
        val symbols = JrxmlSymbols.of(element.containingFile) ?: return null
        val tag = (element.parent as? XmlAttribute)?.parent ?: return null
        return symbols.resolve(kind, name, PsiTagNode(tag))?.let(::declarationTarget)
    }

    override fun getVariants(): Array<Any> {
        val symbols = JrxmlSymbols.of(element.containingFile) ?: return emptyArray()
        val tag = (element.parent as? XmlAttribute)?.parent ?: return emptyArray()
        return symbols.completionNames(kind, PsiTagNode(tag)).keys
            .map { LookupElementBuilder.create(it).withIcon(symbolIcon(kind)) }
            .toTypedArray()
    }
}

class JasperReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(XmlText::class.java), ExpressionReferenceProvider())
        registrar.registerReferenceProvider(
            XmlPatterns.xmlAttributeValue().withLocalName(*ATTRIBUTE_KINDS.keys.toTypedArray()),
            AttributeReferenceProvider(),
        )
    }

    private class ExpressionReferenceProvider : PsiReferenceProvider() {
        override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
            val text = element as? XmlText ?: return PsiReference.EMPTY_ARRAY
            val tag = text.parentTag ?: return PsiReference.EMPTY_ARRAY
            if (!ExpressionSyntax.isExpressionTag(tag.localName)) return PsiReference.EMPTY_ARRAY
            if (JrxmlSymbols.of(text.containingFile) == null) return PsiReference.EMPTY_ARRAY
            return ExpressionSyntax.occurrences(text.text).map {
                JasperSymbolReference(text, TextRange(it.nameStart, it.nameEnd), it.kind, it.name)
            }.toTypedArray()
        }
    }

    private class AttributeReferenceProvider : PsiReferenceProvider() {
        override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
            val value = element as? XmlAttributeValue ?: return PsiReference.EMPTY_ARRAY
            val attribute = value.parent as? XmlAttribute ?: return PsiReference.EMPTY_ARRAY
            val kind = ATTRIBUTE_KINDS[attribute.localName] ?: return PsiReference.EMPTY_ARRAY
            if (value.value.isBlank() || JrxmlSymbols.of(value.containingFile) == null) return PsiReference.EMPTY_ARRAY
            return arrayOf(JasperAttributeReference(value, kind))
        }
    }

    companion object {
        val ATTRIBUTE_KINDS: Map<String, SymbolKind> = mapOf(
            "style" to SymbolKind.STYLE,
            "resetGroup" to SymbolKind.GROUP,
            "incrementGroup" to SymbolKind.GROUP,
            "evaluationGroup" to SymbolKind.GROUP,
            "printWhenGroupChanges" to SymbolKind.GROUP,
            "subDataset" to SymbolKind.DATASET,
        )
    }
}
