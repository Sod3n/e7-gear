package e7.gearscan.ui

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

/** User's explicit exception: the optimizer's existing numeric stat fields may use the keyboard.
 * Only the pre-existing numeric controls in Sheets.kt may call this. */
@Composable
internal fun LegacyStatField(value: String, onValueChange: (String) -> Unit, textStyle: TextStyle,
                             singleLine: Boolean = true, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
                             keyboardActions: KeyboardActions = KeyboardActions.Default, modifier: Modifier = Modifier) {
    BasicTextField(value, onValueChange, textStyle = textStyle, singleLine = singleLine,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, modifier = modifier)
}
