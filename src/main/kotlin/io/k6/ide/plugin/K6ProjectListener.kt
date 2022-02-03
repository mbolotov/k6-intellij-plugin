package io.k6.ide.plugin

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import io.k6.ide.plugin.actions.K6_NOTIFICATION_GROUP

internal class K6ProjectListener : ProjectManagerListener {
    private val which = if (java.io.File.separatorChar == '\\') "where" else "which"
    private val installationUrl = "https://k6.io/docs/getting-started/installation"

    override fun projectOpened(project: Project) {
        val line = GeneralCommandLine(which, "k6")
        runBackgroundableTask("Check k6 Installation", project, false) {
            val found = run {
                try {
                    val osProcessHandler = OSProcessHandler(line)
                    osProcessHandler.startNotify()

                    if (!osProcessHandler.waitFor(5000)) return@run false
                    return@run osProcessHandler.exitCode == 0
                } catch (e: Exception) {
                    false
                }
            }
            if (!found) {
                val msg = "Could not find any installation of k6 on your system. For this plugin to work, make sure it is available in your PATH"
                Notifications.Bus.notify(Notification(K6_NOTIFICATION_GROUP, "", msg, NotificationType.INFORMATION)
                    .addAction(object : NotificationAction("Go to k6 installation page") {
                        override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                            BrowserUtil.browse(installationUrl)
                        }
                    }))
            }
        }
    }
}
