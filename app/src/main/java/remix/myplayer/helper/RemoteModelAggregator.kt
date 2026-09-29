package remix.myplayer.helper

import remix.myplayer.data.model.audio.Album
import remix.myplayer.data.model.audio.Artist
import remix.myplayer.data.model.audio.Genre
import remix.myplayer.data.model.audio.Song

/**
 * 把远程（WebDAV / SMB）歌曲按**名称**聚合成专辑 / 歌手 / 流派视图项，使远程歌曲与本地歌曲
 * 一样出现在这些列表中。
 *
 * 远程歌曲没有 MediaStore 的 albumId / artistId / genreId，因此：
 * - 使用「名称派生的稳定负 id」（复用 [Song.stableRemoteId]），与本地正 id 不会冲突；
 * - 详情页（[remix.myplayer.repo.SongRepository.getSongsByModels]）再用名称反查远程歌曲。
 */
object RemoteModelAggregator {

  /**
   * 远程歌曲聚合出的专辑列表。
   * 专辑名为空的歌曲**按所在文件夹分组**（显示文件夹名），与本地专辑列表的行为一致；
   * 文件夹名也为空时才落到「未知专辑」。
   */
  fun albums(songs: List<Song>): List<Album> =
    songs.asSequence()
      .filter { !it.isLocal() }
      .groupBy { song ->
        if (song.album.isBlank()) {
          "folder:${FolderAlbumGrouping.folderPathOf(song)}"
        } else {
          "album:${song.album}"
        }
      }
      .mapNotNull { (key, group) ->
        val first = group.first()
        val artist = first.artist
        val album = if (first.album.isBlank()) {
          val folderPath = FolderAlbumGrouping.folderPathOf(first)
          val folderName = FolderAlbumGrouping.displayNameOf(folderPath)
          Album(
            albumID = FolderAlbumGrouping.idOf(folderPath),
            album = folderName,
            artistID = if (artist.isBlank()) 0L else Song.stableRemoteId(artist),
            artist = artist,
            count = group.size,
            folderPath = folderPath
          ).takeIf { folderName.isNotBlank() }
        } else {
          Album(
            albumID = Song.stableRemoteId(first.album),
            album = first.album,
            artistID = if (artist.isBlank()) 0L else Song.stableRemoteId(artist),
            artist = artist,
            count = group.size
          )
        }
        album
      }

  /** 远程歌曲聚合出的歌手列表；歌手名为空时统一归入「未知艺术家」分组 */
  fun artists(songs: List<Song>): List<Artist> =
    songs.asSequence()
      .filter { !it.isLocal() }
      .groupBy { it.artist }
      .map { (artist, group) ->
        Artist(
          artistID = Song.stableRemoteId(artist),
          artist = artist,
          count = group.size
        )
      }

  /** 远程歌曲聚合出的流派列表 */
  fun genres(songs: List<Song>): List<Genre> =
    songs.asSequence()
      .filter { !it.isLocal() && it.genre.isNotBlank() }
      .groupBy { it.genre }
      .map { (genre, group) ->
        Genre(
          id = Song.stableRemoteId(genre),
          genre = genre,
          count = group.size
        )
      }
}
