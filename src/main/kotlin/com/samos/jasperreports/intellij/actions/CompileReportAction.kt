package com.samos.jasperreports.intellij.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.samos.jasperreports.intellij.JasperBundle
import com.samos.jasperreports.intellij.NOTIFICATION_GROUP
import com.samos.jasperreports.intellij.filetype.JrxmlFileType
import com.samos.jasperreports.intellij.project.JasperModuleResolver
import com.samos.jasperreports.intellij.project.ResolveResult
import com.samos.jasperreports.intellij.runner.ReportRunnerClient
import com.samos.jasperreports.intellij.runner.RunnerResult
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * EF-47 : compile le rapport actif dans le runner, en tâche de fond annulable. Vérification seule : aucun
 * .jasper n'est écrit dans le projet (EF-52). Les diagnostics iront dans la fenêtre Problems (EF-50, sprint 4).
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
                val target = when (val resolved = JasperModuleResolver.resolve(project, file)) {
                    is ResolveResult.Error -> return notify(project, resolved.message, NotificationType.ERROR)
                    is ResolveResult.Ok -> resolved.target
                }
                val client = ReportRunnerClient.getInstance(project)
                val future = client.compile(target)
                while (true) {
                    if (indicator.isCanceled) {
                        client.cancel()
                        return
                    }
                    try {
                        report(project, file, future.get(200, TimeUnit.MILLISECONDS))
                        return
                    } catch (waiting: TimeoutException) {
                        // on continue d'attendre en surveillant l'annulation
                    } catch (error: Exception) {
                        notify(project, error.cause?.message ?: error.message ?: error.toString(), NotificationType.ERROR)
                        return
                    }
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

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(content, type)
            .notify(project)
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
