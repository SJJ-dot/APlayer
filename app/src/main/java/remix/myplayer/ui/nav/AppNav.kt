package remix.myplayer.ui.nav

import android.net.Uri
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.toRoute
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.savedstate.SavedState
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import remix.myplayer.R
import remix.myplayer.data.db.room.entity.PlayList
import remix.myplayer.data.db.room.entity.Smb
import remix.myplayer.data.db.room.entity.WebDav
import remix.myplayer.data.model.audio.APlayerModel
import remix.myplayer.data.model.audio.Album
import remix.myplayer.data.model.audio.Artist
import remix.myplayer.data.model.audio.Folder
import remix.myplayer.data.model.audio.Genre
import remix.myplayer.misc.cache.DiskCache
import remix.myplayer.ui.AppScaffold
import remix.myplayer.ui.dialog.DialogContainer
import remix.myplayer.ui.screen.AboutScreen
import remix.myplayer.ui.screen.CustomSortScreen
import remix.myplayer.ui.screen.EQScreen
import remix.myplayer.ui.screen.LastAddedScreen
import remix.myplayer.ui.screen.SearchScreen
import remix.myplayer.ui.screen.SongChooserScreen
import remix.myplayer.ui.screen.SupportScreen
import remix.myplayer.ui.screen.TagEditScreen
import remix.myplayer.ui.screen.crop.CropScreen
import remix.myplayer.ui.screen.detail.DetailScreen
import remix.myplayer.ui.screen.history.HistoryScreen
import remix.myplayer.ui.screen.home.HomeScreen
import remix.myplayer.ui.screen.setting.SettingDetailScreen
import remix.myplayer.ui.screen.setting.SettingScreen
import remix.myplayer.ui.screen.setting.ReplayGainSettingScreen
import remix.myplayer.ui.screen.source.SourceManageScreen
import remix.myplayer.ui.screen.smb.SmbDetailScreen
import remix.myplayer.ui.screen.webdav.WebDavDetailScreen
import remix.myplayer.util.Constants
import remix.myplayer.viewmodel.libraryViewModel
import remix.myplayer.viewmodel.smbViewModel
import remix.myplayer.viewmodel.webDavViewModel
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.typeOf

const val RouteHome = "home"
const val RouteSetting = "setting"
const val RouteSettingDetail = "setting_detail"
const val RouteReplayGain = "replay_gain"
const val RouteSongChoose = "song_choose"
const val RouteAbout = "about"
const val RouteCustomSort = "custom_sort"
const val RouteLastAdded = "last_added"
const val RouteHistory = "history"
const val RouteSearch = "search"
const val RouteTagEdit = "tag_edit"
const val RouteCustomCoverCrop = "custom_cover_crop"
const val RouteSourceManage = "source_manage"
const val RouteTagEditCrop = "tag_edit_crop"
const val RouteEq = "eq"
const val RouteSupport = "support"

/** 远程音源选目录模式：无播放 UI，底部“使用此目录”确认。仅传 id，实体从 ViewModel 列表解析 */
@Serializable
data class WebDavPickRoute(val id: Int)

@Serializable
data class SmbPickRoute(val id: Int)

// 存在目标页 NavBackStackEntry.savedStateHandle 里的标志，
// 标记该页是从播放页浮窗跳转过来的，退出时需要恢复浮窗
const val ExtraRestorePlayingScreen = "restore_playing_screen"

val playingScreenDeepLink = "aplayer://playingScreen".toUri()

