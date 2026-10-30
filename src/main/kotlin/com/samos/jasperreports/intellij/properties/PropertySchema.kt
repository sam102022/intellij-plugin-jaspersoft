package com.samos.jasperreports.intellij.properties

/*
 * Propriétés éditables par nature de balise (EF-20, EF-21, EF-23). Indépendant d'IntelliJ.
 * Noms et valeurs relevés dans le code source de JasperReports 7.1.0 (énumérations de net.sf.jasperreports.engine.type)
 * et vérifiés sur les 201 rapports de test.
 */

enum class PropertyCategory { GENERAL, POSITION, APPEARANCE, TEXT, EXPRESSIONS, ADVANCED }

enum class PropertyType {
    INT, DECIMAL, BOOLEAN, ENUM, COLOR, TEXT, CLASS, STYLE, GROUP,
    /** Balise enfant contenant une expression (`<expression><![CDATA[…]]></expression>`). */
    EXPRESSION,
    /** Balise enfant contenant du texte (`<text>`, `<description>`). */
    CHILD_TEXT,
    /** Texte de la balise elle-même (`<query>`). */
    OWN_TEXT,
}

data class PropertyDef(
    val name: String,
    val type: PropertyType,
    val category: PropertyCategory,
    /** Valeur appliquée par le moteur quand l'attribut est absent (affichée en gris, jamais écrite). */
    val default: String? = null,
    val values: List<String> = emptyList(),
    /** Absent = valeur du style (ou du style parent), et non une valeur fixe. */
    val inherited: Boolean = false,
    val required: Boolean = false,
    val min: Double? = null,
) {
    val isAttribute: Boolean get() = type != PropertyType.EXPRESSION && type != PropertyType.CHILD_TEXT && type != PropertyType.OWN_TEXT
}

object PropertySchema {

    // Énumérations de JasperReports 7 (net.sf.jasperreports.engine.type).
    val POSITION_TYPE = listOf("Float", "FixRelativeToTop", "FixRelativeToBottom")
    val STRETCH_TYPE = listOf("NoStretch", "ElementGroupHeight", "ElementGroupBottom", "ContainerHeight", "ContainerBottom")
    val MODE = listOf("Opaque", "Transparent")
    val H_TEXT_ALIGN = listOf("Left", "Center", "Right", "Justified")
    val V_TEXT_ALIGN = listOf("Top", "Middle", "Bottom", "Justified")
    val ROTATION = listOf("None", "Left", "Right", "UpsideDown")
    val EVALUATION_TIME = listOf("Now", "Report", "Page", "Column", "Group", "Band", "Auto", "Master")
    val SPLIT_TYPE = listOf("Stretch", "Prevent", "Immediate")
    val WHEN_NO_DATA = listOf("NoPages", "BlankPage", "AllSectionsNoDetail", "NoDataSection")
    val ORIENTATION = listOf("Portrait", "Landscape")
    val PRINT_ORDER = listOf("Vertical", "Horizontal")
    val CALCULATION = listOf("Nothing", "Count", "DistinctCount", "Sum", "Average", "Lowest", "Highest",
        "StandardDeviation", "Variance", "System", "First")
    val RESET_TYPE = listOf("None", "Report", "Page", "Column", "Group", "Master")
    val INCREMENT_TYPE = listOf("None", "Report", "Page", "Column", "Group")
    val SCALE_IMAGE = listOf("Clip", "FillFrame", "RetainShape", "RealHeight", "RealSize")
    val H_IMAGE_ALIGN = listOf("Left", "Center", "Right")
    val V_IMAGE_ALIGN = listOf("Top", "Middle", "Bottom")
    val ON_ERROR = listOf("Error", "Blank", "Icon")
    val LINE_DIRECTION = listOf("TopDown", "BottomUp")
    val TEXT_ADJUST = listOf("CutText", "StretchHeight", "ScaleFont")
    val WHEN_RESOURCE_MISSING = listOf("Null", "Empty", "Key", "Error")
    val SECTION_TYPE = listOf("Band", "Part")
    val BREAK_TYPE = listOf("Page", "Column")
    val FOOTER_POSITION = listOf("Normal", "StackAtBottom", "ForceAtBottom", "CollateAtBottom")
    val MARKUP = listOf("none", "styled", "html", "rtf")
    val LANGUAGE = listOf("java", "groovy", "javascript")
    val HYPERLINK_TYPE = listOf("None", "Reference", "LocalAnchor", "LocalPage", "RemoteAnchor", "RemotePage")
    val HYPERLINK_TARGET = listOf("Self", "Blank", "Parent", "Top")

