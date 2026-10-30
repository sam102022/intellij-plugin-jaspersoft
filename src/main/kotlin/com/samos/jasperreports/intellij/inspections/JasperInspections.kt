package com.samos.jasperreports.intellij.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.xml.util.XmlTagUtil
import com.samos.jasperreports.intellij.JasperBundle
import com.samos.jasperreports.intellij.references.JasperAttributeReference
import com.samos.jasperreports.intellij.references.JasperSymbolReference
import com.samos.jasperreports.intellij.references.JrxmlSymbols
import com.samos.jasperreports.intellij.references.PsiTagNode
import com.samos.jasperreports.intellij.references.ResourceBundles
import com.samos.jasperreports.intellij.references.xmlTag
import com.samos.jasperreports.intellij.symbols.ReportChecks
import com.samos.jasperreports.intellij.symbols.ReportSymbols
import com.samos.jasperreports.intellij.symbols.Scope
import com.samos.jasperreports.intellij.symbols.SymbolKind

/*
 * Inspections des rapports (section 8 de la spécification). Elles ne s'activent que sur les .jrxml au format 7.
 * Les vérifications globales (doublons, géométrie, classes, déclarations inutilisées) s'exécutent une fois, en
 * visitant la balise racine.
 */

/** Où placer un problème qui porte sur une balise : son attribut name, sinon son nom de balise. */
internal fun anchorOf(tag: XmlTag): PsiElement =
    tag.getAttribute("name")?.valueElement ?: XmlTagUtil.getStartTagNameElement(tag) ?: tag

internal fun message(problem: ReportChecks.Problem): String = JasperBundle.message(problem.key, *problem.args)

abstract class JasperReportInspection : LocalInspectionTool() {

    final override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val symbols = JrxmlSymbols.of(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                if (tag.parentTag == null) checkReport(symbols, tag, holder)
            }

            override fun visitXmlText(text: XmlText) = checkText(symbols, text, holder)

            override fun visitXmlAttributeValue(value: XmlAttributeValue) = checkAttributeValue(symbols, value, holder)
        }
    }

    protected open fun checkReport(symbols: ReportSymbols, root: XmlTag, holder: ProblemsHolder) {}
    protected open fun checkText(symbols: ReportSymbols, text: XmlText, holder: ProblemsHolder) {}
    protected open fun checkAttributeValue(symbols: ReportSymbols, value: XmlAttributeValue, holder: ProblemsHolder) {}
}

/** EF-34, EF-40 : $F{} $P{} $V{} $R{}, style, groupe ou sous-jeu de données inconnus. */
class JasperUnresolvedSymbolInspection : JasperReportInspection() {

    override fun checkText(symbols: ReportSymbols, text: XmlText, holder: ProblemsHolder) {
        val tag = text.parentTag ?: return
        for (reference in text.references.filterIsInstance<JasperSymbolReference>()) {
            if (reference.kind == SymbolKind.RESOURCE) {
                val bundle = symbols.resourceBundle ?: continue
                if (ResourceBundles.files(holder.file, bundle).isEmpty()) continue
                if (reference.resolve() == null) {
                    holder.registerProblemForReference(reference, ProblemHighlightType.LIKE_UNKNOWN_SYMBOL,
                        JasperBundle.message("inspection.unresolved.resource", reference.name, bundle))
                }
                continue
            }
            if (symbols.isUnknown(reference.kind, reference.name, PsiTagNode(tag))) {
                holder.registerProblemForReference(reference, ProblemHighlightType.LIKE_UNKNOWN_SYMBOL,
                    JasperBundle.message("inspection.unresolved.message", ReportChecks.kindLabel(reference.kind), reference.name),
                    CreateDeclarationFix(reference.kind, reference.name))
            }
        }
    }

    override fun checkAttributeValue(symbols: ReportSymbols, value: XmlAttributeValue, holder: ProblemsHolder) {
        for (reference in value.references.filterIsInstance<JasperAttributeReference>()) {
            val tag = PsiTreeUtil.getParentOfType(value, XmlTag::class.java) ?: continue
            val unknown = if (reference.kind == SymbolKind.DATASET) {
                symbols.resolve(SymbolKind.DATASET, reference.name, PsiTagNode(tag)) == null
            } else {
                symbols.isUnknown(reference.kind, reference.name, PsiTagNode(tag))
            }
            if (unknown) {
                holder.registerProblemForReference(reference, ProblemHighlightType.LIKE_UNKNOWN_SYMBOL,
                    JasperBundle.message("inspection.unresolved.message", ReportChecks.kindLabel(reference.kind), reference.name))
            }
        }
    }
}