@Composable
fun AppNav() {
  val snackBarHostState = remember { SnackbarHostState() }
  ProvideSnackBarHostState(snackBarHostState) {
    Box(modifier = Modifier.fillMaxSize()) {
      AppScaffold {
        Box(modifier = Modifier.fillMaxSize()) {
          DialogContainer()

          NavHost(LocalNavController.current, startDestination = RouteHome) {
            normalAnimatedScreen(
              RouteHome,
            ) {
              HomeScreen()
            }

            normalAnimatedScreen(RouteSetting) {
              SettingScreen()
            }

            normalAnimatedScreen(RouteSourceManage) {
              SourceManageScreen()
            }

            normalAnimatedScreen(
              "$RouteSettingDetail/{category}",
              arguments = listOf(navArgument("category") { type = NavType.StringType })
            ) {
              val category = it.arguments?.getString("category") ?: return@normalAnimatedScreen
              SettingDetailScreen(category)
            }

            normalAnimatedScreen(RouteReplayGain) {
              ReplayGainSettingScreen()
            }

            normalAnimatedScreen(
              "${RouteSongChoose}/{id}/{name}", arguments = listOf(navArgument("id") {
                type = NavType.LongType
              })
            ) {
              val id = it.arguments?.getLong("id") ?: return@normalAnimatedScreen
              val name = Uri.decode(it.arguments?.getString("name") ?: return@normalAnimatedScreen)
              SongChooserScreen(id, name)
            }

            normalAnimatedScreen(RouteAbout) {
              AboutScreen()
            }

            composable<DetailScreenRoute>(
              typeMap = mapOf(
                typeOf<Album?>() to ModelRouteType.album,
                typeOf<Artist?>() to ModelRouteType.artist,
                typeOf<Genre?>() to ModelRouteType.genre,
                typeOf<PlayList?>() to ModelRouteType.playList,
                typeOf<Folder?>() to ModelRouteType.folder,
              ),
              enterTransition = enterTransition(),
              exitTransition = exitTransition(),
              popEnterTransition = popEnterTransition(),
              popExitTransition = popExitTransition(),
            ) {
              val route = it.toRoute<DetailScreenRoute>()

              DetailScreen(route.findNotNull())
            }

            normalAnimatedScreen("${RouteCustomSort}/{id}", arguments = listOf(navArgument("id") {
              type = NavType.LongType
            })) {
              val id = it.arguments?.getLong("id") ?: return@normalAnimatedScreen
              CustomSortScreen(id)
            }

            normalAnimatedScreen(RouteLastAdded) {
              LastAddedScreen()
            }

            normalAnimatedScreen(RouteHistory) {
              HistoryScreen()
            }

            normalAnimatedScreen(RouteSearch) {
              SearchScreen()
            }

            normalAnimatedScreen(RouteTagEdit) {
              TagEditScreen(it)
            }

            composable<WebDav>(
              enterTransition = enterTransition(),
              exitTransition = exitTransition(),
              popEnterTransition = popEnterTransition(),
              popExitTransition = popExitTransition(),
            ) {
              val webDav = it.toRoute<WebDav>()
              WebDavDetailScreen(webDav)
            }

            composable<Smb>(
              enterTransition = enterTransition(),
              exitTransition = exitTransition(),
              popEnterTransition = popEnterTransition(),
              popExitTransition = popExitTransition(),
            ) {
              val smb = it.toRoute<Smb>()
              SmbDetailScreen(smb)
            }

            composable<WebDavPickRoute>(
              enterTransition = enterTransition(),
              exitTransition = exitTransition(),
              popEnterTransition = popEnterTransition(),
              popExitTransition = popExitTransition(),
            ) {
              val route = it.toRoute<WebDavPickRoute>()
              val navController = LocalNavController.current
              val webDavVM = webDavViewModel
              val webDavList by webDavVM.webDavList.collectAsStateWithLifecycle()
              // 兜底：列表 Flow 可能尚未发出刚插入的行，先做一次性查询
              var fetchedDone by remember { mutableStateOf(false) }
              val fetched by produceState<WebDav?>(null, route.id) {
                value = webDavVM.getWebDavById(route.id)
                fetchedDone = true
              }
              val webDav = webDavList.firstOrNull { item -> item.id == route.id } ?: fetched
              // 查询完成仍找不到（如配置已被删除）：提示并返回，避免停在空白页
              LaunchedEffect(webDav, fetchedDone) {
                if (webDav == null && fetchedDone) {
                  MessageNotifier.show(R.string.load_failed)
                  navController.popBackStack()
                }
              }
              if (webDav != null) {
                WebDavDetailScreen(webDav, pickMode = true)
              }
            }

            composable<SmbPickRoute>(
              enterTransition = enterTransition(),
              exitTransition = exitTransition(),
              popEnterTransition = popEnterTransition(),
              popExitTransition = popExitTransition(),
            ) {
              val route = it.toRoute<SmbPickRoute>()
              val navController = LocalNavController.current
              val smbVM = smbViewModel
              val smbList by smbVM.smbList.collectAsStateWithLifecycle()
              var fetchedDone by remember { mutableStateOf(false) }
              val fetched by produceState<Smb?>(null, route.id) {
                value = smbVM.getSmbById(route.id)
                fetchedDone = true
              }
              val smb = smbList.firstOrNull { item -> item.id == route.id } ?: fetched
              // 查询完成仍找不到（如配置已被删除）：提示并返回，避免停在空白页
              LaunchedEffect(smb, fetchedDone) {
                if (smb == null && fetchedDone) {
                  MessageNotifier.show(R.string.load_failed)
                  navController.popBackStack()
                }
              }
              if (smb != null) {
                SmbDetailScreen(smb, pickMode = true)
              }
            }

            normalAnimatedScreen(
              "${RouteCustomCoverCrop}/{id}/{type}",
              arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("type") { type = NavType.IntType })
            ) {
              val id = it.arguments?.getLong("id") ?: return@normalAnimatedScreen
              val type = it.arguments?.getInt("type") ?: return@normalAnimatedScreen
              val context = LocalContext.current
              val nav = LocalNavController.current
              val libraryVM = libraryViewModel

              val destinationUri = remember(id, type) {
                val cacheDir = DiskCache.getDiskCacheDir(context, "thumbnail")
                if (!cacheDir.exists() && !cacheDir.mkdir()) {
                  Uri.EMPTY
                } else {
                  val file = File(cacheDir, "$type-${id}.jpg")
                  Uri.fromFile(file)
                }
              }

              CropScreen(destinationUri = destinationUri, onCropSuccess = {
                libraryVM.fetchMedia(
                  clear = true,
                  updateAlbumVersion = type == Constants.ALBUM,
                  updateArtistVersion = type == Constants.ARTIST,
                  updatePlayListVersion = type == Constants.PLAYLIST,
                )
                nav.popBackStack()
              }, onCancel = {
                nav.popBackStack()
              })
            }

            composable(
              "$RouteTagEditCrop/{uri}",
              arguments = listOf(navArgument("uri") { type = NavType.StringType })
            ) {
              val uriString = it.arguments?.getString("uri") ?: return@composable
              val destinationUri = Uri.decode(uriString).toUri()
              val nav = LocalNavController.current

              CropScreen(destinationUri = destinationUri, onCropSuccess = {
                nav.previousBackStackEntry?.savedStateHandle?.set(
                  "song_crop_result", System.currentTimeMillis()
                )
                nav.popBackStack()
              }, onCancel = {
                nav.popBackStack()
              })
            }

            normalAnimatedScreen(RouteEq) {
              EQScreen(it)
            }

            normalAnimatedScreen(RouteSupport) {
              SupportScreen()
            }
          }
        }
      }

      SnackbarHost(
        hostState = snackBarHostState,
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues())
      )
    }

    LaunchedEffect(Unit) {
      MessageNotifier.messages.collect {
        snackBarHostState.currentSnackbarData?.dismiss()
        snackBarHostState.showSnackbar(it)
      }
    }
  }
}

