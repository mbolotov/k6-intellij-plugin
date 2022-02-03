package com.devexperts.K6.plug.idea.run

import com.intellij.execution.configuration.EnvironmentVariablesTextFieldWithBrowseButton
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.PanelWithAnchor
import io.k6.ide.plugin.run.K6RunConfig
import io.k6.ide.plugin.run.RunType
import javax.swing.*

class K6ConfigurableEditorPanel(myProject: Project) : SettingsEditor<K6RunConfig>(), PanelWithAnchor, DumbAware {

    private lateinit var myWholePanel: JPanel
    private var anchor: JComponent? = null

    private lateinit var myScript: TextFieldWithBrowseButton
    private lateinit var myEnvs: EnvironmentVariablesTextFieldWithBrowseButton
    private lateinit var myArguments: JTextField
    private lateinit var myLocal: JRadioButton
    private lateinit var myCloud: JRadioButton
    private lateinit var myPty: JCheckBox
    private lateinit var myThresholdsAsTests: JCheckBox

    private lateinit var myPathLabel: JLabel
    private lateinit var myEnvLabel: JLabel

    private val radios : List<JRadioButton> get() = listOf(myLocal, myCloud)

    init {
        val scriptDescriptor = FileChooserDescriptor(true, false, false, false, false, false)
        scriptDescriptor.title = "k6 Script Path"
        scriptDescriptor.description = "Select path to k6 Script to be run"
        myScript.addBrowseFolderListener(
            scriptDescriptor.title,
            scriptDescriptor.description,
            myProject,
            scriptDescriptor
        )
        radios.forEach { it.addActionListener { e ->
            val find = radios.find { it == e.source } ?: return@addActionListener
            (radios - find).forEach { if (it.isSelected) it.isSelected = false }
            if (myCloud.isSelected) {
                myThresholdsAsTests.isEnabled = false
                myThresholdsAsTests.isSelected = false
            } else {
                myThresholdsAsTests.isEnabled = true
            }
        } }

        myPathLabel.labelFor = myScript
        myEnvLabel.labelFor = myEnvs
    }

    override fun resetEditorFrom(s: K6RunConfig) {
        s.data.apply {
            myScript.text = script ?: ""
            myEnvs.envs = envs.toMutableMap()
            myArguments.text = additionalParams
            val selected = if (type == RunType.local) myLocal else myCloud
            selected.isSelected = true
            (radios - selected).first().isSelected = false
            myPty.isSelected = pty
            myThresholdsAsTests.isSelected = thresholdsAsTests
            myThresholdsAsTests.isEnabled = type == RunType.local
        }
    }

    override fun applyEditorTo(s: K6RunConfig) {
        s.data.apply {
            script = myScript.text
            envs = myEnvs.envs.toMutableMap()
            additionalParams = myArguments.text
            type = if (myLocal.isSelected) RunType.local else RunType.cloud
            pty = myPty.isSelected
            thresholdsAsTests = myThresholdsAsTests.isSelected
        }
    }

    override fun createEditor() = myWholePanel

    override fun getAnchor() = anchor

    override fun setAnchor(anchor: JComponent?) {
        this.anchor = anchor
    }

}
