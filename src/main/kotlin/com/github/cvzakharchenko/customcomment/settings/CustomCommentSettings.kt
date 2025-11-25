package com.github.cvzakharchenko.customcomment.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.OptionTag
import com.intellij.util.xmlb.annotations.XCollection

/**
 * Enum for comment insertion position modes.
 */
enum class InsertPosition {
    /** Insert at column 0 */
    FIRST_COLUMN,
    /** Insert after leading whitespace */
    AFTER_WHITESPACE,
    /** Insert at the same column as the previous comment, or earlier if line content starts before */
    ALIGN_WITH_PREVIOUS
}

/**
 * Represents a single comment configuration for a set of file extensions or language.
 * 
 * @property commentStrings List of comment strings. The first one is used when adding,
 *                         all of them are checked when removing.
 * @property fileExtensions Set of file extensions (without dot) where this config applies.
 * @property languageId Optional language ID (e.g., "JAVA", "kotlin"). If set, takes precedence over extensions.
 * @property insertPosition The position mode for inserting comments.
 * @property indentEmptyLines When true and using ALIGN_WITH_PREVIOUS, add indent to empty lines matching previous line.
 * @property skipEmptyLines When true, don't add comments to empty lines.
 */
data class CommentConfiguration(
    var commentStrings: MutableList<String> = mutableListOf(),
    var fileExtensions: MutableSet<String> = mutableSetOf(),
    var languageId: String = "",
    var insertPosition: InsertPosition = InsertPosition.FIRST_COLUMN,
    var indentEmptyLines: Boolean = false,
    var skipEmptyLines: Boolean = false
) {
    /**
     * Returns the primary comment string (the one to add).
     */
    fun getPrimaryComment(): String = commentStrings.firstOrNull() ?: ""
    
    /**
     * Checks if this configuration matches the given file extension.
     */
    fun matchesExtension(extension: String): Boolean {
        return fileExtensions.any { it.equals(extension, ignoreCase = true) }
    }
    
    /**
     * Checks if this configuration matches the given language ID.
     */
    fun matchesLanguage(langId: String): Boolean {
        return languageId.isNotEmpty() && languageId.equals(langId, ignoreCase = true)
    }
    
    /**
     * Returns a display name for this configuration.
     */
    fun getDisplayName(): String {
        return when {
            languageId.isNotEmpty() -> "Language: $languageId"
            fileExtensions.isNotEmpty() -> "Extensions: ${fileExtensions.joinToString(", ")}"
            else -> "Unconfigured"
        }
    }
    
    /**
     * Returns a display string for the insert position.
     */
    fun getPositionDisplayName(): String {
        return when (insertPosition) {
            InsertPosition.FIRST_COLUMN -> "First column"
            InsertPosition.AFTER_WHITESPACE -> "After whitespace"
            InsertPosition.ALIGN_WITH_PREVIOUS -> "Align with previous"
        }
    }
    
    /**
     * Creates a deep copy of this configuration.
     */
    fun copy(): CommentConfiguration {
        return CommentConfiguration(
            commentStrings = commentStrings.toMutableList(),
            fileExtensions = fileExtensions.toMutableSet(),
            languageId = languageId,
            insertPosition = insertPosition,
            indentEmptyLines = indentEmptyLines,
            skipEmptyLines = skipEmptyLines
        )
    }
}

/**
 * Application-level settings state for custom comments.
 */
@State(
    name = "CustomCommentSettings",
    storages = [Storage("CustomCommentSettings.xml")]
)
@Service(Service.Level.APP)
class CustomCommentSettings : PersistentStateComponent<CustomCommentSettings> {
    
    @OptionTag
    @XCollection(style = XCollection.Style.v2)
    var configurations: MutableList<CommentConfiguration> = mutableListOf()
    
    init {
        // Provide sensible defaults to make the plugin usable out of the box
        if (configurations.isEmpty()) {
            configurations.add(
                CommentConfiguration(
                    commentStrings = mutableListOf("// "),
                    fileExtensions = mutableSetOf("c", "cpp", "cc", "cxx", "h", "hpp", "hh"),
                    insertPosition = InsertPosition.AFTER_WHITESPACE
                )
            )
        }
    }
    
    override fun getState(): CustomCommentSettings = this
    
    override fun loadState(state: CustomCommentSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }
    
    /**
     * Finds a matching configuration for the given file extension and language ID.
     * Language ID takes precedence over file extension.
     */
    fun findConfiguration(extension: String?, languageId: String?): CommentConfiguration? {
        // First, try to match by language ID
        if (!languageId.isNullOrEmpty()) {
            configurations.find { it.matchesLanguage(languageId) }?.let { return it }
        }
        
        // Then, try to match by file extension
        if (!extension.isNullOrEmpty()) {
            configurations.find { it.matchesExtension(extension) }?.let { return it }
        }
        
        return null
    }
    
    companion object {
        @JvmStatic
        fun getInstance(): CustomCommentSettings = service()
    }
}
