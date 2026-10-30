package com.samos.jasperreports.intellij.symbols

/*
 * Symboles d'un rapport JRXML 7 (section 7 de la spécification) : déclarations, portées et occurrences
 * $F{} $P{} $V{} $R{}. Ce fichier ne dépend pas d'IntelliJ : il travaille sur une vue minimale des balises
 * (TagNode), implémentée sur le PSI dans le plugin et sur le DOM W3C dans les tests.
 */

/** Vue minimale d'une balise XML. `equals`/`hashCode` doivent déléguer à la balise d'origine. */
interface TagNode {
    val tagName: String
    fun attribute(name: String): String?
    val parentTag: TagNode?
    val subTags: List<TagNode>
    /** Texte brut du contenu (sections CDATA comprises) : seules les occurrences de symboles y sont cherchées. */
    val textContent: String
}

enum class SymbolKind(val prefix: Char?, val tagName: String) {
    PARAMETER('P', "parameter"),
    FIELD('F', "field"),
    VARIABLE('V', "variable"),
    RESOURCE('R', ""),
    GROUP(null, "group"),
    STYLE(null, "style"),
    DATASET(null, "dataset");

    companion object {
        fun ofPrefix(prefix: Char): SymbolKind? = entries.firstOrNull { it.prefix == prefix }
    }
}

/** `$F{name}` trouvé dans un texte ; [nameStart] et [nameEnd] délimitent le nom dans ce texte. */
data class SymbolOccurrence(val kind: SymbolKind, val name: String, val nameStart: Int, val nameEnd: Int, val raw: Boolean = false) {
    /** Début de l'occurrence complète (`${'$'}`) et fin après l'accolade fermante. */
    val start: Int get() = nameStart - if (raw) 4 else 3
    val end: Int get() = nameEnd + 1
}

/** Chemin littéral d'un sous-rapport compilé (`"sub/Detail.jasper"`) ; [baseStart]..[baseEnd] couvrent `Detail`. */
data class SubreportLiteral(val baseName: String, val baseStart: Int, val baseEnd: Int, val literalStart: Int, val literalEnd: Int)

object ExpressionSyntax {
    /** `$P!{x}` insère le paramètre tel quel dans la requête ; les noms peuvent contenir des espaces (colonnes CSV). */
    private val OCCURRENCE = Regex("""\$([FPVR])(!?)\{([^}\r\n]*)}""")

    /** Début d'occurrence juste avant le curseur, pour la complétion : `$F{nom_partiel`. */
    val PARTIAL = Regex("""\$([FPVR])(!?)\{([^}\r\n]*)$""")

    fun occurrences(text: CharSequence): List<SymbolOccurrence> = OCCURRENCE.findAll(text).mapNotNull { match ->
        val kind = SymbolKind.ofPrefix(match.groupValues[1][0]) ?: return@mapNotNull null
        val raw = match.groupValues[2] == "!"
        if (raw && kind != SymbolKind.PARAMETER) return@mapNotNull null
        val group = match.groups[3]!!
        if (group.value.isBlank()) return@mapNotNull null
        SymbolOccurrence(kind, group.value, group.range.first, group.range.last + 1, raw)
    }.toList()

