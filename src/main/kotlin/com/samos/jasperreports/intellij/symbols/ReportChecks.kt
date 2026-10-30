package com.samos.jasperreports.intellij.symbols

/**
 * Vérifications du rapport (section 8, EF-40 à EF-45), indépendantes d'IntelliJ. Chaque problème désigne la
 * balise concernée ; le plugin le place sur l'attribut `name` ou sur le nom de la balise.
 */
object ReportChecks {

    class Problem(val tag: TagNode, val key: String, vararg val args: Any) {
        override fun toString(): String = "$key ${args.toList()} @ ${tag.tagName}"
    }

    /** EF-41 : nom déclaré deux fois dans la même portée (le moteur refuse de compiler). */
    fun duplicates(symbols: ReportSymbols): List<Problem> = buildList {
        for (scope in symbols.allScopes()) {
            scope.declarations.groupBy { it.kind to it.name }.values.filter { it.size > 1 }.forEach { group ->
                group.drop(1).forEach { duplicate ->
                    add(Problem(duplicate.tag, "inspection.duplicate.message", kindLabel(duplicate.kind), duplicate.name))
                }
            }
        }
    }

    /**
     * EF-45 : paramètre, champ ou variable jamais utilisé dans le fichier. Comptent comme usages : les
     * occurrences dans les expressions, `<sortField name>`, `returnValue toVariable`, les paramètres passés par
     * nom à un sous-jeu de données (`datasetRun/parameter name`) et les noms passés en attribut.
     */
    fun unused(symbols: ReportSymbols): List<Problem> {
        val used = HashMap<SymbolKind, MutableSet<String>>()
        for ((_, occurrences) in symbols.expressionOccurrences()) {
            occurrences.forEach { used.getOrPut(it.kind) { HashSet() } += it.name }
        }
        fun visit(tag: TagNode) {
            when (tag.tagName) {
                "sortField" -> tag.attribute("name")?.let {
                    val kind = if (tag.attribute("type") == "Variable") SymbolKind.VARIABLE else SymbolKind.FIELD
                    used.getOrPut(kind) { HashSet() } += it
                }
                "returnValue" -> {
                    tag.attribute("toVariable")?.let { used.getOrPut(SymbolKind.VARIABLE) { HashSet() } += it }
                    tag.attribute("subreportVariable")?.let { used.getOrPut(SymbolKind.VARIABLE) { HashSet() } += it }
                    tag.attribute("fromVariable")?.let { used.getOrPut(SymbolKind.VARIABLE) { HashSet() } += it }
                }
                "parameter" -> if (tag.parentTag?.tagName == "datasetRun") {
                    tag.attribute("name")?.let { used.getOrPut(SymbolKind.PARAMETER) { HashSet() } += it }
                }
            }
            tag.subTags.forEach(::visit)
        }
        visit(symbols.root)
        return buildList {
            for (scope in listOf(symbols.main) + symbols.datasets.values) {
                for (declaration in scope.declarations) {
                    if (declaration.kind !in UNUSED_KINDS) continue
                    if (used[declaration.kind]?.contains(declaration.name) == true) continue
                    add(Problem(declaration.tag, "inspection.unused.message", kindLabel(declaration.kind), declaration.name))
                }
            }
        }
    }

    private val UNUSED_KINDS = setOf(SymbolKind.PARAMETER, SymbolKind.FIELD, SymbolKind.VARIABLE)

    private val PAGE_SECTIONS = setOf("title", "pageHeader", "pageFooter", "lastPageFooter", "summary", "noData", "background")
    private val COLUMN_SECTIONS = setOf("columnHeader", "columnFooter")
    private val MULTI_BAND_SECTIONS = setOf("detail", "groupHeader", "groupFooter")

