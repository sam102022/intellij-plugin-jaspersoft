package com.samos.jasperreports.intellij.preview

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.xmlb.annotations.MapAnnotation
import com.intellij.util.xmlb.annotations.XMap

/** Réglages d'aperçu d'un rapport : valeurs des paramètres, source de données et zoom (EF-70). */
class ReportPreviewState : BaseState() {
    @get:XMap(propertyElementName = "parameters", keyAttributeName = "name", valueAttributeName = "value")
    var parameters by map<String, String>()
    var dataSource by string()
    /** Zoom en pourcentage ; 0 = ajuster à la largeur. */
    var zoom by property(0)
}

class PreviewSettingsState : BaseState() {
    @get:MapAnnotation(sortBeforeSave = true)
    var reports by map<String, ReportPreviewState>()
}

/**
 * Configuration d'aperçu par rapport, enregistrée dans `.idea/jasperreports.xml` : partageable par Git si l'équipe
 * le souhaite (EF-70). Aucune donnée secrète n'y est écrite.
 */
@Service(Service.Level.PROJECT)
@State(name = "JasperReportsPreview", storages = [Storage("jasperreports.xml")])
class PreviewSettings(private val project: Project) : SimplePersistentStateComponent<PreviewSettingsState>(PreviewSettingsState()) {

    /** Clé stable d'un rapport : chemin relatif au projet (indépendant de l'emplacement du dépôt). */
    fun key(file: VirtualFile): String {
        val base = project.guessProjectDir()
        return base?.let { VfsUtilCore.getRelativePath(file, it, '/') } ?: file.path
    }

    fun get(file: VirtualFile): ReportPreviewState? = state.reports[key(file)]

    fun save(file: VirtualFile, parameters: Map<String, String>, dataSource: String?, zoom: Int) {
        val entry = ReportPreviewState().also {
            it.parameters = parameters.filterValues { value -> value.isNotEmpty() }.toMutableMap()
            it.dataSource = dataSource
            it.zoom = zoom
        }
        // Affecter la propriété (et non modifier la map) marque l'état comme modifié : il sera enregistré.
        state.reports = state.reports.toMutableMap().also { it[key(file)] = entry }
    }

    companion object {
        fun getInstance(project: Project): PreviewSettings = project.service()
    }
}
