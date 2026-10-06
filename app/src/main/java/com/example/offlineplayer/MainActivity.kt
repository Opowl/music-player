@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.offlineplayer

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

data class Song(
    val uri: String, val title: String, val artist: String = "", val album: String = "",
    val art: String? = null, val looked: Boolean = false,
    val albumArtist: String = "", val track: Int = 0
)
data class Playlist(val id: String, val name: String, val uris: List<String>)
data class Rel(val id: String, val rg: String, val title: String, val score: Int)
data class Cand(val title: String, val artist: String, val album: String, val releases: List<Rel>)
data class CoverOpt(val thumb: String, val full: String)
data class Review(val uri: String, val name: String, val cands: List<Cand>)
data class QItem(val title: String, val artist: String, val art: String?)
class LookResult(val cands: List<Cand>, val art: String?)

class LibNav {
    var artist by mutableStateOf<String?>(null)
    var album by mutableStateOf<String?>(null)
}

fun folderName(f: String): String = try {
    DocumentsContract.getTreeDocumentId(Uri.parse(f)).substringAfter(':').ifBlank { "Internal storage" }
} catch (e: Exception) { f }

fun stripFeat(a: String): String =
    a.replace(Regex("(?i)\\s*[(\\[]?\\s*\\b(feat|ft|featuring)\\b\\.?\\s.*$"), "").trim()

fun grp(s: Song): String = s.albumArtist.ifBlank { stripFeat(s.artist) }.ifBlank { "Unknown artist" }

fun sameArtist(s: Song, name: String): Boolean = grp(s).equals(name, true)

fun artistsOf(lib: List<Song>): List<Pair<String, List<Song>>> =
    lib.groupBy { grp(it).lowercase() }.values.map { grp(it[0]) to it }
        .sortedWith(compareBy<Pair<String, List<Song>>>({ it.first == "Unknown artist" }, { it.first.lowercase() }))

fun albumsOf(songs: List<Song>): List<Pair<String, List<Song>>> =
    songs.groupBy { it.album.trim().lowercase() }.values
        .map { l -> l[0].album.trim() to l.sortedWith(compareBy<Song>({ if (it.track == 0) 9999 else it.track }, { it.title.lowercase() })) }
        .sortedWith(compareBy<Pair<String, List<Song>>>({ it.first.isBlank() }, { it.first.lowercase() }))

fun cleanName(raw: String): String {
    var s = raw.replace('_', ' ')
    s = s.replace(Regex("\\[[^\\]]*\\]"), " ")
    s = s.replace(Regex("\\([^)]*(official|video|audio|lyric|hd|remaster)[^)]*\\)", RegexOption.IGNORE_CASE), " ")
    s = s.replace(Regex("^\\s*\\d{1,3}[\\s.)\\-]+"), "")
    return s.replace(Regex("\\s+"), " ").trim()
}

fun decodeArt(path: String, target: Int): ImageBitmap? = try {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    var ss = 1
    while (o.outWidth / (ss * 2) >= target && o.outHeight / (ss * 2) >= target) ss *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = ss })?.asImageBitmap()
} catch (e: Exception) { null }

