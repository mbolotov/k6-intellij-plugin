package io.k6.ide.plugin.actions

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.ide.projectView.impl.ProjectViewTree
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import io.k6.ide.plugin.run.isK6Supported
import io.k6.ide.plugin.settings.K6Settings
import io.k6.ide.plugin.settings.K6SettingsConfigurable
import java.nio.charset.Charset
import javax.swing.tree.DefaultMutableTreeNode

const val TOKEN_ENV_NAME = "K6_CLOUD_TOKEN"
const val K6_NOTIFICATION_GROUP = "k6"

open class RunK6ActionBase(private val command: String) : AnAction() {

    override fun update(e: AnActionEvent) {
        if (e.extractSupportedFile() == null) {
            e.presentation.isVisible = false
        }
    }

    open fun configureCommandLine(project: Project, commandLine: GeneralCommandLine) = true

    override fun actionPerformed(e: AnActionEvent) {
        val file = e.extractSupportedFile()?.also { if (!it.isInLocalFileSystem) {
            showBalloon("The k6 plugin currently only works with files saved on disk")
            return
        } } ?: return
        val project = e.project ?: return
        val generalCommandLine = GeneralCommandLine("k6", command, file.path)
        generalCommandLine.charset = Charset.forName("UTF-8")
        project.guessProjectDir()?.let {
            generalCommandLine.setWorkDirectory(it.path)
        }

        if (!configureCommandLine(project, generalCommandLine)) {
            return
        }

        val processHandler = try {
            OSProcessHandler(generalCommandLine)
        } catch (e: Exception) {
            showBalloon("Unable to start k6: ${e.localizedMessage}")
            return
        }
        RunContentExecutor(project, processHandler).run()
    }

    private fun showBalloon(msg: String) {
        Notifications.Bus.notify(Notification(K6_NOTIFICATION_GROUP, "", msg, NotificationType.INFORMATION))
    }
}

class RunK6Action : RunK6ActionBase("run")

class RunK6CloudAction : RunK6ActionBase("cloud") {
    override fun configureCommandLine(project: Project, commandLine: GeneralCommandLine) : Boolean {
        val token = K6Settings.instance.cloudToken.takeIf { it.isNotBlank() } ?: commandLine.effectiveEnvironment[TOKEN_ENV_NAME] ?: run {
            val msg = "This option requires you to either have a cloud token added in your environment variables or in the k6 plugin settings"
            Notifications.Bus.notify(Notification(K6_NOTIFICATION_GROUP, "No cloud token", msg, NotificationType.ERROR)
                .addAction(object : NotificationAction("Edit settings") {
                    override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                        ShowSettingsUtil.getInstance().editConfigurable(project, K6SettingsConfigurable())
                    }
                }))
            return false
        }
        commandLine.environment[TOKEN_ENV_NAME] = token
        return true
    }
}

private fun AnActionEvent.extractSupportedFile() = extractFile()?.takeIf { it.isK6Supported() }

private fun AnActionEvent.extractFile() : VirtualFile? {
    getData(PlatformDataKeys.FILE_EDITOR)?.file?.let { return it }
    (getData(PlatformDataKeys.CONTEXT_COMPONENT) as? ProjectViewTree)?.selectionPath?.lastPathComponent?.let {
        ((it as? PsiFileNode)?.virtualFile ?: ((it as? DefaultMutableTreeNode)?.userObject as? PsiFileNode)?.virtualFile)?.let { return it }
    }

    return null
}
