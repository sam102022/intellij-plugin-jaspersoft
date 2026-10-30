package com.samos.jasperreports.intellij.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.samos.jasperreports.intellij.JasperBundle
import com.samos.jasperreports.intellij.NOTIFICATION_GROUP
import com.samos.jasperreports.intellij.diagnostics.ReportCompiler
import com.samos.jasperreports.intellij.filetype.JrxmlFileType
import com.samos.jasperreports.intellij.format.JrxmlFormat
import com.samos.jasperreports.intellij.format.JrxmlFormatDetector
import com.samos.jasperreports.intellij.runner.RunnerResult

/**
 * EF-47 : compile le rapport actif dans le runner, en tâche de fond annulable. Vérification seule : aucun
 * .jasper n'est écrit dans le projet (EF-52). Les erreurs s'affichent dans l'éditeur et la fenêtre Problems (EF-50).
 */
class CompileReportAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE)?.fileType == JrxmlFileType
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        FileDocumentManager.getInstance().getDocument(file)?.let(FileDocumentManager.getInstance()::saveDocument)

        object : Task.Backgroundable(project, JasperBundle.message("compile.progress", file.name), true) {
            override fun run(indicator: ProgressIndicator) {
                when (val outcome = ReportCompiler.compile(project, file, indicator)) {
                    is ReportCompiler.Outcome.Done -> report(project, file, outcome.result)
                    is ReportCompiler.Outcome.Failed -> notify(project, outcome.message, NotificationType.ERROR)
                    ReportCompiler.Outcome.Cancelled -> Unit
                }
            }
        }.queue()
    }

    private fun report(project: Project, file: VirtualFile, result: RunnerResult) {
        val errors = result.errors
        if (errors.isEmpty()) {
            notify(project, JasperBundle.message("compile.success", file.name), NotificationType.INFORMATION)
        } else {
            val details = errors.joinToString("<br>") { d ->
                escape(d.message) + (d.code?.let { "<br><code>${escape(it)}</code>" } ?: "")
            }
            notify(project, JasperBundle.message("compile.failure", file.name, errors.size) + "<br>" + details, NotificationType.ERROR)
        }
    }
}

/** EF-48 : compile tous les rapports au format 7 du projet, en tâche de fond avec progression et annulation. */
class CompileAllReportsAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        object : Task.Backgroundable(project, JasperBundle.message("compileAll.progress"), true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                // Les dossiers exclus (target, build) ne sont pas dans la portée du projet.
                val files = ReadAction.computeBlocking<List<VirtualFile>, RuntimeException> {
                    FilenameIndex.getAllFilesByExt(project, "jrxml", GlobalSearchScope.projectScope(project)).toList()
                }.filter { JrxmlFormatDetector.detect(it) == JrxmlFormat.V7 }.sortedBy { it.path }

                val failed = mutableListOf<String>()
                for ((index, file) in files.withIndex()) {
                    if (indicator.isCanceled) return
                    indicator.text = JasperBundle.message("compile.progress", file.name)
                    indicator.fraction = index.toDouble() / files.size
                    when (val outcome = ReportCompiler.compile(project, file, indicator)) {
                        is ReportCompiler.Outcome.Done -> if (outcome.result.errors.isNotEmpty()) {
                            failed += "${file.name}: ${outcome.result.errors.size}"
                        }
                        is ReportCompiler.Outcome.Failed -> failed += "${file.name}: ${outcome.message}"
                        ReportCompiler.Outcome.Cancelled -> return
                    }
                }
                val summary = JasperBundle.message("compileAll.done", files.size, files.size - failed.size, failed.size)
                notify(project, summary + failed.joinToString("") { "<br>" + escape(it) },
                    if (failed.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING)
            }
        }.queue()
    }
}

internal fun notify(project: Project, content: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_GROUP)
        .createNotification(content, type)
        .notify(project)
}

internal fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