class PlayerVM(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication<Application>()
    private val file = File(app.filesDir, "data.json")
    private val artDir = File(app.filesDir, "art").apply { mkdirs() }
    private val prefs = app.getSharedPreferences("p", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    val library = mutableStateListOf<Song>()
    val playlists = mutableStateListOf<Playlist>()
    val queue = mutableStateListOf<QItem>()
    val reviews = mutableStateListOf<Review>()
    var theme by mutableIntStateOf(prefs.getInt("theme", 0))
    var controller: MediaController? = null
    var isPlaying by mutableStateOf(false)
    var title by mutableStateOf<String?>(null)
    var artist by mutableStateOf("")
    var artPath by mutableStateOf<String?>(null)
    var index by mutableIntStateOf(0)
    var shuffle by mutableStateOf(false)
    var repeatAll by mutableStateOf(false)
    var timerOn by mutableStateOf(false)
    var lookingUp by mutableStateOf(false)
    var lookDone by mutableIntStateOf(0)
    var lookTotal by mutableIntStateOf(0)
    var coverFor by mutableStateOf<Song?>(null)
    val covers = mutableStateListOf<CoverOpt>()
    var coversLoading by mutableStateOf(false)
    val artistPhotos = mutableStateMapOf<String, String>()
    var editingSong by mutableStateOf<Song?>(null)
    var editingArtist by mutableStateOf<String?>(null)
    var editingAlbum by mutableStateOf<Pair<String, String>?>(null)
    var addTo by mutableStateOf<Song?>(null)
    val folders = mutableStateListOf<String>()
    var scanning by mutableStateOf(false)
    var scanDone by mutableIntStateOf(0)
    var scanTotal by mutableIntStateOf(0)
    var scanMsg by mutableStateOf("")

    init { load(); connect() }

    // ---------- storage ----------
    private fun load() {
        try {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            val l = o.getJSONArray("library")
            for (i in 0 until l.length()) {
                val s = l.getJSONObject(i)
                library.add(
                    Song(
                        s.getString("u"), s.getString("t"), s.optString("a"), s.optString("b"),
                        if (s.has("p")) s.getString("p") else null, s.optBoolean("l"),
                        s.optString("aa"), s.optInt("n")
                    )
                )
            }
            if (o.has("ap")) {
                val m = o.getJSONObject("ap")
                m.keys().forEach { k -> artistPhotos[k] = m.getString(k) }
            }
            if (o.has("fd")) {
                val fa = o.getJSONArray("fd")
                for (i in 0 until fa.length()) folders.add(fa.getString(i))
            }
            val p = o.getJSONArray("playlists")
            for (i in 0 until p.length()) {
                val x = p.getJSONObject(i)
                val a = x.getJSONArray("s")
                playlists.add(Playlist(x.getString("id"), x.getString("n"), List(a.length()) { a.getString(it) }))
            }
        } catch (e: Exception) { }
    }

    private fun save() {
        try {
            val l = JSONArray()
            library.forEach { s ->
                val o = JSONObject().put("u", s.uri).put("t", s.title).put("a", s.artist).put("b", s.album).put("l", s.looked).put("aa", s.albumArtist).put("n", s.track)
                if (s.art != null) o.put("p", s.art)
                l.put(o)
            }
            val p = JSONArray()
            playlists.forEach { p.put(JSONObject().put("id", it.id).put("n", it.name).put("s", JSONArray(it.uris))) }
            val ap = JSONObject()
            artistPhotos.forEach { (k, v) -> ap.put(k, v) }
            file.writeText(JSONObject().put("library", l).put("playlists", p).put("ap", ap).put("fd", JSONArray(folders.toList())).toString())
        } catch (e: Exception) { }
    }

    // ---------- library ----------
    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun artKey(artist: String, album: String, uri: String) =
        md5("v2|" + if (album.isNotBlank()) artist.lowercase() + "|" + album.lowercase() else uri)

    private fun readTags(u: Uri): Song {
        var name = "Unknown"
        try {
            ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) name = c.getString(i)
                }
            }
        } catch (e: Exception) { }
        var title = ""; var artist = ""; var album = ""; var art: String? = null; var aa = ""; var track = 0
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, u)
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
            artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: ""
            album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            aa = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: ""
            track = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
            val pic = r.embeddedPicture
            if (pic != null) {
                val f = File(artDir, artKey(artist, album, u.toString()) + ".jpg")
                if (!f.exists()) f.writeBytes(pic)
                art = f.path
            }
        } catch (e: Exception) {
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
        return Song(u.toString(), title.ifBlank { cleanName(name.substringBeforeLast('.')) }, artist.trim(), album.trim(), art, false, aa.trim(), track)
    }

    fun importSongs(uris: List<Uri>, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val added = ArrayList<Song>()
            for (u in uris) {
                try { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
                if (library.any { it.uri == u.toString() }) continue
                val song = withContext(Dispatchers.IO) { readTags(u) }
                library.add(song)
                added.add(song)
            }
            save()
            val g = added.map { grp(it) }.distinct()
            onDone(if (g.size == 1 && g[0] != "Unknown artist") g[0] else null)
            findInfo()
        }
    }

    // ---------- folder scanning ----------
    private fun scanTree(tree: Uri): List<Uri> {
        val out = ArrayList<Uri>()
        val exts = listOf(".mp3", ".m4a", ".flac", ".ogg", ".opus", ".wav", ".aac")
        fun walk(docId: String) {
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            ctx.contentResolver.query(
                kids,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val mime = c.getString(1) ?: ""
                    val name = (c.getString(2) ?: "").lowercase()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(id)
                    else if (mime.startsWith("audio/") || exts.any { name.endsWith(it) }) out.add(DocumentsContract.buildDocumentUriUsingTree(tree, id))
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree))
        return out
    }

    fun addFolder(u: Uri) {
        try { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
        if (folders.none { it == u.toString() }) { folders.add(u.toString()); save() }
        scanFolders()
    }

    fun removeFolder(f: String) { folders.remove(f); save() }

    fun scanFolders() {
        if (scanning || folders.isEmpty()) return
        scanning = true
        scanDone = 0
        scanTotal = 0
        scanMsg = ""
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                folders.toList().flatMap { try { scanTree(Uri.parse(it)) } catch (e: Exception) { emptyList() } }
            }
            val known = library.map { it.uri }.toHashSet()
            val fresh = found.filter { it.toString() !in known }.distinct()
            scanTotal = fresh.size
            var added = 0
            for (u in fresh) {
                val song = withContext(Dispatchers.IO) { readTags(u) }
                val dup = library.any {
                    it.title.equals(song.title, true) && it.artist.equals(song.artist, true) && it.album.equals(song.album, true)
                }
                if (!dup) { library.add(song); added++ }
                scanDone++
                if (scanDone % 25 == 0) save()
            }
            save()
            scanning = false
            scanMsg = if (added == 0) "No new songs found" else "Added $added new songs"
            findInfo()
            delay(5000)
            scanMsg = ""
        }
    }

    fun song(uri: String): Song? = library.firstOrNull { it.uri == uri }

    private fun editWhere(pred: (Song) -> Boolean, f: (Song) -> Song) {
        val changed = HashSet<String>()
        for (i in library.indices) {
            if (pred(library[i])) { library[i] = f(library[i]); changed.add(library[i].uri) }
        }
        if (changed.isEmpty()) return
        save()
        val c = controller ?: return
        for (k in 0 until c.mediaItemCount) {
            val id = c.getMediaItemAt(k).mediaId
            if (k != c.currentMediaItemIndex && id in changed) song(id)?.let { c.replaceMediaItem(k, item(it)) }
        }
    }

    private fun edit(uri: String, f: (Song) -> Song) = editWhere({ it.uri == uri }, f)

    private fun readUri(u: Uri): ByteArray? =
        try { ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } } catch (e: Exception) { null }

    fun artistArt(name: String): String? =
        artistPhotos[name.lowercase()] ?: library.firstOrNull { sameArtist(it, name) && it.art != null }?.art

    fun setArtistPhoto(name: String, u: Uri) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                val b = readUri(u)
                if (b == null) null else {
                    val f = File(artDir, "artist_" + md5(name.lowercase()) + "_" + System.currentTimeMillis() + ".jpg")
                    f.writeBytes(b)
                    f.path
                }
            }
            if (path != null) { artistPhotos[name.lowercase()] = path; save() }
        }
    }

    fun removeArtistPhoto(name: String) { artistPhotos.remove(name.lowercase()); save() }

    fun renameArtist(old: String, newName: String) {
        val n = newName.trim()
        if (n.isEmpty() || n == old) return
        editWhere({ sameArtist(it, old) }) { s ->
            val base = stripFeat(s.artist)
            s.copy(
                albumArtist = n,
                artist = if (base.equals(old, true)) n + s.artist.substring(base.length) else s.artist
            )
        }
        val p = artistPhotos.remove(old.lowercase())
        if (p != null && !artistPhotos.containsKey(n.lowercase())) artistPhotos[n.lowercase()] = p
        save()
    }

    fun renameAlbum(artist: String, old: String, newName: String) {
        val n = newName.trim()
        if (n.isEmpty() || n == old) return
        editWhere({ sameArtist(it, artist) && it.album.trim().equals(old, true) }) { it.copy(album = n) }
    }

    fun setAlbumCover(artist: String, album: String, bytes: ByteArray) {
        val f = File(artDir, md5("alb|" + artist.lowercase() + "|" + album.lowercase()) + "_" + System.currentTimeMillis() + ".jpg")
        f.writeBytes(bytes)
        editWhere({ sameArtist(it, artist) && it.album.trim().equals(album, true) }) { it.copy(art = f.path, looked = true) }
    }

    fun pickAlbumPhoto(artist: String, album: String, u: Uri) {
        viewModelScope.launch {
            val b = withContext(Dispatchers.IO) { readUri(u) }
            if (b != null) setAlbumCover(artist, album, b)
        }
    }

    fun editSongInfo(uri: String, t: String, a: String, b: String) {
        val old = song(uri) ?: return
        val na = a.trim()
        val nb = b.trim()
        val g = if (na.isBlank()) "Unknown artist" else stripFeat(na)
        var art = old.art
        if (nb.isNotBlank() && (!nb.equals(old.album.trim(), true) || !na.equals(old.artist, true))) {
            val other = library.firstOrNull { it.uri != uri && it.art != null && it.album.trim().equals(nb, true) && grp(it).equals(g, true) }
            if (other != null) art = other.art
        }
        edit(uri) {
            it.copy(
                title = t.trim().ifEmpty { it.title }, artist = na, album = nb, art = art, looked = true,
                albumArtist = if (na.equals(old.artist, true)) it.albumArtist else stripFeat(na)
            )
        }
    }

    fun renameSong(uri: String, name: String) { if (name.isNotBlank()) edit(uri) { it.copy(title = name.trim()) } }

    fun removeFromLibrary(s: Song) {
        library.remove(s)
        for (i in playlists.indices) playlists[i] = playlists[i].copy(uris = playlists[i].uris - s.uri)
        save()
    }

    fun clearLibrary() {
        controller?.clearMediaItems()
        library.clear()
        for (i in playlists.indices) playlists[i] = playlists[i].copy(uris = emptyList())
        save()
    }

    // ---------- online lookup ----------
    private fun httpGet(url: String): ByteArray? {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "OfflinePlayer/1.0 (personal hobby app)")
            c.connectTimeout = 10000
            c.readTimeout = 15000
            if (c.responseCode != 200) { c.disconnect(); return null }
            val b = c.inputStream.use { it.readBytes() }
            c.disconnect()
            return b
        } catch (e: Exception) { return null }
    }

    private fun q(s: String) = s.replace("\"", " ").replace("\\", " ")

    private fun search(query: String, albumHint: String = ""): List<Cand> {
        val b = httpGet("https://musicbrainz.org/ws/2/recording?fmt=json&limit=6&query=" + URLEncoder.encode(query, "UTF-8"))
            ?: return emptyList()
        val out = ArrayList<Cand>()
        try {
            val arr = JSONObject(String(b)).getJSONArray("recordings")
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                var artist = ""
                val ac = r.optJSONArray("artist-credit")
                if (ac != null) for (k in 0 until ac.length()) {
                    val x = ac.getJSONObject(k)
                    artist += x.optString("name") + x.optString("joinphrase")
                }
                val rels = ArrayList<Rel>()
                val rel = r.optJSONArray("releases")
                if (rel != null) for (k in 0 until rel.length()) {
                    val x = rel.getJSONObject(k)
                    val rg = x.optJSONObject("release-group")
                    val sec = rg?.optJSONArray("secondary-types")
                    val t = x.optString("title")
                    var sc = 0
                    if (x.optString("status") == "Official") sc += 4
                    if (rg?.optString("primary-type") == "Album") sc += 3
                    if (sec == null || sec.length() == 0) sc += 5
                    if (albumHint.isNotBlank() && t.equals(albumHint, true)) sc += 10
                    rels.add(Rel(x.getString("id"), rg?.optString("id") ?: "", t, sc))
                }
                rels.sortWith(compareByDescending<Rel> { it.score }.thenBy { it.title.length })
                out.add(Cand(r.optString("title"), artist.trim(), rels.firstOrNull()?.title ?: "", rels))
            }
        } catch (e: Exception) { }
        return out
    }

    private fun bestRels(c: List<Cand>, title: String): List<Rel> {
        val same = c.filter { it.title.equals(title, true) }.ifEmpty { c }
        return same.flatMap { it.releases }
            .sortedWith(compareByDescending<Rel> { it.score }.thenBy { it.title.length })
            .distinctBy { it.id }
    }

    private fun saveArt(key: String, b: ByteArray): String {
        val f = File(artDir, "$key.jpg")
        f.writeBytes(b)
        return f.path
    }

    private fun itunes(term: String, entity: String): List<JSONObject> {
        val b = httpGet("https://itunes.apple.com/search?media=music&limit=8&entity=$entity&term=" + URLEncoder.encode(term, "UTF-8"))
            ?: return emptyList()
        return try {
            val a = JSONObject(String(b)).getJSONArray("results")
            List(a.length()) { a.getJSONObject(it) }
        } catch (e: Exception) { emptyList() }
    }

    private fun big(u: String, n: Int) = u.replace("100x100bb", "${n}x${n}bb")

    private fun itunesArt(key: String, artist: String, title: String, album: String): String? {
        if (artist.isBlank()) return null
        val a = artist.lowercase()
        val ok = itunes("$artist $title", "song")
            .filter { val n = it.optString("artistName").lowercase(); n.contains(a) || a.contains(n) }
            .sortedBy { it.optString("collectionName").contains("live", true) }
        val pick = ok.firstOrNull { album.isNotBlank() && it.optString("collectionName").contains(album, true) }
            ?: ok.firstOrNull() ?: return null
        val u = pick.optString("artworkUrl100")
        if (u.isBlank()) return null
        val b = httpGet(big(u, 600)) ?: return null
        return saveArt(key, b)
    }

    private fun fetchArt(key: String, rels: List<Rel>, artist: String, title: String, album: String): String? {
        val f = File(artDir, "$key.jpg")
        if (f.exists()) return f.path
        for (r in rels.take(10)) {
            val b = httpGet("https://coverartarchive.org/release/${r.id}/front-500")
            if (b != null && b.size > 1000) return saveArt(key, b)
        }
        return itunesArt(key, artist, title, album)
    }

    private fun lookup(s: Song): LookResult {
        if (s.artist.isBlank()) {
            val name = cleanName(s.title)
            val queries = ArrayList<String>()
            val parts = name.split(" - ", limit = 2)
            if (parts.size == 2) queries.add("recording:\"${q(parts[1])}\" AND artist:\"${q(parts[0])}\"")
            queries.add(name.replace(Regex("[^\\p{L}\\p{N}' ]"), " "))
            for (qq in queries) {
                val c = search(qq)
                if (c.isNotEmpty()) return LookResult(c, null)
                Thread.sleep(1100)
            }
            return LookResult(emptyList(), null)
        }
        val c = search("recording:\"${q(s.title)}\" AND artist:\"${q(s.artist)}\"", s.album)
        return LookResult(c, fetchArt(artKey(s.artist, s.album, s.uri), bestRels(c, s.title), s.artist, s.title, s.album))
    }

    // ---------- cover chooser ----------
    private fun gatherCovers(s: Song): List<CoverOpt> {
        val out = ArrayList<CoverOpt>()
        val dir = File(ctx.cacheDir, "covers").apply { mkdirs() }
        fun add(thumbUrl: String, fullUrl: String) {
            val b = httpGet(thumbUrl) ?: return
            if (b.size < 1000) return
            val f = File(dir, md5(fullUrl) + ".jpg")
            f.writeBytes(b)
            out.add(CoverOpt(f.path, fullUrl))
        }
        val c = search("recording:\"${q(s.title)}\" AND artist:\"${q(s.artist.ifBlank { s.title })}\"", s.album)
        val seen = HashSet<String>()
        for (r in bestRels(c, s.title).take(14)) {
            if (out.size >= 6) break
            if (r.rg.isNotBlank() && seen.add(r.rg)) {
                add("https://coverartarchive.org/release-group/${r.rg}/front-250", "https://coverartarchive.org/release-group/${r.rg}/front-500")
            }
        }
        val names = HashSet<String>()
        val term = (s.artist + " " + s.album.ifBlank { s.title }).trim()
        var n = 0
        for (o in itunes(term, "album")) {
            if (n >= 6) break
            val u = o.optString("artworkUrl100")
            if (u.isBlank() || !names.add(o.optString("collectionName"))) continue
            add(big(u, 300), big(u, 600))
            n++
        }
        return out
    }

    fun loadCovers(s: Song) {
        coverFor = s
        covers.clear()
        coversLoading = true
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { try { gatherCovers(s) } catch (e: Exception) { emptyList() } }
            covers.addAll(list)
            coversLoading = false
        }
    }

    private fun setCover(s: Song, bytes: ByteArray) {
        if (s.album.isBlank()) {
            val f = File(artDir, artKey(s.artist, s.album, s.uri) + "_" + System.currentTimeMillis() + ".jpg")
            f.writeBytes(bytes)
            edit(s.uri) { it.copy(art = f.path, looked = true) }
        } else setAlbumCover(grp(s), s.album.trim(), bytes)
    }

    fun pickCover(o: CoverOpt) {
        val s = coverFor ?: return
        coverFor = null
        viewModelScope.launch {
            val b = withContext(Dispatchers.IO) { httpGet(o.full) ?: try { File(o.thumb).readBytes() } catch (e: Exception) { null } }
            if (b != null) setCover(s, b)
        }
    }

    fun pickPhoto(u: Uri) {
        val s = coverFor ?: return
        coverFor = null
        viewModelScope.launch {
            val b = withContext(Dispatchers.IO) {
                try { ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } } catch (e: Exception) { null }
            }
            if (b != null) setCover(s, b)
        }
    }

    fun findInfo() {
        if (lookingUp) return
        val todo = library.filter { x -> !x.looked && (x.artist.isBlank() || x.art == null) && reviews.none { it.uri == x.uri } }
        if (todo.isEmpty()) return
        lookingUp = true
        lookDone = 0
        lookTotal = todo.size
        viewModelScope.launch {
            for (s in todo) {
                val r = withContext(Dispatchers.IO) {
                    try { lookup(s) } catch (e: Exception) { LookResult(emptyList(), null) }
                }
                if (s.artist.isBlank()) {
                    if (r.cands.isEmpty()) edit(s.uri) { it.copy(looked = true) }
                    else reviews.add(Review(s.uri, s.title, r.cands.take(4)))
                } else {
                    edit(s.uri) { it.copy(art = r.art ?: it.art, looked = true) }
                }
                lookDone++
                delay(1100)
            }
            lookingUp = false
            findInfo()
        }
    }

    fun acceptReview(r: Review, c: Cand) {
        reviews.remove(r)
        edit(r.uri) { it.copy(title = c.title, artist = c.artist, album = c.album, looked = true) }
        if (song(r.uri)?.art != null) return
        viewModelScope.launch {
            val a = withContext(Dispatchers.IO) { fetchArt(artKey(c.artist, c.album, r.uri), c.releases, c.artist, c.title, c.album) }
            if (a != null) edit(r.uri) { it.copy(art = a) }
        }
    }

    fun skipReview(r: Review) {
        reviews.remove(r)
        edit(r.uri) { it.copy(looked = true) }
    }

    // ---------- playlists ----------
    private fun update(id: String, f: (Playlist) -> Playlist) {
        val i = playlists.indexOfFirst { it.id == id }
        if (i >= 0) { playlists[i] = f(playlists[i]); save() }
    }

    fun createPlaylist(name: String) {
        playlists.add(Playlist(UUID.randomUUID().toString(), name.trim().ifEmpty { "New playlist" }, emptyList()))
        save()
    }

    fun renamePlaylist(id: String, name: String) = update(id) { p -> p.copy(name = name.trim().ifEmpty { p.name }) }

    fun deletePlaylist(id: String) { playlists.removeAll { it.id == id }; save() }

    fun toggleInPlaylist(id: String, uri: String) = update(id) { p ->
        if (uri in p.uris) p.copy(uris = p.uris - uri) else p.copy(uris = p.uris + uri)
    }

    // ---------- playback ----------
    private fun item(s: Song): MediaItem {
        val md = MediaMetadata.Builder().setTitle(s.title)
        if (s.artist.isNotBlank()) md.setArtist(s.artist)
        if (s.album.isNotBlank()) md.setAlbumTitle(s.album)
        val a = s.art
        if (a != null && File(a).exists()) md.setArtworkUri(Uri.fromFile(File(a)))
        return MediaItem.Builder().setMediaId(s.uri).setUri(s.uri).setMediaMetadata(md.build()).build()
    }

    fun play(songs: List<Song>, start: Int = 0, shuffleOn: Boolean = false) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        c.shuffleModeEnabled = shuffleOn
        c.setMediaItems(songs.map { item(it) }, start, 0L)
        c.prepare()
        c.play()
    }

    fun playNext(s: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) play(listOf(s)) else c.addMediaItem(c.currentMediaItemIndex + 1, item(s))
    }

    fun addToQueue(s: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) play(listOf(s)) else c.addMediaItem(item(s))
    }

    fun removeFromQueue(i: Int) { controller?.removeMediaItem(i) }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun sleepTimer(min: Int) {
        handler.removeCallbacksAndMessages(null)
        timerOn = min > 0
        if (min > 0) handler.postDelayed({ controller?.pause(); timerOn = false }, min * 60_000L)
    }

    fun setThemeMode(i: Int) { theme = i; prefs.edit().putInt("theme", i).apply() }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { sync(player) }
    }

    private fun sync(p: Player) {
        isPlaying = p.isPlaying
        val md = p.currentMediaItem?.mediaMetadata
        title = md?.title?.toString()
        artist = md?.artist?.toString() ?: ""
        artPath = md?.artworkUri?.path
        index = p.currentMediaItemIndex
        shuffle = p.shuffleModeEnabled
        repeatAll = p.repeatMode == Player.REPEAT_MODE_ALL
        queue.clear()
        for (i in 0 until p.mediaItemCount) {
            val m = p.getMediaItemAt(i).mediaMetadata
            queue.add(QItem(m.title?.toString() ?: "", m.artist?.toString() ?: "", m.artworkUri?.path))
        }
    }

    private fun connect() {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val f = MediaController.Builder(ctx, token).buildAsync()
        f.addListener({
            try {
                val c = f.get()
                controller = c
                c.addListener(listener)
                sync(c)
            } catch (e: Exception) { }
        }, ContextCompat.getMainExecutor(ctx))
    }

    override fun onCleared() { controller?.release(); super.onCleared() }
}