    private val SUBREPORT = Regex(""""(?:[^"\\]*/)?([^"\\/]+)\.jasper"""")

    fun subreportLiterals(text: CharSequence): List<SubreportLiteral> = SUBREPORT.findAll(text).map {
        val group = it.groups[1]!!
        SubreportLiteral(group.value, group.range.first, group.range.last + 1, it.range.first, it.range.last + 1)
    }.toList()

    /** Balises dont le contenu est une expression Java ou une requête (qui accepte $P{} et $P!{}). */
    fun isExpressionTag(tagName: String): Boolean =
        tagName == "expression" || tagName.endsWith("Expression") || tagName == "query"

    /** Paramètres intégrés (JRDesignDataset.BUILT_IN_PARAMETERS, 7.1.0) et leur classe. */
    val BUILT_IN_PARAMETERS: Map<String, String> = linkedMapOf(
        "REPORT_CONTEXT" to "net.sf.jasperreports.engine.ReportContext",
        "REPORT_PARAMETERS_MAP" to "java.util.Map",
        "JASPER_REPORTS_CONTEXT" to "net.sf.jasperreports.engine.JasperReportsContext",
        "JASPER_REPORT" to "net.sf.jasperreports.engine.JasperReport",
        "REPORT_CONNECTION" to "java.sql.Connection",
        "REPORT_MAX_COUNT" to "java.lang.Integer",
        "REPORT_DATA_SOURCE" to "net.sf.jasperreports.engine.JRDataSource",
        "REPORT_SCRIPTLET" to "net.sf.jasperreports.engine.JRAbstractScriptlet",
        "REPORT_LOCALE" to "java.util.Locale",
        "REPORT_RESOURCE_BUNDLE" to "java.util.ResourceBundle",
        "REPORT_TIME_ZONE" to "java.util.TimeZone",
        "REPORT_FORMAT_FACTORY" to "net.sf.jasperreports.engine.util.FormatFactory",
        "REPORT_CLASS_LOADER" to "java.lang.ClassLoader",
        "REPORT_TEMPLATES" to "java.util.Collection",
        "SORT_FIELDS" to "java.util.List",
        "FILTER" to "net.sf.jasperreports.engine.DatasetFilter",
    )

    /** Paramètres intégrés au seul jeu de données principal. */
    val BUILT_IN_MAIN_PARAMETERS: Map<String, String> = linkedMapOf(
        "REPORT_VIRTUALIZER" to "net.sf.jasperreports.engine.JRVirtualizer",
        "IS_IGNORE_PAGINATION" to "java.lang.Boolean",
    )

    /** Variables intégrées (JRVariable). */
    val BUILT_IN_VARIABLES: Map<String, String> = linkedMapOf(
        "PAGE_NUMBER" to "java.lang.Integer",
        "MASTER_CURRENT_PAGE" to "java.lang.Integer",
        "MASTER_TOTAL_PAGES" to "java.lang.Integer",
        "COLUMN_NUMBER" to "java.lang.Integer",
        "REPORT_COUNT" to "java.lang.Integer",
        "PAGE_COUNT" to "java.lang.Integer",
        "COLUMN_COUNT" to "java.lang.Integer",
    )

    /** Variables intégrées d'un crosstab (JRCrosstab). */
    val BUILT_IN_CROSSTAB_VARIABLES: Map<String, String> = linkedMapOf(
        "ROW_COUNT" to "java.lang.Integer",
        "IS_EVEN_ROW" to "java.lang.Boolean",
        "COLUMN_COUNT" to "java.lang.Integer",
    )
}

/** Déclaration nommée : `<parameter name class>`, `<field>`, `<variable>`, `<group>`, `<style>`, `<dataset>`… */
class Declaration(val kind: SymbolKind, val name: String, val tag: TagNode, val valueClass: String?) {
    override fun toString(): String = "${kind.name.lowercase()} $name"
}

/** Portée : le rapport, un sous-jeu de données (`<dataset name>`) ou un crosstab. */
class Scope(val tag: TagNode, val type: Type, val name: String?) {
    enum class Type { REPORT, DATASET, CROSSTAB }

    /** Toutes les déclarations, doublons compris, dans l'ordre du fichier. */
    val declarations: MutableList<Declaration> = mutableListOf()

    /** Noms générés par le moteur : `groupe_COUNT`, totaux du crosstab, paramètres des scriptlets… */
    val generated: MutableMap<SymbolKind, MutableMap<String, String>> = mutableMapOf()

    /** Requête dans un autre langage que SQL : son exécuteur déclare des paramètres que le fichier ne montre pas. */
    fun hasNonSqlQuery(): Boolean {
        val query = tag.subTags.firstOrNull { it.tagName == "query" } ?: return false
        val language = query.attribute("language")?.lowercase() ?: "sql"
        return language != "sql" && language != "plsql"
    }

    fun find(kind: SymbolKind, name: String): Declaration? = declarations.firstOrNull { it.kind == kind && it.name == name }

    fun names(kind: SymbolKind): Map<String, String?> {
        val result = linkedMapOf<String, String?>()
        declarations.filter { it.kind == kind }.forEach { result.putIfAbsent(it.name, it.valueClass) }
        return result
    }

    internal fun generate(kind: SymbolKind, name: String, valueClass: String) {
        generated.getOrPut(kind) { linkedMapOf() }.putIfAbsent(name, valueClass)
    }

    override fun toString(): String = "$type ${name ?: ""}"
}

class ReportSymbols private constructor(val root: TagNode) {

    val main: Scope = Scope(root, Scope.Type.REPORT, null)
    val datasets: Map<String, Scope>
    val styles: List<Declaration>
    /** Styles externes (`<template>` .jrtx) : les noms de style ne peuvent alors pas être vérifiés. */
    val hasStyleTemplates: Boolean = root.subTags.any { it.tagName == "template" }
    val resourceBundle: String? = root.attribute("resourceBundle")?.takeIf { it.isNotBlank() }
    private val crosstabs = HashMap<TagNode, Scope>()