/** EF-41 : nom déclaré deux fois dans la même portée. */
class JasperDuplicateDeclarationInspection : JasperReportInspection() {
    override fun checkReport(symbols: ReportSymbols, root: XmlTag, holder: ProblemsHolder) {
        ReportChecks.duplicates(symbols).forEach { holder.registerProblem(anchorOf(it.tag.xmlTag), message(it)) }
    }
}

/** EF-45 : paramètre, champ ou variable jamais utilisé. */
class JasperUnusedDeclarationInspection : JasperReportInspection() {
    override fun checkReport(symbols: ReportSymbols, root: XmlTag, holder: ProblemsHolder) {
        ReportChecks.unused(symbols).forEach {
            holder.registerProblem(anchorOf(it.tag.xmlTag), message(it), ProblemHighlightType.LIKE_UNUSED_SYMBOL)
        }
    }
}

/** EF-42, EF-43 : élément qui dépasse sa bande, bandes et marges trop hautes pour la page. */
class JasperLayoutInspection : JasperReportInspection() {
    override fun checkReport(symbols: ReportSymbols, root: XmlTag, holder: ProblemsHolder) {
        ReportChecks.geometry(symbols).forEach {
            val tag = it.tag.xmlTag
            holder.registerProblem(XmlTagUtil.getStartTagNameElement(tag) ?: tag, message(it))
        }
    }
}

/** EF-44 : classe Java déclarée introuvable dans le classpath du module (bibliothèques comprises). */
class JasperUnknownClassInspection : JasperReportInspection() {
    override fun checkReport(symbols: ReportSymbols, root: XmlTag, holder: ProblemsHolder) {
        val module = ModuleUtilCore.findModuleForPsiElement(holder.file) ?: return
        val scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, true)
        val facade = JavaPsiFacade.getInstance(holder.project)
        // Sans JDK configuré, tout serait signalé : on s'abstient.
        if (facade.findClass("java.lang.Object", scope) == null) return
        for ((node, declared) in ReportChecks.declaredClasses(symbols)) {
            val name = ReportChecks.classToFind(declared) ?: continue
            if (facade.findClass(name, scope) != null) continue
            val tag = node.xmlTag
            val attribute = if (tag.getAttributeValue("class") == declared) tag.getAttribute("class") else tag.getAttribute("nestedType")
            holder.registerProblem(attribute?.valueElement ?: anchorOf(tag),
                JasperBundle.message("inspection.unknownClass.message", declared), ProblemHighlightType.LIKE_UNKNOWN_SYMBOL)
        }
    }
}

/**
 * EF-34 : crée la déclaration manquante dans la portée de l'expression (rapport ou sous-jeu de données), à la place
 * imposée par l'ordre des balises du format 7, avec la classe java.lang.String à ajuster.
 */
class CreateDeclarationFix(private val kind: SymbolKind, private val name: String) : LocalQuickFix {

    override fun getFamilyName(): String = JasperBundle.message("quickfix.create.family")

    override fun getName(): String =
        JasperBundle.message("quickfix.create.name", ReportChecks.kindLabel(kind).lowercase(), name)

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val file = element.containingFile as? XmlFile ?: return
        val symbols = JrxmlSymbols.of(file) ?: return
        val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java, false) ?: return
        val scope = symbols.scopesAt(PsiTagNode(tag)).first { it.type != Scope.Type.CROSSTAB }
        DeclarationInserter.insert(scope.tag.xmlTag, kind.tagName, name)
    }
}

object DeclarationInserter {
    /** Ordre des balises de JRReport et JRDataset (@JsonPropertyOrder, 7.1.0), jusqu'aux sections. */
    private val ORDER = listOf(
        "property", "propertyExpression", "import", "template", "style", "dataset", "scriptlet",
        "parameter", "query", "field", "sortField", "variable", "filterExpression", "group",
    )

    fun insert(scope: XmlTag, tagName: String, name: String): XmlTag {
        val child = scope.createChildTag(tagName, scope.namespace, null, false)
        child.setAttribute("name", name)
        child.setAttribute("class", "java.lang.String")
        val subTags = scope.subTags
        val same = subTags.lastOrNull { it.localName == tagName }
        if (same != null) return scope.addAfter(child, same) as XmlTag
        val index = ORDER.indexOf(tagName)
        val next = subTags.firstOrNull { ORDER.indexOf(it.localName).let { i -> i == -1 || i > index } }
        return if (next != null) scope.addBefore(child, next) as XmlTag else scope.addSubTag(child, false)
    }
}
