package com.github.cvzakharchenko.customcomment.settings

import com.github.cvzakharchenko.customcomment.MyBundle
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.table.AbstractTableModel

/**
 * Configurable for Custom Comment settings.
 */
class CustomCommentConfigurable : Configurable {
    
    private var mainPanel: JPanel? = null
    private var tableModel: ConfigurationTableModel? = null
    private var table: JBTable? = null
    private var editedConfigurations: MutableList<CommentConfiguration> = mutableListOf()
    
    override fun getDisplayName(): String = MyBundle.message("settings.displayName")
    
    override fun createComponent(): JComponent {
        // Load current configurations
        editedConfigurations = CustomCommentSettings.getInstance().configurations
            .map { it.copy() }
            .toMutableList()
        
        tableModel = ConfigurationTableModel(editedConfigurations)
        table = JBTable(tableModel).apply {
            setShowGrid(true)
            rowHeight = JBUI.scale(24)
            columnModel.getColumn(0).preferredWidth = 200
            columnModel.getColumn(1).preferredWidth = 300
            columnModel.getColumn(2).preferredWidth = 120
        }
        
        val toolbarDecorator = ToolbarDecorator.createDecorator(table!!)
            .setAddAction { addConfiguration() }
            .setRemoveAction { removeConfiguration() }
            .setEditAction { editConfiguration() }
            .disableUpDownActions()
        
        val tablePanel = toolbarDecorator.createPanel().apply {
            preferredSize = Dimension(700, 300)
        }
        
        mainPanel = JPanel(BorderLayout()).apply {
            add(JBLabel(MyBundle.message("settings.configurationsLabel")), BorderLayout.NORTH)
            add(tablePanel, BorderLayout.CENTER)
            add(createHelpPanel(), BorderLayout.SOUTH)
        }
        
        return mainPanel!!
    }
    
    private fun createHelpPanel(): JPanel {
        val helpText = """
            <html>
            <b>How to configure:</b><br>
            • Add configurations with comment strings and file extensions or language ID<br>
            • The first comment string is used when adding comments<br>
            • All listed comment strings are checked when removing<br>
            • Language ID (if set) takes precedence over file extensions<br>
            • Common language IDs: JAVA, kotlin, TEXT, ObjectiveC, C++, Python, JavaScript, TypeScript<br>
            <br>
            <b>Insert Position:</b><br>
            • <b>First column</b>: Insert comment at column 0<br>
            • <b>After whitespace</b>: Insert comment after leading whitespace<br>
            <br>
            <b>Align with previous:</b><br>
            • When enabled, aligns comments with the previous line's comment column<br>
            • Uses Insert Position for the first line (when no previous comment exists)<br>
            • Maintains alignment across consecutive invocations
            </html>
        """.trimIndent()
        
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(10)
            add(JLabel(helpText), BorderLayout.CENTER)
        }
    }
    
    private fun addConfiguration() {
        val dialog = ConfigurationEditDialog(null)
        if (dialog.showAndGet()) {
            dialog.getConfiguration()?.let {
                editedConfigurations.add(it)
                tableModel?.fireTableDataChanged()
            }
        }
    }
    
    private fun removeConfiguration() {
        val selectedRow = table?.selectedRow ?: return
        if (selectedRow >= 0 && selectedRow < editedConfigurations.size) {
            editedConfigurations.removeAt(selectedRow)
            tableModel?.fireTableDataChanged()
        }
    }
    
    private fun editConfiguration() {
        val selectedRow = table?.selectedRow ?: return
        if (selectedRow >= 0 && selectedRow < editedConfigurations.size) {
            val config = editedConfigurations[selectedRow]
            val dialog = ConfigurationEditDialog(config)
            if (dialog.showAndGet()) {
                dialog.getConfiguration()?.let {
                    editedConfigurations[selectedRow] = it
                    tableModel?.fireTableDataChanged()
                }
            }
        }
    }
    
    override fun isModified(): Boolean {
        val current = CustomCommentSettings.getInstance().configurations
        if (current.size != editedConfigurations.size) return true
        
        return current.zip(editedConfigurations).any { (a, b) ->
            a.commentStrings != b.commentStrings ||
            a.fileExtensions != b.fileExtensions ||
            a.languageId != b.languageId ||
            a.insertPosition != b.insertPosition ||
            a.alignWithPrevious != b.alignWithPrevious ||
            a.indentEmptyLines != b.indentEmptyLines ||
            a.skipEmptyLines != b.skipEmptyLines ||
            a.onlyDetectUpToAlignColumn != b.onlyDetectUpToAlignColumn
        }
    }
    
    override fun apply() {
        val settings = CustomCommentSettings.getInstance()
        settings.configurations.clear()
        settings.configurations.addAll(editedConfigurations.map { it.copy() })
    }
    
    override fun reset() {
        editedConfigurations.clear()
        editedConfigurations.addAll(
            CustomCommentSettings.getInstance().configurations.map { it.copy() }
        )
        tableModel?.fireTableDataChanged()
    }
    
    override fun disposeUIResources() {
        mainPanel = null
        tableModel = null
        table = null
    }
}

/**
 * Table model for displaying configurations.
 */
