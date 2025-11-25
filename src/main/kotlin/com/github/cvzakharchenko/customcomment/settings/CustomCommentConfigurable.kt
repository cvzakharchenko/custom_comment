package com.github.cvzakharchenko.customcomment.settings

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

/**
 * Settings page visible under "Settings | Tools | Custom Comment".
 */
class CustomCommentConfigurable : Configurable {

    private var settingsComponent: CustomCommentSettingsComponent? = null

    override fun getDisplayName(): String = "Custom Comment"

    override fun createComponent(): JComponent {
        val component = CustomCommentSettingsComponent()
        settingsComponent = component
        reset()
        return component.panel
    }

    override fun isModified(): Boolean {
        val settings = CustomCommentSettings.getInstance()
        val uiProfiles = settingsComponent?.getProfiles() ?: emptyList()
        return uiProfiles != settings.profiles
    }

    override fun apply() {
        val settings = CustomCommentSettings.getInstance()
        val uiProfiles = settingsComponent?.getProfiles() ?: emptyList()
        settings.profiles = uiProfiles.map { profile ->
            profile.copy(
                extensions = profile.extensions.toMutableList(),
                commentStrings = profile.commentStrings.toMutableList(),
            )
        }.toMutableList()
    }

    override fun reset() {
        val settings = CustomCommentSettings.getInstance()
        settingsComponent?.setProfiles(settings.profiles)
    }

    override fun disposeUIResources() {
        settingsComponent = null
    }

    override fun getPreferredFocusedComponent(): JComponent? =
        settingsComponent?.preferredFocusedComponent
}


