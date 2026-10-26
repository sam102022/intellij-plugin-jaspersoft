package com.samos.jasperreports.intellij.preview

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.samos.jasperreports.intellij.JasperBundle
import com.samos.jasperreports.intellij.format.JrxmlFormat
import com.samos.jasperreports.intellij.format.JrxmlFormatDetector

/**
 * EF-03 : éditeur composé (XML natif + aperçu), comme l'éditeur Markdown, avec les modes
 * Code / Code + Aperçu / Aperçu. Les fichiers au format 6 gardent l'éditeur XML simple (EF-05).
 */
class JrxmlSplitEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        file.extension.equals("jrxml", ignoreCase = true) && JrxmlFormatDetector.detect(file) == JrxmlFormat.V7

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val textEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        val preview = ReportPreviewEditor(project, file)
        return TextEditorWithPreview(textEditor, preview, JasperBundle.message("editor.name"), TextEditorWithPreview.Layout.SHOW_EDITOR)
    }

    override fun getEditorTypeId(): String = "jasperreports-split-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