    init {
        collect(main, root)
        main.generated[SymbolKind.PARAMETER] = LinkedHashMap(ExpressionSyntax.BUILT_IN_PARAMETERS + ExpressionSyntax.BUILT_IN_MAIN_PARAMETERS)
        datasets = linkedMapOf<String, Scope>().also { map ->
            root.subTags.filter { it.tagName == "dataset" }.forEach { tag ->
                val name = tag.attribute("name") ?: return@forEach
                val scope = Scope(tag, Scope.Type.DATASET, name)
                collect(scope, tag)
                scope.generated[SymbolKind.PARAMETER] = LinkedHashMap(ExpressionSyntax.BUILT_IN_PARAMETERS)
                map.putIfAbsent(name, scope)
                main.declarations += Declaration(SymbolKind.DATASET, name, tag, null)
            }
        }
        styles = root.subTags.filter { it.tagName == "style" }.mapNotNull { tag ->
            tag.attribute("name")?.let { Declaration(SymbolKind.STYLE, it, tag, null) }
        }
        main.declarations += styles
        // Les variables générées sont ajoutées après coup pour ne pas masquer les noms intégrés redéclarés.
        for (scope in listOf(main) + datasets.values) {
            val variables = scope.generated.getOrPut(SymbolKind.VARIABLE) { linkedMapOf() }
            ExpressionSyntax.BUILT_IN_VARIABLES.forEach { (name, type) -> variables.putIfAbsent(name, type) }
            scope.declarations.filter { it.kind == SymbolKind.GROUP }.forEach {
                variables.putIfAbsent(it.name + "_COUNT", "java.lang.Integer")
            }
        }
    }

    private fun collect(scope: Scope, tag: TagNode) {
        for (child in tag.subTags) {
            val name = child.attribute("name") ?: continue
            when (child.tagName) {
                "parameter" -> scope.declarations += Declaration(SymbolKind.PARAMETER, name, child, child.attribute("class") ?: "java.lang.String")
                "field" -> scope.declarations += Declaration(SymbolKind.FIELD, name, child, child.attribute("class") ?: "java.lang.String")
                "variable" -> scope.declarations += Declaration(SymbolKind.VARIABLE, name, child, child.attribute("class") ?: "java.lang.String")
                "group" -> scope.declarations += Declaration(SymbolKind.GROUP, name, child, null)
                "scriptlet" -> scope.generate(SymbolKind.PARAMETER, name + "_SCRIPTLET", child.attribute("class") ?: "net.sf.jasperreports.engine.JRAbstractScriptlet")
            }
        }
    }

    /** Portée d'un crosstab : ses paramètres, ses groupes de lignes / colonnes et ses mesures (des variables). */
    fun crosstabScope(crosstab: TagNode): Scope = crosstabs.getOrPut(crosstab) {
        val scope = Scope(crosstab, Scope.Type.CROSSTAB, crosstab.attribute("key"))
        val rowGroups = mutableListOf<String>()
        val columnGroups = mutableListOf<String>()
        val measures = mutableListOf<String>()
        for (child in crosstab.subTags) {
            val name = child.attribute("name") ?: continue
            when (child.tagName) {
                "parameter" -> scope.declarations += Declaration(SymbolKind.PARAMETER, name, child, child.attribute("class") ?: "java.lang.Object")
                "rowGroup", "columnGroup" -> {
                    (if (child.tagName == "rowGroup") rowGroups else columnGroups) += name
                    val bucketClass = child.subTags.firstOrNull { it.tagName == "bucket" }?.attribute("class")
                    scope.declarations += Declaration(SymbolKind.VARIABLE, name, child, bucketClass ?: "java.lang.Object")
                }
                "measure" -> {
                    measures += name
                    scope.declarations += Declaration(SymbolKind.VARIABLE, name, child, child.attribute("class") ?: "java.lang.Object")
                }
            }
        }
        ExpressionSyntax.BUILT_IN_CROSSTAB_VARIABLES.forEach { (name, type) -> scope.generate(SymbolKind.VARIABLE, name, type) }
        // Totaux : mesure[_groupeLigne][_groupeColonne]_ALL (JRDesignCrosstab.getTotalVariableName).
        for (measure in measures) {
            val type = scope.find(SymbolKind.VARIABLE, measure)?.valueClass ?: "java.lang.Object"
            for (row in listOf<String?>(null) + rowGroups) {
                for (column in listOf<String?>(null) + columnGroups) {
                    val name = buildString {
                        append(measure)
                        row?.let { append('_').append(it) }
                        column?.let { append('_').append(it) }
                        append("_ALL")
                    }
                    scope.generate(SymbolKind.VARIABLE, name, type)
                }
            }
        }
        scope
    }