private class ConfigurationTableModel(
    private val configurations: MutableList<CommentConfiguration>
) : AbstractTableModel() {
    
    private val columnNames = arrayOf("Target (Language/Extensions)", "Comment Strings", "Insert Position")
    
    override fun getRowCount(): Int = configurations.size
    
    override fun getColumnCount(): Int = columnNames.size
    
    override fun getColumnName(column: Int): String = columnNames[column]
    
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val config = configurations[rowIndex]
        return when (columnIndex) {
            0 -> config.getDisplayName()
            1 -> config.commentStrings.joinToString(", ") { "\"$it\"" }
            2 -> config.getPositionDisplayName()
            else -> ""
        }
    }
}

/**
 * Wrapper for InsertPosition enum to provide display names in ComboBox.
 */
private data class InsertPositionItem(val position: InsertPosition) {
    override fun toString(): String = when (position) {
        InsertPosition.FIRST_COLUMN -> "First column"
        InsertPosition.AFTER_WHITESPACE -> "After whitespace"
    }
}

/**
 * Dialog for editing a single configuration.
 */
private class ConfigurationEditDialog(
    private val existingConfig: CommentConfiguration?
) : DialogWrapper(true) {
    
    private val commentStringsArea = JBTextArea(5, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        text = existingConfig?.commentStrings?.joinToString("\n") ?: ""
    }
    
    private val extensionsField = JBTextField().apply {
        text = existingConfig?.fileExtensions?.joinToString(", ") ?: ""
    }
    
    private val languageIdField = JBTextField().apply {
        text = existingConfig?.languageId ?: ""
    }
    
    private val insertPositionCombo = ComboBox(
        arrayOf(
            InsertPositionItem(InsertPosition.FIRST_COLUMN),
            InsertPositionItem(InsertPosition.AFTER_WHITESPACE)
        )
    ).apply {
        val currentPosition = existingConfig?.insertPosition ?: InsertPosition.FIRST_COLUMN
        selectedItem = InsertPositionItem(currentPosition)
    }
    
    private val alignWithPreviousCheckbox = JBCheckBox(
        MyBundle.message("settings.alignWithPrevious"),
        existingConfig?.alignWithPrevious ?: false
    )
    
    private val indentEmptyLinesCheckbox = JBCheckBox(
        MyBundle.message("settings.indentEmptyLines"),
        existingConfig?.indentEmptyLines ?: false
    )
    
    private val skipEmptyLinesCheckbox = JBCheckBox(
        MyBundle.message("settings.skipEmptyLines"),
        existingConfig?.skipEmptyLines ?: false
    )
    
    private val onlyDetectUpToAlignColumnCheckbox = JBCheckBox(
        MyBundle.message("settings.onlyDetectUpToAlignColumn"),
        existingConfig?.onlyDetectUpToAlignColumn ?: false
    )
    
    init {
        title = if (existingConfig == null) 
            MyBundle.message("settings.addConfiguration") 
        else 
            MyBundle.message("settings.editConfiguration")
        init()
    }
    
    override fun createCenterPanel(): JComponent {
        val commentStringsPanel = JPanel(BorderLayout()).apply {
            add(JBLabel(MyBundle.message("settings.commentStringsHint")), BorderLayout.NORTH)
            add(JBScrollPane(commentStringsArea), BorderLayout.CENTER)
        }
        
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(MyBundle.message("settings.commentStrings")), commentStringsPanel)
            .addLabeledComponent(JBLabel(MyBundle.message("settings.languageId")), languageIdField)
            .addLabeledComponent(JBLabel(MyBundle.message("settings.fileExtensions")), extensionsField)
            .addLabeledComponent(JBLabel(MyBundle.message("settings.insertPosition")), insertPositionCombo)
            .addComponent(alignWithPreviousCheckbox)
            .addComponent(indentEmptyLinesCheckbox)
            .addComponent(skipEmptyLinesCheckbox)
            .addComponent(onlyDetectUpToAlignColumnCheckbox)
            .addComponentFillVertically(JPanel(), 0)
            .panel.apply {
                preferredSize = Dimension(500, 400)
            }
    }
    
    fun getConfiguration(): CommentConfiguration? {
        val commentStrings = commentStringsArea.text
            .split("\n")
            .filter { it.isNotEmpty() }
            .toMutableList()
        
        if (commentStrings.isEmpty()) {
            return null
        }
        
        val extensions = extensionsField.text
            .split(",", ";", " ")
            .map { it.trim().removePrefix(".") }
            .filter { it.isNotEmpty() }
            .toMutableSet()
        
        val languageId = languageIdField.text.trim()
        
        val selectedPosition = (insertPositionCombo.selectedItem as? InsertPositionItem)?.position 
            ?: InsertPosition.FIRST_COLUMN
        
        return CommentConfiguration(
            commentStrings = commentStrings,
            fileExtensions = extensions,
            languageId = languageId,
            insertPosition = selectedPosition,
            alignWithPrevious = alignWithPreviousCheckbox.isSelected,
            indentEmptyLines = indentEmptyLinesCheckbox.isSelected,
            skipEmptyLines = skipEmptyLinesCheckbox.isSelected,
            onlyDetectUpToAlignColumn = onlyDetectUpToAlignColumnCheckbox.isSelected
        )
    }
}
