package com.samos.jasperreports.intellij.dom

import com.intellij.util.xml.Attribute
import com.intellij.util.xml.DomElement
import com.intellij.util.xml.GenericAttributeValue
import com.intellij.util.xml.GenericDomValue
import com.intellij.util.xml.NameValue
import com.intellij.util.xml.SubTag
import com.intellij.util.xml.SubTagList

/*
 * Modèle DOM du format JRXML 7 (section 10.2).
 *
 * Ces interfaces sont des vues sur le PSI XML : lire getHeight() lit l'attribut, le modifier écrit dans le
 * fichier (Undo/Redo natif). Les balises et attributs absents de ces interfaces ne sont jamais touchés.
 * Noms de balises et d'attributs relevés dans JRReport, JRBand, JRSection et JRChild (7.1.0).
 */

/** Contenu texte d'une balise d'expression (`<expression><![CDATA[…]]></expression>`). */
interface Expression : GenericDomValue<String>

interface ReportProperty : DomElement {
    @Attribute("name") fun getName(): GenericAttributeValue<String>
    @Attribute("value") fun getValue(): GenericAttributeValue<String>
}

interface JasperReport : DomElement {
    @NameValue
    @Attribute("name") fun getName(): GenericAttributeValue<String>
    @Attribute("language") fun getLanguage(): GenericAttributeValue<String>
    @Attribute("columnCount") fun getColumnCount(): GenericAttributeValue<Int>
    @Attribute("printOrder") fun getPrintOrder(): GenericAttributeValue<String>
    @Attribute("pageWidth") fun getPageWidth(): GenericAttributeValue<Int>
    @Attribute("pageHeight") fun getPageHeight(): GenericAttributeValue<Int>
    @Attribute("orientation") fun getOrientation(): GenericAttributeValue<String>
    @Attribute("whenNoDataType") fun getWhenNoDataType(): GenericAttributeValue<String>
    @Attribute("columnWidth") fun getColumnWidth(): GenericAttributeValue<Int>
    @Attribute("columnSpacing") fun getColumnSpacing(): GenericAttributeValue<Int>
    @Attribute("leftMargin") fun getLeftMargin(): GenericAttributeValue<Int>
    @Attribute("rightMargin") fun getRightMargin(): GenericAttributeValue<Int>
    @Attribute("topMargin") fun getTopMargin(): GenericAttributeValue<Int>
    @Attribute("bottomMargin") fun getBottomMargin(): GenericAttributeValue<Int>
    @Attribute("resourceBundle") fun getResourceBundle(): GenericAttributeValue<String>
    @Attribute("whenResourceMissingType") fun getWhenResourceMissingType(): GenericAttributeValue<String>
    @Attribute("uuid") fun getUuid(): GenericAttributeValue<String>

    @SubTagList("property") fun getProperties(): List<ReportProperty>
    @SubTagList("import") fun getImports(): List<GenericDomValue<String>>
    @SubTagList("template") fun getTemplates(): List<GenericDomValue<String>>
    @SubTagList("style") fun getStyles(): List<Style>
    @SubTagList("dataset") fun getDatasets(): List<Dataset>
    @SubTagList("parameter") fun getParameters(): List<Parameter>
    @SubTag("query") fun getQuery(): Query
    @SubTagList("field") fun getFields(): List<Field>
    @SubTagList("variable") fun getVariables(): List<Variable>
    @SubTagList("group") fun getGroups(): List<Group>

    // Sections à bande unique : la section est la bande (`<title height="25">…</title>`).
    @SubTag("background") fun getBackground(): Band
    @SubTag("title") fun getTitle(): Band
    @SubTag("pageHeader") fun getPageHeader(): Band
    @SubTag("columnHeader") fun getColumnHeader(): Band
    // Section à plusieurs bandes.
    @SubTag("detail") fun getDetail(): Section
    @SubTag("columnFooter") fun getColumnFooter(): Band
    @SubTag("pageFooter") fun getPageFooter(): Band
    @SubTag("lastPageFooter") fun getLastPageFooter(): Band
    @SubTag("summary") fun getSummary(): Band
    @SubTag("noData") fun getNoData(): Band
}