    private class Builder {
        val list = mutableListOf<PropertyDef>()
        var category = PropertyCategory.GENERAL
        fun int(name: String, default: String? = null, required: Boolean = false, min: Double? = null, inherited: Boolean = false) {
            list += PropertyDef(name, PropertyType.INT, category, default, required = required, min = min, inherited = inherited)
        }
        fun decimal(name: String, default: String? = null, inherited: Boolean = false, min: Double? = null) {
            list += PropertyDef(name, PropertyType.DECIMAL, category, default, inherited = inherited, min = min)
        }
        fun bool(name: String, default: String? = null, inherited: Boolean = false) {
            list += PropertyDef(name, PropertyType.BOOLEAN, category, default, listOf("true", "false"), inherited)
        }
        fun enum(name: String, values: List<String>, default: String? = null, inherited: Boolean = false) {
            list += PropertyDef(name, PropertyType.ENUM, category, default, values, inherited)
        }
        fun color(name: String) { list += PropertyDef(name, PropertyType.COLOR, category, inherited = true) }
        fun text(name: String, default: String? = null, required: Boolean = false, inherited: Boolean = false) {
            list += PropertyDef(name, PropertyType.TEXT, category, default, required = required, inherited = inherited)
        }
        fun className(name: String, default: String? = null) { list += PropertyDef(name, PropertyType.CLASS, category, default) }
        fun style(name: String = "style") { list += PropertyDef(name, PropertyType.STYLE, category) }
        fun group(name: String) { list += PropertyDef(name, PropertyType.GROUP, category) }
        fun expression(name: String) { list += PropertyDef(name, PropertyType.EXPRESSION, PropertyCategory.EXPRESSIONS) }
        fun childText(name: String) { list += PropertyDef(name, PropertyType.CHILD_TEXT, category) }
        fun ownText(name: String) { list += PropertyDef(name, PropertyType.OWN_TEXT, category) }
    }

    private fun build(block: Builder.() -> Unit): List<PropertyDef> = Builder().apply(block).list

    /** Attributs de police et d'alignement, communs aux éléments texte et aux styles (hérités du style). */
    private fun Builder.textStyle() {
        category = PropertyCategory.TEXT
        text("fontName", inherited = true)
        decimal("fontSize", inherited = true, min = 0.0)
        bool("bold", inherited = true)
        bool("italic", inherited = true)
        bool("underline", inherited = true)
        bool("strikeThrough", inherited = true)
        enum("hTextAlign", H_TEXT_ALIGN, inherited = true)
        enum("vTextAlign", V_TEXT_ALIGN, inherited = true)
        enum("rotation", ROTATION, inherited = true)
        list += PropertyDef("markup", PropertyType.ENUM, category, values = MARKUP, inherited = true)
    }

    private val ELEMENT_COMMON = build {
        category = PropertyCategory.GENERAL
        text("key")
        style()
        enum("mode", MODE, inherited = true)
        category = PropertyCategory.POSITION
        int("x", "0")
        int("y", "0")
        int("width", "0", min = 0.0)
        int("height", "0", min = 0.0)
        enum("positionType", POSITION_TYPE, "FixRelativeToTop")
        enum("stretchType", STRETCH_TYPE, "NoStretch")
        bool("removeLineWhenBlank", "false")
        bool("printInFirstWholeBand", "false")
        bool("printWhenDetailOverflows", "false")
        bool("printRepeatedValues", "true")
        group("printWhenGroupChanges")
        category = PropertyCategory.APPEARANCE
        color("forecolor")
        color("backcolor")
        expression("printWhenExpression")
        category = PropertyCategory.ADVANCED
        text("uuid")
    }

    private val TEXT_FIELD = build {
        textStyle()
        enum("textAdjust", TEXT_ADJUST, "CutText")
        text("pattern", inherited = true)
        bool("blankWhenNull", "false", inherited = true)
        category = PropertyCategory.GENERAL
        enum("evaluationTime", EVALUATION_TIME, "Now")
        group("evaluationGroup")
        expression("expression")
        expression("patternExpression")
        expression("anchorNameExpression")
        expression("hyperlinkReferenceExpression")
        expression("hyperlinkTooltipExpression")
        category = PropertyCategory.ADVANCED
        enum("linkType", HYPERLINK_TYPE, "None")
        enum("linkTarget", HYPERLINK_TARGET, "Self")
        int("bookmarkLevel", "0", min = 0.0)
    }

    private val STATIC_TEXT = build {
        category = PropertyCategory.TEXT
        childText("text")
        textStyle()
    }

