@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.offlineplayer

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
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

data class LyricLine(val t: Long, val text: String)

val LocalIsPlaying = compositionLocalOf { false }
val Red = Color(0xFFBF1B27)

val HeaderFont = FontFamily(
    Font(R.font.liberation_sans_narrow_bold, FontWeight.Normal),
    Font(R.font.liberation_sans_narrow_bold, FontWeight.Bold),
    Font(R.font.liberation_sans_narrow_bold, FontWeight.Black)
)
val BodyFont = FontFamily(
    Font(R.font.liberation_serif_bold, FontWeight.Normal),
    Font(R.font.liberation_serif_bold, FontWeight.Medium),
    Font(R.font.liberation_serif_bold, FontWeight.Bold),
    Font(R.font.liberation_serif_bold, FontWeight.Black)
)

fun Typography.withFont(f: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f), headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f), bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f)
)

fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")

fun fmtTime(ms: Long): String {
    val sec = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(sec / 60, sec % 60)
}

fun avgColor(path: String?): Color? {
    if (path == null) return null
    return try {
        val o = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bmp = BitmapFactory.decodeFile(path, o) ?: return null
        val sm = Bitmap.createScaledBitmap(bmp, 12, 12, true)
        var r = 0.0; var g = 0.0; var b = 0.0; var w = 0.0
        val hsv = FloatArray(3)
        for (x in 0 until 12) for (y in 0 until 12) {
            val px = sm.getPixel(x, y)
            android.graphics.Color.colorToHSV(px, hsv)
            val wt = (hsv[1] * hsv[2]).toDouble() + 0.05
            r += android.graphics.Color.red(px) * wt
            g += android.graphics.Color.green(px) * wt
            b += android.graphics.Color.blue(px) * wt
            w += wt
        }
        val c = android.graphics.Color.rgb((r / w).toInt(), (g / w).toInt(), (b / w).toInt())
        android.graphics.Color.colorToHSV(c, hsv)
        hsv[1] = minOf(1f, hsv[1] * 1.25f)
        hsv[2] = maxOf(hsv[2], 0.55f)
        Color(android.graphics.Color.HSVToColor(hsv))
    } catch (e: Exception) { null }
}

fun parseLrc(text: String): List<LyricLine> {
    val out = ArrayList<LyricLine>()
    val re = Regex("\\[(\\d+):(\\d+(?:\\.\\d+)?)]")
    for (line in text.lines()) {
        val ms = re.findAll(line).toList()
        if (ms.isEmpty()) continue
        val t = line.replace(re, "").trim()
        for (m in ms) out.add(LyricLine(((m.groupValues[1].toLong() * 60 + m.groupValues[2].toDouble()) * 1000).toLong(), t))
    }
    return out.sortedBy { it.t }
}

class LibNav {
    var artist by mutableStateOf<String?>(null)
    var album by mutableStateOf<String?>(null)
}

