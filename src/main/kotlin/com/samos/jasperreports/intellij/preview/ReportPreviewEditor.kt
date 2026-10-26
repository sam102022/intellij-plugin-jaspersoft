package com.samos.jasperreports.intellij.preview

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.samos.jasperreports.intellij.JasperBundle
import com.samos.jasperreports.intellij.project.JasperModuleResolver
import com.samos.jasperreports.intellij.project.ResolveResult
import com.samos.jasperreports.intellij.runner.DataSourceConfig
import com.samos.jasperreports.intellij.runner.PageImage
import com.samos.jasperreports.intellij.runner.PreviewOptions
import com.samos.jasperreports.intellij.runner.ReportRunnerClient
import com.samos.jasperreports.intellij.runner.RunStatus
import com.samos.jasperreports.intellij.runner.RunnerResult
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.beans.PropertyChangeListener
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Panneau d'aperçu (section 9) : pages rendues en PNG par le runner, affichées en colonne défilante. */
class ReportPreviewEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val options = PreviewOptions()
    private val pages = JPanel(VerticalLayout(JBUI.scale(16))).apply {
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(16)
    }
    private val status = JBLabel(JasperBundle.message("preview.empty"), SwingConstants.LEFT).apply {
        border = JBUI.Borders.empty(4, 8)
        foreground = UIUtil.getContextHelpForeground()
    }
    private val root = JPanel(BorderLayout())
    @Volatile private var disposed = false
    @Volatile private var running = false

    init {
        val actions = DefaultActionGroup(RefreshAction(), CancelAction())
        val toolbar = ActionManager.getInstance().createActionToolbar("JasperReportsPreview", actions, true)
        toolbar.targetComponent = root
        val header = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(status, BorderLayout.CENTER)
        }
        root.add(header, BorderLayout.NORTH)
        root.add(JBScrollPane(pages).apply { verticalScrollBar.unitIncrement = JBUI.scale(24) }, BorderLayout.CENTER)
    }

    /** EF-60 : compilation, remplissage et rendu ; les pages s'affichent dès qu'elles arrivent. */
    fun refresh() {
        if (running) return
        running = true
        FileDocumentManager.getInstance().getDocument(file)?.let(FileDocumentManager.getInstance()::saveDocument)
        pages.removeAll()
        pages.revalidate()
        pages.repaint()
        status.text = JasperBundle.message("preview.running")

        ApplicationManager.getApplication().executeOnPooledThread(Runnable {
            when (val resolved = JasperModuleResolver.resolve(project, file)) {
                is ResolveResult.Error -> onUi { finish(null, IllegalStateException(resolved.message)) }
                is ResolveResult.Ok -> ReportRunnerClient.getInstance(project)
                    .preview(resolved.target, options.copy(dataSource = DataSourceConfig.Auto(resolved.target.searchRoots))) { page ->
                        onUi { addPage(page) }
                    }
                    .whenComplete { result, error -> onUi { finish(result, error) } }
            }
        })
    }

    private fun addPage(page: PageImage) {
        val image = runCatching { ImageIO.read(page.image.toFile()) }.getOrNull() ?: return
        pages.add(PageComponent(image, options.scale))
        pages.revalidate()
        pages.repaint()
    }

    private fun finish(result: RunnerResult?, error: Throwable?) {
        running = false
        status.text = when {
            error != null -> JasperBundle.message("preview.failed") + " : " + (error.cause?.message ?: error.message)
            result == null -> JasperBundle.message("preview.failed")
            result.status == RunStatus.CANCELLED -> JasperBundle.message("preview.cancelled")
            result.status == RunStatus.ERROR -> JasperBundle.message("preview.failed") + " : " +
                result.errors.joinToString(" | ") { it.message }
            result.pages > options.maxPages -> JasperBundle.message("preview.truncated", options.maxPages, result.pages)
            else -> JasperBundle.message("preview.done", result.pages)
        } + (result?.dataSource?.let { " · " + JasperBundle.message("preview.dataSource", it) } ?: "")
        // EF-66 : le détail complet des diagnostics est affiché dans le panneau.
        // Avertissements (adaptateur introuvable…) affichés au-dessus des pages, même si le rendu a réussi.
        if (result != null && result.diagnostics.isNotEmpty()) {
            result.diagnostics.forEachIndexed { index, d ->
                val label = JBLabel("<html><b>${d.severity}</b> ${escape(d.message)}" +
                    (d.code?.let { "<br><code>${escape(it)}</code>" } ?: "") + "</html>")
                pages.add(label, index)
            }
            pages.revalidate()
        }
    }

    private fun onUi(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater({ if (!disposed) block() }, ModalityState.any())

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private inner class RefreshAction : DumbAwareAction(JasperBundle.message("preview.refresh"), JasperBundle.message("preview.refresh"), AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) = refresh()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !running
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class CancelAction : DumbAwareAction("Cancel", "Cancel the preview", AllIcons.Actions.Suspend) {
        override fun actionPerformed(e: AnActionEvent) = ReportRunnerClient.getInstance(project).cancel()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = running
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /** Une page, rendue à `scale` fois sa taille et affichée à sa taille logique : nette en haute densité. */
    private class PageComponent(private val image: BufferedImage, private val scale: Float) : JComponent() {
        init {
            preferredSize = Dimension((image.width / scale).toInt(), (image.height / scale).toInt())
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g2.drawImage(image, 0, 0, preferredSize.width, preferredSize.height, null)
                g2.color = JBColor.border()
                g2.drawRect(0, 0, preferredSize.width - 1, preferredSize.height - 1)
            } finally {
                g2.dispose()
            }
        }
    }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = pages
    override fun getName(): String = JasperBundle.message("preview.name")
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() {
        disposed = true
    }
}
