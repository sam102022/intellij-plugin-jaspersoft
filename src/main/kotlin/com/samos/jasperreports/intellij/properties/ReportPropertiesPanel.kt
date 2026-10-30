package com.samos.jasperreports.intellij.properties

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.FixedSizeButton
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.xml.XmlTag
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TitledSeparator
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.samos.jasperreports.intellij.JasperBundle
import com.samos.jasperreports.intellij.references.JrxmlSymbols
import com.samos.jasperreports.intellij.references.PsiTagNode
import com.samos.jasperreports.intellij.symbols.SymbolKind
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.KeyboardFocusManager
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableModel

/**
 * Panneau de propriétés (EF-20 à EF-28) sous l'arborescence. Affiche les propriétés de la ou des balises
 * sélectionnées, regroupées par catégorie ; chaque modification est une seule opération annulable.
 * Avec plusieurs balises de même nature (EF-27), seules les propriétés communes sont proposées et une
 * modification s'applique à toutes.
 */
class ReportPropertiesPanel(private val project: Project) : JPanel(BorderLayout()) {

    private var pointers: List<SmartPsiElementPointer<XmlTag>> = emptyList()
    private var definitions: List<PropertyDef> = emptyList()
    private val editors = mutableListOf<PropertyEditor>()
    private var customModel: DefaultTableModel? = null
    /** Vrai pendant le remplissage des champs : les écouteurs ne doivent pas écrire dans le fichier. */
    private var loading = false

    init {
        showMessage(JasperBundle.message("properties.empty"))
    }

    private fun tags(): List<XmlTag> = pointers.mapNotNull { it.element }

    /** Depuis 2026.1, le fil graphique n'a plus d'accès implicite au PSI : lectures en action de lecture. */
    private fun <T> read(block: () -> T): T = ReadAction.computeBlocking<T, RuntimeException>(block)

    /** Nouvelle sélection : reconstruit le panneau, ou rafraîchit les valeurs si ce sont les mêmes balises. */
    fun show(selection: List<XmlTag>) = read { showInRead(selection.filter { it.isValid }) }

    private fun showInRead(selection: List<XmlTag>) {
        val same = selection.isNotEmpty() && selection.size == pointers.size && selection.zip(tags()).all { (a, b) -> a == b }
        if (same) {
            refreshInRead()
            return
        }
        val manager = SmartPointerManager.getInstance(project)
        pointers = selection.map { manager.createSmartPsiElementPointer(it) }
        rebuild(selection)
    }

