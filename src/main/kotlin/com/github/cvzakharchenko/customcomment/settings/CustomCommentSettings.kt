package com.github.cvzakharchenko.customcomment.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.xmlb.XmlSerializerUtil
import java.util.Locale

/**
 * Application-level settings storing custom comment rules.
 *
 * The settings bean itself is used as the persistent state.
 */
@State(
    name = "CustomCommentSettings",
    storages = [Storage("CustomCommentSettings.xml")],
)
@Service(Service.Level.APP)
class CustomCommentSettings : PersistentStateComponent<CustomCommentSettings> {

    /**
     * List of configured rules.
     *
     * Each rule can target multiple file extensions and define multiple comment strings.
     */
    var profiles: MutableList<CommentProfileState> = mutableListOf()

    override fun getState(): CustomCommentSettings = this

    override fun loadState(state: CustomCommentSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    /**
     * Returns the first matching profile for the given file, based on its extension.
     */
    fun findProfile(virtualFile: VirtualFile?): CommentProfileState? {
        if (virtualFile == null) return null
        val extension = virtualFile.extension?.lowercase(Locale.getDefault()) ?: return null
        return profiles.firstOrNull { profile ->
            profile.extensions.any { it.equals(extension, ignoreCase = true) }
        }
    }

    companion object {
        fun getInstance(): CustomCommentSettings = service()
    }

    init {
        // Provide a small sensible default to make the plugin usable out of the box.
        if (profiles.isEmpty()) {
            profiles.add(
                CommentProfileState(
                    name = "",
                    extensions = mutableListOf("c", "cpp", "cc", "cxx", "h", "hpp", "hh"),
                    commentStrings = mutableListOf("//"),
                    insertPosition = InsertPosition.AFTER_INDENT,
                ),
            )
        }
    }
}

/**
 * Where the comment string should be inserted relative to the line start.
 */
enum class InsertPosition {
    /**
     * Insert comment at the very start of the line (column 0), before any whitespace.
     */
    COLUMN_START,

    /**
     * Insert comment after the leading whitespace (spaces/tabs) of the line.
     */
    AFTER_INDENT,

    /**
     * Insert comment aligned to the previous comment column (or the line's indentation for the first line).
     */
    ALIGN_TO_PREVIOUS,
}

/**
 * Persistent state for a single comment rule.
 *
 * - [extensions] contains file extensions without dots, e.g. "cpp", "h", "kt".
 * - [commentStrings] contains all strings that should be removed from a line if present.
 *   The first string is the one that will be added when toggling on (this is the "A" string).
 */
data class CommentProfileState(
    var name: String = "",
    var extensions: MutableList<String> = mutableListOf(),
    var commentStrings: MutableList<String> = mutableListOf(),
    var insertPosition: InsertPosition = InsertPosition.AFTER_INDENT,
    var addIndentOnEmptyAlign: Boolean = false,
    var skipEmptyLines: Boolean = false,
)


