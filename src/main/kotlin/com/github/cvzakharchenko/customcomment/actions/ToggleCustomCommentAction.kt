package com.github.cvzakharchenko.customcomment.actions

import com.github.cvzakharchenko.customcomment.settings.CommentProfileState
import com.github.cvzakharchenko.customcomment.settings.CustomCommentSettings
import com.github.cvzakharchenko.customcomment.settings.InsertPosition
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import kotlin.math.max

/**
 * Toggles a custom comment string at the beginning of selected lines.
 *
 * Behaviour:
 * - Works with multi-line selections and multiple carets.
 * - For each caret, the first line in its selection decides whether we are adding or removing comments.
 * - For removal, any of the configured comment strings (A, B, C, ...) are removed if present.
 * - For addition, only the first configured string (A) is inserted.
 */
class ToggleCustomCommentAction : AnAction() {

    private data class ProcessResult(
        val lastColumn: Int?,
        val lastLine: Int,
    )

    companion object {
        private var lastAlignedColumn: Int? = null
        private var lastFilePath: String? = null
        private var lastLineIndex: Int? = null
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val settings = CustomCommentSettings.getInstance()
        val hasProfile = settings.findProfile(file) != null

        e.presentation.isEnabled = project != null && editor != null && hasProfile
        e.presentation.isVisible = editor != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getRequiredData(CommonDataKeys.EDITOR)
        val document = editor.document
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)

        val settings = CustomCommentSettings.getInstance()
        val profile = settings.findProfile(virtualFile) ?: return

        val allStrings = profile.commentStrings.filter { it.isNotBlank() }
        if (allStrings.isEmpty()) return

        val defaultString = allStrings.first()
        val filePath = virtualFile?.path

