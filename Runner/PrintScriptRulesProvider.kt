package com.grupo14IngSis.snippetSearcherRunner.service

import com.grupo14IngSis.snippetSearcherRunner.dto.LanguageRuleEntry

class PrintScriptRulesProvider : RulesProvider {
    private val formattingRules =
        mapOf(
            "enforce-spacing-around-equals" to true,
            "enforce-no-spacing-around-equals" to false,
            "enforce-spacing-before-colon-in-declaration" to false,
            "enforce-spacing-after-colon-in-declaration" to true,
            "line-breaks-after-println" to true,
            "line-breaks-before-println" to true,
            "indent-inside-if" to true,
            "if-brace-below-line" to true,
            "mandatory-single-space-separation" to true,
            "mandatory-space-surrounding-operations" to true,
            "mandatory-line-break-after-statement" to true,
        )

    private val lintingRules =
        mapOf(
            "identifier_format" to true,
            "mandatory-variable-or-literal-in-println" to true,
            "mandatory-variable-or-literal-in-readInput" to true,
        )

    override fun getFormattingRules(version: String?): LanguageRuleEntry {
        return LanguageRuleEntry("printscript", formattingRules)
    }

    override fun getLintingRules(version: String?): LanguageRuleEntry {
        return LanguageRuleEntry("printscript", lintingRules)
    }
}
