package com.github.cvzakharchenko.customcomment.settings

import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * UI component used in the Settings page.
 *
 * It lets the user manage multiple comment profiles:
 *  - file extensions list
 *  - multiple comment strings (first string is the one that will be added)
 *  - position where the comment is inserted
 */
class CustomCommentSettingsComponent {

    private val profilesModel = DefaultListModel<CommentProfileState>()
    private val profilesList = JBList(profilesModel)

    private val extensionsField = JBTextField()
    private val commentsField = JBTextField()
    private val columnStartRadio = JBRadioButton("At column 0")
    private val afterIndentRadio = JBRadioButton("After indentation")
    private val alignPreviousRadio = JBRadioButton("Align to previous comment")
    private val addIndentOnEmptyCheck = JBCheckBox("Add indent to empty lines (align mode)")
    private val skipEmptyLinesCheck = JBCheckBox("Skip empty lines")
    private val positionGroup = ButtonGroup().apply {
        add(columnStartRadio)
        add(afterIndentRadio)
        add(alignPreviousRadio)
    }

    val panel: JPanel

    val preferredFocusedComponent: JComponent
        get() = profilesList

    init {
        profilesList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        profilesList.cellRenderer = SimpleListCellRenderer.create("") { profile ->
            if (profile == null) return@create ""
            val exts = profile.extensions.joinToString(", ").ifBlank { "<all extensions>" }
            val comments = profile.commentStrings.joinToString(", ").ifBlank { "<no comments>" }
            "[$exts]  $comments"
        }
        profilesList.emptyText.text = "No custom comment rules defined"
        profilesList.addListSelectionListener {
            updateEditorsFromSelection()
        }

        // When the text fields change, update the selected profile.
        extensionsField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = syncExtensionsToProfile()
            override fun removeUpdate(e: DocumentEvent) = syncExtensionsToProfile()
            override fun changedUpdate(e: DocumentEvent) = syncExtensionsToProfile()
        })
        commentsField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = syncCommentsToProfile()
            override fun removeUpdate(e: DocumentEvent) = syncCommentsToProfile()
            override fun changedUpdate(e: DocumentEvent) = syncCommentsToProfile()
        })

        columnStartRadio.addActionListener { syncInsertPositionToProfile() }
        afterIndentRadio.addActionListener { syncInsertPositionToProfile() }
        alignPreviousRadio.addActionListener { syncInsertPositionToProfile() }
        addIndentOnEmptyCheck.addActionListener { syncEmptyLineOptionsToProfile() }
        skipEmptyLinesCheck.addActionListener { syncEmptyLineOptionsToProfile() }

        val addButton = JButton("Add rule").apply {
            addActionListener {
                val profile = CommentProfileState(
                    name = "",
                    extensions = mutableListOf(),
                    commentStrings = mutableListOf("//"),
                    insertPosition = InsertPosition.AFTER_INDENT,
                )
                profilesModel.addElement(profile)
                profilesList.selectedIndex = profilesModel.size - 1
            }
        }

        val removeButton = JButton("Remove rule").apply {
            addActionListener {
                val index = profilesList.selectedIndex
                if (index >= 0) {
                    profilesModel.remove(index)
                    if (!profilesModel.isEmpty) {
                        profilesList.selectedIndex = (index - 1).coerceAtLeast(0)
                    } else {
                        clearEditors()
                    }
                }
            }
        }

        val rulesScrollPane = JBScrollPane(profilesList)
        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(addButton)
            add(removeButton)
        }

        val insertPositionPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(columnStartRadio)
            add(afterIndentRadio)
            add(alignPreviousRadio)
        }

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Rules:", rulesScrollPane, 1, true)
            .addComponent(buttonsPanel)
            .addSeparator()
            .addLabeledComponent("File extensions (comma-separated):", extensionsField, 1, false)
            .addLabeledComponent("Comment strings (comma-separated):", commentsField, 1, false)
            .addLabeledComponent("Insert position:", insertPositionPanel, 1, false)
            .addComponent(addIndentOnEmptyCheck, 1)
            .addComponent(skipEmptyLinesCheck, 1)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    /**
     * Replace the current profiles in the UI with [profiles].
     */
    fun setProfiles(profiles: List<CommentProfileState>) {
        profilesModel.removeAllElements()
        profiles.forEach { profilesModel.addElement(it.copy()) }
        if (!profilesModel.isEmpty) {
            profilesList.selectedIndex = 0
        } else {
            clearEditors()
        }
    }

    /**
     * Returns the profiles currently edited in the UI.
     */
    fun getProfiles(): List<CommentProfileState> {
        // Make sure latest field values are pushed into the selected profile.
        syncExtensionsToProfile()
        syncCommentsToProfile()
        syncInsertPositionToProfile()

        return (0 until profilesModel.size)
            .map { profilesModel.getElementAt(it).copy() }
    }

    private fun updateEditorsFromSelection() {
        val profile = profilesList.selectedValue ?: run {
            clearEditors()
            return
        }

        extensionsField.text = profile.extensions.joinToString(", ")
        commentsField.text = profile.commentStrings.joinToString(",")
        when (profile.insertPosition) {
            InsertPosition.COLUMN_START -> {
                columnStartRadio.isSelected = true
                afterIndentRadio.isSelected = false
                alignPreviousRadio.isSelected = false
            }
            InsertPosition.AFTER_INDENT -> {
                columnStartRadio.isSelected = false
                afterIndentRadio.isSelected = true
                alignPreviousRadio.isSelected = false
            }
            InsertPosition.ALIGN_TO_PREVIOUS -> {
                columnStartRadio.isSelected = false
                afterIndentRadio.isSelected = false
                alignPreviousRadio.isSelected = true
            }
        }
        addIndentOnEmptyCheck.isSelected = profile.addIndentOnEmptyAlign
        skipEmptyLinesCheck.isSelected = profile.skipEmptyLines
    }

    private fun clearEditors() {
        extensionsField.text = ""
        commentsField.text = ""
        columnStartRadio.isSelected = false
        afterIndentRadio.isSelected = true
        alignPreviousRadio.isSelected = false
        addIndentOnEmptyCheck.isSelected = false
        skipEmptyLinesCheck.isSelected = false
    }

    private fun syncExtensionsToProfile() {
        val profile = profilesList.selectedValue ?: return
        val text = extensionsField.text.orEmpty()
        val values = text
            .split(',', ';')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        profile.extensions.clear()
        profile.extensions.addAll(values)
        profilesList.repaint()
    }

    private fun syncCommentsToProfile() {
        val profile = profilesList.selectedValue ?: return
        val text = commentsField.text.orEmpty()
        val values = text
            .split(',', ';')
            .filter { it.isNotEmpty() }

        profile.commentStrings.clear()
        profile.commentStrings.addAll(values)
        profilesList.repaint()
    }

    private fun syncInsertPositionToProfile() {
        val profile = profilesList.selectedValue ?: return
        profile.insertPosition = when {
            columnStartRadio.isSelected -> InsertPosition.COLUMN_START
            alignPreviousRadio.isSelected -> InsertPosition.ALIGN_TO_PREVIOUS
            else -> InsertPosition.AFTER_INDENT
        }
        profilesList.repaint()
    }

    private fun syncEmptyLineOptionsToProfile() {
        val profile = profilesList.selectedValue ?: return
        profile.addIndentOnEmptyAlign = addIndentOnEmptyCheck.isSelected
        profile.skipEmptyLines = skipEmptyLinesCheck.isSelected
        profilesList.repaint()
    }
}