fun fileKey(u: String): String = try {
    val x = Uri.parse(u)
    x.authority + "|" + DocumentsContract.getDocumentId(x)
} catch (e: Exception) { u }

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
    val selected = mutableStateListOf<String>()
    var selDialog by mutableIntStateOf(0)
    var updateMsg by mutableStateOf("")
    var updateUrl by mutableStateOf<String?>(null)
    var updateBusy by mutableStateOf(false)
    var playingUri by mutableStateOf<String?>(null)
    var posMs by mutableLongStateOf(0L)
    var durMs by mutableLongStateOf(0L)
    var lyricLines by mutableStateOf<List<LyricLine>>(emptyList())
    var plainLyrics by mutableStateOf("")
    var lyricsState by mutableIntStateOf(0)
    private var lyricsUri: String? = null

    init { load(); connect(); startTicker() }

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
            val known = library.map { fileKey(it.uri) }.toHashSet()
            val fresh = found.filter { fileKey(it.toString()) !in known }.distinctBy { fileKey(it.toString()) }
            scanTotal = fresh.size
            var added = 0
            for (u in fresh) {
                val song = withContext(Dispatchers.IO) { readTags(u) }
                library.add(song)
                added++
                scanDone++
                if (scanDone % 25 == 0) save()
            }
            save()
            scanning = false
            val removed = dedupeLibrary()
            scanMsg = (if (added == 0) "No new songs found" else "Added $added new songs") +
                (if (removed > 0) ", removed $removed duplicates" else "")
            findInfo()
            delay(5000)
            scanMsg = ""
        }
    }

    private fun dedupeLibrary(): Int {
        val seen = HashMap<String, Song>()
        val drop = ArrayList<Song>()
        for (s in library.toList()) {
            val k = fileKey(s.uri)
            if (seen.containsKey(k)) drop.add(s) else seen[k] = s
        }
        if (drop.isEmpty()) return 0
        for (d in drop) {
            val keep = seen[fileKey(d.uri)] ?: continue
            for (i in playlists.indices) {
                if (d.uri in playlists[i].uris) {
                    playlists[i] = playlists[i].copy(uris = playlists[i].uris.map { u -> if (u == d.uri) keep.uri else u }.distinct())
                }
            }
            library.remove(d)
        }
        save()
        return drop.size
    }

    // ---------- multi-select ----------
    fun toggleSel(uri: String) { if (uri in selected) selected.remove(uri) else selected.add(uri) }

    fun clearSel() { selected.clear(); selDialog = 0 }

    fun addAllTo(pid: String, uris: List<String>) = update(pid) { p -> p.copy(uris = p.uris + uris.filter { it !in p.uris }) }

    fun removeSongs(uris: Set<String>) {
        library.removeAll { it.uri in uris }
        for (i in playlists.indices) playlists[i] = playlists[i].copy(uris = playlists[i].uris.filter { it !in uris })
        save()
    }

    fun moveSongs(uris: Set<String>, artist: String, album: String) {
        val na = artist.trim()
        val nb = album.trim()
        val g = if (na.isBlank()) "Unknown artist" else stripFeat(na)
        val artFrom = library.firstOrNull {
            it.uri !in uris && it.art != null && it.album.trim().equals(nb, true) && grp(it).equals(g, true)
        }?.art
        editWhere({ it.uri in uris }) { s ->
            val same = na.isBlank() || grp(s).equals(g, true)
            s.copy(
                album = nb,
                albumArtist = if (same) s.albumArtist else stripFeat(na),
                artist = if (same) s.artist else na,
                art = artFrom ?: s.art,
                looked = true
            )
        }
    }

    // ---------- app updates ----------
    fun installedBuild(): Int = try {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0)).toInt()
    } catch (e: Exception) { 0 }

    fun checkUpdate() {
        if (updateBusy) return
        updateBusy = true
        updateUrl = null
        updateMsg = "Checking..."
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { httpGet("https://api.github.com/repos/Opowl/music-player/releases/latest") }
            val cur = installedBuild()
            updateMsg = "Couldn't check for updates. Are you online?"
            if (r != null) {
                try {
                    val o = JSONObject(String(r))
                    val n = o.getString("tag_name").substringAfter("build-").toIntOrNull() ?: 0
                    var url: String? = null
                    val assets = o.getJSONArray("assets")
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
                    }
                    if (n > cur && url != null) {
                        updateUrl = url
                        updateMsg = "Build $n is available (you have build $cur)"
                    } else updateMsg = "You're up to date (build $cur)"
                } catch (e: Exception) { }
            }
            updateBusy = false
        }
    }

    fun installUpdate() {
        val url = updateUrl ?: return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            updateMsg = "Allow Offline Player to install apps, then tap Update again."
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        updateBusy = true
        updateMsg = "Downloading..."
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) {
                val b = httpGet(url)
                if (b == null) null else {
                    val d = File(ctx.cacheDir, "updates").apply { mkdirs() }
                    val out = File(d, "update.apk")
                    out.writeBytes(b)
                    out
                }
            }
            updateBusy = false
            if (f == null) { updateMsg = "Download failed. Try again."; return@launch }
            updateMsg = "Opening installer..."
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ---------- progress, resume ----------
    private fun startTicker() {
        viewModelScope.launch {
            var n = 0
            while (true) {
                val c = controller
                if (c != null) {
                    posMs = c.currentPosition
                    durMs = maxOf(c.duration, 0L)
                    n++
                    if (c.isPlaying && n % 17 == 0) saveSession()
                }
                delay(300)
            }
        }
    }

    private fun saveSession() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        try {
            val q = JSONArray()
            for (i in 0 until c.mediaItemCount) q.put(c.getMediaItemAt(i).mediaId)
            File(ctx.filesDir, "session.json").writeText(
                JSONObject().put("q", q).put("i", c.currentMediaItemIndex).put("p", c.currentPosition)
                    .put("s", c.shuffleModeEnabled).put("r", c.repeatMode == Player.REPEAT_MODE_ALL).toString()
            )
        } catch (e: Exception) { }
    }

    private fun restoreSession(c: MediaController) {
        try {
            val f = File(ctx.filesDir, "session.json")
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            val q = o.getJSONArray("q")
            val savedIdx = o.optInt("i")
            val items = ArrayList<MediaItem>()
            var start = 0
            var found = false
            for (k in 0 until q.length()) {
                val sg = song(q.getString(k))
                if (k == savedIdx) { start = items.size; found = sg != null }
                if (sg != null) items.add(item(sg))
            }
            if (items.isEmpty()) return
            start = start.coerceAtMost(items.size - 1)
            c.shuffleModeEnabled = o.optBoolean("s")
            c.repeatMode = if (o.optBoolean("r")) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            c.setMediaItems(items, start, if (found) o.optLong("p") else 0L)
            c.prepare()
        } catch (e: Exception) { }
    }

    // ---------- lyrics ----------
    private fun fetchLyrics(s: Song, durSec: Int): Pair<String, String>? {
        val q = if (s.artist.isNotBlank()) "track_name=" + URLEncoder.encode(s.title, "UTF-8") + "&artist_name=" + URLEncoder.encode(grp(s), "UTF-8")
        else "q=" + URLEncoder.encode(s.title, "UTF-8")
        val b = httpGet("https://lrclib.net/api/search?$q") ?: return null
        try {
            val arr = JSONArray(String(b))
            var best: JSONObject? = null
            var bestScore = Int.MAX_VALUE
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val syn = o.str("syncedLyrics")
                val pl = o.str("plainLyrics")
                if (syn.isBlank() && pl.isBlank()) continue
                var sc = if (syn.isNotBlank()) 0 else 1000
                val d = o.optDouble("duration", 0.0)
                if (durSec > 0 && d > 0) sc += Math.abs(d - durSec).toInt()
                if (sc < bestScore) { bestScore = sc; best = o }
            }
            val o = best ?: return Pair("", "")
            return Pair(o.str("syncedLyrics"), o.str("plainLyrics"))
        } catch (e: Exception) { return null }
    }

    fun loadLyrics(force: Boolean = false) {
        val uri = playingUri ?: return
        if (!force && uri == lyricsUri && lyricsState != 0) return
        lyricsUri = uri
        lyricLines = emptyList()
        plainLyrics = ""
        val s = song(uri)
        if (s == null) { lyricsState = 3; return }
        lyricsState = 1
        val d = controller?.duration ?: 0L
        val durSec = if (d > 0) (d / 1000).toInt() else 0
        viewModelScope.launch {
            val dir = File(ctx.filesDir, "lyrics").apply { mkdirs() }
            val cf = File(dir, md5(uri) + ".json")
            var syn = ""
            var plain = ""
            var ok = false
            if (!force && cf.exists()) {
                try {
                    val o = JSONObject(cf.readText())
                    syn = o.optString("s")
                    plain = o.optString("p")
                    ok = true
                } catch (e: Exception) { }
            }
            if (!ok) {
                val r = withContext(Dispatchers.IO) { try { fetchLyrics(s, durSec) } catch (e: Exception) { null } }
                if (r != null) {
                    syn = r.first
                    plain = r.second
                    withContext(Dispatchers.IO) { try { cf.writeText(JSONObject().put("s", syn).put("p", plain).toString()) } catch (e: Exception) { } }
                }
            }
            if (lyricsUri != uri) return@launch
            lyricLines = parseLrc(syn)
            plainLyrics = plain
            lyricsState = if (lyricLines.isNotEmpty() || plain.isNotBlank()) 2 else 3
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
        override fun onEvents(player: Player, events: Player.Events) { sync(player); saveSession() }
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
        playingUri = p.currentMediaItem?.mediaId
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
                if (c.mediaItemCount == 0) restoreSession(c)
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
        primary = Red, onPrimary = Color.White,
        background = Color.Black, surface = Color.Black, surfaceVariant = Color(0xFF111111),
        onSurface = Color.White, onBackground = Color.White, onSurfaceVariant = Color(0xFFB0B0B0),
        surfaceTint = Color.Transparent, outline = Color(0xFF3A3A3A),
        surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0A0A0A),
        surfaceContainer = Color(0xFF101010), surfaceContainerHigh = Color(0xFF1A1A1A),
        surfaceContainerHighest = Color(0xFF1C1C1C)
    ) else lightColorScheme(
        primary = Red, onPrimary = Color.White,
        background = Color(0xFFFAF6F6), surface = Color(0xFFFAF6F6), surfaceVariant = Color(0xFFF0E8E8),
        surfaceTint = Color.Transparent
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(2.dp), small = RoundedCornerShape(4.dp),
        medium = RoundedCornerShape(4.dp), large = RoundedCornerShape(6.dp), extraLarge = RoundedCornerShape(6.dp)
    )
    MaterialTheme(colorScheme = scheme, shapes = shapes, typography = Typography().withFont(BodyFont), content = content)
}