    private fun showMessage(text: String) {
        removeAll()
        editors.clear()
        customModel = null
        add(JBLabel(text, SwingConstants.CENTER).apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun rebuild(selection: List<XmlTag>) {
        if (selection.isEmpty()) return showMessage(JasperBundle.message("properties.empty"))
        val keys = selection.map { PropertySchema.keyOf(it.localName, it.getAttributeValue("kind")) }.distinct()
        val key = keys.singleOrNull()
        if (key == null) return showMessage(JasperBundle.message(if (keys.size > 1) "properties.mixed" else "properties.none"))
        definitions = PropertySchema.forKey(key) ?: return showMessage(JasperBundle.message("properties.none"))

        removeAll()
        editors.clear()
        val form = FormBuilder.createFormBuilder()
        val first = selection.first()
        val title = if (selection.size == 1) describe(first) else JasperBundle.message("properties.multiple", selection.size, describe(first))
        form.addComponent(JBLabel(title).apply { font = font.deriveFont(Font.BOLD) })
        if (first.getAttributeValue("kind") in PropertySchema.OPAQUE_KINDS) {
            form.addComponent(JBLabel(JasperBundle.message("properties.opaque")).apply { foreground = UIUtil.getContextHelpForeground() })
        }

        for (category in PropertyCategory.entries) {
            val defs = definitions.filter { it.category == category }
            if (defs.isEmpty()) continue
            form.addComponent(TitledSeparator(JasperBundle.message("properties.category." + category.name.lowercase())))
            for (def in defs) {
                val editor = createEditor(def, first)
                editors += editor
                form.addLabeledComponent(JBLabel(def.name).apply { toolTipText = def.name }, editor.component)
            }
        }

        if (selection.size == 1) {
            customPropertiesSection(form, first)
            unknownAttributesSection(form, first)
        }
        form.addComponentFillVertically(JPanel(), 0)
        add(JBScrollPane(form.panel.apply { border = JBUI.Borders.empty(6, 8) }).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        refreshInRead()
        revalidate()
        repaint()
    }

    private fun describe(tag: XmlTag): String {
        val kind = tag.getAttributeValue("kind")
        val name = tag.getAttributeValue("name")
        return listOfNotNull(if (kind != null) "${tag.localName} · $kind" else tag.localName, name).joinToString("  ")
    }

    /** Recharge les valeurs depuis le XML, sauf dans le champ en cours de saisie. */
    fun refresh() = read { refreshInRead() }

    private fun refreshInRead() {
        val tags = tags()
        if (tags.isEmpty()) return
        val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        loading = true
        try {
            for (editor in editors) {
                if (focused != null && isAncestor(editor.component, focused)) continue
                val values = tags.map { PropertyAccess.read(it, editor.def) }.distinct()
                editor.setValue(values.singleOrNull(), values.size > 1)
            }
            customModel?.let { model ->
                val properties = PropertyAccess.customProperties(tags.first())
                if (model.rowCount != properties.size || properties.indices.any { model.getValueAt(it, 0) != properties[it].first || model.getValueAt(it, 1) != properties[it].second }) {
                    model.rowCount = 0
                    properties.forEach { model.addRow(arrayOf(it.first, it.second)) }
                }
            }
        } finally {
            loading = false
        }
    }

    private fun isAncestor(parent: Component, child: Component): Boolean {
        var current: Component? = child
        while (current != null) {
            if (current === parent) return true
            current = current.parent
        }
        return false
    }

    private fun write(name: String, action: (XmlTag) -> Unit) {
        if (loading) return
        val file = read { tags().firstOrNull()?.containingFile } ?: return
        // Chaque balise est relue par son pointeur : une écriture précédente peut avoir reconstruit le PSI.
        WriteCommandAction.writeCommandAction(project, file)
            .withName(JasperBundle.message("properties.command", name))
            .run<RuntimeException> { pointers.forEach { pointer -> pointer.element?.takeIf { it.isValid }?.let(action) } }
    }

    private fun commit(def: PropertyDef, value: String?) = write(def.name) { PropertyAccess.write(it, def, value) }

    // --- Éditeurs ------------------------------------------------------------------------------------------------

    private abstract inner class PropertyEditor(val def: PropertyDef) {
        abstract val component: JComponent
        abstract fun setValue(value: String?, multiple: Boolean)

        /** Texte gris d'une valeur absente : défaut du moteur, héritage du style ou valeurs différentes. */
        fun placeholder(multiple: Boolean): String = when {
            multiple -> JasperBundle.message("properties.placeholder.multiple")
            def.default != null -> JasperBundle.message("properties.placeholder.default", def.default)
            def.inherited -> JasperBundle.message("properties.placeholder.inherited")
            else -> ""
        }

        /** EF-24 : la saisie invalide est signalée sans être écrite ni bloquer le reste du panneau. */
        fun validate(field: JComponent, value: String?): Boolean {
            val error = PropertySchema.validate(def, value)
            field.putClientProperty("JComponent.outline", if (error != null) "error" else null)
            field.toolTipText = error?.let { JasperBundle.message(it.first, it.second) }
            field.repaint()
            return error == null
        }
    }

    private inner class TextEditor(def: PropertyDef) : PropertyEditor(def) {
        private val field = JBTextField()
        private var current: String? = null
        override val component: JComponent = field

        init {
            field.addActionListener { commitText() }
            field.addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) = commitText()
            })
        }

        private fun commitText() {
            val value = field.text.trim().ifEmpty { null }
            if (!validate(field, value) || value == current) return
            current = value
            commit(def, value)
        }

        override fun setValue(value: String?, multiple: Boolean) {
            current = value
            field.text = value.orEmpty()
            field.emptyText.text = placeholder(multiple)
            validate(field, value)
        }
    }

