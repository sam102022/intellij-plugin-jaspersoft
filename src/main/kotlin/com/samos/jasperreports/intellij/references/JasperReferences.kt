package com.samos.jasperreports.intellij.references

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.XmlPatterns
import com.intellij.pom.references.PomService
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.XmlElementFactory
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
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

/**
 * `$F{nom}` dans le texte d'une balise d'expression. La référence est portée par la balise (XmlText ne consulte
 * pas les fournisseurs de références) ; [rangeInElement] couvre le seul nom, dans le texte de la balise.
 */
class JasperSymbolReference(tag: XmlTag, range: TextRange, val kind: SymbolKind, val name: String) :
    PsiReferenceBase<XmlTag>(tag, range, true) {

    override fun resolve(): PsiElement? {
        val file = element.containingFile
        val symbols = JrxmlSymbols.of(file) ?: return null
        if (kind == SymbolKind.RESOURCE) return ResourceBundles.findProperty(file, symbols.resourceBundle, name)
        return symbols.resolve(kind, name, PsiTagNode(element))?.let(::declarationTarget)
    }

    /** La complétion passe par JasperCompletionContributor, qui marche aussi avant la fermeture de l'accolade. */
    override fun getVariants(): Array<Any> = emptyArray()

    override fun handleElementRename(newElementName: String): PsiElement = replaceInTag(element, rangeInElement, newElementName)
}

/** Remplace une portion du texte d'une balise, dans le XmlText qui la contient. */
fun replaceInTag(tag: XmlTag, range: TextRange, replacement: String): PsiElement {
    val tagStart = tag.textRange.startOffset
    for (text in tag.value.textElements) {
        val textRange = text.textRange.shiftLeft(tagStart)
        if (textRange.contains(range)) {
            replaceInText(text, range.shiftLeft(textRange.startOffset), replacement)
            return tag
        }
    }
    return tag
}

/**
 * Remplace une portion du texte physique d'un XmlText. Le manipulateur standard travaille sur le texte affiché
 * et perdrait la section CDATA : on reconstruit le texte et on remplace le nœud.
 */
fun replaceInText(text: XmlText, range: TextRange, replacement: String): PsiElement {
    // Cas courant : le nom est dans un seul jeton (contenu CDATA ou texte) ; on remplace ce jeton tel quel, sans
    // reformatage, ce qui garde la section CDATA et la mise en page de l'expression.
    val leaf = text.findElementAt(range.startOffset)
    if (leaf is LeafPsiElement) {
        val leafRange = leaf.textRange.shiftLeft(text.textRange.startOffset)
        if (leafRange.contains(range)) {
            val old = leaf.text
            val start = range.startOffset - leafRange.startOffset
            leaf.replaceWithText(old.substring(0, start) + replacement + old.substring(start + range.length))
            return text
        }
    }
    val old = text.text
    val updated = old.substring(0, range.startOffset) + replacement + old.substring(range.endOffset)
    val holder = XmlElementFactory.getInstance(text.project).createTagFromText("<e>$updated</e>")
    val node = holder.value.textElements.firstOrNull() ?: return text
    return text.replace(node)
}

/** EF-36 : `"Detail.jasper"` dans une expression désigne le .jrxml de même nom (à côté du rapport, puis dans le module). */
class JasperSubreportReference(tag: XmlTag, range: TextRange, val baseName: String) :
    PsiReferenceBase<XmlTag>(tag, range, true) {

    override fun resolve(): PsiElement? = SubreportFiles.findSource(element.containingFile, baseName)

    override fun getVariants(): Array<Any> = emptyArray()

    /** Renommer Detail.jrxml en Summary.jrxml écrit "Summary.jasper". */
    override fun handleElementRename(newElementName: String): PsiElement =
        replaceInTag(element, rangeInElement, newElementName.removeSuffix(".jrxml").removeSuffix(".jasper"))

    override fun bindToElement(target: PsiElement): PsiElement {
        val file = target as? PsiFile ?: return element
        return handleElementRename(file.name)
    }
}

object SubreportFiles {
    fun findSource(file: PsiFile, baseName: String): PsiFile? = find(file, "$baseName.jrxml")

    fun findCompiled(file: PsiFile, baseName: String): PsiFile? = find(file, "$baseName.jasper")

    private fun find(file: PsiFile, fileName: String): PsiFile? {
        val origin = file.originalFile
        val manager = PsiManager.getInstance(file.project)
        origin.virtualFile?.parent?.findChild(fileName)?.let { found -> manager.findFile(found)?.let { return it } }
        val module = ModuleUtilCore.findModuleForPsiElement(origin)
        val scope = module?.let { GlobalSearchScope.moduleScope(it) } ?: GlobalSearchScope.projectScope(file.project)
        return FilenameIndex.getVirtualFilesByName(fileName, scope).firstNotNullOfOrNull { manager.findFile(it) }
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
        registrar.registerReferenceProvider(XmlPatterns.xmlTag(), ExpressionReferenceProvider())
        registrar.registerReferenceProvider(
            XmlPatterns.xmlAttributeValue().withLocalName(*ATTRIBUTE_KINDS.keys.toTypedArray()),
            AttributeReferenceProvider(),
        )
    }

    private class ExpressionReferenceProvider : PsiReferenceProvider() {
        override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
            val tag = element as? XmlTag ?: return PsiReference.EMPTY_ARRAY
            if (!ExpressionSyntax.isExpressionTag(tag.localName)) return PsiReference.EMPTY_ARRAY
            if (JrxmlSymbols.of(tag.containingFile) == null) return PsiReference.EMPTY_ARRAY
            val tagStart = tag.textRange.startOffset
            val references = mutableListOf<PsiReference>()
            for (text in tag.value.textElements) {
                val base = text.textRange.startOffset - tagStart
                val physical = text.text
                ExpressionSyntax.occurrences(physical).forEach {
                    references += JasperSymbolReference(tag, TextRange(base + it.nameStart, base + it.nameEnd), it.kind, it.name)
                }
                if (tag.localName != "query") {
                    ExpressionSyntax.subreportLiterals(physical).forEach {
                        references += JasperSubreportReference(tag, TextRange(base + it.baseStart, base + it.baseEnd), it.baseName)
                    }
                }
            }
            return references.toTypedArray()
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
