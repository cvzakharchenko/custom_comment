package com.github.cvzakharchenko.customcomment.actions

import com.github.cvzakharchenko.customcomment.settings.CommentConfiguration
import com.github.cvzakharchenko.customcomment.settings.CustomCommentSettings
import com.github.cvzakharchenko.customcomment.settings.InsertPosition
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Action to toggle custom comments on selected lines.
 * 
 * Behavior:
 * - If the first line of selection has any configured comment string, remove it from all selected lines
 * - If the first line doesn't have any configured comment string, add the primary comment to all selected lines
 * - Works with multiple cursors/carets
 * - Respects insertPosition setting for comment positioning
 * - Moves cursor to next line after action (only if single cursor with no selection)
 */
class ToggleCustomCommentAction : AnAction(), DumbAware {
    
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        
        // Enable only when we have a valid editor and matching configuration
        val hasConfiguration = if (project != null && editor != null && file != null) {
            findConfiguration(editor, file) != null
        } else {
            false
        }
        
        e.presentation.isEnabledAndVisible = hasConfiguration
    }
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        
        val config = findConfiguration(editor, file) ?: return
        
        // Check if we should move to next line after action
        // Only move if there's a single cursor with no selection
        val shouldMoveToNextLine = shouldMoveCaretAfterAction(editor)
        
        WriteCommandAction.runWriteCommandAction(project) {
            processAllCarets(project, editor, config)
        }
        
        // Move caret to next line only if single cursor with no selection
        if (shouldMoveToNextLine) {
            moveCaretToNextLine(editor)
        }
    }
    
    /**
     * Determines if the caret should move to the next line after the action.
     * Returns true only if there's a single cursor with no selection.
     */
    private fun shouldMoveCaretAfterAction(editor: Editor): Boolean {
        val caretModel = editor.caretModel
        if (caretModel.caretCount != 1) return false
        
        val caret = caretModel.primaryCaret
        return !caret.hasSelection()
    }
    
    /**
     * Finds the matching configuration for the current file.
     * Works during indexing by falling back to file extension only.
     */
    private fun findConfiguration(editor: Editor, file: VirtualFile): CommentConfiguration? {
        val extension = file.extension
        val languageId = try {
            // Try to get the language ID from the PSI file
            // This may fail during indexing, which is fine - we'll just use extension
            val project = editor.project ?: return CustomCommentSettings.getInstance().findConfiguration(extension, null)
            com.intellij.psi.PsiManager.getInstance(project)
                .findFile(file)
                ?.language
                ?.id
        } catch (ex: Exception) {
            // During indexing or other issues, fall back to extension-only matching
            null
        }
        
        return CustomCommentSettings.getInstance().findConfiguration(extension, languageId)
    }
    
    /**
     * Processes all carets in the editor.
     */
    private fun processAllCarets(project: Project, editor: Editor, config: CommentConfiguration) {
        val document = editor.document
        val caretModel = editor.caretModel
        
        // Process each caret
        // We need to process from bottom to top to avoid line number changes affecting later carets
        val carets = caretModel.allCarets.sortedByDescending { it.offset }
        
        for (caret in carets) {
            processCaret(document, caret, config)
        }
    }
    
    /**
     * Moves the primary caret to the next line.
     */
    private fun moveCaretToNextLine(editor: Editor) {
        val document = editor.document
        val caret = editor.caretModel.primaryCaret
        val lineCount = document.lineCount
        
        val currentLine = document.getLineNumber(caret.offset)
        val nextLine = currentLine + 1
        
        if (nextLine < lineCount) {
            // Move to the beginning of the next line
            val nextLineStart = document.getLineStartOffset(nextLine)
            caret.moveToOffset(nextLineStart)
        }
    }
    
    /**
     * Processes a single caret/selection.
     */
    private fun processCaret(document: Document, caret: Caret, config: CommentConfiguration) {
        val (startLine, endLine) = getSelectedLines(document, caret)
        
        // Determine action based on first line
        val firstLineText = getLineText(document, startLine)
        val shouldRemove = hasAnyCommentPrefix(firstLineText, config)
        
        // Process all lines from top to bottom when adding (to use previous line for alignment),
        // but from bottom to top when removing (to avoid offset issues)
        if (shouldRemove) {
            for (lineNum in endLine downTo startLine) {
                removeCommentFromLine(document, lineNum, config)
            }
        } else {
            for (lineNum in startLine..endLine) {
                addCommentToLine(document, lineNum, config)
            }
        }
    }
    
    /**
     * Gets the start and end line numbers for a caret/selection.
     */
    private fun getSelectedLines(document: Document, caret: Caret): Pair<Int, Int> {
        return if (caret.hasSelection()) {
            val startOffset = caret.selectionStart
            val endOffset = caret.selectionEnd
            
            val startLine = document.getLineNumber(startOffset)
            var endLine = document.getLineNumber(endOffset)
            
            // If selection ends at the beginning of a line, don't include that line
            if (endOffset == document.getLineStartOffset(endLine) && endLine > startLine) {
                endLine--
            }
            
            Pair(startLine, endLine)
        } else {
            val line = document.getLineNumber(caret.offset)
            Pair(line, line)
        }
    }
    
    /**
     * Gets the text of a line.
     */
    private fun getLineText(document: Document, lineNum: Int): String {
        val startOffset = document.getLineStartOffset(lineNum)
        val endOffset = document.getLineEndOffset(lineNum)
        return document.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset))
    }
    
    /**
     * Checks if a line is empty (contains only whitespace or nothing).
     */
    private fun isEmptyLine(lineText: String): Boolean {
        return lineText.isBlank()
    }
    
    /**
     * Checks if a line has any of the configured comment prefixes.
     * Checks both at column 0 and after leading whitespace.
     */
    private fun hasAnyCommentPrefix(lineText: String, config: CommentConfiguration): Boolean {
        // Check at column 0
        if (config.commentStrings.any { lineText.startsWith(it) }) {
            return true
        }
        
        // Check after leading whitespace
        val trimmedText = lineText.trimStart()
        return config.commentStrings.any { trimmedText.startsWith(it) }
    }
    
    /**
     * Finds which comment prefix (if any) is present on a line and its position.
     * Returns a Pair of (comment prefix, position after leading whitespace).
     * Position is the number of leading whitespace characters before the comment.
     */
    private fun findCommentPrefixWithPosition(lineText: String, config: CommentConfiguration): Pair<String, Int>? {
        // Check longest prefixes first to handle cases like "//" and "///"
        val sortedComments = config.commentStrings.sortedByDescending { it.length }
        
        // First check at column 0
        sortedComments.find { lineText.startsWith(it) }?.let {
            return Pair(it, 0)
        }
        
        // Then check after leading whitespace
        val leadingWhitespaceLength = lineText.takeWhile { it.isWhitespace() }.length
        if (leadingWhitespaceLength > 0) {
            val textAfterWhitespace = lineText.drop(leadingWhitespaceLength)
            sortedComments.find { textAfterWhitespace.startsWith(it) }?.let {
                return Pair(it, leadingWhitespaceLength)
            }
        }
        
        return null
    }
    
    /**
     * Removes a comment from a line.
     * Removes the comment wherever it is found (at column 0 or after whitespace).
     */
    private fun removeCommentFromLine(document: Document, lineNum: Int, config: CommentConfiguration) {
        val lineStartOffset = document.getLineStartOffset(lineNum)
        val lineEndOffset = document.getLineEndOffset(lineNum)
        val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStartOffset, lineEndOffset))
        
        val (commentPrefix, position) = findCommentPrefixWithPosition(lineText, config) ?: return
        
        // Remove the comment at the position where it was found
        val newText = lineText.substring(0, position) + lineText.substring(position + commentPrefix.length)
        
        if (newText != lineText) {
            document.replaceString(lineStartOffset, lineEndOffset, newText)
        }
    }
    
    /**
     * Finds the first non-empty line above the given line number.
     * Returns the line number or -1 if not found.
     */
    private fun findPreviousNonEmptyLine(document: Document, lineNum: Int): Int {
        for (i in (lineNum - 1) downTo 0) {
            val text = getLineText(document, i)
            if (!isEmptyLine(text)) {
                return i
            }
        }
        return -1
    }
    
    /**
     * Gets the indent (leading whitespace) from the first non-empty line above.
     */
    private fun getPreviousNonEmptyLineIndent(document: Document, lineNum: Int): String {
        val prevLineNum = findPreviousNonEmptyLine(document, lineNum)
        if (prevLineNum < 0) return ""
        
        val prevLineText = getLineText(document, prevLineNum)
        return prevLineText.takeWhile { it.isWhitespace() }
    }
    
    /**
     * Adds a comment to a line.
     * For ALIGN_WITH_PREVIOUS mode, looks at the first non-empty line above to find alignment.
     */
    private fun addCommentToLine(
        document: Document, 
        lineNum: Int, 
        config: CommentConfiguration
    ) {
        val lineStartOffset = document.getLineStartOffset(lineNum)
        val lineEndOffset = document.getLineEndOffset(lineNum)
        val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStartOffset, lineEndOffset))
        
        // Check if line is empty and skipEmptyLines is enabled
        if (isEmptyLine(lineText) && config.skipEmptyLines) {
            return
        }
        
        val commentToAdd = config.getPrimaryComment()
        if (commentToAdd.isEmpty()) return
        
        val newText = when (config.insertPosition) {
            InsertPosition.FIRST_COLUMN -> {
                // Add comment at the beginning of the line
                commentToAdd + lineText
            }
            InsertPosition.AFTER_WHITESPACE -> {
                // Add comment after leading whitespace
                val leadingWhitespace = lineText.takeWhile { it.isWhitespace() }
                val rest = lineText.drop(leadingWhitespace.length)
                leadingWhitespace + commentToAdd + rest
            }
            InsertPosition.ALIGN_WITH_PREVIOUS -> {
                // Handle empty lines with indentEmptyLines option
                if (isEmptyLine(lineText) && config.indentEmptyLines) {
                    val previousIndent = getPreviousNonEmptyLineIndent(document, lineNum)
                    previousIndent + commentToAdd
                } else {
                    // Look at the first non-empty line above to find comment position
                    val prevNonEmptyLine = findPreviousNonEmptyLine(document, lineNum)
                    val previousLineColumn = if (prevNonEmptyLine >= 0) {
                        findCommentColumnInLine(document, prevNonEmptyLine, config)
                    } else {
                        -1
                    }
                    
                    val leadingWhitespaceLength = lineText.takeWhile { it.isWhitespace() }.length
                    val firstNonWhitespaceColumn = leadingWhitespaceLength
                    
                    val targetColumn = if (previousLineColumn < 0) {
                        // Previous non-empty line has no comment, use after whitespace behavior
                        firstNonWhitespaceColumn
                    } else {
                        // Use previous line's comment column, but shift left if line content starts before it
                        minOf(previousLineColumn, firstNonWhitespaceColumn)
                    }
                    
                    // Build the new line with comment at target column
                    val leadingPart = lineText.take(targetColumn)
                    val rest = lineText.drop(targetColumn)
                    leadingPart + commentToAdd + rest
                }
            }
        }
        
        document.replaceString(lineStartOffset, lineEndOffset, newText)
    }
    
    /**
     * Finds the column where a comment starts in the given line.
     * Returns -1 if no comment is found.
     */
    private fun findCommentColumnInLine(document: Document, lineNum: Int, config: CommentConfiguration): Int {
        if (lineNum < 0 || lineNum >= document.lineCount) return -1
        
        val lineText = getLineText(document, lineNum)
        val result = findCommentPrefixWithPosition(lineText, config)
        
        return result?.second ?: -1
    }
}
