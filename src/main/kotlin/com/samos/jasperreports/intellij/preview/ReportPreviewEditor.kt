package com.samos.jasperreports.intellij.preview

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.samos.jasperreports.intellij.JasperBundle
import com.samos.jasperreports.intellij.project.JasperModuleResolver
import com.samos.jasperreports.intellij.project.ResolveResult
import com.samos.jasperreports.intellij.runner.PageImage
import com.samos.jasperreports.intellij.runner.PreviewOptions
import com.samos.jasperreports.intellij.runner.ReportRunnerClient
import com.samos.jasperreports.intellij.runner.RunStatus
import com.samos.jasperreports.intellij.runner.RunnerResult
import java.awt.BorderLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.lang.ref.SoftReference
import java.beans.PropertyChangeListener
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Onglet d'aperçu (section 9), organisé comme celui de Jaspersoft Studio :
 * - en haut : source de données, format de sortie, Run / Reset / Cancel, affichage des paramètres ;
 * - à gauche : paramètres d'entrée ($P{}) et paramètres du rapport (REPORT_LOCALE pour les $R{}…) ;
 * - à droite : les pages rendues en images.
 */
class ReportPreviewEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val baseOptions = PreviewOptions()
    private val parametersPanel = PreviewParametersPanel()
    /** Largeur plafonnée : les noms d'adaptateurs longs ne doivent pas élargir toute la barre (libellé complet en infobulle). */
    private val dataSources = object : ComboBox<DataSourceChoice>() {
        override fun getPreferredSize(): Dimension =
            super.getPreferredSize().apply { width = minOf(width, JBUI.scale(MAX_COMBO_WIDTH)) }
    }.apply {
        renderer = SimpleListCellRenderer.create<DataSourceChoice>("") { it.label }
        addItemListener { toolTipText = (selectedItem as? DataSourceChoice)?.label }
    }
    private val outputFormats = ComboBox(OutputFormat.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create<OutputFormat>("") { it.label }
        // Un seul format pour l'instant : la liste montre où viendront PDF, HTML…
        isEnabled = OutputFormat.entries.size > 1
    }
    private val pages = JPanel(VerticalLayout(JBUI.scale(16))).apply {
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(16)
    }
    private val status = JBLabel(JasperBundle.message("preview.empty"), SwingConstants.LEFT).apply {
        border = JBUI.Borders.empty(2, 8, 4, 8)
        foreground = UIUtil.getContextHelpForeground()
    }
    private val splitter = OnePixelSplitter(false, "JasperReportsPreview.splitter.v2", 0.4f).apply {
        // Respecte la largeur minimale du panneau des paramètres.
        setHonorComponentsMinimumSize(true)
    }
    private val root = JPanel(BorderLayout())
    private val reloadAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    @Volatile private var disposed = false
    @Volatile private var running = false
    private var parametersVisible = true

    init {
        val actions = DefaultActionGroup(RunAction(), ResetAction(), CancelAction(), ShowParametersAction())
        val toolbar = ActionManager.getInstance().createActionToolbar("JasperReportsPreview", actions, true)
        toolbar.targetComponent = root

        // Trois groupes qui passent à la ligne quand la fenêtre se rétrécit ; un libellé reste avec sa liste.
        fun group(vararg components: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            components.forEach { add(it) }
        }
        val controls = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))).apply {
            add(group(JBLabel(JasperBundle.message("preview.dataSource.label")), dataSources))
            add(group(JBLabel(JasperBundle.message("preview.output.label")), outputFormats))
            add(group(toolbar.component))
        }
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(4, 4, 0, 4))
            add(controls, BorderLayout.NORTH)
            add(status, BorderLayout.SOUTH)
        }

        splitter.firstComponent = parametersPanel
        splitter.secondComponent = JBScrollPane(pages).apply { verticalScrollBar.unitIncrement = JBUI.scale(24) }
        root.add(header, BorderLayout.NORTH)
        root.add(splitter, BorderLayout.CENTER)
        // Recalcule le nombre de lignes de la barre à chaque redimensionnement.
        root.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = controls.revalidate()
        })

        // Les paramètres du formulaire suivent les modifications du rapport.
        FileDocumentManager.getInstance().getDocument(file)?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                reloadAlarm.cancelAllRequests()
                reloadAlarm.addRequest({ reload(rescanAdapters = false) }, 800)
            }
        }, this)
        reload(rescanAdapters = true)
    }

    /**
     * Relit les paramètres et l'adaptateur déclarés par le rapport et, si demandé, la liste des adaptateurs Studio
     * du module. La sélection courante de la source de données est conservée quand elle existe encore.
     */
    private fun reload(rescanAdapters: Boolean) {
        ReadAction.nonBlocking<PreviewReportInfo?> { PreviewReportInfoReader.read(project, file) }
            .withDocumentsCommitted(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) { info ->
                parametersPanel.setParameters(info?.parameters.orEmpty())
                if (rescanAdapters) {
                    scanAdapters(info?.declaredAdapter)
                } else {
                    updateDataSources(info?.declaredAdapter, currentAdapters)
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private var currentAdapters: List<StudioAdapter> = emptyList()

    private fun scanAdapters(declared: String?) {
        ApplicationManager.getApplication().executeOnPooledThread(Runnable {
            val roots = JasperModuleResolver.searchRoots(project, file)
            val adapters = StudioAdapters.scan(roots.map { Path.of(it) })
            onUi {
                currentAdapters = adapters
                updateDataSources(declared, adapters)
            }
        })
    }

    private fun updateDataSources(declared: String?, adapters: List<StudioAdapter>) {
        val choices = DataSourceChoice.build(declared, adapters)
        val previous = dataSources.selectedItem as? DataSourceChoice
        dataSources.model = DefaultComboBoxModel(choices.toTypedArray())
        dataSources.selectedItem = choices.firstOrNull { it == previous } ?: choices.first()
    }

    /** Run (EF-60) : compilation, remplissage avec la source et les paramètres choisis, rendu page par page. */
    fun run() {
        if (running) return
        running = true
        FileDocumentManager.getInstance().getDocument(file)?.let(FileDocumentManager.getInstance()::saveDocument)
        clearPages()
        status.text = JasperBundle.message("preview.running")
        val choice = dataSources.selectedItem as? DataSourceChoice ?: DataSourceChoice.OneEmptyRecord
        val parameters = parametersPanel.values()

        ApplicationManager.getApplication().executeOnPooledThread(Runnable {
            when (val resolved = JasperModuleResolver.resolve(project, file)) {
                is ResolveResult.Error -> onUi { finish(null, IllegalStateException(resolved.message)) }
                is ResolveResult.Ok -> {
                    val options = baseOptions.copy(
                        dataSource = choice.config(resolved.target.searchRoots),
                        parameters = parameters,
                    )
                    ReportRunnerClient.getInstance(project)
                        .preview(resolved.target, options) { page -> onUi { addPage(page) } }
                        .whenComplete { result, error -> onUi { finish(result, error) } }
                }
            }
        })
    }

    /** Reset : paramètres par défaut, source de données du rapport, liste des adaptateurs relue, pages effacées. */
    fun reset() {
        if (running) return
        parametersPanel.reset()
        dataSources.selectedItem = null
        clearPages()
        status.text = JasperBundle.message("preview.empty")
        reload(rescanAdapters = true)
    }

    private fun clearPages() {
        pages.removeAll()
        pages.revalidate()
        pages.repaint()
    }

    private fun addPage(page: PageImage) {
        val size = PageComponent.imageSize(page.image) ?: return
        pages.add(PageComponent(page.image, size, baseOptions.scale))
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
                result.errors.firstOrNull()?.let { it.code ?: it.message }.orEmpty()
            result.pages > baseOptions.maxPages -> JasperBundle.message("preview.truncated", baseOptions.maxPages, result.pages)
            else -> JasperBundle.message("preview.done", result.pages)
        } + (result?.dataSource?.let { " · " + JasperBundle.message("preview.dataSource", it) } ?: "")
        status.toolTipText = status.text
        // EF-66 : diagnostics et avertissements (adaptateur introuvable…) au-dessus des pages.
        if (result != null && result.diagnostics.isNotEmpty()) {
            result.diagnostics.forEachIndexed { index, d ->
                // Texte complet, renvoyé à la ligne et sélectionnable (copie d'une URL, d'une expression…).
                val label = JBLabel("<html><b>${d.severity}</b> ${escape(d.message)}" +
                    (d.code?.let { "<br><code>${escape(it)}</code>" } ?: "") + "</html>").apply {
                    setAllowAutoWrapping(true)
                    setCopyable(true)
                }
                pages.add(label, index)
            }
            pages.revalidate()
        }
    }

    private fun onUi(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater({ if (!disposed) block() }, ModalityState.any())

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private inner class RunAction : DumbAwareAction(
        JasperBundle.message("preview.run"), JasperBundle.message("preview.run.description"), AllIcons.Actions.Execute,
    ) {
        override fun actionPerformed(e: AnActionEvent) = run()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !running
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class ResetAction : DumbAwareAction(
        JasperBundle.message("preview.reset"), JasperBundle.message("preview.reset.description"), AllIcons.Actions.Rollback,
    ) {
        override fun actionPerformed(e: AnActionEvent) = reset()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !running
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class CancelAction : DumbAwareAction(
        JasperBundle.message("preview.cancel"), JasperBundle.message("preview.cancel"), AllIcons.Actions.Suspend,
    ) {
        override fun actionPerformed(e: AnActionEvent) = ReportRunnerClient.getInstance(project).cancel()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = running
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /** Affiche ou masque le panneau des paramètres. */
    private inner class ShowParametersAction : DumbAwareToggleAction(
        JasperBundle.message("preview.params.toggle"), JasperBundle.message("preview.params.toggle"), AllIcons.General.Settings,
    ) {
        override fun isSelected(e: AnActionEvent) = parametersVisible
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            parametersVisible = state
            splitter.firstComponent = if (state) parametersPanel else null
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /**
     * Une page, rendue à `scale` fois sa taille et affichée à sa taille logique : nette en haute densité.
     * L'image (≈ 6 Mo pour une page A4 à 2×) n'est décodée qu'au premier affichage et gardée par une référence
     * « douce » : la JVM peut la libérer si la mémoire manque, elle est alors relue depuis le PNG.
     */
    private class PageComponent(private val file: Path, imageSize: Dimension, scale: Float) : JComponent() {
        private var cached = SoftReference<BufferedImage>(null)

        init {
            preferredSize = Dimension((imageSize.width / scale).toInt(), (imageSize.height / scale).toInt())
        }

        private fun image(): BufferedImage? =
            cached.get() ?: runCatching { ImageIO.read(file.toFile()) }.getOrNull()?.also { cached = SoftReference(it) }

        override fun paintComponent(g: Graphics) {
            val image = image() ?: return
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

        companion object {
            /** Dimensions lues dans l'en-tête du PNG, sans décoder l'image. */
            fun imageSize(file: Path): Dimension? = runCatching {
                ImageIO.createImageInputStream(file.toFile()).use { input ->
                    val reader = ImageIO.getImageReaders(input).next()
                    try {
                        reader.input = input
                        Dimension(reader.getWidth(0), reader.getHeight(0))
                    } finally {
                        reader.dispose()
                    }
                }
            }.getOrNull()
        }
    }

    private companion object {
        const val MAX_COMBO_WIDTH = 320
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
