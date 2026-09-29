package remix.myplayer.repo

import android.content.Context
import android.provider.MediaStore.Audio
import dagger.hilt.android.qualifiers.ApplicationContext
import remix.myplayer.data.model.audio.Album
import remix.myplayer.data.prefs.SettingPrefs
import remix.myplayer.helper.FolderAlbumGrouping
import remix.myplayer.helper.ItemsSorter
import remix.myplayer.util.PermissionUtil
import timber.log.Timber
import javax.inject.Inject

interface AlbumRepository {
  fun allAlbums(): List<Album>
}

class AlbumRepoImpl @Inject constructor(
  @param:ApplicationContext private val context: Context,
  private val settingPrefs: SettingPrefs
) : AlbumRepository, AbstractRepository(settingPrefs) {

  override fun allAlbums(): List<Album> {
    if (!PermissionUtil.hasNecessaryPermission()) {
      return emptyList()
    }
    // 有专辑标签：按 albumId 分组；无专辑标签：按所在文件夹分组（与远程保持一致）
    val albumMaps: MutableMap<String, MutableList<Album>> = LinkedHashMap()
    val albums: MutableList<Album> = ArrayList()
    val sortOrder = settingPrefs.albumSortOrder
    try {
      context.contentResolver
        .query(
          Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(
            Audio.Media.ALBUM_ID,
            Audio.Media.ALBUM,
            Audio.Media.ARTIST_ID,
            Audio.Media.ARTIST,
            Audio.Media.DATA
          ),
          baseSelection,
          baseSelectionArgs,
          sortOrder
        ).use { cursor ->
          if (cursor != null) {
            while (cursor.moveToNext()) {
              try {
                val albumId = cursor.getLong(0)
                val albumName = cursor.getString(1) ?: ""
                val artistId = cursor.getLong(2)
                val artistName = cursor.getString(3) ?: ""
                val key: String
                val album: Album
                if (albumName.isBlank()) {
                  val folderPath = FolderAlbumGrouping.folderPathOf(cursor.getString(4) ?: "")
                  key = "folder:$folderPath"
                  album = Album(
                    albumID = FolderAlbumGrouping.idOf(folderPath),
                    album = FolderAlbumGrouping.displayNameOf(folderPath),
                    artistID = artistId,
                    artist = artistName,
                    count = 0,
                    folderPath = folderPath
                  )
                } else {
                  key = "album:$albumId"
                  album = Album(albumId, albumName, artistId, artistName, 0)
                }
                albumMaps.getOrPut(key) { ArrayList() }.add(album)
              } catch (ignored: Exception) {
              }
            }
            for ((_, value) in albumMaps) {
              try {
                val album = value[0]
                album.count = value.size
                albums.add(album)
              } catch (e: Exception) {
                Timber.v("addAlbum failed: $e")
              }
            }
          }
        }
    } catch (e: Exception) {
      Timber.v("getAllAlbum failed: $e")
    }
    return ItemsSorter.sortedAlbums(albums, sortOrder)
  }
}
