package com.samos.jasperreports.intellij.diagnostics

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.samos.jasperreports.intellij.JasperBundle
import com.samos.jasperreports.intellij.filetype.JrxmlFileType

class JasperSettingsState : BaseState() {
    /** EF-49 : désactivé par défaut. */
    var compileOnSave by property(false)
}

@Service(Service.Level.APP)
@State(name = "JasperReportsSettings", storages = [Storage("jasperreports.xml")])
class JasperSettings : SimplePersistentStateComponent<JasperSettingsState>(JasperSettingsState()) {
    companion object {
        fun getInstance(): JasperSettings = service()
    }
}

/** Réglages › Outils › JasperReports. */
class JasperSettingsConfigurable : BoundConfigurable(JasperBundle.message("settings.title")) {
    override fun createPanel(): DialogPanel = panel {
        val state = JasperSettings.getInstance().state
        row {
            checkBox(JasperBundle.message("settings.compileOnSave"))
                .bindSelected({ state.compileOnSave }, { state.compileOnSave = it })
                .comment(JasperBundle.message("settings.compileOnSave.comment"))
        }
    }
}

/** EF-49 : compile le rapport après chaque enregistrement, sans notification ; les erreurs vont dans l'éditeur. */
class CompileOnSaveListener : FileDocumentManagerListener {
    override fun beforeDocumentSaving(document: Document) {
        if (!JasperSettings.getInstance().state.compileOnSave) return
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (file.fileType != JrxmlFileType) return
        val project = ProjectLocator.getInstance().guessProjectForFile(file) ?: return
        // Après l'écriture du fichier : le runner lit le .jrxml sur le disque.
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || !file.isValid) return@invokeLater
            object : Task.Backgroundable(project, JasperBundle.message("compile.progress", file.name), true) {
                override fun run(indicator: ProgressIndicator) {
                    ReportCompiler.compile(project, file, indicator)
                }
            }.queue()
        }, project.disposed)
    }
}
