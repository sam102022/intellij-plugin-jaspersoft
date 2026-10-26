package com.samos.jasperreports.intellij.actions

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.samos.jasperreports.intellij.JasperBundle
import com.samos.jasperreports.intellij.JasperIcons

/** EF-07 : New > JasperReports Report, à partir du modèle « JasperReports Report.jrxml.ft ». */
class NewReportAction : CreateFileFromTemplateAction(), DumbAware {

    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle(JasperBundle.message("newReport.dialog.title"))
            .addKind(JasperBundle.message("newReport.kind"), JasperIcons.REPORT, TEMPLATE)
    }

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String =
        JasperBundle.message("newReport.dialog.title")

    private companion object {
        const val TEMPLATE = "JasperReports Report"
    }
}
