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
        
        // Pre-sort comment strings by length (descending) once for all operations
        // This avoids O(K log K) sorting on every line
        val sortedComments = config.commentStrings.sortedByDescending { it.length }
        
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
        
        // Check if we're continuing from the previous invocation (consecutive line)
        val minLine = allLines.minOrNull() ?: return
        val maxLine = allLines.maxOrNull() ?: return
        
        val continuing = filePath == lastFilePath &&
                         lastLineIndex != null &&
                         minLine == lastLineIndex!! + 1
        
        // Get the starting column for alignment (only when alignWithPrevious is enabled)
        var currentColumn: Int? = if (continuing && config.alignWithPrevious) {
            lastAlignedColumn
        } else {
            null
        }
        
        // Determine action based on first line of first caret
        // When onlyDetectUpToAlignColumn is enabled, we may limit detection based on alignment
        val shouldRemove = if (config.onlyDetectUpToAlignColumn) {
            val detectColumn = getDetectColumn(document, firstLineOfFirstCaret, config, currentColumn, sortedComments)
            if (detectColumn != null) {
                hasAnyCommentPrefixUpToColumn(getLineText(document, firstLineOfFirstCaret), sortedComments, detectColumn)
            } else {
                // No limit - detect anywhere
                hasAnyCommentPrefix(getLineText(document, firstLineOfFirstCaret), sortedComments)
            }
        } else {
            hasAnyCommentPrefix(getLineText(document, firstLineOfFirstCaret), sortedComments)
        }
        
        // Process all unique lines
        if (shouldRemove) {
            // Reset alignment state when removing
            resetAlignmentState()
            // Process from bottom to top to avoid offset issues
            for (lineNum in allLines.sortedDescending()) {
                removeCommentFromLine(document, lineNum, config, sortedComments)
            }
        } else {
            // For multiple lines, find the leftmost insert position that works for all lines
            val sortedLines = allLines.sorted()
            val multiLineColumn = if (sortedLines.size > 1) {
                calculateMultiLineInsertColumn(document, sortedLines, config, currentColumn, sortedComments)
            } else {
                null // Single line - use normal per-line logic
            }
            
            // Process from top to bottom
            for (lineNum in sortedLines) {
                currentColumn = addCommentToLine(document, lineNum, config, currentColumn, multiLineColumn, sortedComments)
            }
            // Save alignment state for next invocation (only if alignWithPrevious is enabled)
            if (config.alignWithPrevious) {
                lastAlignedColumn = currentColumn
                lastFilePath = filePath
                lastLineIndex = maxLine
            } else {
                resetAlignmentState()
            }
        }
    }
    
    /**
     * Calculates the insert column for multi-line selection.
     * All selected lines will receive comments at the same position - the leftmost that works for all.
     * Honors alignment with previous line if applicable.
     */
    private fun calculateMultiLineInsertColumn(
        document: Document,
        sortedLines: List<Int>,
        config: CommentConfiguration,
        trackedColumn: Int?,
        sortedComments: List<String>
    ): Int {
        val firstLine = sortedLines.first()
        val firstLineText = getLineText(document, firstLine)
        val firstLineWhitespace = firstLineText.takeWhile { it.isWhitespace() }.length
        
        // Start with base column for the first line
        var targetColumn = getBaseInsertColumn(config, firstLineWhitespace)
        
        // Check for alignment with previous line (before the selection)
        if (config.alignWithPrevious) {
            val prevColumn = trackedColumn ?: run {
                val prevNonEmptyLine = findPreviousNonEmptyLine(document, firstLine)
                if (prevNonEmptyLine >= 0) {
                    findCommentColumnInLine(document, prevNonEmptyLine, sortedComments)
                } else {
                    -1
                }
            }
            
            // Only shift left if previous comment is to the LEFT of target
            if (prevColumn >= 0 && prevColumn < targetColumn) {
                targetColumn = prevColumn
            }
        }
        
        // Find the minimum leading whitespace across all selected lines
        for (lineNum in sortedLines) {
            val lineText = getLineText(document, lineNum)
            
            if (isEmptyLine(lineText)) {
                // Skip empty lines if configured to skip them
                if (config.skipEmptyLines) {
                    continue
                }
                // If indentEmptyLines is enabled, empty lines will use the calculated column
                // Otherwise, empty lines have 0 whitespace, so targetColumn must be 0
                if (!config.indentEmptyLines) {
                    targetColumn = 0
                }
            } else {
                // For non-empty lines, we can't insert past the leading whitespace
                val lineWhitespace = lineText.takeWhile { it.isWhitespace() }.length
                targetColumn = minOf(targetColumn, lineWhitespace)
            }
        }
        
        return targetColumn
    }
    
    /**
     * Gets the column up to which we should detect comments when onlyDetectUpToAlignColumn is enabled.
     * Returns null if detection should not be limited (detect anywhere).
     * 
     * Logic:
     * - The "only detect" restriction only applies when alignWithPrevious is enabled AND 
     *   there's a previous comment that would cause alignment
     * - Without alignment context, there's no reason to limit detection
     * 
     * @return The column limit for detection, or null to detect anywhere
     */
    private fun getDetectColumn(document: Document, lineNum: Int, config: CommentConfiguration, trackedColumn: Int?, sortedComments: List<String>): Int? {
        // The "only detect" restriction only makes sense with alignment enabled
        if (!config.alignWithPrevious) {
            return null  // No limit - detect anywhere
        }
        
        val lineText = getLineText(document, lineNum)
        val leadingWhitespaceLength = lineText.takeWhile { it.isWhitespace() }.length
        val baseColumn = getBaseInsertColumn(config, leadingWhitespaceLength)
        
        // Get previous line's comment column
        val prevColumn = trackedColumn ?: run {
            val prevNonEmptyLine = findPreviousNonEmptyLine(document, lineNum)
            if (prevNonEmptyLine >= 0) {
                findCommentColumnInLine(document, prevNonEmptyLine, sortedComments)
            } else {
                -1
            }
        }
        
        // If no previous comment, no limit
        if (prevColumn < 0) {
            return null
        }
        
        // Return the alignment column (minimum of base and previous - only shift left)
        return minOf(baseColumn, prevColumn)
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
     * 
     * @param lineText The line text to check
     * @param sortedComments Comment strings pre-sorted by length (descending)
     */
    private fun hasAnyCommentPrefix(lineText: String, sortedComments: List<String>): Boolean {
        // Check at column 0
        if (sortedComments.any { lineText.startsWith(it) }) {
            return true
        }
        
        // Check after leading whitespace
        val trimmedText = lineText.trimStart()
        return sortedComments.any { trimmedText.startsWith(it) }
    }
    
    /**
     * Checks if a line has any of the configured comment prefixes, but only up to the specified column.
     * Comments that start strictly after the maxColumn are ignored.
     * Comments at exactly maxColumn are still detected (so they can be removed).
     * This is used when onlyDetectUpToAlignColumn is enabled.
     * 
     * @param lineText The line text to check
     * @param sortedComments Comment strings pre-sorted by length (descending)
     * @param maxColumn The column to check up to (inclusive). If -1, checks the entire line.
     */
    private fun hasAnyCommentPrefixUpToColumn(lineText: String, sortedComments: List<String>, maxColumn: Int): Boolean {
        // If maxColumn is -1 or negative, fall back to normal detection
        if (maxColumn < 0) {
            return hasAnyCommentPrefix(lineText, sortedComments)
        }
        
        // Check at column 0 (always within bounds if maxColumn >= 0)
        if (sortedComments.any { lineText.startsWith(it) }) {
            return true
        }
        
        // Check after leading whitespace, but only if the comment would start at or before maxColumn
        val leadingWhitespaceLength = lineText.takeWhile { it.isWhitespace() }.length
        
        // If the first non-whitespace character is strictly after maxColumn, no comment in range
        if (leadingWhitespaceLength > maxColumn) {
            return false
        }
        
        val textAfterWhitespace = lineText.drop(leadingWhitespaceLength)
        return sortedComments.any { textAfterWhitespace.startsWith(it) }
    }
    
    /**
     * Finds which comment prefix (if any) is present on a line and its position.
     * Detection is always based on the first non-whitespace character,
     * regardless of the configured insert position.
     * Returns a Pair of (comment prefix, position).
     * 
     * @param lineText The line text to check
     * @param sortedComments Comment strings pre-sorted by length (descending)
     */
    private fun findCommentPrefixWithPosition(lineText: String, sortedComments: List<String>): Pair<String, Int>? {
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
     * If trimEmptyLinesOnUncomment is enabled, also removes whitespace from lines that become empty.
     * 
     * @param sortedComments Comment strings pre-sorted by length (descending)
     */
    private fun removeCommentFromLine(document: Document, lineNum: Int, config: CommentConfiguration, sortedComments: List<String>) {
        val lineStartOffset = document.getLineStartOffset(lineNum)
        val lineEndOffset = document.getLineEndOffset(lineNum)
        val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStartOffset, lineEndOffset))
        
        val (commentPrefix, position) = findCommentPrefixWithPosition(lineText, sortedComments) ?: return
        
        // Remove the comment at the position where it was found
        var newText = lineText.substring(0, position) + lineText.substring(position + commentPrefix.length)
        
        // If the line is now empty (only whitespace) and trimEmptyLinesOnUncomment is enabled, trim it
        if (config.trimEmptyLinesOnUncomment && newText.isBlank()) {
            newText = ""
        }
        
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
     * 
     * Logic:
     * 1. If multiLineColumn is provided, use it (for consistent multi-line positioning)
     * 2. Otherwise, start with base insert column from Insert Position setting
     * 3. If alignWithPrevious is enabled AND previous line's comment is MORE TO THE LEFT, shift left
     * 4. Also shift left if line content starts before the target column
     * 
     * Returns the column where the comment was inserted (for tracking).
     * 
     * @param sortedComments Comment strings pre-sorted by length (descending)
     */
    private fun addCommentToLine(
        document: Document, 
        lineNum: Int, 
        config: CommentConfiguration,
        trackedColumn: Int?,
        multiLineColumn: Int? = null,
        sortedComments: List<String>
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
        
        val leadingWhitespaceLength = lineText.takeWhile { it.isWhitespace() }.length
        
        // Determine target column
        val targetColumn = if (multiLineColumn != null) {
            // Multi-line selection: use pre-calculated column (already accounts for all lines)
            minOf(multiLineColumn, leadingWhitespaceLength)
        } else {
            // Single line or per-line calculation
            val baseColumn = getBaseInsertColumn(config, leadingWhitespaceLength)
            var column = baseColumn
            
            if (config.alignWithPrevious) {
                // Get previous line's comment column
                val prevColumn = trackedColumn ?: run {
                    val prevNonEmptyLine = findPreviousNonEmptyLine(document, lineNum)
                    if (prevNonEmptyLine >= 0) {
                        findCommentColumnInLine(document, prevNonEmptyLine, sortedComments)
                    } else {
                        -1
                    }
                }
                
                // Only shift left if previous comment is to the LEFT of base column
                if (prevColumn >= 0 && prevColumn < column) {
                    column = prevColumn
                }
            }
            
            // Respect line content - can't insert into non-whitespace
            minOf(column, leadingWhitespaceLength)
        }
        
        // Handle empty lines with indentEmptyLines option
        val (newText, usedColumn) = if (isEmptyLine(lineText) && config.alignWithPrevious && config.indentEmptyLines) {
            // For multi-line selection, use multiLineColumn; for single-line, use tracked or lookup
            // Note: Empty lines don't have a "base column" to shift left from, so we use the
            // previous column directly (this is the whole point of indentEmptyLines)
            val emptyLineColumn = multiLineColumn ?: trackedColumn ?: run {
                val prevNonEmptyLine = findPreviousNonEmptyLine(document, lineNum)
                if (prevNonEmptyLine >= 0) {
                    val col = findCommentColumnInLine(document, prevNonEmptyLine, sortedComments)
                    if (col >= 0) col else getPreviousNonEmptyLineIndent(document, lineNum).length
                } else {
                    0  // No previous line, use column 0
                }
            }
            
            val previousIndent = getPreviousNonEmptyLineIndent(document, lineNum)
            val indent = if (emptyLineColumn <= previousIndent.length) {
                previousIndent.take(emptyLineColumn)
            } else {
                previousIndent + " ".repeat(emptyLineColumn - previousIndent.length)
            }
            Pair(indent + commentToAdd, emptyLineColumn)
        } else {
            // Build the new line with comment at target column
            val leadingPart = lineText.take(targetColumn)
            val rest = lineText.drop(targetColumn)
            Pair(leadingPart + commentToAdd + rest, targetColumn)
        }
        
        document.replaceString(lineStartOffset, lineEndOffset, newText)
        return usedColumn
    }
    
    /**
     * Gets the base insert column based on the insertPosition setting.
     */
    private fun getBaseInsertColumn(config: CommentConfiguration, leadingWhitespaceLength: Int): Int {
        return when (config.insertPosition) {
            InsertPosition.FIRST_COLUMN -> 0
            InsertPosition.AFTER_WHITESPACE -> leadingWhitespaceLength
        }
    }
    
    /**
     * Finds the column where a comment starts in the given line.
     * Returns -1 if no comment is found.
     * 
     * @param sortedComments Comment strings pre-sorted by length (descending)
     */
    private fun findCommentColumnInLine(document: Document, lineNum: Int, sortedComments: List<String>): Int {
        if (lineNum < 0 || lineNum >= document.lineCount) return -1
        
        val lineText = getLineText(document, lineNum)
        val result = findCommentPrefixWithPosition(lineText, sortedComments)
        
        return result?.second ?: -1
    }
}
