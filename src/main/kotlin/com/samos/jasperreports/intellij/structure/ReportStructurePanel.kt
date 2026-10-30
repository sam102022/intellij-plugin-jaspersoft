package com.samos.jasperreports.intellij.structure

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadAction
import com.intellij.ide.util.treeView.TreeState
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.psi.SmartPointerManager
import com.intellij.ui.PopupHandler
import com.samos.jasperreports.intellij.references.symbolIcon
import com.samos.jasperreports.intellij.symbols.SymbolKind
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
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
import com.samos.jasperreports.intellij.properties.ReportPropertiesPanel
import com.intellij.psi.xml.XmlTag
import javax.swing.tree.TreeSelectionModel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Fenêtre « Jasper Report » : arborescence en haut, propriétés en bas (EF-04, EF-20).
 * Suit le .jrxml actif, se reconstruit 300 ms après chaque modification (EF-13), synchronise la sélection
 * avec le curseur dans les deux sens (EF-11, EF-12).
 */
class ReportStructurePanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(treeModel)
    private val emptyLabel = JBLabel(JasperBundle.message("structure.empty"), SwingConstants.CENTER)
    private val propertiesPanel = ReportPropertiesPanel(project)
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
        // EF-27 : plusieurs nœuds sélectionnables pour modifier ensemble leurs propriétés communes.
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        tree.addTreeSelectionListener {
            propertiesPanel.show(read { selectedTags() })
            if (!selectingFromCaret && tree.selectionCount == 1) navigateToSelection()
        }

        installActions()

        val splitter = JBSplitter(true, 0.7f).apply {
            firstComponent = JBScrollPane(tree)
            secondComponent = propertiesPanel
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
        // La sélection restaurée ne doit pas déplacer le curseur de l'éditeur pendant la saisie.
        selectingFromCaret = true
        try {
            treeModel.setRoot(toSwing(root))
            if (firstBuild) TreeUtil.expand(tree, 2) else state.applyTo(tree)
        } finally {
            selectingFromCaret = false
        }
        propertiesPanel.show(read { selectedTags() })
    }

    /** Depuis 2026.1, le fil graphique n'a plus d'accès implicite au PSI : chaque lecture passe par une action de lecture. */
    private fun <T> read(block: () -> T): T = ReadAction.computeBlocking<T, RuntimeException>(block)

    private fun selectedTags(): List<XmlTag> = tree.selectionPaths.orEmpty().mapNotNull {
        ((it.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? StructureNode)?.pointer?.element
    }

    private fun toSwing(node: StructureNode): DefaultMutableTreeNode =
        DefaultMutableTreeNode(node).also { swing -> node.children.forEach { swing.add(toSwing(it)) } }

    private fun navigateToSelection() {
        val node = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? StructureNode ?: return
        val (file, offset) = read {
            node.pointer?.element?.let { tag -> tag.containingFile?.virtualFile?.let { it to tag.textOffset } }
        } ?: return
        OpenFileDescriptor(project, file, offset).navigate(false)
    }

    private fun selectAt(offset: Int) {
        val root = treeModel.root as DefaultMutableTreeNode
        var best: DefaultMutableTreeNode? = null
        TreeUtil.treeNodeTraverser(root).forEach { treeNode ->
            val swing = treeNode as DefaultMutableTreeNode
            val range = (swing.userObject as? StructureNode)?.range
            if (range != null && range.containsOffset(offset)) {
                if (best == null || swing.level > best.level) best = swing
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

    // --- EF-15, EF-16 : actions de l'arborescence ------------------------------------------------------------------

    private fun installActions() {
        val add = DefaultActionGroup(JasperBundle.message("structure.add"), true).apply {
            templatePresentation.icon = AllIcons.General.Add
            add(DeclarationAction(SymbolKind.PARAMETER))
            add(DeclarationAction(SymbolKind.FIELD))
            add(DeclarationAction(SymbolKind.VARIABLE))
            add(DeclarationAction(SymbolKind.GROUP))
            addSeparator()
            add(BandAction())
            addSeparator()
            StructureEditing.NewElement.entries.forEach { add(ElementAction(it)) }
        }
        val group = DefaultActionGroup(add, DeleteAction(), DuplicateAction(), MoveAction(up = true), MoveAction(up = false))
        PopupHandler.installPopupMenu(tree, group, "JasperReportsStructurePopup")
        val toolbar = ActionManager.getInstance().createActionToolbar("JasperReportsStructure", group, true)
        toolbar.targetComponent = tree
        setToolbar(toolbar.component)
    }

    private fun single(): XmlTag? = read { selectedTags().singleOrNull()?.takeIf { it.isValid } }

    /** Une modification = une commande annulable ; la sélection suit la balise créée ou déplacée. */
    private fun edit(name: String, action: () -> XmlTag?) {
        val file = read {
            selectedTags().firstOrNull()?.containingFile ?: currentFile?.let { PsiManager.getInstance(project).findFile(it) }
        } ?: return
        var offset: Int? = null
        WriteCommandAction.writeCommandAction(project, file).withName(name).run<RuntimeException> {
            offset = action()?.takeIf { it.isValid }?.textOffset
        }
        offset?.let { OpenFileDescriptor(project, file.virtualFile, it).navigate(false) }
        scheduleRebuild(0)
    }

    private fun reportRoot(): XmlTag? =
        read { (currentFile?.let { PsiManager.getInstance(project).findFile(it) } as? XmlFile)?.rootTag }

    private abstract inner class StructureAction(text: String, icon: javax.swing.Icon? = null) : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        final override fun update(e: AnActionEvent) = read { updateEnabled(e) }
        protected open fun updateEnabled(e: AnActionEvent) = Unit
    }

    private inner class DeclarationAction(private val kind: SymbolKind) :
        StructureAction(JasperBundle.message("structure.add." + kind.tagName), symbolIcon(kind)) {
        override fun updateEnabled(e: AnActionEvent) {
            e.presentation.isEnabled = reportRoot() != null
        }
        override fun actionPerformed(e: AnActionEvent) {
            val root = reportRoot() ?: return
            edit(templatePresentation.text) { StructureEditing.addDeclaration(root, kind) }
        }
    }

    private inner class BandAction : StructureAction(JasperBundle.message("structure.add.band"), AllIcons.Nodes.Tag) {
        override fun updateEnabled(e: AnActionEvent) {
            e.presentation.isEnabled = single()?.let(StructureEditing::bandTarget) != null
        }
        override fun actionPerformed(e: AnActionEvent) {
            val target = read { single()?.let(StructureEditing::bandTarget) } ?: return
            edit(templatePresentation.text) { StructureEditing.addBand(target) }
        }
    }

    private inner class ElementAction(private val element: StructureEditing.NewElement) :
        StructureAction(element.kind, AllIcons.Nodes.Property) {
        override fun updateEnabled(e: AnActionEvent) {
            e.presentation.isEnabled = single()?.let(StructureEditing::elementContainer) != null
        }
        override fun actionPerformed(e: AnActionEvent) {
            val container = read { single()?.let(StructureEditing::elementContainer) } ?: return
            edit(JasperBundle.message("structure.add.element", element.kind)) { StructureEditing.addElement(container, element) }
        }
    }

    private inner class DeleteAction : StructureAction(JasperBundle.message("structure.delete"), AllIcons.General.Remove) {
        override fun updateEnabled(e: AnActionEvent) {
            e.presentation.isEnabled = selectedTags().any { it.parentTag != null }
        }
        override fun actionPerformed(e: AnActionEvent) {
            val tags = read { selectedTags().filter { it.isValid && it.parentTag != null } }
            if (tags.isEmpty()) return
            // EF-16 : comme Safe Delete, liste les usages d'une déclaration encore utilisée et demande confirmation.
            val usages = read {
                tags.associate { tag -> "${tag.localName}|${tag.getAttributeValue("name").orEmpty()}" to StructureEditing.usages(tag) }
            }.filterValues { it.isNotEmpty() }
            if (usages.isNotEmpty()) {
                val details = usages.entries.joinToString(BLANK_LINE) { (key, lines) ->
                    JasperBundle.message("structure.delete.used", key.substringBefore('|'), key.substringAfter('|'), lines.size) +
                        NEW_LINE + lines.take(MAX_USAGES).joinToString(NEW_LINE) { "  • " + it.take(120) } +
                        if (lines.size > MAX_USAGES) NEW_LINE + "  …" else ""
                }
                val answer = Messages.showYesNoDialog(project, details + BLANK_LINE + JasperBundle.message("structure.delete.confirm"),
                    JasperBundle.message("structure.delete"), Messages.getWarningIcon())
                if (answer != Messages.YES) return
            }
            edit(JasperBundle.message("structure.delete")) {
                tags.filter { it.isValid }.sortedByDescending { it.textRange.startOffset }.forEach(StructureEditing::delete)
                null
            }
            scheduleRebuild(0)
        }
    }

    private inner class DuplicateAction : StructureAction(JasperBundle.message("structure.duplicate"), AllIcons.Actions.Copy) {
        override fun updateEnabled(e: AnActionEvent) {
            e.presentation.isEnabled = single()?.parentTag != null
        }
        override fun actionPerformed(e: AnActionEvent) {
            val tag = single() ?: return
            edit(templatePresentation.text) { StructureEditing.duplicate(tag) }
        }
    }

    private inner class MoveAction(private val up: Boolean) : StructureAction(
        JasperBundle.message(if (up) "structure.moveUp" else "structure.moveDown"),
        if (up) AllIcons.Actions.MoveUp else AllIcons.Actions.MoveDown,
    ) {
        override fun updateEnabled(e: AnActionEvent) {
            e.presentation.isEnabled = single()?.let { StructureEditing.sibling(it, up) } != null
        }
        override fun actionPerformed(e: AnActionEvent) {
            val tag = single() ?: return
            edit(templatePresentation.text) {
                val pointer = SmartPointerManager.createPointer(tag)
                StructureEditing.move(tag, up)
                // Après l'échange, la balise est à la place de sa voisine : on la retrouve par son début de texte.
                pointer.element
            }
        }
    }

    private class StructureRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? StructureNode ?: return
            icon = node.icon
            append(node.label, if (node.hasError) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            toolTipText = node.problem
            node.detail?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        }
    }

    companion object {
        private const val MAX_USAGES = 8
        private const val NEW_LINE = "\n"
        private const val BLANK_LINE = "\n\n"

        fun create(project: Project, parent: Disposable): ReportStructurePanel =
            ReportStructurePanel(project).also { Disposer.register(parent, it) }
    }
}
