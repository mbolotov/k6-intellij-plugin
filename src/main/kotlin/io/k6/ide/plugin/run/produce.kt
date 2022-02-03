package io.k6.ide.plugin.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment
import com.intellij.lang.javascript.JSKeywordElementType
import com.intellij.lang.javascript.JSTokenTypes
import com.intellij.lang.javascript.psi.JSFile
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.containers.ContainerUtil

class K6Producer : LazyRunConfigurationProducer<K6RunConfig>() {
    override fun getConfigurationFactory() =
        ConfigurationTypeUtil.findConfigurationType(K6RunConfigurationType::class.java).configurationFactories[0]

    override fun isConfigurationFromContext(configuration: K6RunConfig, context: ConfigurationContext): Boolean {
        val script = configuration.data.script ?: return false
        val file = context.location?.virtualFile ?: return false
        return file == LocalFileSystem.getInstance().findFileByPath(script)
    }

    override fun setupConfigurationFromContext(
        configuration: K6RunConfig,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val file = context.location?.virtualFile ?: return false
        if (!file.isK6Supported()) {
            return false
        }
        configuration.data.script = file.path
        configuration.setGeneratedName()
        return true
    }
}

class K6RunLineMarkerProvider : RunLineMarkerContributor() {
    private val allActions = ExecutorAction.getActions(0).filter { it.toString().startsWith("Run context configuration") }.toTypedArray()

    override fun getInfo(e: PsiElement): Info? {
        if (e !is LeafElement || e.containingFile !is JSFile || !e.containingFile.virtualFile.isK6Supported()) {
            return null
        }
        val elementType = PsiUtilCore.getElementType(e as PsiElement)
        if ((elementType is JSKeywordElementType && elementType.keyword == "function" || elementType == JSTokenTypes.EQGT) && e.parent.parent is ES6ExportDefaultAssignment) {
            val actions = allActions
            return object : Info(
                AllIcons.RunConfigurations.TestState.Run,
                { element1 -> StringUtil.join(ContainerUtil.mapNotNull<AnAction, String>(actions) { action -> getText(action, element1) }, "\n") },
                *actions) {
                override fun shouldReplace(other: Info): Boolean {
                    return true
                }
            }
        }
        return null
    }
}