/** Sous-jeu de données (`<dataset name>` sous la racine), utilisé par les tables, listes, crosstabs et graphiques. */
interface Dataset : DomElement {
    @NameValue
    @Attribute("name") fun getName(): GenericAttributeValue<String>
    @SubTagList("parameter") fun getParameters(): List<Parameter>
    @SubTag("query") fun getQuery(): Query
    @SubTagList("field") fun getFields(): List<Field>
    @SubTagList("variable") fun getVariables(): List<Variable>
    @SubTagList("group") fun getGroups(): List<Group>
}

interface Style : DomElement {
    @NameValue
    @Attribute("name") fun getName(): GenericAttributeValue<String>
    /** Style parent (héritage). */
    @Attribute("style") fun getParentStyle(): GenericAttributeValue<String>
    @Attribute("default") fun getDefault(): GenericAttributeValue<Boolean>
    @Attribute("mode") fun getMode(): GenericAttributeValue<String>
    @Attribute("forecolor") fun getForecolor(): GenericAttributeValue<String>
    @Attribute("backcolor") fun getBackcolor(): GenericAttributeValue<String>
    @Attribute("hTextAlign") fun getHTextAlign(): GenericAttributeValue<String>
    @Attribute("vTextAlign") fun getVTextAlign(): GenericAttributeValue<String>
    @Attribute("fontName") fun getFontName(): GenericAttributeValue<String>
    @Attribute("fontSize") fun getFontSize(): GenericAttributeValue<String>
    @Attribute("bold") fun getBold(): GenericAttributeValue<Boolean>
    @Attribute("italic") fun getItalic(): GenericAttributeValue<Boolean>
    @SubTagList("conditionalStyle") fun getConditionalStyles(): List<ConditionalStyle>
}

interface ConditionalStyle : DomElement {
    @SubTag("conditionExpression") fun getConditionExpression(): Expression
}

interface Parameter : DomElement {
    @NameValue
    @Attribute("name") fun getName(): GenericAttributeValue<String>
    @Attribute("class") fun getValueClass(): GenericAttributeValue<String>
    @Attribute("forPrompting") fun getForPrompting(): GenericAttributeValue<Boolean>
    @Attribute("nestedType") fun getNestedType(): GenericAttributeValue<String>
    @SubTag("description") fun getDescription(): GenericDomValue<String>
    @SubTag("defaultValueExpression") fun getDefaultValueExpression(): Expression
    @SubTagList("property") fun getProperties(): List<ReportProperty>
}

interface Query : GenericDomValue<String> {
    @Attribute("language") fun getLanguage(): GenericAttributeValue<String>
}

interface Field : DomElement {
    @NameValue
    @Attribute("name") fun getName(): GenericAttributeValue<String>
    @Attribute("class") fun getValueClass(): GenericAttributeValue<String>
    @SubTag("description") fun getDescription(): GenericDomValue<String>
    @SubTagList("property") fun getProperties(): List<ReportProperty>
}

interface Variable : DomElement {
    @NameValue
    @Attribute("name") fun getName(): GenericAttributeValue<String>
    @Attribute("class") fun getValueClass(): GenericAttributeValue<String>
    @Attribute("calculation") fun getCalculation(): GenericAttributeValue<String>
    @Attribute("resetType") fun getResetType(): GenericAttributeValue<String>
    @Attribute("resetGroup") fun getResetGroup(): GenericAttributeValue<String>
    @Attribute("incrementType") fun getIncrementType(): GenericAttributeValue<String>
    @Attribute("incrementGroup") fun getIncrementGroup(): GenericAttributeValue<String>
    @SubTag("expression") fun getExpression(): Expression
    @SubTag("initialValueExpression") fun getInitialValueExpression(): Expression
}

