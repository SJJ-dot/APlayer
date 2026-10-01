package remix.myplayer.ui.widget.library

import android.graphics.Bitmap
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import remix.myplayer.data.model.audio.APlayerModel
import remix.myplayer.glide.addBitmapListener
import remix.myplayer.ui.theme.LocalTheme

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun GlideCover(
  modifier: Modifier = Modifier,
  model: APlayerModel,
  circle: Boolean = true,
  album: Boolean = true,
  /** 图片加载完成回调（取色用）；加载失败时 bitmap 为 null */
  onBitmapLoaded: ((Bitmap?) -> Unit)? = null
) {
  var coverModifier = modifier
  if (circle) {
    coverModifier = modifier.clip(CircleShape)
  }
  val placeHolder =
    if (album) LocalTheme.current.albumPlaceHolder else LocalTheme.current.artistPlaceHolder

  if (onBitmapLoaded == null) {
    GlideImage(
      model = model,
      failure = placeholder(placeHolder),
      loading = placeholder(placeHolder),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = coverModifier
    )
  } else {
    GlideImage(
      model = model,
      failure = placeholder(placeHolder),
      loading = placeholder(placeHolder),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = coverModifier
    ) { builder ->
      builder.addBitmapListener { bitmap -> onBitmapLoaded(bitmap) }
    }
  }
}
