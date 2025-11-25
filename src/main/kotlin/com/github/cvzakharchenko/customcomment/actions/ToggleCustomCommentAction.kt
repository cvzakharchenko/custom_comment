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
 * - Maintains alignment across consecutive line-by-line invocations
 */
class ToggleCustomCommentAction : AnAction(), DumbAware {
    
    /**
     * Companion object for tracking alignment state across invocations.
     * This allows alignment to be maintained when executing the action line-by-line.
     */
    companion object {
        // Track the last aligned column for consecutive line execution
        private var lastAlignedColumn: Int? = null
        // Track which file the last alignment was in
        private var lastFilePath: String? = null
        // Track the last line index to detect consecutive lines
        private var lastLineIndex: Int? = null
        
        /**
         * Resets the alignment tracking state.
         */
        fun resetAlignmentState() {
            lastAlignedColumn = null
            lastFilePath = null
            lastLineIndex = null
        }
    }
    
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
        val filePath = file.path
        
        WriteCommandAction.runWriteCommandAction(project) {
            processAllCarets(project, editor, config, filePath)
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
     * Deduplicates lines so each line is only processed once, even if multiple cursors are on it.
     */
    private fun processAllCarets(project: Project, editor: Editor, config: CommentConfiguration, filePath: String) {
        val document = editor.document
        val caretModel = editor.caretModel
        
        // Collect all unique lines from all carets
        val allLines = mutableSetOf<Int>()
        var firstLineOfFirstCaret: Int? = null
        
        // Process carets sorted by offset to find the first line correctly
        val caretsSortedByOffset = caretModel.allCarets.sortedBy { it.offset }
        
        for (caret in caretsSortedByOffset) {
            val (startLine, endLine) = getSelectedLines(document, caret)
            
            // Track the first line of the first caret for determining add/remove
            if (firstLineOfFirstCaret == null) {
                firstLineOfFirstCaret = startLine
            }
            
            for (lineNum in startLine..endLine) {
                allLines.add(lineNum)
            }
        }
        
        if (allLines.isEmpty() || firstLineOfFirstCaret == null) return
        
        // Determine action based on first line of first caret
        val firstLineText = getLineText(document, firstLineOfFirstCaret)
        val shouldRemove = hasAnyCommentPrefix(firstLineText, config)
        
        // Check if we're continuing from the previous invocation (consecutive line)
        val minLine = allLines.minOrNull() ?: return
        val maxLine = allLines.maxOrNull() ?: return
        
        val continuing = filePath == lastFilePath &&
                         lastLineIndex != null &&
                         minLine == lastLineIndex!! + 1
        
        // Get the starting column for alignment
        var currentColumn: Int? = if (continuing && config.insertPosition == InsertPosition.ALIGN_WITH_PREVIOUS) {
            lastAlignedColumn
        } else {
            null
        }
        
        // Process all unique lines
        if (shouldRemove) {
            // Reset alignment state when removing
            resetAlignmentState()
            // Process from bottom to top to avoid offset issues
            for (lineNum in allLines.sortedDescending()) {
                removeCommentFromLine(document, lineNum, config)
            }
        } else {
            // Process from top to bottom for proper alignment tracking
            for (lineNum in allLines.sorted()) {
                currentColumn = addCommentToLine(document, lineNum, config, currentColumn)
            }
            // Save alignment state for next invocation
            lastAlignedColumn = currentColumn
            lastFilePath = filePath
            lastLineIndex = maxLine
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
     * Checks if a line is empty using document offsets (more efficient).
     */
    private fun isEmptyLine(document: Document, lineNum: Int): Boolean {
        val lineStart = document.getLineStartOffset(lineNum)
        val lineEnd = document.getLineEndOffset(lineNum)
        val chars = document.charsSequence
        for (i in lineStart until lineEnd) {
            val c = chars[i]
            if (c != ' ' && c != '\t') {
                return false
            }
        }
        return true
    }
    
    /**
     * Checks if a line has any of the configured comment prefixes.
     * Always checks after leading whitespace for detection (regardless of insert position).
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
     * Detection is always based on the first non-whitespace character,
     * regardless of the configured insert position.
     * Returns a Pair of (comment prefix, position).
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
            if (!isEmptyLine(document, i)) {
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
        
        val lineStart = document.getLineStartOffset(prevLineNum)
        val lineEnd = document.getLineEndOffset(prevLineNum)
        val chars = document.charsSequence
        
        var offset = lineStart
        while (offset < lineEnd) {
            val c = chars[offset]
            if (c != ' ' && c != '\t') break
            offset++
        }
        
        return chars.subSequence(lineStart, offset).toString()
    }
    
    /**
     * Adds a comment to a line.
     * For ALIGN_WITH_PREVIOUS mode, uses either the tracked column from consecutive execution
     * or looks at the first non-empty line above to find alignment.
     * Returns the column where the comment was inserted (for tracking).
     */
    private fun addCommentToLine(
        document: Document, 
        lineNum: Int, 
        config: CommentConfiguration,
        trackedColumn: Int?
    ): Int? {
        val lineStartOffset = document.getLineStartOffset(lineNum)
        val lineEndOffset = document.getLineEndOffset(lineNum)
        val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStartOffset, lineEndOffset))
        
        // Check if line is empty and skipEmptyLines is enabled
        if (isEmptyLine(lineText) && config.skipEmptyLines) {
            return trackedColumn
        }
        
        val commentToAdd = config.getPrimaryComment()
        if (commentToAdd.isEmpty()) return trackedColumn
        
        val (newText, usedColumn) = when (config.insertPosition) {
            InsertPosition.FIRST_COLUMN -> {
                // Add comment at the beginning of the line
                Pair(commentToAdd + lineText, 0)
            }
            InsertPosition.AFTER_WHITESPACE -> {
                // Add comment after leading whitespace
                val leadingWhitespace = lineText.takeWhile { it.isWhitespace() }
                val rest = lineText.drop(leadingWhitespace.length)
                Pair(leadingWhitespace + commentToAdd + rest, leadingWhitespace.length)
            }
            InsertPosition.ALIGN_WITH_PREVIOUS -> {
                // Handle empty lines with indentEmptyLines option
                if (isEmptyLine(lineText) && config.indentEmptyLines && trackedColumn != null) {
                    // Use tracked column and add appropriate indent
                    val previousIndent = getPreviousNonEmptyLineIndent(document, lineNum)
                    val indent = if (trackedColumn <= previousIndent.length) {
                        previousIndent.take(trackedColumn)
                    } else {
                        previousIndent + " ".repeat(trackedColumn - previousIndent.length)
                    }
                    Pair(indent + commentToAdd, trackedColumn)
                } else if (isEmptyLine(lineText) && config.indentEmptyLines) {
                    val previousIndent = getPreviousNonEmptyLineIndent(document, lineNum)
                    Pair(previousIndent + commentToAdd, previousIndent.length)
                } else {
                    // Determine the target column
                    val previousLineColumn = if (trackedColumn != null) {
                        // Use tracked column from consecutive execution
                        trackedColumn
                    } else {
                        // Look at the first non-empty line above to find comment position
                        val prevNonEmptyLine = findPreviousNonEmptyLine(document, lineNum)
                        if (prevNonEmptyLine >= 0) {
                            findCommentColumnInLine(document, prevNonEmptyLine, config)
                        } else {
                            -1
                        }
                    }
                    
                    val leadingWhitespaceLength = lineText.takeWhile { it.isWhitespace() }.length
                    val firstNonWhitespaceColumn = leadingWhitespaceLength
                    
                    val targetColumn = if (previousLineColumn < 0) {
                        // No previous column, use after whitespace behavior
                        firstNonWhitespaceColumn
                    } else {
                        // Use previous column, but shift left if line content starts before it
                        minOf(previousLineColumn, firstNonWhitespaceColumn)
                    }
                    
                    // Build the new line with comment at target column
                    val leadingPart = lineText.take(targetColumn)
                    val rest = lineText.drop(targetColumn)
                    Pair(leadingPart + commentToAdd + rest, targetColumn)
                }
            }
        }
        
        document.replaceString(lineStartOffset, lineEndOffset, newText)
        return usedColumn
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
