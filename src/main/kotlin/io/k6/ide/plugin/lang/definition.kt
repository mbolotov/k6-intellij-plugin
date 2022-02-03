package io.k6.ide.plugin.lang

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.*
import com.intellij.lang.ParserDefinition.SpaceRequirements
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.impl.JSLiteralExpressionImpl
import com.intellij.lexer.FlexAdapter
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.SingleLazyInstanceSyntaxHighlighterFactory
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PsiElementPattern
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiUtilBase
import com.intellij.util.ProcessingContext
import io.k6.ide.plugin.K6Icons
import io.k6.ide.plugin.lang.ThresholdTypes.*
import kotlin.math.max


object ThresholdLang : Language("K6Threshold")

class ThresholdTokenType(debugName: String) : IElementType(debugName, ThresholdLang)

class ThresholdElementType(debugName: String) : IElementType(debugName, ThresholdLang) {
    override fun toString(): String {
        return "ThresholdElementType.${super.toString()}"
    }
}

class ThresholdLexerAdapter : FlexAdapter(_ThresholdLexer(null))

object ThresholdFileType : LanguageFileType(ThresholdLang) {
  object DEFAULTS {
    const val DESCRIPTION = "K6 Threshold Expression"
  }

  override fun getName() = DEFAULTS.DESCRIPTION
  override fun getDescription() = DEFAULTS.DESCRIPTION
  override fun getDefaultExtension() = "thr"
  override fun getIcon() = K6Icons.k6
}



class ThresholdParserDefinition : ParserDefinition {
    override fun createLexer(project: Project): Lexer {
        return ThresholdLexerAdapter()
    }

    override fun getWhitespaceTokens(): TokenSet {
        return WHITE_SPACES
    }

    override fun getCommentTokens(): TokenSet {
        return TokenSet.EMPTY
    }

    override fun getStringLiteralElements(): TokenSet {
        return TokenSet.EMPTY
    }

    override fun createParser(project: Project): PsiParser {
        return ThresholdParser()
    }

    override fun getFileNodeType(): IFileElementType {
        return FILE
    }

    override fun createFile(viewProvider: FileViewProvider): PsiFile {
        return object : PsiFileBase(viewProvider, ThresholdLang) {
            override fun getFileType() = ThresholdFileType
        }
    }

    override fun spaceExistenceTypeBetweenTokens(left: ASTNode, right: ASTNode): SpaceRequirements {
        return SpaceRequirements.MAY
    }

    override fun createElement(node: ASTNode): PsiElement {
        return ThresholdTypes.Factory.createElement(node)
    }

    companion object {
        val WHITE_SPACES = TokenSet.create(TokenType.WHITE_SPACE)
        val FILE = IFileElementType(ThresholdLang)
    }
}

class K6ThresholdInjector : MultiHostInjector {
    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        if (context is JSLiteralExpressionImpl && isUnderThresholdExpression(context)) {
            registrar.startInjecting(ThresholdLang).addPlace(null, null, context, TextRange(1, context.textLength - 1))
                .doneInjecting()
        }
    }

    private fun isUnderThresholdExpression(context: JSLiteralExpression): Boolean {
        return context.parent is JSArrayLiteralExpression && context.parent.parent is JSProperty &&
            (context.parent.parent.parent.parent as? JSProperty)?.let { it.name == "thresholds" } ?: false
    }

    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(JSLiteralExpressionImpl::class.java)
}

val thrExprPattern = object : PsiElementPattern.Capture<PsiElement>(PsiElement::class.java) {
    override fun accepts(o: Any?, context: ProcessingContext): Boolean {
        val element = o as? PsiElement ?: return false
        context.put("element", element)
        return true
    }
}


class ThresholdBraceMatcher : PairedBraceMatcher {

    private val myPairs = arrayOf(
        BracePair(LPAREN, ThresholdTypes.RPAREN, false),
    )

    override fun getPairs() = myPairs

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean {
        return true
    }

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int) = openingBraceOffset

}

val aggregations = setOf("count ", "rate ", "value ", "avg ", "min ", "max ", "med ", "p()")

class ThresholdCompletionContributor :  CompletionContributor() {
    override fun beforeCompletion(context: CompletionInitializationContext) {
        context.dummyIdentifier = "avg"
    }
    private val operatorChars = setOf('>', '=', '<')
    private val operators = setOf(">", "<", "=", ">=", "<=")


    init {
        extend(CompletionType.BASIC, thrExprPattern, object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(
                parameters: CompletionParameters,
                context: ProcessingContext,
                result: CompletionResultSet
            ) {
                val element = context.get("element") as? PsiElement ?: return
                val allText = PsiUtilBase.getRoot(element.node).text
                val operatorInd = allText.indexOfFirst { it in operatorChars }

                val trimmed = allText.trimStart()
                val method = aggregations.find { trimmed.startsWith(it) }
                val methodInd = method?.let { allText.indexOf(it) + it.length - 1} ?: -1
                val completions = when {
                    operatorInd >= 0 && parameters.offset < operatorInd - 1 || methodInd >= 0 && parameters.offset <= methodInd -> aggregations
                    operatorInd >= 0 && parameters.offset in max(0, operatorInd-1)..operatorInd+1 || operatorInd < 0 && methodInd >= 0 && parameters.offset > methodInd -> operators
                    operatorInd >= 0 && parameters.offset > operatorInd + 1 -> emptySet()
                    else -> aggregations
                }

                result.addAllElements(completions.map { LookupElementBuilder.create(it).withIcon(K6Icons.k6) })
            }
        })
    }
}

class ThresholdHighlighterFactory : SingleLazyInstanceSyntaxHighlighterFactory() {
  override fun createHighlighter() = ThresholdHighlighter
}

object ThresholdHighlighter : SyntaxHighlighterBase() {
  override fun getHighlightingLexer() = ThresholdLexerAdapter()

  override fun getTokenHighlights(tokenType: IElementType): Array<out TextAttributesKey> {
    return pack(tokenMapping[tokenType])
  }

  private val tokenMapping: Map<IElementType, TextAttributesKey> = mapOf(
    LPAREN to ThresholdColor.PARENTHESES,
    RPAREN to ThresholdColor.PARENTHESES,

    FIXEDNUMBER to ThresholdColor.NUMBER,
  ).plus(
    keywords().map { it to ThresholdColor.KEYWORD }
  ).plus(
    operators().map { it to ThresholdColor.OPERATION_SIGN }
  ).mapValues { it.value.textAttributesKey }

  private fun keywords() = setOf(
      COUNT, RATE, VALUE, AVG, MIN, MAX, MED, PERCENTILE
  )

  private fun operators() = setOf<IElementType>(
    LESS, MORE, LESSEQ, MOREEQ,
    EQ
  )
}


enum class ThresholdColor(humanName: String, default: TextAttributesKey) {
  PARENTHESES("Parentheses", DefaultLanguageHighlighterColors.PARENTHESES),

  NUMBER("Number", DefaultLanguageHighlighterColors.NUMBER),
  KEYWORD("Keyword", DefaultLanguageHighlighterColors.KEYWORD),

  OPERATION_SIGN("Operation signs", DefaultLanguageHighlighterColors.OPERATION_SIGN),
  ;

  val textAttributesKey = TextAttributesKey.createTextAttributesKey("io.k6.ide.plugin.threshold.$name", default)
  val attributesDescriptor = AttributesDescriptor(humanName, textAttributesKey)
}
