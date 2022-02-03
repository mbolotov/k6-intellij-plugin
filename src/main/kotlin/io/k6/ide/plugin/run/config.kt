package io.k6.ide.plugin.run

import com.devexperts.K6.plug.idea.run.K6ConfigurableEditorPanel
import com.intellij.execution.Executor
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.execution.configurations.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.xmlb.XmlSerializer
import io.k6.ide.plugin.K6Icons
import io.k6.ide.plugin.actions.TOKEN_ENV_NAME
import io.k6.ide.plugin.settings.K6Settings
import io.k6.ide.plugin.settings.K6SettingsConfigurable
import org.jdom.Element
import org.jetbrains.io.LocalFileFinder
import java.io.File

val supportedK6FileTypes = setOf("js")
fun VirtualFile?.isK6Supported() = this?.extension in supportedK6FileTypes

class K6RunConfigurationType : ConfigurationTypeBase("K6ConfigurationType", "k6", "Run k6 Script", K6Icons.k6) {
    init {
        addFactory(configurationFactory())
    }

    private fun configurationFactory(): ConfigurationFactory {
        return object : ConfigurationFactory(this) {
            override fun createTemplateConfiguration(p: Project): RunConfiguration {
                return K6RunConfig(p, this)
            }

            override fun getIcon() = K6Icons.k6

            override fun isApplicable(project: Project): Boolean {
                return true
            }

            override fun getId(): String {
                return name
            }
        }
    }
}

class K6RunConfig(project: Project, factory: ConfigurationFactory) :
    LocatableConfigurationBase<K6RunConfigurationType>(project, factory, "") {

    var data = K6RunData()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        return K6RunState(environment, this)
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        return K6ConfigurableEditorPanel(project)
    }

    override fun clone(): RunConfiguration {
        val res = super.clone() as K6RunConfig
        res.data = res.data.clone()
        return res
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        XmlSerializer.deserializeInto(this, element)
        XmlSerializer.deserializeInto(data, element)

        EnvironmentVariablesComponent.readExternal(element, data.envs)
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        XmlSerializer.serializeInto(this, element)
        XmlSerializer.serializeInto(data, element)

        EnvironmentVariablesComponent.writeExternal(element, data.envs)
    }

    override fun suggestedName(): String? {
        return getRelativePath(project, data.script ?: return null)
    }

    override fun getActionName(): String? {
        return getLastPathComponent(data.script ?: return null)
    }

    override fun checkConfiguration() {
        if (!File(data.script ?: "").isFile) {
            throw RuntimeConfigurationError("Script file does not exist: ${data.script}")
        }
        if (data.type == RunType.cloud) {
            if (K6Settings.instance.cloudToken.takeIf { it.isNotBlank() } ?: System.getenv(TOKEN_ENV_NAME) == null) {
                throw RuntimeConfigurationError(
                    "The cloud execution mode requires you to either have a cloud token added in your environment variables or in the k6 plugin settings",
                    Runnable {
                        ShowSettingsUtil.getInstance().editConfigurable(project, K6SettingsConfigurable())
                    }
                )
            }
        }
    }
}

private fun getRelativePath(project: Project, path: String): String {
    val file = LocalFileFinder.findFile(path)
    if (file != null && file.isValid) {
        val root = ProjectFileIndex.getInstance(project).getContentRootForFile(file)
        if (root != null && root.isValid) {
            val relativePath = VfsUtilCore.getRelativePath(file, root, File.separatorChar)
            relativePath?.let { return relativePath }
        }
    }
    return getLastPathComponent(path)
}

private fun getLastPathComponent(path: String): String {
    val lastIndex = path.lastIndexOf('/')
    return if (lastIndex >= 0) path.substring(lastIndex + 1) else path
}

enum class RunType { local, cloud }

class K6RunData : Cloneable {
    var script: String? = null

    var type = RunType.local

    var envs: MutableMap<String, String> = LinkedHashMap()

    var additionalParams: String? = null

    var pty = true

    var thresholdsAsTests = true

    public override fun clone(): K6RunData {
        try {
            val data = super.clone() as K6RunData
            data.envs = LinkedHashMap(envs)
            return data
        } catch (e: CloneNotSupportedException) {
            throw RuntimeException(e)
        }
    }
}
