package io.k6.ide.plugin.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.PsiLocation
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.util.net.NetUtils
import com.sun.net.httpserver.HttpServer
import io.k6.ide.plugin.actions.TOKEN_ENV_NAME
import io.k6.ide.plugin.settings.K6Settings
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.util.*

class K6ConsoleProperties(val config: K6RunConfig, executor: Executor): SMTRunnerConsoleProperties(config, "k6", executor), SMCustomMessagesParsing {
    @Volatile
    var k6TestEventsConverter: K6TestEventsConverter? = null
    override fun createTestEventsConverter(
        testFrameworkName: String,
        consoleProperties: TestConsoleProperties
    ): OutputToGeneralTestEventsConverter {
        return K6TestEventsConverter(consoleProperties).also { k6TestEventsConverter = it }
    }

    override fun getTestLocator() = SMTestLocator { protocol, path, project, scope ->
        if (protocol != "k6") return@SMTestLocator emptyList()
        val script = config.data.script?.toVFile() ?: return@SMTestLocator emptyList()
        val psiFile = PsiManager.getInstance(project).findFile(script) ?: return@SMTestLocator emptyList()
        val element = psiFile.findElementAt(psiFile.text.indexOf(path)) ?: return@SMTestLocator emptyList()
        return@SMTestLocator listOf(PsiLocation(element))
    }
}

internal fun String.toVFile(base: String? = null) = LocalFileSystem.getInstance().findFileByIoFile(File(base, this))

private const val portEvnKey = "intellij_plugin_test_port" // <==> port in /resources/wrapper.js

class K6RunState(val myEnv: ExecutionEnvironment, val myRunConfiguration: K6RunConfig) : RunProfileState {
    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        val myConsoleProperties = K6ConsoleProperties(myRunConfiguration, myEnv.executor)
        val createConsole = SMTestRunnerConnectionUtil.createConsole( myConsoleProperties.testFrameworkName, myConsoleProperties)
        val data = myRunConfiguration.data
        val generalCommandLine = GeneralCommandLine("k6", if (data.type == RunType.local) "run" else "cloud",
            if (data.thresholdsAsTests) wrapScript(data.script ?: error("undefined script path")) else data.script,
            *translateCommandline(data.additionalParams ?: "")
        )
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        val commandLine = if (data.pty) PtyCommandLine(generalCommandLine).withInitialColumns(120) else generalCommandLine
        commandLine.charset = Charset.forName("UTF-8")
        commandLine.environment.putAll(data.envs)
        val testPort = if (data.thresholdsAsTests) {
            NetUtils.findAvailableSocketPort().also { commandLine.environment[portEvnKey] = it.toString() }
        } else 0
        if (data.type == RunType.cloud) {
            K6Settings.instance.cloudToken.takeIf { it.isNotBlank() }?.let { commandLine.environment.put(TOKEN_ENV_NAME, it)}
        }
        myEnv.project.guessProjectDir()?.let {
            commandLine.setWorkDirectory(it.path)
        }

        val processHandler = KillableColoredProcessHandler(commandLine)
        createConsole.attachToProcess(processHandler)
        processHandler.setHasPty(true)
        val testName = File(data.script!!).name
        val location = LocalFileSystem.getInstance().findFileByPath(data.script!!)?.url
        val smTestProxy = (createConsole as SMTRunnerConsoleView).resultsViewer.root as SMTestProxy.SMRootTestProxy
        smTestProxy.setTestsReporterAttached()
        smTestProxy.setSuiteStarted()
        fun testNode() = smTestProxy.children.first()
        processHandler.addProcessListener(object : ProcessAdapter() {
            lateinit var server : HttpServer

            init {
                if (data.thresholdsAsTests) {
                    server = HttpServer.create(InetSocketAddress(testPort), 0)
                    server.createContext("/") {
                        val response = ""
                        val readAllBytes = it.requestBody.readAllBytes()
                        val req = readAllBytes.toString(Charset.defaultCharset())
                        myConsoleProperties.k6TestEventsConverter?.processConsistentText(req, ProcessOutputTypes.SYSTEM)
                        it.sendResponseHeaders(200, response.length.toLong())
                        val os = it.responseBody
                        os.write(response.toByteArray())
                        os.close()
                    }
                    server.executor = null
                    server.start()
                }
            }

            override fun startNotified(event: ProcessEvent) {
                myConsoleProperties.k6TestEventsConverter?.processConsistentText("##teamcity[testSuiteStarted name='$testName' locationHint='$location']", ProcessOutputTypes.SYSTEM)
            }

            override fun processTerminated(event: ProcessEvent) {
                if (data.thresholdsAsTests) server.stop(0)
                if (event.exitCode != 0 && !data.thresholdsAsTests) {
                    testNode().setTestFailed("", null, false)
                    smTestProxy.setTestFailed("", null, false)
                }
            }

            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
                testNode().setFinished()
                smTestProxy.setFinished()
            }
        })
        return DefaultExecutionResult(createConsole, processHandler)
    }

    private fun wrapScript(script: String): String {
        val wrapper = this::class.java.getResourceAsStream("/wrapper.js")?.readBytes()?.toString(Charset.defaultCharset())
            ?: error("unable to extract wrapper")
        val wrapped = wrapper.replace("\$PATH\$", File(script).absolutePath.replace("\\", "/"))
        return File(System.getProperty("java.io.tmpdir"), File(script).name).also { it.writeText(wrapped); it.deleteOnExit() }.absolutePath
    }
}

/* this function was taken from the ant lib (ant:ant:1.6.5) */
fun translateCommandline(toProcess: String): Array<String> {
    if (toProcess.isEmpty()) {
        return emptyArray()
    }

    val normal = 0
    val inQuote = 1
    val inDoubleQuote = 2
    var state = normal
    val tok = StringTokenizer(toProcess, "\"\' ", true)
    val result = mutableListOf<String>()
    var current = StringBuffer()
    var lastTokenHasBeenQuoted = false
    while (tok.hasMoreTokens()) {
        val nextTok = tok.nextToken()
        when (state) {
            inQuote -> if ("\'" == nextTok) {
                lastTokenHasBeenQuoted = true
                state = normal
            } else {
                current.append(nextTok)
            }
            inDoubleQuote -> if ("\"" == nextTok) {
                lastTokenHasBeenQuoted = true
                state = normal
            } else {
                current.append(nextTok)
            }
            else -> {
                if ("\'" == nextTok) {
                    state = inQuote
                } else if ("\"" == nextTok) {
                    state = inDoubleQuote
                } else if (" " == nextTok) {
                    if (lastTokenHasBeenQuoted || current.isNotEmpty()) {
                        result.add(current.toString())
                        current = StringBuffer()
                    }
                } else {
                    current.append(nextTok)
                }
                lastTokenHasBeenQuoted = false
            }
        }
    }
    if (lastTokenHasBeenQuoted || current.isNotEmpty()) {
        result.add(current.toString())
    }
    if (state == inQuote || state == inDoubleQuote) {
        error("unbalanced quotes in $toProcess")
    }
    return result.toTypedArray()
}

class K6TestEventsConverter(consoleProperties: TestConsoleProperties) : OutputToGeneralTestEventsConverter("k6", consoleProperties) {

    public override fun processConsistentText(text: String, outputType: Key<*>) {
        super.processConsistentText(text, outputType)
    }
}