    private val IMAGE = build {
        category = PropertyCategory.APPEARANCE
        enum("scaleImage", SCALE_IMAGE, inherited = true)
        enum("hImageAlign", H_IMAGE_ALIGN, inherited = true)
        enum("vImageAlign", V_IMAGE_ALIGN, inherited = true)
        enum("onErrorType", ON_ERROR, "Error")
        category = PropertyCategory.GENERAL
        enum("evaluationTime", EVALUATION_TIME, "Now")
        group("evaluationGroup")
        expression("expression")
        expression("hyperlinkReferenceExpression")
        expression("hyperlinkTooltipExpression")
        category = PropertyCategory.ADVANCED
        bool("lazy", "false")
        bool("usingCache")
        enum("linkType", HYPERLINK_TYPE, "None")
        enum("linkTarget", HYPERLINK_TARGET, "Self")
    }

    private val SUBREPORT = build {
        category = PropertyCategory.GENERAL
        bool("runToBottom")
        bool("usingCache")
        enum("overflowType", listOf("Stretch", "NoStretch"))
        expression("expression")
        expression("dataSourceExpression")
        expression("connectionExpression")
        expression("parametersMapExpression")
    }

    private val REPORT = build {
        category = PropertyCategory.GENERAL
        text("name", required = true)
        enum("language", LANGUAGE, "java")
        text("resourceBundle")
        enum("whenNoDataType", WHEN_NO_DATA, "NoPages")
        enum("whenResourceMissingType", WHEN_RESOURCE_MISSING, "Null")
        className("scriptletClass")
        category = PropertyCategory.POSITION
        int("pageWidth", "595", min = 0.0)
        int("pageHeight", "842", min = 0.0)
        enum("orientation", ORIENTATION, "Portrait")
        int("columnCount", "1", min = 1.0)
        enum("printOrder", PRINT_ORDER, "Vertical")
        int("columnWidth", "555", min = 0.0)
        int("columnSpacing", "0", min = 0.0)
        int("leftMargin", "20", min = 0.0)
        int("rightMargin", "20", min = 0.0)
        int("topMargin", "30", min = 0.0)
        int("bottomMargin", "30", min = 0.0)
        expression("filterExpression")
        category = PropertyCategory.ADVANCED
        bool("titleNewPage", "false")
        bool("summaryNewPage", "false")
        bool("summaryWithPageHeaderAndFooter", "false")
        bool("floatColumnFooter", "false")
        bool("ignorePagination", "false")
        enum("sectionType", SECTION_TYPE, "Band")
        className("formatFactoryClass")
        text("uuid")
    }

    private val BAND = build {
        category = PropertyCategory.GENERAL
        int("height", "0", required = true, min = 0.0)
        enum("splitType", SPLIT_TYPE)
        expression("printWhenExpression")
    }

    private val GROUP = build {
        category = PropertyCategory.GENERAL
        text("name", required = true)
        int("minHeightToStartNewPage", "0", min = 0.0)
        int("minDetailsToStartFromTop", "0", min = 0.0)
        bool("startNewColumn", "false")
        bool("startNewPage", "false")
        bool("resetPageNumber", "false")
        bool("reprintHeaderOnEachPage", "false")
        bool("keepTogether", "false")
        bool("preventOrphanFooter", "false")
        enum("footerPosition", FOOTER_POSITION, "Normal")
        expression("expression")
    }

    private val PARAMETER = build {
        category = PropertyCategory.GENERAL
        text("name", required = true)
        className("class", "java.lang.String")
        bool("forPrompting", "true")
        className("nestedType")
        childText("description")
        expression("defaultValueExpression")
    }

    private val FIELD = build {
        category = PropertyCategory.GENERAL
        text("name", required = true)
        className("class", "java.lang.String")
        childText("description")
    }

    private val VARIABLE = build {
        category = PropertyCategory.GENERAL
        text("name", required = true)
        className("class", "java.lang.String")
        enum("calculation", CALCULATION, "Nothing")
        enum("resetType", RESET_TYPE, "Report")
        group("resetGroup")
        enum("incrementType", INCREMENT_TYPE, "None")
        group("incrementGroup")
        expression("expression")
        expression("initialValueExpression")
        category = PropertyCategory.ADVANCED
        className("incrementerFactoryClass")
    }

    private val STYLE = build {
        category = PropertyCategory.GENERAL
        text("name", required = true)
        style("style")
        bool("default", "false")
        enum("mode", MODE, inherited = true)
        category = PropertyCategory.APPEARANCE
        color("forecolor")
        color("backcolor")
        int("radius", inherited = true, min = 0.0)
        enum("scaleImage", SCALE_IMAGE, inherited = true)
        enum("hImageAlign", H_IMAGE_ALIGN, inherited = true)
        enum("vImageAlign", V_IMAGE_ALIGN, inherited = true)
        textStyle()
        text("pattern", inherited = true)
        bool("blankWhenNull", inherited = true)
    }