    /**
     * EF-42, EF-43 : éléments qui dépassent leur bande ou leur cadre, bandes et marges qui ne tiennent pas dans la
     * page (mêmes règles que JRVerifier, qui refuse alors de compiler).
     */
    fun geometry(symbols: ReportSymbols): List<Problem> = buildList {
        val root = symbols.root
        val pageWidth = root.int("pageWidth") ?: 595
        val pageHeight = root.int("pageHeight") ?: 842
        val left = root.int("leftMargin") ?: 20
        val right = root.int("rightMargin") ?: 20
        val top = root.int("topMargin") ?: 30
        val bottom = root.int("bottomMargin") ?: 30
        val columnCount = root.int("columnCount") ?: 1
        val columnWidth = root.int("columnWidth") ?: (pageWidth - left - right)
        val columnSpacing = root.int("columnSpacing") ?: 0
        val usableHeight = pageHeight - top - bottom

        if (left + columnCount * columnWidth + (columnCount - 1) * columnSpacing + right > pageWidth) {
            add(Problem(root, "inspection.geometry.columns", columnCount, columnWidth, pageWidth))
        }

        fun height(section: String): Int = root.subTags.firstOrNull { it.tagName == section }?.int("height") ?: 0
        val headersAndFooters = height("pageHeader") + height("columnHeader") + height("columnFooter") + height("pageFooter")
        if (headersAndFooters > usableHeight) {
            add(Problem(root, "inspection.geometry.headersFooters", headersAndFooters, usableHeight))
        }

        // Seule la hauteur est vérifiée, comme JRVerifier : un élément plus large que sa colonne est permis
        // (rapports multicolonnes, Studio) et l'avertir rendrait l'inspection bruyante.
        fun checkElements(container: TagNode, availableHeight: Int, label: String) {
            for (element in container.subTags.filter { it.tagName == "element" }) {
                val y = element.int("y") ?: 0
                val height = element.int("height") ?: 0
                if (y + height > availableHeight) {
                    add(Problem(element, "inspection.geometry.elementHeight", y, height, label, availableHeight))
                }
                if (element.attribute("kind") == "frame") {
                    checkElements(element, height, "frame")
                }
            }
        }

        fun checkBand(band: TagNode, section: String) {
            val height = band.int("height") ?: 0
            if (height > usableHeight) {
                add(Problem(band, "inspection.geometry.bandHeight", section, height, usableHeight))
            }
            checkElements(band, height, section)
        }

        for (section in root.subTags) {
            when (section.tagName) {
                in PAGE_SECTIONS, in COLUMN_SECTIONS -> checkBand(section, section.tagName)
                "detail" -> section.subTags.filter { it.tagName == "band" }.forEach { checkBand(it, "detail") }
                "group" -> section.subTags.filter { it.tagName in MULTI_BAND_SECTIONS }.forEach { part ->
                    part.subTags.filter { it.tagName == "band" }.forEach { checkBand(it, part.tagName) }
                }
            }
        }
    }

    /** EF-44 : classes Java déclarées (attributs `class` et `nestedType`), à vérifier dans le module. */
    fun declaredClasses(symbols: ReportSymbols): List<Pair<TagNode, String>> = buildList {
        fun visit(tag: TagNode) {
            if (tag.tagName in CLASS_TAGS) {
                tag.attribute("class")?.let { add(tag to it) }
                if (tag.tagName == "parameter") tag.attribute("nestedType")?.let { add(tag to it) }
            }
            tag.subTags.forEach(::visit)
        }
        visit(symbols.root)
    }

    private val CLASS_TAGS = setOf("parameter", "field", "variable", "bucket", "measure", "scriptlet")

    /** Nom de classe à chercher : sans paramètres de type ni tableau, classes internes en notation pointée. */
    fun classToFind(declared: String): String? {
        val name = declared.substringBefore('<').removeSuffix("[]").removeSuffix("[]").trim().replace('$', '.')
        if (name.isEmpty() || name in PRIMITIVES || name.contains(' ')) return null
        return name
    }

    private val PRIMITIVES = setOf("boolean", "byte", "char", "short", "int", "long", "float", "double")

    fun kindLabel(kind: SymbolKind): String = when (kind) {
        SymbolKind.PARAMETER -> "Parameter"
        SymbolKind.FIELD -> "Field"
        SymbolKind.VARIABLE -> "Variable"
        SymbolKind.RESOURCE -> "Resource key"
        SymbolKind.GROUP -> "Group"
        SymbolKind.STYLE -> "Style"
        SymbolKind.DATASET -> "Dataset"
    }

    private fun TagNode.int(name: String): Int? = attribute(name)?.trim()?.toDoubleOrNull()?.toInt()
}
