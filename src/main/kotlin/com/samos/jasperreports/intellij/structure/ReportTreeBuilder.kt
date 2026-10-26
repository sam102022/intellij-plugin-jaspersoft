package com.samos.jasperreports.intellij.structure

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.TextRange
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.util.xml.DomElement
import com.intellij.util.xml.DomManager
import com.samos.jasperreports.intellij.JasperBundle
import com.samos.jasperreports.intellij.JasperIcons
import com.samos.jasperreports.intellij.dom.Band
import com.samos.jasperreports.intellij.dom.ElementKind
import com.samos.jasperreports.intellij.dom.Group
import com.samos.jasperreports.intellij.dom.JasperReport
import com.samos.jasperreports.intellij.dom.ReportElement
import com.samos.jasperreports.intellij.dom.Section
import javax.swing.Icon

/** Nœud de l'arborescence (EF-10). Construit en lecture, hors du thread graphique. */
data class StructureNode(
    val label: String,
    val detail: String?,
    val icon: Icon?,
    val pointer: SmartPsiElementPointer<XmlTag>?,
    /** Position de la balise au moment de la construction, pour la synchronisation curseur → nœud (EF-12). */
    val range: TextRange?,
    val children: List<StructureNode> = emptyList(),
    val hasError: Boolean = false,
) {
    override fun toString(): String = label
}

/** Construit l'arborescence à partir du modèle DOM, dans l'ordre où le moteur imprime les sections. */
object ReportTreeBuilder {

    private const val EXPRESSION_PREVIEW = 40

    fun build(file: XmlFile): StructureNode? {
        val report = DomManager.getDomManager(file.project).getFileElement(file, JasperReport::class.java)?.rootElement
            ?: return null

        val declarations = listOfNotNull(
            folder(JasperBundle.message("structure.imports"), report.getImports().map { leaf(it, it.stringValue.orEmpty(), null, AllIcons.Nodes.Package) }),
            folder(JasperBundle.message("structure.styles"), report.getStyles().map {
                leaf(it, it.getName().stringValue ?: "style", it.getParentStyle().stringValue?.let { p -> "← $p" }, AllIcons.Nodes.Enum)
            }),
            folder(JasperBundle.message("structure.parameters"), report.getParameters().map {
                leaf(it, it.getName().stringValue ?: "?", simpleClass(it.getValueClass().stringValue), AllIcons.Nodes.Parameter)
            }),
            folder(JasperBundle.message("structure.fields"), report.getFields().map {
                leaf(it, it.getName().stringValue ?: "?", simpleClass(it.getValueClass().stringValue), AllIcons.Nodes.Field)
            }),
            folder(JasperBundle.message("structure.variables"), report.getVariables().map {
                leaf(it, it.getName().stringValue ?: "?", it.getCalculation().stringValue ?: simpleClass(it.getValueClass().stringValue), AllIcons.Nodes.Variable)
            }),
            report.getQuery().takeIf { it.exists() }?.let { leaf(it, "query", it.getLanguage().stringValue, AllIcons.Nodes.DataTables) },
        )

        val sections = mutableListOf<StructureNode>()
        sections += listOfNotNull(
            band("background", report.getBackground()),
            band("title", report.getTitle()),
            band("pageHeader", report.getPageHeader()),
            band("columnHeader", report.getColumnHeader()),
        )
        report.getGroups().forEach { sections += group(it) }
        section("detail", report.getDetail())?.let { sections += it }
        sections += listOfNotNull(
            band("columnFooter", report.getColumnFooter()),
            band("pageFooter", report.getPageFooter()),
            band("lastPageFooter", report.getLastPageFooter()),
            band("summary", report.getSummary()),
            band("noData", report.getNoData()),
        )

        return node(
            report,
            report.getName().stringValue ?: file.name,
            "${report.getPageWidth().stringValue ?: "?"} × ${report.getPageHeight().stringValue ?: "?"}",
            JasperIcons.REPORT,
            declarations + sections,
        )
    }

    private fun group(group: Group): StructureNode = node(
        group,
        "group ${group.getName().stringValue ?: "?"}",
        expressionPreview(group.getExpression().stringValue),
        AllIcons.Nodes.Folder,
        listOfNotNull(section("groupHeader", group.getGroupHeader()), section("groupFooter", group.getGroupFooter())),
    )

    private fun section(name: String, section: Section): StructureNode? {
        if (!section.exists()) return null
        val bands = section.getBands().mapIndexed { i, band -> bandNode("band ${i + 1}", band) }
        return node(section, name, null, AllIcons.Nodes.Folder, bands)
    }

    private fun band(name: String, band: Band): StructureNode? = if (band.exists()) bandNode(name, band) else null

    private fun bandNode(label: String, band: Band): StructureNode =
        node(band, label, band.getHeight().stringValue?.let { "$it px" }, AllIcons.Nodes.Tag, band.getElements().map(::element))

    private fun element(element: ReportElement): StructureNode {
        val kindName = element.getKind().stringValue
        val kind = ElementKind.of(kindName)
        val detail = when (kind) {
            ElementKind.TEXT_FIELD, ElementKind.IMAGE, ElementKind.SUBREPORT -> expressionPreview(element.getExpression().stringValue)
            ElementKind.STATIC_TEXT -> expressionPreview(element.getText().stringValue)
            else -> null
        } ?: element.getKey().stringValue
        val children = if (kind == ElementKind.FRAME || kind == ElementKind.ELEMENT_GROUP) element.getElements().map(::element) else emptyList()
        return node(element, kindName ?: "element", detail, iconFor(kind), children, hasError = kind == null)
    }

    private fun iconFor(kind: ElementKind?): Icon = when (kind) {
        ElementKind.TEXT_FIELD, ElementKind.STATIC_TEXT -> AllIcons.FileTypes.Text
        ElementKind.IMAGE -> AllIcons.FileTypes.Image
        ElementKind.SUBREPORT -> JasperIcons.REPORT
        ElementKind.FRAME, ElementKind.ELEMENT_GROUP -> AllIcons.Nodes.Folder
        ElementKind.CHART -> AllIcons.Nodes.DataSchema
        ElementKind.CROSSTAB, ElementKind.COMPONENT, ElementKind.GENERIC -> AllIcons.Nodes.Plugin
        ElementKind.LINE, ElementKind.RECTANGLE, ElementKind.ELLIPSE, ElementKind.BREAK -> AllIcons.Nodes.Property
        null -> AllIcons.General.Warning
    }

    private fun folder(label: String, children: List<StructureNode>): StructureNode? =
        if (children.isEmpty()) null else StructureNode(label, children.size.toString(), AllIcons.Nodes.Folder, null, null, children)

    private fun leaf(dom: DomElement, label: String, detail: String?, icon: Icon) = node(dom, label, detail, icon, emptyList())

    private fun node(dom: DomElement, label: String, detail: String?, icon: Icon?, children: List<StructureNode>, hasError: Boolean = false): StructureNode {
        val tag = dom.xmlTag
        return StructureNode(
            label = label,
            detail = detail,
            icon = icon,
            pointer = tag?.let { SmartPointerManager.createPointer(it) },
            range = tag?.textRange,
            children = children,
            hasError = hasError,
        )
    }

    private fun simpleClass(name: String?): String? = name?.substringAfterLast('.')

    private fun expressionPreview(text: String?): String? {
        val oneLine = text?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() } ?: return null
        return if (oneLine.length <= EXPRESSION_PREVIEW) oneLine else oneLine.take(EXPRESSION_PREVIEW - 1) + "…"
    }
}