@Composable
fun Modifier.enter(index: Int): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(if (index < 12) index * 35L else 0L)
        a.animateTo(1f, tween(320))
    }
    return this.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 40f }
}

@Composable
fun Modifier.card(
    selected: Boolean = false, playing: Boolean = false,
    onClick: () -> Unit, onLong: (() -> Unit)? = null
): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val off by animateFloatAsState(
        if (pressed) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press"
    )
    val haptic = LocalHapticFeedback.current
    val c = MaterialTheme.colorScheme
    val d = LocalDensity.current.density
    val shape = RoundedCornerShape(4.dp)
    val bg = if (selected) Color.White else if (playing) c.primary else c.surfaceVariant
    val shadow = if (playing && !selected) Color.White else c.primary
    val border = if (selected) c.primary else c.onSurface
    val lc: (() -> Unit)? = if (onLong == null) null else ({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLong() })
    return this
        .drawBehind { drawRoundRect(shadow, Offset(4f * d, 4f * d), size, CornerRadius(4f * d)) }
        .graphicsLayer { translationX = off * 3f * d; translationY = off * 3f * d }
        .clip(shape)
        .background(bg)
        .border(2.dp, border, shape)
        .combinedClickable(
            interactionSource = src, indication = LocalIndication.current,
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
            onLongClick = lc
        )
}