        WriteCommandAction.runWriteCommandAction(project) {
            var runLastColumn: Int? = null
            val hasSingleCaret = editor.caretModel.allCarets.size == 1

            for (caret in editor.caretModel.allCarets) {
                val startOffset = if (caret.hasSelection()) caret.selectionStart else caret.offset
                val startLine = document.getLineNumber(startOffset)

                val continuing = filePath != null &&
                    filePath == lastFilePath &&
                    lastLineIndex != null &&
                    startLine == lastLineIndex!! + 1

                runLastColumn = if (continuing) lastAlignedColumn else null

                val result = processCaret(
                    document = document,
                    caret = caret,
                    profile = profile,
                    allStrings = allStrings,
                    defaultString = defaultString,
                    lastColumn = runLastColumn,
                )

                runLastColumn = result.lastColumn
                lastAlignedColumn = result.lastColumn
                lastFilePath = filePath
                lastLineIndex = result.lastLine

                if (hasSingleCaret && !caret.hasSelection()) {
                    moveCaretToNextLine(caret, document, result.lastLine)
                }
            }
        }
    }

    private fun processCaret(
        document: Document,
        caret: Caret,
        profile: CommentProfileState,
        allStrings: List<String>,
        defaultString: String,
        lastColumn: Int?,
    ): ProcessResult {
        if (document.textLength == 0) return ProcessResult(lastColumn, 0)

        val startOffset = if (caret.hasSelection()) caret.selectionStart else caret.offset
        val endOffset = if (caret.hasSelection()) caret.selectionEnd else caret.offset

        var startLine = document.getLineNumber(startOffset)
        var endLine = document.getLineNumber(max(endOffset - 1, 0))
        if (!caret.hasSelection()) {
            endLine = startLine
        }

        val firstLineHasComment = lineHasAnyComment(document, startLine, allStrings)
        val shouldAdd = !firstLineHasComment

        var currentLastColumn = lastColumn

        if (shouldAdd) {
            for (line in startLine..endLine) {
                if (profile.skipEmptyLines && isBlankLine(document, line)) {
                    continue
                }
                val usedColumn = insertCommentAtLine(
                    document = document,
                    line = line,
                    profile = profile,
                    stringToInsert = defaultString,
                    lastColumn = currentLastColumn,
                )
                currentLastColumn = usedColumn
            }
        } else {
            for (line in startLine..endLine) {
                removeCommentsFromLine(document, line, allStrings)
            }
        }

        return ProcessResult(currentLastColumn, endLine)
    }

    private fun lineHasAnyComment(
        document: Document,
        line: Int,
        allStrings: List<String>,
    ): Boolean {
        val anchorOffset = getDetectionOffset(document, line)
        val lineEnd = document.getLineEndOffset(line)
        if (anchorOffset >= lineEnd) return false
        val text = document.getText(TextRange(anchorOffset, lineEnd))
        return allStrings.any { text.startsWith(it) }
    }

    private fun insertCommentAtLine(
        document: Document,
        line: Int,
        profile: CommentProfileState,
        stringToInsert: String,
        lastColumn: Int?,
    ): Int {
        // Special handling for blank lines when aligning and "add indent on empty" is enabled.
        if (profile.insertPosition == InsertPosition.ALIGN_TO_PREVIOUS &&
            profile.addIndentOnEmptyAlign &&
            lastColumn != null &&
            isBlankLine(document, line) &&
            line > 0
        ) {
            val lineStart = document.getLineStartOffset(line)
            var lineEnd = document.getLineEndOffset(line)
            val prevLineStart = document.getLineStartOffset(line - 1)
            val prevLineEnd = document.getLineEndOffset(line - 1)
            val prevChars = document.charsSequence

            // Compute previous line's indent and copy its whitespace as indent for the blank line.
            var prevOffset = prevLineStart
            while (prevOffset < prevLineEnd) {
                val c = prevChars[prevOffset]
                if (c != ' ' && c != '\t') break
                prevOffset++
            }
            val prevIndent = prevChars.subSequence(prevLineStart, prevOffset).toString()

            // Replace current blank line content with previous indent.
            document.replaceString(lineStart, lineEnd, prevIndent)
            lineEnd = lineStart + prevIndent.length

            // Ensure we can insert the comment at the lastColumn position by padding spaces if needed.
            val currentLength = lineEnd - lineStart
            if (lastColumn > currentLength) {
                val pad = " ".repeat(lastColumn - currentLength)
                document.insertString(lineEnd, pad)
                lineEnd += pad.length
            }

            val anchorOffset = lineStart + lastColumn
            document.insertString(anchorOffset, stringToInsert)
            return lastColumn
        }

        val (anchorOffset, column) = getInsertOffset(document, line, profile.insertPosition, lastColumn)
        document.insertString(anchorOffset, stringToInsert)
        return column
    }

    private fun removeCommentsFromLine(
        document: Document,
        line: Int,
        allStrings: List<String>,
    ) {
        var anchorOffset = getDetectionOffset(document, line)
        var lineEnd = document.getLineEndOffset(line)
        if (anchorOffset >= lineEnd) return

        // Remove all configured strings that appear consecutively at the anchor position.
        while (anchorOffset < lineEnd) {
            val text = document.getText(TextRange(anchorOffset, lineEnd))
            val match = allStrings.firstOrNull { text.startsWith(it) } ?: break

            document.deleteString(anchorOffset, anchorOffset + match.length)
            lineEnd -= match.length
        }
    }

    private fun getInsertOffset(
        document: Document,
        line: Int,
        insertPosition: InsertPosition,
        lastColumn: Int?,
    ): Pair<Int, Int> {
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val chars = document.charsSequence

        return when (insertPosition) {
            InsertPosition.COLUMN_START -> {
                lineStart to 0
            }

            InsertPosition.AFTER_INDENT -> {
                var offset = lineStart
                while (offset < lineEnd) {
                    val c = chars[offset]
                    if (c != ' ' && c != '\t') break
                    offset++
                }
                offset to (offset - lineStart)
            }

            InsertPosition.ALIGN_TO_PREVIOUS -> {
                var offset = lineStart
                while (offset < lineEnd) {
                    val c = chars[offset]
                    if (c != ' ' && c != '\t') break
                    offset++
                }
                val indentColumn = offset - lineStart
                val baseColumn = lastColumn ?: indentColumn
                val columnToUse = minOf(baseColumn, indentColumn)
                val clampedColumn = columnToUse.coerceAtMost(lineEnd - lineStart)
                val insertOffset = lineStart + clampedColumn
                insertOffset to clampedColumn
            }
        }
    }

    /**
     * Returns the offset used for detecting/removing comments.
     *
     * Detection is always based on the first non-whitespace character on the line,
     * regardless of the configured insert position. This allows us to remove a comment
     * even if it was previously inserted at a different column than currently configured.
     */
    private fun getDetectionOffset(
        document: Document,
        line: Int,
    ): Int {
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val chars = document.charsSequence
        var offset = lineStart
        while (offset < lineEnd) {
            val c = chars[offset]
            if (c != ' ' && c != '\t') {
                break
            }
            offset++
        }
        return offset
    }

    private fun isBlankLine(
        document: Document,
        line: Int,
    ): Boolean {
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val chars = document.charsSequence
        var offset = lineStart
        while (offset < lineEnd) {
            val c = chars[offset]
            if (c != ' ' && c != '\t') {
                return false
            }
            offset++
        }
        return true
    }

    private fun moveCaretToNextLine(
        caret: Caret,
        document: Document,
        lastLine: Int,
    ) {
        val totalLines = document.lineCount
        val targetLine = (lastLine + 1).coerceAtMost(totalLines - 1)
        val targetOffset = document.getLineStartOffset(targetLine)
        caret.moveToOffset(targetOffset)
        caret.removeSelection()
    }
}


