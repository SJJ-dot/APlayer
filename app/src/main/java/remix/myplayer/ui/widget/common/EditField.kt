package remix.myplayer.ui.widget.common

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import remix.myplayer.R
import remix.myplayer.ui.theme.LocalTheme
import remix.myplayer.ui.theme.icon

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EditField(
  value: String,
  labelRes: Int,
  modifier: Modifier = Modifier,
  isError: Boolean = false,
  isLast: Boolean = false,
  maxLine: Int = 1,
  contentType: ContentType? = null,
  keyboardType: KeyboardType = KeyboardType.Text,
  isPassword: Boolean = false,
  onDone: () -> Unit = {},
  onValueChange: (String) -> Unit,
) {
  val theme = LocalTheme.current
  var showPassword by remember { mutableStateOf(false) }

  OutlinedTextField(
    value = value,
    // singleLine 会真正过滤换行符（maxLines 仅控制显示行数），避免粘贴带入不可见 \n 污染凭据
    singleLine = maxLine == 1,
    maxLines = maxLine,
    keyboardActions = KeyboardActions(onDone = {
      onDone()
    }),
    keyboardOptions = if (!isLast) KeyboardOptions(
      imeAction = ImeAction.Next,
      keyboardType = keyboardType
    ) else KeyboardOptions(
      imeAction = ImeAction.Done,
      keyboardType = keyboardType
    ),
    visualTransformation = if (isPassword && !showPassword) {
      PasswordVisualTransformation()
    } else {
      VisualTransformation.None
    },
    isError = isError,
    onValueChange = onValueChange,
    label = {
      TextPrimary(stringResource(labelRes))
    },
    trailingIcon = if (isPassword) {
      {
        IconButton(onClick = { showPassword = !showPassword }) {
          Icon(
            painter = painterResource(
              if (showPassword) R.drawable.ic_visibility_off_24dp else R.drawable.ic_visibility_24dp
            ),
            contentDescription = "TogglePasswordVisible",
            tint = theme.icon()
          )
        }
      }
    } else {
      null
    },
    colors = OutlinedTextFieldDefaults.colors(
      focusedTextColor = theme.textPrimary,
      unfocusedTextColor = theme.textPrimary,
      errorTextColor = theme.textPrimary,
      cursorColor = theme.primary,
      errorCursorColor = theme.primary,
      focusedContainerColor = Color.Transparent,
      unfocusedContainerColor = Color.Transparent,
      errorContainerColor = Color.Transparent
    ),
    modifier = Modifier
      .semantics {
        if (contentType != null) {
          this.contentType = contentType
        }
      }
      .then(modifier)
  )
}