fun fgOn(selected: Boolean, playing: Boolean): Color =
    if (selected) Color.Black else if (playing) Color.White else Color.Unspecified

fun subOn(selected: Boolean, playing: Boolean, normal: Color): Color =
    if (selected) Color(0xFF444444) else if (playing) Color(0xFFFFD9DB) else normal

@Composable
fun EqBars(color: Color) {
    val live = LocalIsPlaying.current
    val t = rememberInfiniteTransition(label = "eq")
    val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(560, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(350, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    Row(Modifier.height(16.dp).width(18.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(a, b, c).forEach { h ->
            Box(Modifier.width(4.dp).fillMaxHeight(if (live) h else 0.3f).background(color, RoundedCornerShape(1.dp)))
        }
    }
}

@Composable
fun rememberGlow(path: String?): Color {
    val fallback = MaterialTheme.colorScheme.primary
    val c by produceState(fallback, path) { value = withContext(Dispatchers.IO) { avgColor(path) } ?: fallback }
    return c
}

@Composable
fun Art(
    path: String?, modifier: Modifier = Modifier, px: Int = 128,
    shape: Shape = RoundedCornerShape(2.dp), icon: ImageVector = Icons.Rounded.MusicNote
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
    var lyrics by remember { mutableStateOf(false) }
    val nav = remember { LibNav() }
    CompositionLocalProvider(LocalIsPlaying provides vm.isPlaying) {
        CoverDialog(vm)
        EditSongDialog(vm)
        EditArtistDialog(vm, nav)
        EditAlbumDialog(vm, nav)
        AddToPlaylistDialog(vm)
        SelectionDialogs(vm)
        Box(Modifier.fillMaxSize()) {
            if (open != null) BackHandler { open = null }
            if (tab == 1 && nav.artist != null) BackHandler { if (nav.album != null) nav.album = null else nav.artist = null }
            if (vm.selected.isNotEmpty()) BackHandler { vm.clearSel() }
            Scaffold(bottomBar = {
                Column {
                    if (vm.title != null) MiniPlayer(vm) { full = true }
                    else Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.onSurface))
                    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                        listOf(
                            "Playlists" to Icons.Rounded.LibraryMusic,
                            "Library" to Icons.Rounded.MusicNote,
                            "Settings" to Icons.Rounded.Settings
                        ).forEachIndexed { i, (label, icon) ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { vm.clearSel(); if (i == 1 && tab == 1) { nav.artist = null; nav.album = null }; tab = i; open = null },
                                icon = { Icon(icon, label) },
                                label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primary,
                                    selectedIconColor = Color.White,
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
                    if (vm.selected.isNotEmpty()) SelectionBar(vm, Modifier.align(Alignment.TopCenter))
                }
            }
            if (full) BackHandler { if (lyrics) lyrics = false else full = false }
            AnimatedVisibility(
                visible = full,
                enter = slideInVertically(tween(320)) { it } + fadeIn(tween(320)),
                exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(260))
            ) {
                if (lyrics) LyricsScreen(vm) { lyrics = false }
                else NowPlaying(vm, { full = false; lyrics = false }, { lyrics = true })
            }
        }
    }
}

@Composable
fun MiniPlayer(vm: PlayerVM, onOpen: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(0.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }
    ) {
        Column {
            Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(vm.artPath, Modifier.size(44.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(vm.title ?: "", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (vm.artist.isNotBlank()) Text(vm.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ vm.controller?.seekToPrevious() }) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
                IconButton({ vm.toggle() }) {
                    Icon(if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play or pause")
                }
                IconButton({ vm.controller?.seekToNext() }) { Icon(Icons.Rounded.SkipNext, "Next") }
            }
            val frac = if (vm.durMs > 0) (vm.posMs.toFloat() / vm.durMs).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF333333))) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

@Composable
fun MenuItem(text: String, onClick: () -> Unit) = DropdownMenuItem(text = { Text(text) }, onClick = onClick)

@Composable
fun SongRow(
    s: Song, onClick: () -> Unit, selected: Boolean = false, onLong: (() -> Unit)? = null,
    playing: Boolean = false, index: Int = 0, menu: @Composable (() -> Unit) -> Unit
) {
    var show by remember { mutableStateOf(false) }
    val subC = subOn(selected, playing, MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).enter(index)
            .card(selected, playing, onClick, onLong)
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Art(s.art, Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, color = fgOn(selected, playing))
            if (s.artist.isNotBlank()) Text(s.artist, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = subC)
        }
        if (playing) EqBars(if (selected) MaterialTheme.colorScheme.primary else Color.White)
        Box {
            IconButton({ show = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = if (selected) Color.Black else LocalContentColor.current) }
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
            Text("PLAYLISTS", fontSize = 36.sp, fontWeight = FontWeight.Black, fontFamily = HeaderFont, letterSpacing = (-1).sp, modifier = Modifier.weight(1f))
            Btn({ creating = true }) { Text("New playlist") }
        }
        if (vm.playlists.isEmpty()) EmptyCard("NO PLAYLISTS YET", "Make your first one and start filling it.", "NEW PLAYLIST") { creating = true }
        LazyColumn {
            itemsIndexed(vm.playlists, key = { _, p -> p.id }) { i, p ->
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).enter(i)
                        .card(onClick = { onOpen(p.id) }).padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(p.name, fontSize = 18.sp)
                    Text("${p.uris.size} songs", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { Footer("${vm.playlists.size} PLAYLISTS") }
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
            Btn({ vm.play(songs) }, enabled = songs.isNotEmpty()) { Text("Play") }
            OBtn({ vm.play(songs, songs.indices.random(), true) }, enabled = songs.isNotEmpty()) { Text("Shuffle") }
            OBtn({ adding = true }) { Text("Add songs") }
        }
        LazyColumn {
            itemsIndexed(songs) { i, s ->
                SongRow(s, { if (vm.selected.isNotEmpty()) vm.toggleSel(s.uri) else vm.play(songs, i) }, s.uri in vm.selected, { vm.toggleSel(s.uri) }, s.uri == vm.playingUri, i) { dismiss ->
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
    AnimatedContent(
        targetState = Pair(nav.artist, nav.album),
        transitionSpec = {
            val from = (if (initialState.first == null) 0 else if (initialState.second == null) 1 else 2)
            val to = (if (targetState.first == null) 0 else if (targetState.second == null) 1 else 2)
            if (to >= from) (slideInHorizontally(tween(300)) { it / 3 } + fadeIn(tween(300))) togetherWith
                (slideOutHorizontally(tween(300)) { -it / 3 } + fadeOut(tween(200)))
            else (slideInHorizontally(tween(300)) { -it / 3 } + fadeIn(tween(300))) togetherWith
                (slideOutHorizontally(tween(300)) { it / 3 } + fadeOut(tween(200)))
        },
        label = "library"
    ) { st ->
        val a = st.first
        val b = st.second
        when {
            a == null -> ArtistsScreen(vm, nav)
            b == null -> ArtistPage(vm, nav, a)
            else -> AlbumPage(vm, nav, a, b)
        }
    }
}

@Composable
fun ArtistRow(vm: PlayerVM, name: String, count: Int, index: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).enter(index)
            .card(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Art(vm.artistArt(name), Modifier.size(56.dp), 160, RoundedCornerShape(2.dp), Icons.Rounded.Person)
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
            Text("LIBRARY", fontSize = 36.sp, fontWeight = FontWeight.Black, fontFamily = HeaderFont, letterSpacing = (-1).sp, modifier = Modifier.weight(1f))
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
        if (vm.library.isEmpty()) EmptyCard("NO MUSIC YET", "Pick your music folder and the app finds your songs.", "SCAN A FOLDER") {
            if (vm.folders.isEmpty()) folderPicker.launch(null) else vm.scanFolders()
        }
        else if (ql.isEmpty()) Btn(
            { vm.play(vm.library.toList(), vm.library.indices.random(), true) },
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        ) { Text("SHUFFLE ALL", fontWeight = FontWeight.Black, fontFamily = HeaderFont, letterSpacing = 2.sp) }
        LazyColumn {
            if (ql.isEmpty()) {
                itemsIndexed(artists, key = { _, a -> a.first.lowercase() }) { i, (n, ss) -> ArtistRow(vm, n, ss.size, i) { nav.artist = n; nav.album = null } }
                item { Footer("${artists.size} ARTISTS - ${vm.library.size} SONGS") }
            } else {
                val hitArtists = artists.filter { it.first.lowercase().contains(ql) }
                val hitAlbums = artists.flatMap { (an, ss) ->
                    albumsOf(ss).filter { it.first.isNotBlank() && it.first.lowercase().contains(ql) }.map { Triple(an, it.first, it.second) }
                }
                val hitSongs = vm.library.filter { it.title.lowercase().contains(ql) }
                if (hitArtists.isNotEmpty()) item { SectionLabel("Artists") }
                items(hitArtists) { (n, ss) -> ArtistRow(vm, n, ss.size, 0) { nav.artist = n; nav.album = null } }
                if (hitAlbums.isNotEmpty()) item { SectionLabel("Albums") }
                items(hitAlbums) { t ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).card(onClick = { nav.artist = t.first; nav.album = t.second }).padding(8.dp),
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
                    SongRow(s, { if (vm.selected.isNotEmpty()) vm.toggleSel(s.uri) else vm.play(hitSongs, i) }, s.uri in vm.selected, { vm.toggleSel(s.uri) }, s.uri == vm.playingUri, i) { d ->
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
                    Art(vm.artistArt(artist), Modifier.size(96.dp), 300, RoundedCornerShape(2.dp), Icons.Rounded.Person)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(artist, fontSize = 26.sp, fontWeight = FontWeight.Black, fontFamily = HeaderFont, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${albums.size} ${if (albums.size == 1) "album" else "albums"} - ${songs.size} songs",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn({ vm.play(all) }) { Text("Play") }
                    OBtn({ vm.play(all, all.indices.random(), true) }) { Text("Shuffle") }
                }
            }
            itemsIndexed(albums, key = { _, a -> a.first.lowercase() }) { idx, (name, ss) ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).enter(idx)
                        .card(onClick = { nav.album = name }).padding(8.dp),
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
                    Text(album.ifBlank { "Singles & other" }, Modifier.padding(top = 12.dp), fontSize = 26.sp, fontWeight = FontWeight.Black, fontFamily = HeaderFont)
                    Text(artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn({ vm.play(songs) }) { Text("Play") }
                    OBtn({ vm.play(songs, songs.indices.random(), true) }) { Text("Shuffle") }
                }
            }
            itemsIndexed(songs) { i, s ->
                var show by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).enter(i)
                        .card(
                            selected = s.uri in vm.selected, playing = s.uri == vm.playingUri,
                            onClick = { if (vm.selected.isNotEmpty()) vm.toggleSel(s.uri) else vm.play(songs, i) },
                            onLong = { vm.toggleSel(s.uri) }
                        )
                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (s.track > 0) "${s.track}" else "${i + 1}", Modifier.width(32.dp), fontWeight = FontWeight.Black, fontFamily = HeaderFont, color = subOn(s.uri in vm.selected, s.uri == vm.playingUri, MaterialTheme.colorScheme.onSurfaceVariant))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, color = fgOn(s.uri in vm.selected, s.uri == vm.playingUri))
                        if (s.artist.isNotBlank() && !s.artist.equals(artist, true))
                            Text(s.artist, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = subOn(s.uri in vm.selected, s.uri == vm.playingUri, MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                    if (s.uri == vm.playingUri) EqBars(if (s.uri in vm.selected) MaterialTheme.colorScheme.primary else Color.White)
                    Box {
                        IconButton({ show = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = if (s.uri in vm.selected) Color.Black else LocalContentColor.current) }
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
fun SelectionBar(vm: PlayerVM, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.clearSel() }) { Icon(Icons.Rounded.Close, "Cancel selection") }
            Text("${vm.selected.size} selected", Modifier.weight(1f))
            TextButton({ vm.selDialog = 1 }) { Text("Playlist") }
            TextButton({ vm.selDialog = 2 }) { Text("Move") }
            IconButton({ vm.selDialog = 3 }) { Icon(Icons.Rounded.Delete, "Delete") }
        }
    }
}

@Composable
fun SelectionDialogs(vm: PlayerVM) {
    val n = vm.selected.size
    if (n == 0) return
    val sel = vm.selected.toSet()
    when (vm.selDialog) {
        1 -> AlertDialog(
            onDismissRequest = { vm.selDialog = 0 },
            title = { Text("Add $n songs to playlist") },
            text = {
                if (vm.playlists.isEmpty()) Text("Create a playlist first.")
                else LazyColumn {
                    items(vm.playlists, key = { it.id }) { p ->
                        Text(
                            p.name,
                            Modifier.fillMaxWidth().clickable { vm.addAllTo(p.id, vm.selected.toList()); vm.clearSel() }.padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton({ vm.selDialog = 0 }) { Text("Cancel") } }
        )
        2 -> MoveDialog(vm, sel)
        3 -> AlertDialog(
            onDismissRequest = { vm.selDialog = 0 },
            title = { Text("Remove $n songs?") },
            text = { Text("They're removed from the app and your playlists. Your files aren't deleted, and a folder scan will bring them back.") },
            confirmButton = { TextButton({ vm.removeSongs(sel); vm.clearSel() }) { Text("Remove") } },
            dismissButton = { TextButton({ vm.selDialog = 0 }) { Text("Cancel") } }
        )
    }
}

@Composable
fun MoveDialog(vm: PlayerVM, sel: Set<String>) {
    val groups = vm.library.filter { it.uri in sel }.map { grp(it) }.distinct()
    var a by remember { mutableStateOf(if (groups.size == 1 && groups[0] != "Unknown artist") groups[0] else "") }
    var b by remember { mutableStateOf("") }
    val sugg = vm.library
        .filter { it.album.isNotBlank() && (a.isBlank() || grp(it).equals(stripFeat(a), true)) }
        .map { it.album.trim() }.distinct()
        .filter { b.isBlank() || (!it.equals(b.trim(), true) && it.contains(b.trim(), true)) }
        .take(4)
    AlertDialog(
        onDismissRequest = { vm.selDialog = 0 },
        title = { Text("Move ${sel.size} songs") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(a, { a = it }, singleLine = true, label = { Text("Artist") })
                OutlinedTextField(b, { b = it }, singleLine = true, label = { Text("Album") })
                sugg.forEach { n ->
                    Text(n, Modifier.fillMaxWidth().clickable { b = n }.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = { TextButton({ vm.moveSongs(sel, a, b); vm.clearSel() }) { Text("Move") } },
        dismissButton = { TextButton({ vm.selDialog = 0 }) { Text("Cancel") } }
    )
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
                    Art(vm.artistArt(old), Modifier.size(64.dp), 200, RoundedCornerShape(2.dp), Icons.Rounded.Person)
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
        Text("SETTINGS", fontSize = 36.sp, fontWeight = FontWeight.Black, fontFamily = HeaderFont, letterSpacing = (-1).sp)
        Text("THEME", fontWeight = FontWeight.Black, fontFamily = HeaderFont, letterSpacing = 1.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("System", "Dark", "Light").forEachIndexed { i, n ->
                Pill(vm.theme == i, { vm.setThemeMode(i) }, { Text(n) })
            }
        }
        Text("SLEEP TIMER" + (if (vm.timerOn) " (ACTIVE)" else ""), fontWeight = FontWeight.Black, fontFamily = HeaderFont, letterSpacing = 1.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 60).forEach { m -> Pill(false, { vm.sleepTimer(m) }, { Text("$m min") }) }
            Pill(false, { vm.sleepTimer(0) }, { Text("Off") })
        }
        Text("MUSIC FOLDERS", fontWeight = FontWeight.Black, fontFamily = HeaderFont, letterSpacing = 1.sp)
        if (vm.folders.isEmpty()) Text("No folders yet. Add one and the app will find your songs.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        vm.folders.toList().forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(folderName(f), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton({ vm.removeFolder(f) }) { Icon(Icons.Rounded.Close, "Remove folder") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OBtn({ folderPicker.launch(null) }) { Text("Add folder") }
            OBtn({ vm.scanFolders() }, enabled = vm.folders.isNotEmpty() && !vm.scanning) {
                Text(if (vm.scanning) "Scanning..." else "Scan now")
            }
        }
        Text("APP UPDATES", fontWeight = FontWeight.Black, fontFamily = HeaderFont, letterSpacing = 1.sp)
        Text(
            vm.updateMsg.ifBlank { "Installed: build ${vm.installedBuild()}" },
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OBtn({ vm.checkUpdate() }, enabled = !vm.updateBusy) { Text("Check for updates") }
            if (vm.updateUrl != null) Btn({ vm.installUpdate() }, enabled = !vm.updateBusy) { Text("Update") }
        }
        Btn({ confirm = true }) { Text("Clear library") }
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
fun NowPlaying(vm: PlayerVM, onClose: () -> Unit, onLyrics: () -> Unit) {
    val flat by animateColorAsState(rememberGlow(vm.artPath), tween(500), label = "flat")
    Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
        NowPlayingContent(vm, onClose, onLyrics, flat)
    }
}

@Composable
fun NowPlayingContent(vm: PlayerVM, onClose: () -> Unit, onLyrics: () -> Unit, flat: Color) {
    val c = vm.controller
    var pos by remember { mutableFloatStateOf(0f) }
    var dur by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    val dens = LocalDensity.current.density
    val red = MaterialTheme.colorScheme.primary
    LaunchedEffect(c) {
        while (true) {
            if (c != null && !dragging) {
                pos = c.currentPosition.toFloat()
                dur = maxOf(c.duration.toFloat(), 1f)
            }
            delay(300)
        }
    }
    val soft = Color(0xFFBDBDBD)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { IconButton(onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Close") } }
        item {
            Box(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, bottom = 12.dp)
                    .drawBehind { drawRoundRect(red, Offset(8f * dens, 8f * dens), size, CornerRadius(4f * dens)) }
                    .background(flat)
                    .border(3.dp, Color.White, RoundedCornerShape(4.dp))
                    .padding(14.dp)
            ) {
                Art(vm.artPath, Modifier.fillMaxWidth().aspectRatio(1f), 800, RoundedCornerShape(2.dp))
            }
        }
        item {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(vm.title ?: "Nothing playing", fontSize = 28.sp, fontWeight = FontWeight.Black, fontFamily = HeaderFont, lineHeight = 32.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (vm.artist.isNotBlank()) Text(vm.artist, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = soft)
            }
        }
        item {
            Column {
                Slider(
                    value = pos.coerceIn(0f, dur),
                    onValueChange = { dragging = true; pos = it },
                    valueRange = 0f..dur,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = red,
                        inactiveTrackColor = Color(0xFF3A3A3A)
                    ),
                    onValueChangeFinished = { c?.seekTo(pos.toLong()); dragging = false }
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmtTime(pos.toLong()), fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = HeaderFont, color = soft)
                    Text(fmtTime(if (dur > 1f) dur.toLong() else 0L), fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = HeaderFont, color = soft)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton({ c?.shuffleModeEnabled = !vm.shuffle }) {
                    Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (vm.shuffle) red else Color.White)
                }
                IconButton({ c?.seekToPrevious() }) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
                FilledIconButton(
                    { vm.toggle() }, Modifier.size(64.dp),
                    shape = RoundedCornerShape(4.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = red, contentColor = Color.White)
                ) {
                    Icon(if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play or pause", Modifier.size(38.dp))
                }
                IconButton({ c?.seekToNext() }) { Icon(Icons.Rounded.SkipNext, "Next") }
                IconButton({ c?.repeatMode = if (vm.repeatAll) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL }) {
                    Icon(Icons.Rounded.Repeat, "Repeat", tint = if (vm.repeatAll) red else Color.White)
                }
            }
        }
        item {
            OBtn({ onLyrics() }, Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("LYRICS", fontWeight = FontWeight.Black, fontFamily = HeaderFont, letterSpacing = 2.sp)
            }
        }
        item { Text("QUEUE", Modifier.padding(top = 20.dp, bottom = 8.dp), fontSize = 20.sp, fontWeight = FontWeight.Black, fontFamily = HeaderFont, letterSpacing = 1.sp) }
        itemsIndexed(vm.queue) { i, q ->
            Row(
                Modifier.fillMaxWidth().padding(start = 0.dp, end = 6.dp, top = 4.dp, bottom = 6.dp)
                    .card(playing = i == vm.index, onClick = { c?.seekToDefaultPosition(i); c?.play() })
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Art(q.art, Modifier.size(40.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(q.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, color = Color.White)
                    if (q.artist.isNotBlank()) Text(q.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (i == vm.index) Color(0xFFFFD9DB) else soft)
                }
                IconButton({ vm.removeFromQueue(i) }) { Icon(Icons.Rounded.Close, "Remove from queue") }
            }
        }
    }
}

@Composable
fun LyricsScreen(vm: PlayerVM, onClose: () -> Unit) {
    val flat by animateColorAsState(rememberGlow(vm.artPath), tween(500), label = "flat")
    val onFlat = if (flat.luminance() > 0.5f) Color.Black else Color.White
    LaunchedEffect(vm.playingUri) { vm.loadLyrics() }
    val lines = vm.lyricLines
    val cur = lines.indexOfLast { it.t <= vm.posMs + 250 }
    val ls = rememberLazyListState()
    LaunchedEffect(cur) { if (cur >= 0 && lines.isNotEmpty()) ls.animateScrollToItem(maxOf(cur - 2, 0)) }
    val gray = Color(0xFF8C8C8C)
    Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().background(flat).border(2.dp, Color.White)) {
                CompositionLocalProvider(LocalContentColor provides onFlat) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Close") }
                        Column(Modifier.weight(1f)) {
                            Text(vm.title ?: "", fontWeight = FontWeight.Black, fontFamily = HeaderFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (vm.artist.isNotBlank()) Text(vm.artist, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
            when (vm.lyricsState) {
                1 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
                2 -> if (lines.isNotEmpty()) {
                    LazyColumn(state = ls, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 48.dp)) {
                        itemsIndexed(lines) { i, l ->
                            val on = i == cur
                            val bgc by animateColorAsState(if (on) Red else Color.Black, tween(200), label = "lineBg")
                            Text(
                                l.text.ifBlank { "..." }, fontSize = 26.sp, fontWeight = FontWeight.Black, fontFamily = BodyFont, lineHeight = 32.sp,
                                color = if (on) Color.White else gray,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).background(bgc)
                                    .clickable { vm.controller?.seekTo(l.t) }.padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                        Text(vm.plainLyrics, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
                    }
                }
                else -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("NO LYRICS FOUND", fontSize = 22.sp, fontWeight = FontWeight.Black, fontFamily = HeaderFont, color = gray)
                    OBtn({ vm.loadLyrics(true) }, Modifier.padding(top = 16.dp)) { Text("TRY AGAIN", fontWeight = FontWeight.Black, fontFamily = HeaderFont, letterSpacing = 1.sp) }
                }
            }
        }
    }
}

@Composable
fun Pill(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) =
    FilterChip(
        selected, onClick, label,
        shape = RoundedCornerShape(4.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = Color.White
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

@Composable
fun Btn(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    Button(onClick, modifier, enabled, shape = RoundedCornerShape(4.dp), content = content)

@Composable
fun OBtn(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    OutlinedButton(
        onClick, modifier, enabled, shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface), content = content
    )

@Composable
fun Footer(text: String) =
    Text(
        text, Modifier.fillMaxWidth().padding(24.dp), fontSize = 12.sp, fontWeight = FontWeight.Black, fontFamily = HeaderFont,
        letterSpacing = 2.sp, color = Color(0xFF8C8C8C), textAlign = TextAlign.Center
    )

@Composable
fun EmptyCard(title: String, sub: String, button: String, onClick: () -> Unit) {
    val d = LocalDensity.current.density
    val red = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 24.dp, top = 16.dp, bottom = 24.dp)
            .drawBehind { drawRoundRect(red, Offset(8f * d, 8f * d), size, CornerRadius(4f * d)) }
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(3.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(4.dp))
            .padding(20.dp)
    ) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Black, fontFamily = HeaderFont, lineHeight = 30.sp)
        Text(sub, Modifier.padding(top = 6.dp, bottom = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Btn(onClick, Modifier.fillMaxWidth()) { Text(button, fontWeight = FontWeight.Black, fontFamily = HeaderFont, letterSpacing = 1.sp) }
    }
}
