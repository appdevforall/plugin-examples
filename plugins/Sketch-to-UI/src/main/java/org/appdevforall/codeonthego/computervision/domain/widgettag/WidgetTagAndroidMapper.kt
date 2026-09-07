package org.appdevforall.codeonthego.computervision.domain.widgettag

internal object WidgetTagAndroidMapper {
    fun androidTagFor(normalizedTag: String): String = when {
        normalizedTag.startsWith("T-") -> "EditText"
        normalizedTag.startsWith("B-") -> "Button"
        normalizedTag.startsWith("P-") -> "ImageView"
        normalizedTag.startsWith("SW-") -> "Switch"
        normalizedTag.startsWith("C-") -> "CheckBox"
        normalizedTag.startsWith("R-") -> "RadioButton"
        normalizedTag.startsWith("D-") -> "Spinner"
        else -> "View"
    }
}
