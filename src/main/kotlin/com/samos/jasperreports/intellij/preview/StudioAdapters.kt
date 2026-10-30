package com.samos.jasperreports.intellij.preview

import com.samos.jasperreports.intellij.runner.DataSourceConfig
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/** Adaptateur de données Jaspersoft Studio trouvé dans le module (fichier XML `<…DataAdapter>`). */
data class StudioAdapter(val name: String, val kind: String, val path: Path) {
    /** Libellé du type, comme dans Studio. */
    val kindLabel: String
        get() = when (kind) {
            "beanDataAdapter" -> "JavaBeans"
            "csvDataAdapter" -> "CSV"
            "jsonDataAdapter" -> "JSON"
            "jdbcDataAdapter" -> "JDBC"
            "xmlDataAdapter" -> "XML"
            "emptyDataAdapter" -> "Empty"
            else -> kind.removeSuffix("DataAdapter")
        }

    /** Vrai si `declared` (chemin relatif Studio, éventuellement avec des « \ ») désigne ce fichier. */
    fun matches(declared: String): Boolean {
        val wanted = declared.trim().replace('\\', '/').removePrefix("./")
        return wanted.isNotEmpty() && path.toString().replace('\\', '/').endsWith(wanted)
    }
}

/** Recherche des adaptateurs Studio dans les racines du module (hors dossiers de build). */
object StudioAdapters {

    private val SKIPPED = setOf("target", "build", "out", "bin", "node_modules", ".git", ".idea", ".gradle", ".intellijPlatform")
    private const val MAX_FILE_SIZE = 256 * 1024L

    fun scan(roots: List<Path>): List<StudioAdapter> {
        val found = linkedMapOf<Path, StudioAdapter>()
        for (root in roots.filter { Files.isDirectory(it) }) {
            Files.walkFileTree(root, setOf(), 25, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir != root && dir.fileName?.toString() in SKIPPED) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (file.fileName.toString().endsWith(".xml", ignoreCase = true) && attrs.size() <= MAX_FILE_SIZE) {
                        read(file)?.let { found.putIfAbsent(file.normalize(), it) }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
        return found.values.sortedBy { it.name.lowercase() }
    }

    /** Lit la racine et le `<name>` d'un fichier ; `null` si ce n'est pas un adaptateur Studio. */
    fun read(file: Path): StudioAdapter? {
        return try {
            readOrNull(file)
        } catch (e: Exception) {
            null
        }
    }

    private fun readOrNull(file: Path): StudioAdapter? {
        Files.newInputStream(file).use { input ->
            val factory = XMLInputFactory.newFactory().apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            }
            val reader = factory.createXMLStreamReader(input)
            try {
                var kind: String? = null
                var name: String? = null
                while (reader.hasNext() && name == null) {
                    if (reader.next() != XMLStreamConstants.START_ELEMENT) continue
                    if (kind == null) {
                        val root = reader.localName
                        if (!root.endsWith("DataAdapter")) return null
                        kind = root
                    } else if (reader.localName == "name") {
                        name = reader.elementText.trim()
                    }
                }
                return kind?.let { StudioAdapter(name?.takeIf(String::isNotEmpty) ?: file.fileName.toString().removeSuffix(".xml"), it, file) }
            } finally {
                reader.close()
            }
        }
    }
}

/** Entrée de la liste « Data Adapter » de l'aperçu. */
sealed interface DataSourceChoice {
    val label: String
    /** Identifiant mémorisé dans .idea/jasperreports.xml (EF-70). */
    val key: String
    fun config(searchRoots: List<String>): DataSourceConfig

    /** Adaptateur déclaré par le rapport (`com.jaspersoft.studio.data.defaultdataadapter`). */
    data class ReportDefault(val declared: String, val adapter: StudioAdapter?) : DataSourceChoice {
        override val label get() = "Report default: " + (adapter?.let { "${it.name} (${it.kindLabel})" } ?: declared)
        override val key get() = "default"
        override fun config(searchRoots: List<String>) = DataSourceConfig.Auto(searchRoots)
    }

    data class Adapter(val adapter: StudioAdapter, val showFileName: Boolean = false) : DataSourceChoice {
        override val label
            get() = "${adapter.name} (${adapter.kindLabel})" + if (showFileName) " — ${adapter.path.fileName}" else ""
        override fun config(searchRoots: List<String>) = DataSourceConfig.AdapterFile(adapter.path.toString(), searchRoots)
        // Nom de fichier et nom de l'adaptateur, sans chemin absolu : la clé reste valable sur un autre poste.
        override val key get() = "adapter:" + adapter.path.fileName + ":" + adapter.name
    }

    /** « One Empty Record » de Studio. */
    data object OneEmptyRecord : DataSourceChoice {
        override val label get() = "One Empty Record"
        override val key get() = "empty"
        override fun config(searchRoots: List<String>) = DataSourceConfig.Empty(1)
    }

    /** « No Data Adapter » de Studio : aucun enregistrement. */
    data object NoData : DataSourceChoice {
        override val label get() = "No Data Adapter"
        override val key get() = "none"
        override fun config(searchRoots: List<String>) = DataSourceConfig.Empty(0)
    }

    companion object {
        /** Liste dans l'ordre de Studio : adaptateur du rapport, sources vides, puis adaptateurs du module. */
        fun build(declared: String?, adapters: List<StudioAdapter>): List<DataSourceChoice> {
            val default = declared?.takeIf { it.isNotBlank() }?.let { d -> ReportDefault(d, adapters.firstOrNull { it.matches(d) }) }
            val duplicated = adapters.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
            return listOfNotNull(default) + listOf(OneEmptyRecord, NoData) +
                adapters.filter { it != default?.adapter }.map { Adapter(it, it.name in duplicated) }
        }
    }
}

/** Format de sortie de l'aperçu ; seule l'image est disponible pour l'instant. */
enum class OutputFormat(val label: String) {
    IMAGE("Image (PNG)"),
}
