package io.k6.ide.plugin.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.javascript.patterns.JSElementPattern
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSElement
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.patterns.InitialPatternCondition
import com.intellij.patterns.ObjectPattern
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parents
import com.intellij.util.ProcessingContext
import io.k6.ide.plugin.K6Icons

class My<T: JSElement>(klass: Class<T>): JSElementPattern<T, My<T>>(klass)

inline fun <reified T: JSElement> pattern(): My<T> = My(T::class.java)

val metrics = setOf("vus",
    "vus_max",
    "iterations",
    "iteration_duration",
    "dropped_iterations",
    "data_received",
    "data_sent",
    "checks",
    "http_reqs",
    "http_req_blocked",
    "http_req_connecting",
    "http_req_tls_handshaking",
    "http_req_sending",
    "http_req_waiting",
    "http_req_receiving",
    "http_req_duration")

val metricPattern = ObjectPattern.Capture(object : InitialPatternCondition<JSObjectLiteralExpression>(JSObjectLiteralExpression::class.java) {
    override fun accepts(o: Any?, context: ProcessingContext?): Boolean {
        val element = o as? PsiElement ?: return false
        val thr = element.parents.indexOfFirst { (it as? JSProperty)?.name == "thresholds" }.also { if (it < 0) return false }
        return element.parents.take(thr).none { it is JSArrayLiteralExpression }
    }
})


class ThresholdMetricCompletionContributor :  CompletionContributor() {
    init {
        extend(CompletionType.BASIC, metricPattern, object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                result.addAllElements(metrics.map { LookupElementBuilder.create(it).withIcon(K6Icons.k6) })
//                if (withinLiteral) {
//                    result.stopHere()
//                }
            }
        })
    }
}