class MainActivity : ComponentActivity() {
    private val vm: PlayerVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { AppTheme(vm.theme) { Root(vm) } }
    }
}

@Composable
fun AppTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) { 1 -> true; 2 -> false; else -> isSystemInDarkTheme() }
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF1DB954), onPrimary = Color.Black,
        background = Color(0xFF121212), surface = Color(0xFF121212),
        surfaceVariant = Color(0xFF282828), onSurface = Color.White, onBackground = Color.White,
        onSurfaceVariant = Color(0xFFB3B3B3)
    ) else lightColorScheme(primary = Color(0xFF1DB954), onPrimary = Color.Black)
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)
    )
    MaterialTheme(colorScheme = scheme, shapes = shapes, content = content)
}

@Composable
fun Art(
    path: String?, modifier: Modifier = Modifier, px: Int = 128,
    shape: Shape = RoundedCornerShape(8.dp), icon: ImageVector = Icons.Rounded.MusicNote
) {
    val img by produceState<ImageBitmap?>(null, path, px) {
        value = if (path == null) null else withContext(Dispatchers.IO) { decodeArt(path, px) }
    }
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val b = img
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Root(vm: PlayerVM) {
    var tab by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    var full by remember { mutableStateOf(false) }
    val nav = remember { LibNav() }
    CoverDialog(vm)
    EditSongDialog(vm)
    EditArtistDialog(vm, nav)
    EditAlbumDialog(vm, nav)
    AddToPlaylistDialog(vm)
    if (full) {
        BackHandler { full = false }
        NowPlaying(vm) { full = false }
    } else {
        if (open != null) BackHandler { open = null }
        if (tab == 1 && nav.artist != null) BackHandler { if (nav.album != null) nav.album = null else nav.artist = null }
        Scaffold(bottomBar = {
            Column {
                if (vm.title != null) MiniPlayer(vm) { full = true }
                NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                    listOf(
                        "Playlists" to Icons.Rounded.LibraryMusic,
                        "Library" to Icons.Rounded.MusicNote,
                        "Settings" to Icons.Rounded.Settings
                    ).forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { if (i == 1 && tab == 1) { nav.artist = null; nav.album = null }; tab = i; open = null },
                            icon = { Icon(icon, label) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }
        }) { pad ->
            Box(Modifier.padding(pad)) {
                val o = open
                when {
                    tab == 1 -> LibraryScreen(vm, nav)
                    tab == 2 -> SettingsScreen(vm)
                    o != null -> PlaylistDetail(vm, o) { open = null }
                    else -> PlaylistsScreen(vm) { open = it }
                }
            }
        }
    }
}

@Composable
fun MiniPlayer(vm: PlayerVM, onOpen: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }
    ) {
        Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(vm.artPath, Modifier.size(44.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(vm.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (vm.artist.isNotBlank()) Text(vm.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton({ vm.controller?.seekToPrevious() }) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
            IconButton({ vm.toggle() }) {
                Icon(if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play or pause")
            }
            IconButton({ vm.controller?.seekToNext() }) { Icon(Icons.Rounded.SkipNext, "Next") }
        }
    }
}

@Composable
fun MenuItem(text: String, onClick: () -> Unit) = DropdownMenuItem(text = { Text(text) }, onClick = onClick)

@Composable
fun SongRow(s: Song, onClick: () -> Unit, menu: @Composable (() -> Unit) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Art(s.art, Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (s.artist.isNotBlank()) Text(s.artist, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton({ show = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(show, { show = false }) { menu { show = false } }
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(t, { t = it }, singleLine = true, placeholder = { Text("Name") }) },
        confirmButton = { TextButton({ onOk(t) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun PlaylistsScreen(vm: PlayerVM, onOpen: (String) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Your Playlists", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            FilledTonalButton({ creating = true }) { Text("New playlist") }
        }
        if (vm.playlists.isEmpty()) Text("No playlists yet. Tap New playlist to make one.", Modifier.padding(16.dp))
        LazyColumn {
            items(vm.playlists, key = { it.id }) { p ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(p.id) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(p.name, fontSize = 18.sp)
                    Text("${p.uris.size} songs", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (creating) NameDialog("New playlist", "", { creating = false }) { vm.createPlaylist(it); creating = false }
}

@Composable
fun PlaylistDetail(vm: PlayerVM, id: String, onBack: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val pl = vm.playlists.firstOrNull { it.id == id } ?: return
    val songs = pl.uris.mapNotNull { vm.song(it) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Text(pl.name, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton({ renaming = true }) { Icon(Icons.Rounded.Edit, "Rename") }
            IconButton({ deleting = true }) { Icon(Icons.Rounded.Delete, "Delete playlist") }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.play(songs) }, enabled = songs.isNotEmpty()) { Text("Play") }
            OutlinedButton({ vm.play(songs, songs.indices.random(), true) }, enabled = songs.isNotEmpty()) { Text("Shuffle") }
            OutlinedButton({ adding = true }) { Text("Add songs") }
        }
        LazyColumn {
            itemsIndexed(songs) { i, s ->
                SongRow(s, { vm.play(songs, i) }) { dismiss ->
                    SongMenu(vm, s, dismiss) { MenuItem("Remove from playlist") { vm.toggleInPlaylist(id, s.uri); dismiss() } }
                }
            }
        }
    }
    if (adding) AlertDialog(
        onDismissRequest = { adding = false },
        title = { Text("Add to ${pl.name}") },
        text = {
            if (vm.library.isEmpty()) Text("Your library is empty. Use the Library tab first.")
            else LazyColumn {
                items(vm.library, key = { it.uri }) { s ->
                    Row(Modifier.fillMaxWidth().clickable { vm.toggleInPlaylist(id, s.uri) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(s.uri in pl.uris, { vm.toggleInPlaylist(id, s.uri) })
                        Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton({ adding = false }) { Text("Done") } }
    )
    if (renaming) NameDialog("Rename playlist", pl.name, { renaming = false }) { vm.renamePlaylist(id, it); renaming = false }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete \"${pl.name}\"?") },
        text = { Text("Your songs stay in the library.") },
        confirmButton = { TextButton({ deleting = false; vm.deletePlaylist(id); onBack() }) { Text("Delete") } },
        dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } }
    )
}

@Composable
fun SectionLabel(t: String) =
    Text(t, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

@Composable
fun SongMenu(vm: PlayerVM, s: Song, dismiss: () -> Unit, extra: @Composable () -> Unit = {}) {
    MenuItem("Add to playlist") { vm.addTo = s; dismiss() }
    MenuItem("Play next") { vm.playNext(s); dismiss() }
    MenuItem("Add to queue") { vm.addToQueue(s); dismiss() }
    MenuItem("Edit info") { vm.editingSong = s; dismiss() }
    extra()
}

@Composable
fun LibraryScreen(vm: PlayerVM, nav: LibNav) {
    val a = nav.artist
    val b = nav.album
    when {
        a == null -> ArtistsScreen(vm, nav)
        b == null -> ArtistPage(vm, nav, a)
        else -> AlbumPage(vm, nav, a, b)
    }
}

@Composable
fun ArtistRow(vm: PlayerVM, name: String, count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Art(vm.artistArt(name), Modifier.size(56.dp), 160, CircleShape, Icons.Rounded.Person)
        Column(Modifier.padding(start = 16.dp)) {
            Text(name, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (count == 1) "1 song" else "$count songs", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ArtistsScreen(vm: PlayerVM, nav: LibNav) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importSongs(uris) { g -> if (g != null) { nav.artist = g; nav.album = null } }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u -> if (u != null) vm.addFolder(u) }
    var searching by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var review by remember { mutableStateOf(false) }
    val artists = artistsOf(vm.library)
    val ql = q.trim().lowercase()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Library", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton({ searching = !searching; if (!searching) q = "" }) { Icon(Icons.Rounded.Search, "Search") }
            IconButton({ if (vm.folders.isEmpty()) folderPicker.launch(null) else vm.scanFolders() }, enabled = !vm.scanning) {
                Icon(Icons.Rounded.Refresh, "Scan music folders")
            }
            IconButton({ picker.launch(arrayOf("audio/*")) }) { Icon(Icons.Rounded.Add, "Add songs") }
        }
        if (searching) OutlinedTextField(
            q, { q = it }, singleLine = true, placeholder = { Text("Search artists, albums, songs") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        )
        if (vm.scanning) Text(
            if (vm.scanTotal == 0) "Scanning folders..." else "Scanning folders... ${vm.scanDone}/${vm.scanTotal}",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        ) else if (vm.scanMsg.isNotBlank()) Text(
            vm.scanMsg, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary
        )
        if (vm.lookingUp) Text("Looking up songs... ${vm.lookDone}/${vm.lookTotal}", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (vm.reviews.isNotEmpty()) TextButton({ review = true }, Modifier.padding(horizontal = 8.dp)) { Text("Review ${vm.reviews.size} matches") }
        if (vm.library.isEmpty()) Text("Tap + to add songs from your phone.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn {
            if (ql.isEmpty()) {
                items(artists, key = { it.first.lowercase() }) { (n, ss) -> ArtistRow(vm, n, ss.size) { nav.artist = n; nav.album = null } }
            } else {
                val hitArtists = artists.filter { it.first.lowercase().contains(ql) }
                val hitAlbums = artists.flatMap { (an, ss) ->
                    albumsOf(ss).filter { it.first.isNotBlank() && it.first.lowercase().contains(ql) }.map { Triple(an, it.first, it.second) }
                }
                val hitSongs = vm.library.filter { it.title.lowercase().contains(ql) }
                if (hitArtists.isNotEmpty()) item { SectionLabel("Artists") }
                items(hitArtists) { (n, ss) -> ArtistRow(vm, n, ss.size) { nav.artist = n; nav.album = null } }
                if (hitAlbums.isNotEmpty()) item { SectionLabel("Albums") }
                items(hitAlbums) { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.artist = t.first; nav.album = t.second }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Art(t.third.firstOrNull { it.art != null }?.art, Modifier.size(48.dp))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(t.second, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.first, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (hitSongs.isNotEmpty()) item { SectionLabel("Songs") }
                itemsIndexed(hitSongs) { i, s ->
                    SongRow(s, { vm.play(hitSongs, i) }) { d ->
                        SongMenu(vm, s, d) { MenuItem("Remove from library") { vm.removeFromLibrary(s); d() } }
                    }
                }
            }
        }
    }
    LaunchedEffect(vm.reviews.isEmpty()) { if (vm.reviews.isEmpty()) review = false }
    val rv = vm.reviews.firstOrNull()
    if (review && rv != null) AlertDialog(
        onDismissRequest = { review = false },
        title = { Text("Which song is this? (${vm.reviews.size} left)") },
        text = {
            Column {
                Text("File: ${rv.name}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                rv.cands.forEach { c ->
                    Column(Modifier.fillMaxWidth().clickable { vm.acceptReview(rv, c) }.padding(vertical = 8.dp)) {
                        Text(c.title)
                        Text("${c.artist} - ${c.album}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton({ vm.skipReview(rv) }) { Text("None of these") } },
        dismissButton = { TextButton({ review = false }) { Text("Stop") } }
    )
}

@Composable
fun ArtistPage(vm: PlayerVM, nav: LibNav, artist: String) {
    val songs = vm.library.filter { sameArtist(it, artist) }
    if (songs.isEmpty()) { LaunchedEffect(Unit) { nav.artist = null }; return }
    val albums = albumsOf(songs)
    val all = albums.flatMap { it.second }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ nav.artist = null }) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) { MenuItem("Edit artist") { vm.editingArtist = artist; menu = false } }
            }
        }
        LazyColumn {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Art(vm.artistArt(artist), Modifier.size(96.dp), 300, CircleShape, Icons.Rounded.Person)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(artist, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${albums.size} ${if (albums.size == 1) "album" else "albums"} - ${songs.size} songs",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm.play(all) }) { Text("Play") }
                    OutlinedButton({ vm.play(all, all.indices.random(), true) }) { Text("Shuffle") }
                }
            }
            items(albums, key = { it.first.lowercase() }) { (name, ss) ->
                Row(
                    Modifier.fillMaxWidth().clickable { nav.album = name }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Art(ss.firstOrNull { it.art != null }?.art, Modifier.size(56.dp), 160)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(name.ifBlank { "Singles & other" }, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (ss.size == 1) "1 song" else "${ss.size} songs", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumPage(vm: PlayerVM, nav: LibNav, artist: String, album: String) {
    val songs = albumsOf(vm.library.filter { sameArtist(it, artist) }).firstOrNull { it.first.equals(album, true) }?.second
    if (songs == null) { LaunchedEffect(Unit) { nav.album = null }; return }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ nav.album = null }) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) { MenuItem("Edit album") { vm.editingAlbum = artist to album; menu = false } }
            }
        }
        LazyColumn {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Art(songs.firstOrNull { it.art != null }?.art, Modifier.size(200.dp), 600)
                    Text(album.ifBlank { "Singles & other" }, Modifier.padding(top = 12.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm.play(songs) }) { Text("Play") }
                    OutlinedButton({ vm.play(songs, songs.indices.random(), true) }) { Text("Shuffle") }
                }
            }
            itemsIndexed(songs) { i, s ->
                var show by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().clickable { vm.play(songs, i) }.padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (s.track > 0) "${s.track}" else "${i + 1}", Modifier.width(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (s.artist.isNotBlank() && !s.artist.equals(artist, true))
                            Text(s.artist, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton({ show = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(show, { show = false }) {
                            SongMenu(vm, s, { show = false }) { MenuItem("Remove from library") { vm.removeFromLibrary(s); show = false } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddToPlaylistDialog(vm: PlayerVM) {
    val s = vm.addTo ?: return
    AlertDialog(
        onDismissRequest = { vm.addTo = null },
        title = { Text("Add to playlist") },
        text = {
            if (vm.playlists.isEmpty()) Text("Create a playlist first.")
            else LazyColumn {
                items(vm.playlists, key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { vm.toggleInPlaylist(p.id, s.uri) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(s.uri in p.uris, { vm.toggleInPlaylist(p.id, s.uri) })
                        Text(p.name)
                    }
                }
            }
        },
        confirmButton = { TextButton({ vm.addTo = null }) { Text("Done") } }
    )
}

@Composable
fun EditSongDialog(vm: PlayerVM) {
    val s = vm.editingSong ?: return
    var t by remember(s.uri) { mutableStateOf(s.title) }
    var a by remember(s.uri) { mutableStateOf(s.artist.ifBlank { if (grp(s) == "Unknown artist") "" else grp(s) }) }
    var b by remember(s.uri) { mutableStateOf(s.album) }
    val sugg = vm.library
        .filter { it.album.isNotBlank() && (a.isBlank() || grp(it).equals(stripFeat(a), true)) }
        .map { it.album.trim() }.distinct()
        .filter { b.isBlank() || (!it.equals(b.trim(), true) && it.contains(b.trim(), true)) }
        .take(3)
    AlertDialog(
        onDismissRequest = { vm.editingSong = null },
        title = { Text("Edit info") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(t, { t = it }, singleLine = true, label = { Text("Title") })
                OutlinedTextField(a, { a = it }, singleLine = true, label = { Text("Artist") })
                OutlinedTextField(b, { b = it }, singleLine = true, label = { Text("Album") })
                sugg.forEach { n ->
                    Text(n, Modifier.fillMaxWidth().clickable { b = n }.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = { TextButton({ vm.editSongInfo(s.uri, t, a, b); vm.editingSong = null }) { Text("Save") } },
        dismissButton = { TextButton({ vm.editingSong = null }) { Text("Cancel") } }
    )
}

@Composable
fun EditArtistDialog(vm: PlayerVM, nav: LibNav) {
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        val n = vm.editingArtist
        if (u != null && n != null) vm.setArtistPhoto(n, u)
    }
    val old = vm.editingArtist ?: return
    var name by remember(old) { mutableStateOf(old) }
    AlertDialog(
        onDismissRequest = { vm.editingArtist = null },
        title = { Text("Edit artist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Art(vm.artistArt(old), Modifier.size(64.dp), 200, CircleShape, Icons.Rounded.Person)
                    Column {
                        TextButton({ photo.launch("image/*") }) { Text("Change photo") }
                        if (vm.artistPhotos.containsKey(old.lowercase())) TextButton({ vm.removeArtistPhoto(old) }) { Text("Remove photo") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                val n = name.trim()
                if (n.isNotEmpty()) {
                    vm.renameArtist(old, n)
                    if (nav.artist.equals(old, true)) nav.artist = n
                }
                vm.editingArtist = null
            }) { Text("Save") }
        },
        dismissButton = { TextButton({ vm.editingArtist = null }) { Text("Cancel") } }
    )
}

@Composable
fun EditAlbumDialog(vm: PlayerVM, nav: LibNav) {
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        val e = vm.editingAlbum
        if (u != null && e != null) vm.pickAlbumPhoto(e.first, e.second, u)
    }
    val e = vm.editingAlbum ?: return
    var name by remember(e) { mutableStateOf(e.second) }
    AlertDialog(
        onDismissRequest = { vm.editingAlbum = null },
        title = { Text("Edit album") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Album name") })
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ photo.launch("image/*") }) { Text("Change cover") }
                    TextButton({
                        val rep = vm.library.firstOrNull { sameArtist(it, e.first) && it.album.trim().equals(e.second, true) }
                        vm.editingAlbum = null
                        if (rep != null) vm.loadCovers(rep)
                    }) { Text("Find online") }
                }
            }
        },
        confirmButton = {
            TextButton({
                val n = name.trim()
                if (n.isNotEmpty() && n != e.second) {
                    vm.renameAlbum(e.first, e.second, n)
                    if (nav.album.equals(e.second, true)) nav.album = n
                }
                vm.editingAlbum = null
            }) { Text("Save") }
        },
        dismissButton = { TextButton({ vm.editingAlbum = null }) { Text("Cancel") } }
    )
}

@Composable
fun SettingsScreen(vm: PlayerVM) {
    var confirm by remember { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u -> if (u != null) vm.addFolder(u) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Theme")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("System", "Dark", "Light").forEachIndexed { i, n ->
                Pill(vm.theme == i, { vm.setThemeMode(i) }, { Text(n) })
            }
        }
        Text("Sleep timer" + if (vm.timerOn) " (active)" else "")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 60).forEach { m -> Pill(false, { vm.sleepTimer(m) }, { Text("$m min") }) }
            Pill(false, { vm.sleepTimer(0) }, { Text("Off") })
        }
        Text("Music folders")
        if (vm.folders.isEmpty()) Text("No folders yet. Add one and the app will find your songs.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        vm.folders.toList().forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(folderName(f), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton({ vm.removeFolder(f) }) { Icon(Icons.Rounded.Close, "Remove folder") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ folderPicker.launch(null) }) { Text("Add folder") }
            OutlinedButton({ vm.scanFolders() }, enabled = vm.folders.isNotEmpty() && !vm.scanning) {
                Text(if (vm.scanning) "Scanning..." else "Scan now")
            }
        }
        Button({ confirm = true }) { Text("Clear library") }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Clear library?") },
        text = { Text("This removes all songs from the app and your playlists. Your files are not deleted.") },
        confirmButton = { TextButton({ vm.clearLibrary(); confirm = false }) { Text("Clear") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } }
    )
}

@Composable
fun NowPlaying(vm: PlayerVM, onClose: () -> Unit) {
    val top = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, MaterialTheme.colorScheme.background)))) {
            NowPlayingContent(vm, onClose)
        }
    }
}

@Composable
fun NowPlayingContent(vm: PlayerVM, onClose: () -> Unit) {
    val c = vm.controller
    var pos by remember { mutableFloatStateOf(0f) }
    var dur by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(c) {
        while (true) {
            if (c != null && !dragging) {
                pos = c.currentPosition.toFloat()
                dur = maxOf(c.duration.toFloat(), 1f)
            }
            delay(500)
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { IconButton(onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Close") } }
        item {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Art(vm.artPath, Modifier.fillMaxWidth().aspectRatio(1f), 800)
            }
        }
        item {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(vm.title ?: "Nothing playing", fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (vm.artist.isNotBlank()) Text(vm.artist, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Slider(
                value = pos.coerceIn(0f, dur),
                onValueChange = { dragging = true; pos = it },
                valueRange = 0f..dur,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onBackground,
                    activeTrackColor = MaterialTheme.colorScheme.onBackground,
                    inactiveTrackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f)
                ),
                onValueChangeFinished = { c?.seekTo(pos.toLong()); dragging = false }
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton({ c?.shuffleModeEnabled = !vm.shuffle }) {
                    Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (vm.shuffle) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                }
                IconButton({ c?.seekToPrevious() }) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
                FilledIconButton(
                    { vm.toggle() }, Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play or pause", Modifier.size(38.dp))
                }
                IconButton({ c?.seekToNext() }) { Icon(Icons.Rounded.SkipNext, "Next") }
                IconButton({ c?.repeatMode = if (vm.repeatAll) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL }) {
                    Icon(Icons.Rounded.Repeat, "Repeat", tint = if (vm.repeatAll) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                }
            }
        }
        item { Text("Queue", Modifier.padding(top = 16.dp, bottom = 4.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        itemsIndexed(vm.queue) { i, q ->
            Row(
                Modifier.fillMaxWidth().clickable { c?.seekToDefaultPosition(i); c?.play() }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Art(q.art, Modifier.size(40.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        q.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (i == vm.index) MaterialTheme.colorScheme.primary else LocalContentColor.current
                    )
                    if (q.artist.isNotBlank()) Text(q.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ vm.removeFromQueue(i) }) { Icon(Icons.Rounded.Close, "Remove from queue") }
            }
        }
    }
}

@Composable
fun Pill(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) =
    FilterChip(
        selected, onClick, label,
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
        )
    )

@Composable
fun CoverDialog(vm: PlayerVM) {
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) vm.pickPhoto(u) }
    val s = vm.coverFor ?: return
    AlertDialog(
        onDismissRequest = { vm.coverFor = null },
        title = { Text("Choose cover") },
        text = {
            Column {
                Text(
                    if (s.album.isBlank()) s.title else "Applies to all songs from ${s.album}",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp)
                )
                if (vm.coversLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (vm.covers.isEmpty()) Text("No covers found. You can pick one from your phone.")
                LazyVerticalGrid(
                    GridCells.Fixed(3), Modifier.heightIn(max = 320.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    gridItems(vm.covers) { o ->
                        Art(o.thumb, Modifier.fillMaxWidth().aspectRatio(1f).clickable { vm.pickCover(o) }, 300)
                    }
                }
            }
        },
        confirmButton = { TextButton({ photo.launch("image/*") }) { Text("From my phone") } },
        dismissButton = { TextButton({ vm.coverFor = null }) { Text("Cancel") } }
    )
}