    /**
     * Portées visibles depuis une balise, de la plus proche à la plus large. Une balise qui contient un
     * `<datasetRun subDataset="X">` (table, list, crosstab, graphique) ouvre la portée X pour son contenu, sauf
     * pour les expressions du datasetRun lui-même (paramètres passés), évaluées dans la portée englobante.
     */
    fun scopesAt(tag: TagNode): List<Scope> {
        val result = mutableListOf<Scope>()
        val ancestors = generateSequence(tag) { it.parentTag }.toList()
        for (ancestor in ancestors) {
            if (ancestor.tagName == "element" && ancestor.attribute("kind") == "crosstab") {
                result += crosstabScope(ancestor)
            }
            if (ancestor.tagName == "dataset" && ancestor.parentTag == root) {
                ancestor.attribute("name")?.let { datasets[it] }?.let { result += it }
                break
            }
            val datasetRun = datasetRunOf(ancestor)
            if (datasetRun != null && datasetRun !in ancestors) {
                datasetRun.attribute("subDataset")?.let { datasets[it] }?.let { if (it !in result) result += it }
            }
        }
        result += main
        return result.distinct()
    }

    private fun datasetRunOf(tag: TagNode): TagNode? {
        if (tag.tagName == "datasetRun") return null
        tag.subTags.firstOrNull { it.tagName == "datasetRun" }?.let { return it }
        return tag.subTags.firstOrNull { it.tagName == "dataset" && tag != root }
            ?.subTags?.firstOrNull { it.tagName == "datasetRun" }
    }

    /** Déclaration visible depuis [at], sinon la même déclaration ailleurs dans le fichier (navigation tolérante). */
    fun resolve(kind: SymbolKind, name: String, at: TagNode): Declaration? {
        if (kind == SymbolKind.STYLE) return styles.firstOrNull { it.name == name }
        if (kind == SymbolKind.DATASET) return main.find(kind, name)
        scopesAt(at).forEach { scope -> scope.find(kind, name)?.let { return it } }
        return allScopes().firstNotNullOfOrNull { it.find(kind, name) }
    }

    fun isGenerated(kind: SymbolKind, name: String, at: TagNode): Boolean =
        scopesAt(at).any { scope -> scope.generated[kind]?.containsKey(name) == true }

    /** Vrai si le nom n'existe nulle part (ni déclaré, ni intégré) : critère prudent de « référence non résolue ». */
    fun isUnknown(kind: SymbolKind, name: String, at: TagNode): Boolean {
        if (kind == SymbolKind.RESOURCE) return false
        if (kind == SymbolKind.STYLE) return !hasStyleTemplates && styles.none { it.name == name }
        if (resolve(kind, name, at) != null || isGenerated(kind, name, at)) return false
        // Les exécuteurs de requête autres que SQL (xPath, json, ejbql, hql, mdx…) ajoutent leurs propres
        // paramètres intégrés (XML_DATA_DOCUMENT, JPA_ENTITY_MANAGER…) : on ne peut pas les connaître tous.
        if (kind == SymbolKind.PARAMETER && scopesAt(at).any { it.hasNonSqlQuery() }) return false
        // Un nom généré dans une autre portée (variable de crosstab utilisée hors de sa portée détectée).
        return allScopes().none { it.generated[kind]?.containsKey(name) == true }
    }

    /** Noms proposés à la complétion depuis [at] : déclarés puis intégrés, avec leur classe. */
    fun completionNames(kind: SymbolKind, at: TagNode): Map<String, String?> {
        val result = linkedMapOf<String, String?>()
        for (scope in scopesAt(at)) {
            scope.names(kind).forEach { (name, type) -> result.putIfAbsent(name, type) }
        }
        for (scope in scopesAt(at)) {
            scope.generated[kind]?.forEach { (name, type) -> result.putIfAbsent(name, type) }
        }
        return result
    }

    fun allScopes(): List<Scope> = buildList {
        add(main)
        addAll(datasets.values)
        collectCrosstabs(root)
        addAll(crosstabs.values)
    }

    private fun collectCrosstabs(tag: TagNode) {
        for (child in tag.subTags) {
            if (child.tagName == "element" && child.attribute("kind") == "crosstab") crosstabScope(child)
            collectCrosstabs(child)
        }
    }

    /** Toutes les balises d'expression du fichier, avec leurs occurrences. */
    fun expressionOccurrences(): List<Pair<TagNode, List<SymbolOccurrence>>> = buildList {
        fun visit(tag: TagNode) {
            if (ExpressionSyntax.isExpressionTag(tag.tagName)) {
                val occurrences = ExpressionSyntax.occurrences(tag.textContent)
                if (occurrences.isNotEmpty()) add(tag to occurrences)
            }
            tag.subTags.forEach(::visit)
        }
        visit(root)
    }

    companion object {
        fun of(root: TagNode): ReportSymbols? = if (root.tagName == "jasperReport") ReportSymbols(root) else null
    }
}
