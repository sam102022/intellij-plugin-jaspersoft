package com.samos.jasperreports.intellij.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.samos.jasperreports.intellij.JasperBundle
import com.samos.jasperreports.intellij.format.JrxmlFormat
import com.samos.jasperreports.intellij.format.JrxmlFormatDetector
import java.util.function.Function
import javax.swing.JComponent

/** EF-05 : un .jrxml au format 6 s'ouvre en XML simple, avec un bandeau invitant à le convertir. */
class Jrxml6FormatNotificationProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (!file.extension.equals("jrxml", ignoreCase = true)) return null
        if (JrxmlFormatDetector.detect(file) != JrxmlFormat.V6) return null
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).text(JasperBundle.message("banner.v6"))
        }
    }
}
