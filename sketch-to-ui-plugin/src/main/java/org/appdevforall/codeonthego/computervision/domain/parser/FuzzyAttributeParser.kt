package org.appdevforall.codeonthego.computervision.domain.parser

import org.appdevforall.codeonthego.computervision.domain.grammar.UiGrammarValidator
import org.appdevforall.codeonthego.computervision.domain.parser.recovery.WidgetAttributeRecovery
import org.appdevforall.codeonthego.computervision.domain.parser.sanitizer.OcrSanitizerFactory

object FuzzyAttributeParser {
    private val grammarValidator = UiGrammarValidator()
    private val sanitizer = OcrSanitizerFactory.createDefaultSanitizer()

    fun parse(annotation: String?, tag: String): Map<String, String> {
        if (annotation.isNullOrBlank()) return emptyMap()

        val normalizedInput = sanitizer.sanitize(annotation.replace(AttributeRegexPatterns.SPACES_BEFORE_COLON, ":"))
        val tokens = AttributeTokenMapper.tokenize(normalizedInput)
        val parsedAttributes = AttributeTokenMapper.mapTokensToAttributes(tokens, tag)
        val recoveredAttributes = WidgetAttributeRecovery.recoverMissingAttributes(parsedAttributes, normalizedInput, tag)
        val outputAttributes = WidgetAttributeRecovery.enforceOutputRules(recoveredAttributes, normalizedInput, tag)

        return grammarValidator.enforceGrammar(outputAttributes, tag)
    }
}