    private val QUERY = build {
        category = PropertyCategory.GENERAL
        text("language", "sql")
        ownText("query")
    }

    private val SINGLE_BAND_SECTIONS = setOf("background", "title", "pageHeader", "columnHeader", "columnFooter",
        "pageFooter", "lastPageFooter", "summary", "noData", "band")

    /** Natures d'élément dont le contenu n'est éditable qu'en XML (section 3) : seules les propriétés communes. */
    val OPAQUE_KINDS = setOf("crosstab", "component", "chart", "generic")

    /** Clé de schéma d'une balise : `element:textField`, `band`, `parameter`… ; null si la balise n'a pas de propriétés. */
    fun keyOf(tagName: String, kind: String?): String? = when {
        tagName == "element" -> "element:${kind ?: ""}"
        tagName in SINGLE_BAND_SECTIONS -> "band"
        tagName in setOf("jasperReport", "group", "parameter", "field", "variable", "style", "query") -> tagName
        else -> null
    }

    fun forKey(key: String): List<PropertyDef>? = when (key) {
        "jasperReport" -> REPORT
        "band" -> BAND
        "group" -> GROUP
        "parameter" -> PARAMETER
        "field" -> FIELD
        "variable" -> VARIABLE
        "style" -> STYLE
        "query" -> QUERY
        else -> if (key.startsWith("element:")) {
            val kind = key.removePrefix("element:")
            ELEMENT_COMMON.merge(when (kind) {
                "textField" -> TEXT_FIELD
                "staticText" -> STATIC_TEXT
                "image" -> IMAGE
                "subreport" -> SUBREPORT
                "line" -> build { category = PropertyCategory.APPEARANCE; enum("direction", LINE_DIRECTION, "TopDown") }
                "rectangle" -> build { category = PropertyCategory.APPEARANCE; int("radius", inherited = true, min = 0.0) }
                "break" -> build { category = PropertyCategory.GENERAL; enum("type", BREAK_TYPE, "Page") }
                else -> emptyList()
            })
        } else {
            null
        }
    }

    /** Les propriétés propres remplacent les communes de même nom, puis tri par catégorie (ordre stable). */
    private fun List<PropertyDef>.merge(specific: List<PropertyDef>): List<PropertyDef> {
        val names = specific.map { it.name }.toSet()
        return (filter { it.name !in names } + specific).sortedBy { it.category.ordinal }
    }

    fun forTag(tagName: String, kind: String?): List<PropertyDef>? = keyOf(tagName, kind)?.let(::forKey)

    /** Attributs reconnus pour cette balise (les autres sont listés en lecture seule, EF-26). */
    fun knownAttributes(definitions: List<PropertyDef>): Set<String> =
        definitions.filter { it.isAttribute }.map { it.name }.toSet() + "kind"

    private val INTEGER = Regex("-?\\d+")
    private val DECIMAL_NUMBER = Regex("-?\\d+(\\.\\d+)?")
    private val HEX_COLOR = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{3}|[0-9a-fA-F]{8})")
    private val COLOR_NAME = Regex("[a-zA-Z]+")
    private val CLASS_NAME = Regex("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*(<.*>)?(\\[])*")

    /**
     * EF-24 : message d'erreur (clé du bundle et argument) si la valeur ne convient pas, null sinon.
     * Une valeur vide est toujours acceptée (= valeur par défaut), sauf pour une propriété obligatoire.
     */
    fun validate(def: PropertyDef, value: String?): Pair<String, String>? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return if (def.required) "properties.error.required" to def.name else null
        val error = when (def.type) {
            PropertyType.INT -> if (!INTEGER.matches(text)) "properties.error.integer" else null
            PropertyType.DECIMAL -> if (!DECIMAL_NUMBER.matches(text)) "properties.error.number" else null
            PropertyType.BOOLEAN, PropertyType.ENUM ->
                if (def.values.isNotEmpty() && text !in def.values && def.name != "markup") "properties.error.value" else null
            PropertyType.COLOR -> if (!HEX_COLOR.matches(text) && !COLOR_NAME.matches(text)) "properties.error.color" else null
            PropertyType.CLASS -> if (!CLASS_NAME.matches(text)) "properties.error.class" else null
            else -> null
        }
        if (error != null) return error to text
        val min = def.min
        if (min != null && text.toDoubleOrNull()?.let { it < min } == true) return "properties.error.min" to min.toInt().toString()
        return null
    }

    /** Contenu CDATA sûr : une séquence `]]>` dans la valeur est coupée en deux sections. */
    fun cdata(value: String): String = "<![CDATA[" + value.replace("]]>", "]]]]><![CDATA[>") + "]]>"
}