    private inner class ChoiceEditor(def: PropertyDef, private val choices: () -> List<String>) : PropertyEditor(def) {
        private val combo = ComboBox<String>()
        private var current: String? = null
        override val component: JComponent = combo

        init {
            combo.renderer = object : SimpleListCellRenderer<String>() {
                override fun customize(list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    if (value.isNullOrEmpty()) {
                        text = placeholderText.ifEmpty { " " }
                        if (!selected) foreground = UIUtil.getContextHelpForeground()
                    } else {
                        text = value
                    }
                }
            }
            combo.addActionListener {
                if (loading) return@addActionListener
                val value = (combo.selectedItem as? String)?.ifEmpty { null }
                if (value == current) return@addActionListener
                current = value
                commit(def, value)
            }
        }

        private var placeholderText = ""

        override fun setValue(value: String?, multiple: Boolean) {
            placeholderText = placeholder(multiple)
            current = value
            val items = (listOf("") + choices() + listOfNotNull(value)).distinct()
            combo.removeAllItems()
            items.forEach { combo.addItem(it) }
            combo.selectedItem = value.orEmpty()
        }
    }

    /** Expression ou texte : champ d'une ligne, et éditeur multi-lignes dans une fenêtre (EF-22). */
    private inner class ExpressionEditor(def: PropertyDef) : PropertyEditor(def) {
        private val field = JBTextField()
        private var current: String? = null
        override val component: JComponent = JPanel(BorderLayout()).apply {
            add(field, BorderLayout.CENTER)
            val buttons = JPanel(BorderLayout())
            buttons.add(FixedSizeButton(field).apply {
                icon = AllIcons.Actions.Edit
                toolTipText = JasperBundle.message("properties.expression.edit")
                addActionListener { openDialog() }
            }, BorderLayout.WEST)
            buttons.add(FixedSizeButton(field).apply {
                icon = AllIcons.Actions.EditSource
                toolTipText = JasperBundle.message("properties.expression.navigate")
                addActionListener { navigate() }
            }, BorderLayout.EAST)
            add(buttons, BorderLayout.EAST)
        }

        init {
            field.addActionListener { commitText() }
            field.addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) = commitText()
            })
        }

        private fun commitText() {
            if (!field.isEditable) return
            val value = field.text.ifEmpty { null }
            if (value == current) return
            current = value
            commit(def, value)
        }

        override fun setValue(value: String?, multiple: Boolean) {
            current = value
            // Une expression sur plusieurs lignes ne tient pas dans un champ d'une ligne : édition par la fenêtre.
            val multiline = value?.contains('\n') == true
            field.isEditable = !multiline
            field.text = if (multiline) value.lineSequence().first() + " …" else value.orEmpty()
            field.emptyText.text = placeholder(multiple)
        }

        private fun openDialog() {
            val area = JBTextArea(current.orEmpty(), 12, 60).apply {
                font = UIUtil.getFontWithFallback(java.awt.Font.MONOSPACED, Font.PLAIN, font.size)
            }
            val builder = DialogBuilder(project)
                .title(JasperBundle.message("properties.expression.title", def.name))
                .centerPanel(JBScrollPane(area))
            builder.setPreferredFocusComponent(area)
            if (builder.show() == DialogWrapper.OK_EXIT_CODE) {
                val value = area.text.ifEmpty { null }
                if (value != current) {
                    current = value
                    commit(def, value)
                }
            }
        }

        /** La complétion et la vérification Java sont dans l'éditeur XML : on y place le curseur. */
        private fun navigate() {
            val (file, offset) = read {
                val tag = tags().firstOrNull() ?: return@read null
                val target = if (def.type == PropertyType.OWN_TEXT) tag else tag.findFirstSubTag(def.name) ?: tag
                val file = target.containingFile?.virtualFile ?: return@read null
                file to (target.value.textRange.startOffset.takeIf { target.value.textRange.length > 0 } ?: target.textOffset)
            } ?: return
            OpenFileDescriptor(project, file, offset).navigate(true)
        }
    }

    private fun createEditor(def: PropertyDef, tag: XmlTag): PropertyEditor = when (def.type) {
        PropertyType.BOOLEAN, PropertyType.ENUM -> ChoiceEditor(def) { def.values }
        PropertyType.STYLE -> ChoiceEditor(def) { names(tag, SymbolKind.STYLE) }
        PropertyType.GROUP -> ChoiceEditor(def) { names(tag, SymbolKind.GROUP) }
        PropertyType.EXPRESSION, PropertyType.CHILD_TEXT, PropertyType.OWN_TEXT -> ExpressionEditor(def)
        else -> TextEditor(def)
    }

    private fun names(tag: XmlTag, kind: SymbolKind): List<String> {
        if (!tag.isValid) return emptyList()
        val symbols = JrxmlSymbols.of(tag.containingFile) ?: return emptyList()
        return symbols.completionNames(kind, PsiTagNode(tag)).keys.toList()
    }

    // --- Propriétés personnalisées et attributs inconnus ----------------------------------------------------------

    private fun customPropertiesSection(form: FormBuilder, tag: XmlTag) {
        form.addComponent(TitledSeparator(JasperBundle.message("properties.custom")))
        val model = object : DefaultTableModel(arrayOf(JasperBundle.message("properties.custom.name"), JasperBundle.message("properties.custom.value")), 0) {
            override fun setValueAt(value: Any?, row: Int, column: Int) {
                super.setValueAt(value, row, column)
                saveCustomProperties()
            }
        }
        customModel = model
        val table = JBTable(model).apply { setShowGrid(false); emptyText.text = JasperBundle.message("properties.custom.none") }
        val panel = ToolbarDecorator.createDecorator(table)
            .setAddAction {
                model.addRow(arrayOf("", ""))
                table.editCellAt(model.rowCount - 1, 0)
            }
            .setRemoveAction {
                table.selectedRows.sortedDescending().forEach(model::removeRow)
                saveCustomProperties()
            }
            .disableUpDownActions()
            .setPreferredSize(JBUI.size(200, 110))
            .createPanel()
        form.addComponent(panel)
    }

    private fun saveCustomProperties() {
        val model = customModel ?: return
        val properties = (0 until model.rowCount).map {
            model.getValueAt(it, 0)?.toString().orEmpty().trim() to model.getValueAt(it, 1)?.toString().orEmpty()
        }
        write("property") { PropertyAccess.writeCustomProperties(it, properties) }
    }

    private fun unknownAttributesSection(form: FormBuilder, tag: XmlTag) {
        val unknown = PropertyAccess.unknownAttributes(tag, definitions)
        if (unknown.isEmpty()) return
        form.addComponent(TitledSeparator(JasperBundle.message("properties.unknown")))
        for (attribute in unknown) {
            val link = HyperlinkLabel("${attribute.name} = \"${attribute.value.orEmpty()}\"")
            val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(attribute)
            link.addHyperlinkListener {
                val (file, offset) = read {
                    pointer.element?.let { a -> a.containingFile?.virtualFile?.let { it to a.textOffset } }
                } ?: return@addHyperlinkListener
                OpenFileDescriptor(project, file, offset).navigate(true)
            }
            form.addComponent(link)
        }
    }
}