interface Group : DomElement {
    @NameValue
    @Attribute("name") fun getName(): GenericAttributeValue<String>
    @Attribute("minHeightToStartNewPage") fun getMinHeightToStartNewPage(): GenericAttributeValue<Int>
    @Attribute("startNewPage") fun getStartNewPage(): GenericAttributeValue<Boolean>
    @Attribute("startNewColumn") fun getStartNewColumn(): GenericAttributeValue<Boolean>
    @Attribute("reprintHeaderOnEachPage") fun getReprintHeaderOnEachPage(): GenericAttributeValue<Boolean>
    @SubTag("expression") fun getExpression(): Expression
    @SubTag("groupHeader") fun getGroupHeader(): Section
    @SubTag("groupFooter") fun getGroupFooter(): Section
}

/** `detail`, `groupHeader`, `groupFooter` : une liste de bandes. */
interface Section : DomElement {
    @SubTagList("band") fun getBands(): List<Band>
}

interface Band : DomElement {
    @Attribute("height") fun getHeight(): GenericAttributeValue<Int>
    @Attribute("splitType") fun getSplitType(): GenericAttributeValue<String>
    @SubTag("printWhenExpression") fun getPrintWhenExpression(): Expression
    @SubTagList("property") fun getProperties(): List<ReportProperty>
    @SubTagList("element") fun getElements(): List<ReportElement>
}

/**
 * `<element kind="…">`. Un seul type DOM pour toutes les natures : le `kind` choisit le libellé, l'icône
 * et, plus tard, la vue de propriétés. Les enfants spécifiques (chart, crosstab, component) restent opaques.
 */
interface ReportElement : DomElement {
    @Attribute("kind") fun getKind(): GenericAttributeValue<String>
    @Attribute("uuid") fun getUuid(): GenericAttributeValue<String>
    @Attribute("key") fun getKey(): GenericAttributeValue<String>
    @Attribute("x") fun getX(): GenericAttributeValue<Int>
    @Attribute("y") fun getY(): GenericAttributeValue<Int>
    @Attribute("width") fun getWidth(): GenericAttributeValue<Int>
    @Attribute("height") fun getHeight(): GenericAttributeValue<Int>
    @Attribute("style") fun getStyle(): GenericAttributeValue<String>
    @Attribute("mode") fun getMode(): GenericAttributeValue<String>
    @Attribute("positionType") fun getPositionType(): GenericAttributeValue<String>
    @Attribute("stretchType") fun getStretchType(): GenericAttributeValue<String>
    @Attribute("forecolor") fun getForecolor(): GenericAttributeValue<String>
    @Attribute("backcolor") fun getBackcolor(): GenericAttributeValue<String>
    @Attribute("evaluationTime") fun getEvaluationTime(): GenericAttributeValue<String>
    @SubTag("expression") fun getExpression(): Expression
    @SubTag("printWhenExpression") fun getPrintWhenExpression(): Expression
    @SubTag("dataSourceExpression") fun getDataSourceExpression(): Expression
    /** Texte d'un `staticText`. */
    @SubTag("text") fun getText(): GenericDomValue<String>
    @SubTagList("property") fun getProperties(): List<ReportProperty>
    /** Enfants d'un `frame` ou d'un `elementGroup`. */
    @SubTagList("element") fun getElements(): List<ReportElement>
}

/** Les 13 natures déclarées par `JRChild` (7.1.0), plus `chart` (extension jasperreports-charts). */
enum class ElementKind(val xmlName: String) {
    STATIC_TEXT("staticText"), TEXT_FIELD("textField"), LINE("line"), RECTANGLE("rectangle"),
    ELLIPSE("ellipse"), IMAGE("image"), ELEMENT_GROUP("elementGroup"), FRAME("frame"),
    CROSSTAB("crosstab"), SUBREPORT("subreport"), GENERIC("generic"), BREAK("break"),
    COMPONENT("component"), CHART("chart");

    /** Natures dont le contenu n'est édité qu'en XML en v1 (section 3). */
    val isOpaque: Boolean get() = this == CROSSTAB || this == COMPONENT || this == CHART || this == GENERIC

    companion object {
        fun of(xmlName: String?): ElementKind? = entries.firstOrNull { it.xmlName == xmlName }
    }
}
