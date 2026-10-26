package com.samos.jasperreports.intellij.structure

import com.intellij.ide.util.treeView.TreeState
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import com.samos.jasperreports.intellij.JasperBundle
import com.samos.jasperreports.intellij.filetype.JrxmlFileType
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Fenêtre « Jasper Report » : arborescence en haut, propriétés en bas (EF-04).
 * Suit le .jrxml actif, se reconstruit 300 ms après chaque modification (EF-13), synchronise la sélection
 * avec le curseur dans les deux sens (EF-11, EF-12).
 */
class ReportStructurePanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(treeModel)
    private val emptyLabel = JBLabel(JasperBundle.message("structure.empty"), SwingConstants.CENTER)
    private val propertiesPlaceholder = JBLabel(JasperBundle.message("structure.properties.placeholder"), SwingConstants.CENTER)
    private val rebuildAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private var currentFile: VirtualFile? = null
    /** Vrai pendant une sélection provoquée par le curseur, pour ne pas renvoyer le curseur vers la balise. */
    private var selectingFromCaret = false
    var autoscrollFromSource = true

    init {
        tree.isRootVisible = true
        tree.showsRootHandles = true
        tree.cellRenderer = StructureRenderer()
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree) // EF-14 : filtre par saisie
        tree.addTreeSelectionListener { if (!selectingFromCaret) navigateToSelection() }

        val splitter = JBSplitter(true, 0.7f).apply {
            firstComponent = JBScrollPane(tree)
            secondComponent = propertiesPlaceholder.apply { border = JBUI.Borders.empty(8) }
        }
        setContent(splitter)

        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = track(event.newFile)
        })
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (FileDocumentManager.getInstance().getFile(event.document) == currentFile) scheduleRebuild()
            }
        }, this)
        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (autoscrollFromSource && event.editor.virtualFile == currentFile) selectAt(event.editor.caretModel.offset)
            }
        }, this)

        track(FileEditorManager.getInstance(project).selectedFiles.firstOrNull())
    }

    private fun track(file: VirtualFile?) {
        if (file != null && file.fileType != JrxmlFileType) return // on garde le dernier rapport affiché
        currentFile = file
        scheduleRebuild(0)
    }

    private fun scheduleRebuild(delayMs: Int = 300) {
        rebuildAlarm.cancelAllRequests()
        rebuildAlarm.addRequest(::rebuild, delayMs)
    }

    private fun rebuild() {
        val file = currentFile ?: return show(null)
        ReadAction.nonBlocking<StructureNode?> {
            (PsiManager.getInstance(project).findFile(file) as? XmlFile)?.let(ReportTreeBuilder::build)
        }
            .withDocumentsCommitted(project)
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any(), ::show)
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun show(root: StructureNode?) {
        if (root == null) {
            treeModel.setRoot(DefaultMutableTreeNode())
            tree.emptyText.text = emptyLabel.text
            return
        }
        // EF-13 : conserver l'état déplié et la sélection.
        val state = TreeState.createOn(tree, treeModel.root as DefaultMutableTreeNode)
        val firstBuild = (treeModel.root as DefaultMutableTreeNode).userObject == null
        treeModel.setRoot(toSwing(root))
        if (firstBuild) TreeUtil.expand(tree, 2) else state.applyTo(tree)
    }

    private fun toSwing(node: StructureNode): DefaultMutableTreeNode =
        DefaultMutableTreeNode(node).also { swing -> node.children.forEach { swing.add(toSwing(it)) } }

    private fun navigateToSelection() {
        val node = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? StructureNode ?: return
        val tag = node.pointer?.element ?: return
        val file = tag.containingFile?.virtualFile ?: return
        OpenFileDescriptor(project, file, tag.textOffset).navigate(false)
    }

    private fun selectAt(offset: Int) {
        val root = treeModel.root as DefaultMutableTreeNode
        var best: DefaultMutableTreeNode? = null
        TreeUtil.treeNodeTraverser(root).forEach { treeNode ->
            val swing = treeNode as DefaultMutableTreeNode
            val range = (swing.userObject as? StructureNode)?.range
            if (range != null && range.containsOffset(offset)) {
                if (best == null || swing.level > best!!.level) best = swing
            }
        }
        val target = best ?: return
        selectingFromCaret = true
        try {
            val path = TreePath(target.path)
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        } finally {
            selectingFromCaret = false
        }
    }

    override fun dispose() = Unit

    private class StructureRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? StructureNode ?: return
            icon = node.icon
            append(node.label, if (node.hasError) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            node.detail?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        }
    }

    companion object {
        fun create(project: Project, parent: Disposable): ReportStructurePanel =
            ReportStructurePanel(project).also { Disposer.register(parent, it) }
    }
}
