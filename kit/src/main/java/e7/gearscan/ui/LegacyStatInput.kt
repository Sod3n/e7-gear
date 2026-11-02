package e7.gearscan.ui

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

/** User's explicit exceptions: the optimizer's existing numeric stat fields (Sheets.kt) and, on tap of the value,
 * the Sell review sliders (SellParts.kt, user 2026-11-01: "keep option to just press on number to use keyboard"). */
@Composable
internal fun LegacyStatField(value: String, onValueChange: (String) -> Unit, textStyle: TextStyle,
                             singleLine: Boolean = true, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
                             keyboardActions: KeyboardActions = KeyboardActions.Default, modifier: Modifier = Modifier) {
    BasicTextField(value, onValueChange, textStyle = textStyle, singleLine = singleLine,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, modifier = modifier)
}

/** Same exception, with selection: the Sell review value opens with its text selected. */
@Composable
internal fun LegacyStatFieldValue(value: androidx.compose.ui.text.input.TextFieldValue, onValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit, textStyle: TextStyle,
                                  keyboardOptions: KeyboardOptions, keyboardActions: KeyboardActions, modifier: Modifier = Modifier) {
    BasicTextField(value, onValueChange, textStyle = textStyle, singleLine = true, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, modifier = modifier)
}
