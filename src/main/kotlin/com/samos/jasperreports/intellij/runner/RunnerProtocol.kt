package com.samos.jasperreports.intellij.runner

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Path

/** Section 10.4 : messages échangés avec le runner, une ligne JSON par message. */

enum class Severity { ERROR, WARNING, INFO }

data class RunnerDiagnostic(
    val severity: Severity,
    val message: String,
    /** Élément du rapport signalé par le moteur (toString de l'objet JasperReports), si connu. */
    val source: String?,
    /** Ligne de code Java de l'expression en erreur, si connue : sert à retrouver la balise (EF-51). */
    val code: String?,
)

enum class RunStatus { OK, ERROR, CANCELLED }

data class RunnerResult(
    val status: RunStatus,
    val pages: Int,
    val diagnostics: List<RunnerDiagnostic>,
    /** Source de données réellement utilisée (adaptateur Studio, source vide…). */
    val dataSource: String? = null,
) {
    val errors: List<RunnerDiagnostic> get() = diagnostics.filter { it.severity == Severity.ERROR }
}

data class PageImage(val index: Int, val total: Int, val image: Path)

sealed interface DataSourceConfig {
    fun toJson(): JsonObject

    /**
     * Adaptateur Jaspersoft Studio déclaré par le rapport (`com.jaspersoft.studio.data.defaultdataadapter`),
     * cherché depuis les racines du module ; à défaut, source vide d'un enregistrement.
     */
    data class Auto(val searchRoots: List<String> = emptyList()) : DataSourceConfig {
        override fun toJson() = JsonObject().apply {
            addProperty("type", "auto")
            add("searchRoots", JsonArray().also { array -> searchRoots.forEach { root -> array.add(root) } })
        }
    }

    /** Adaptateur Studio choisi dans la liste de l'aperçu. */
    data class AdapterFile(val path: String, val searchRoots: List<String> = emptyList()) : DataSourceConfig {
        override fun toJson() = JsonObject().apply {
            addProperty("type", "adapter")
            addProperty("path", path)
            add("searchRoots", JsonArray().also { array -> searchRoots.forEach { root -> array.add(root) } })
        }
    }

    data class Empty(val records: Int = 1) : DataSourceConfig {
        override fun toJson() = JsonObject().apply {
            addProperty("type", "empty")
            addProperty("records", records)
        }
    }

    data class Csv(val path: Path, val delimiter: Char = ',', val charset: String = "UTF-8") : DataSourceConfig {
        override fun toJson() = JsonObject().apply {
            addProperty("type", "csv")
            addProperty("path", path.toString())
            addProperty("delimiter", delimiter.toString())
            addProperty("charset", charset)
        }
    }

    data class Json(val path: Path, val selectExpression: String? = null) : DataSourceConfig {
        override fun toJson() = JsonObject().apply {
            addProperty("type", "json")
            addProperty("path", path.toString())
            selectExpression?.let { addProperty("selectExpression", it) }
        }
    }

    /** Le mot de passe vient de PasswordSafe et ne transite que par le flux du processus (EF-72). */
    data class Jdbc(val url: String, val user: String?, val password: String?, val driver: String?) : DataSourceConfig {
        override fun toJson() = JsonObject().apply {
            addProperty("type", "jdbc")
            addProperty("url", url)
            user?.let { addProperty("user", it) }
            password?.let { addProperty("password", it) }
            driver?.let { addProperty("driver", it) }
        }
        override fun toString(): String = "Jdbc(url=$url, user=$user, driver=$driver)"
    }
}

data class ParameterValue(val type: String, val value: String?)

data class PreviewOptions(
    val dataSource: DataSourceConfig = DataSourceConfig.Auto(),
    val parameters: Map<String, ParameterValue> = emptyMap(),
    /** EF-62 : rendu à 2× pour les écrans haute densité. */
    val scale: Float = 2.0f,
    /** EF-64 */
    val maxPages: Int = 50,
)
