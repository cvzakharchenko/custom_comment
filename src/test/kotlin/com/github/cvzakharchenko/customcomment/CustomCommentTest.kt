package com.github.cvzakharchenko.customcomment

import com.github.cvzakharchenko.customcomment.settings.CommentConfiguration
import com.github.cvzakharchenko.customcomment.settings.CustomCommentSettings
import com.github.cvzakharchenko.customcomment.settings.InsertPosition
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CustomCommentTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Set up a test configuration
        val settings = CustomCommentSettings.getInstance()
        settings.configurations.clear()
        settings.configurations.add(
            CommentConfiguration(
                commentStrings = mutableListOf("// TODO: ", "// FIXME: ", "// NOTE: "),
                fileExtensions = mutableSetOf("txt"),
                languageId = "",
                insertPosition = InsertPosition.FIRST_COLUMN
            )
        )
    }

    override fun tearDown() {
        CustomCommentSettings.getInstance().configurations.clear()
        super.tearDown()
    }

    fun testConfigurationMatching() {
        val settings = CustomCommentSettings.getInstance()
        
        // Test extension matching
        val config = settings.findConfiguration("txt", null)
        assertNotNull(config)
        assertEquals("// TODO: ", config!!.getPrimaryComment())
        
        // Test no match
        val noConfig = settings.findConfiguration("java", null)
        assertNull(noConfig)
    }

    fun testCommentConfigurationCopy() {
        val original = CommentConfiguration(
            commentStrings = mutableListOf("// A", "// B"),
            fileExtensions = mutableSetOf("cpp", "h"),
            languageId = "C++",
            insertPosition = InsertPosition.AFTER_WHITESPACE
        )
        
        val copy = original.copy()
        
        // Verify copy is equal but not same object
        assertEquals(original.commentStrings, copy.commentStrings)
        assertEquals(original.fileExtensions, copy.fileExtensions)
        assertEquals(original.languageId, copy.languageId)
        assertEquals(original.insertPosition, copy.insertPosition)
        
        // Verify it's a deep copy
        copy.commentStrings.add("// C")
        assertFalse(original.commentStrings.contains("// C"))
    }

    fun testDisplayName() {
        val configWithLanguage = CommentConfiguration(
            languageId = "JAVA"
        )
        assertEquals("Language: JAVA", configWithLanguage.getDisplayName())
        
        val configWithExtensions = CommentConfiguration(
            fileExtensions = mutableSetOf("cpp", "h")
        )
        assertTrue(configWithExtensions.getDisplayName().contains("Extensions:"))
    }
    
    fun testPositionDisplayName() {
        val configFirstColumn = CommentConfiguration(
            insertPosition = InsertPosition.FIRST_COLUMN
        )
        assertEquals("First column", configFirstColumn.getPositionDisplayName())
        
        val configAfterWhitespace = CommentConfiguration(
            insertPosition = InsertPosition.AFTER_WHITESPACE
        )
        assertEquals("After whitespace", configAfterWhitespace.getPositionDisplayName())
        
        val configAlignWithPrevious = CommentConfiguration(
            insertPosition = InsertPosition.ALIGN_WITH_PREVIOUS
        )
        assertEquals("Align with previous", configAlignWithPrevious.getPositionDisplayName())
    }
    
    fun testEmptyLineOptions() {
        val configWithOptions = CommentConfiguration(
            commentStrings = mutableListOf("// "),
            insertPosition = InsertPosition.ALIGN_WITH_PREVIOUS,
            indentEmptyLines = true,
            skipEmptyLines = false
        )
        
        assertTrue(configWithOptions.indentEmptyLines)
        assertFalse(configWithOptions.skipEmptyLines)
        
        val copy = configWithOptions.copy()
        assertEquals(configWithOptions.indentEmptyLines, copy.indentEmptyLines)
        assertEquals(configWithOptions.skipEmptyLines, copy.skipEmptyLines)
    }
}
