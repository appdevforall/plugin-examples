package org.appdevforall.codeonthego.computervision.domain.metadata.recovery

import org.appdevforall.codeonthego.computervision.domain.parser.AttributeKey
import org.appdevforall.codeonthego.computervision.domain.parser.AttributeRegexPatterns

internal object PasswordMetadataRecovery {
    private const val EDIT_TEXT_TAG = "EditText"
    private const val PASSWORD_INPUT_TYPE = "textPassword"

    fun recover(metadata: ParsedMetadata, destination: MutableMap<String, String>) {
        if (metadata.androidTag != EDIT_TEXT_TAG || AttributeKey.INPUT_TYPE.xmlName in destination) return
        if (metadata.rawText.containsPasswordLikeFragment()) {
            destination[AttributeKey.INPUT_TYPE.xmlName] = PASSWORD_INPUT_TYPE
        }
    }

    private fun String.containsPasswordLikeFragment(): Boolean {
        return lowercase()
            .replace(AttributeRegexPatterns.NON_LETTERS, "")
            .contains("password")
    }
}