private fun NavGraphBuilder.normalAnimatedScreen(
  route: String,
  arguments: List<NamedNavArgument> = emptyList(),
  deepLinks: List<NavDeepLink> = emptyList(),
  content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) {
  composable(
    route = route,
    arguments = arguments,
    deepLinks = deepLinks,
    enterTransition = enterTransition(),
    exitTransition = exitTransition(),
    popEnterTransition = popEnterTransition(),
    popExitTransition = popExitTransition(),
    content = content
  )
}

@Serializable
data class DetailScreenRoute(
  val album: Album? = null,
  val artist: Artist? = null,
  val genre: Genre? = null,
  val playList: PlayList? = null,
  val folder: Folder? = null
) {

  fun findNotNull(): APlayerModel {
    return when {
      album != null -> album
      artist != null -> artist
      genre != null -> genre
      playList != null -> playList
      folder != null -> folder
      else -> error("valid model not found")
    }
  }

}

private object ModelRouteType {

  val album = RouteType(Album::class)
  val artist = RouteType(Artist::class)
  val genre = RouteType(Genre::class)
  val playList = RouteType(PlayList::class)
  val folder = RouteType(Folder::class)

  @OptIn(InternalSerializationApi::class)
  class RouteType<T : APlayerModel>(private val kClass: KClass<T>) : NavType<T?>(true) {

    override fun put(bundle: SavedState, key: String, value: T?) {
      if (value != null) {
        bundle.putString(key, Json.encodeToString(kClass.serializer(), value))
      }
    }

    override fun get(bundle: SavedState, key: String): T? {
      return Json.decodeFromString(kClass.serializer(), bundle.getString(key) ?: return null)
    }

    override fun parseValue(value: String): T? {
      if (value.isEmpty()) {
        return null
      }
      return Json.decodeFromString(kClass.serializer(), Uri.decode(value))
    }

    override fun serializeAsValue(value: T?): String {
      if (value == null) {
        return Uri.EMPTY.toString()
      }
      return Uri.encode(Json.encodeToString(kClass.serializer(), value))
    }

  }

}
