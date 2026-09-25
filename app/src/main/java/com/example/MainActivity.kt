package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

  private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
  private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>
  private lateinit var permissionLauncher: ActivityResultLauncher<String>
  private var currentWebView: WebView? = null
  private val isScanning = AtomicBoolean(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // File picker launcher for manual file selection
    filePickerLauncher = registerForActivityResult(
      ActivityResultContracts.StartActivityForResult()
    ) { result ->
      val callback = fileChooserCallback
      fileChooserCallback = null
      if (callback == null) return@registerForActivityResult

      if (result.resultCode == RESULT_OK && result.data != null) {
        val clipData = result.data?.clipData
        val dataUri = result.data?.data
        val results = mutableListOf<Uri>()

        if (clipData != null) {
          for (i in 0 until clipData.itemCount) {
            results.add(clipData.getItemAt(i).uri)
          }
        } else if (dataUri != null) {
          results.add(dataUri)
        }
        callback.onReceiveValue(results.toTypedArray())
      } else {
        callback.onReceiveValue(null)
      }
    }

    // Permission launcher for audio scanning
    permissionLauncher = registerForActivityResult(
      ActivityResultContracts.RequestPermission()
    ) { isGranted ->
      if (isGranted) {
        performAudioScan()
      } else {
        runOnUiThread {
          Toast.makeText(this, "Izin penyimpanan audio dibutuhkan untuk memindai lagu", Toast.LENGTH_LONG).show()
          currentWebView?.evaluateJavascript("window.onAudioPermissionDenied && window.onAudioPermissionDenied()", null)
        }
      }
    }

    setContent {
      MyApplicationTheme {
        Surface(
          modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .testTag("main_web_player_surface"),
          color = MaterialTheme.colorScheme.background
        ) {
          NanangPlayerWebView(
            onWebViewCreated = { webView ->
              currentWebView = webView
              webView.addJavascriptInterface(AndroidAudioBridge(), "AndroidBridge")
            },
            onPageFinished = {
              // Automatically check and scan audio when page finishes loading
              checkAndScanAudio(isSilent = true)
            },
            onOpenFileChooser = { callback, multiple ->
              fileChooserCallback?.onReceiveValue(null)
              fileChooserCallback = callback

              val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
                if (multiple) {
                  putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
              }
              filePickerLauncher.launch(Intent.createChooser(intent, "Pilih Berkas Audio"))
            }
          )
        }
      }
    }
  }

  fun checkAndScanAudio(isSilent: Boolean = false) {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      Manifest.permission.READ_MEDIA_AUDIO
    } else {
      Manifest.permission.READ_EXTERNAL_STORAGE
    }

    if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
      performAudioScan()
    } else if (!isSilent) {
      permissionLauncher.launch(permission)
    }
  }

  private fun performAudioScan() {
    if (!isScanning.compareAndSet(false, true)) {
      return
    }

    runOnUiThread {
      currentWebView?.evaluateJavascript("window.onAudioScanStarted && window.onAudioScanStarted()", null)
    }

    lifecycleScope.launch(Dispatchers.IO) {
      val scannedTracks = mutableListOf<ScannedTrack>()
      val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.ALBUM
      )

      val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR " +
          "${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%' OR " +
          "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.mp3' OR " +
          "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.wav' OR " +
          "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.ogg' OR " +
          "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.m4a' OR " +
          "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.flac' OR " +
          "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.aac'"

      val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

      try {
        contentResolver.query(
          MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
          projection,
          selection,
          null,
          sortOrder
        )?.use { cursor ->
          val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
          val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
          val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
          val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
          val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
          val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
          val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)

          while (cursor.moveToNext()) {
            val id = if (idCol >= 0) cursor.getLong(idCol) else continue
            val rawTitle = if (titleCol >= 0) cursor.getString(titleCol) else null
            val displayName = if (nameCol >= 0) cursor.getString(nameCol) ?: "Audio $id" else "Audio $id"
            val title = if (!rawTitle.isNullOrBlank()) rawTitle.trim() else displayName.substringBeforeLast(".").trim()
            val rawArtist = if (artistCol >= 0) cursor.getString(artistCol) else null
            val artist = if (!rawArtist.isNullOrBlank() && rawArtist != "<unknown>") rawArtist.trim() else "Artis Lokal"
            val durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
            val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
            val rawAlbum = if (albumCol >= 0) cursor.getString(albumCol) else null
            val album = if (!rawAlbum.isNullOrBlank() && rawAlbum != "<unknown>") rawAlbum.trim() else "Musik Lokal"

            val sizeMb = if (sizeBytes > 0) "%.1f MB".format(sizeBytes / (1024.0 * 1024.0)) else "Audio"
            val streamUrl = "https://nanang-player.local/audio?id=$id"

            scannedTracks.add(
              ScannedTrack(
                id = "device_$id",
                title = title,
                artist = artist,
                duration = if (durationMs > 0) durationMs / 1000.0 else 0.0,
                size = sizeMb,
                url = streamUrl,
                album = album
              )
            )
          }
        }
      } catch (e: Exception) {
        e.printStackTrace()
      } finally {
        isScanning.set(false)
      }

      val jsonArray = JSONArray()
      for (track in scannedTracks) {
        val obj = JSONObject()
        obj.put("id", track.id)
        obj.put("title", track.title)
        obj.put("artist", track.artist)
        obj.put("duration", track.duration)
        obj.put("size", track.size)
        obj.put("url", track.url)
        obj.put("album", track.album)
        jsonArray.put(obj)
      }
      val jsonString = jsonArray.toString()

      withContext(Dispatchers.Main) {
        currentWebView?.evaluateJavascript(
          "window.onAudioFilesScanned && window.onAudioFilesScanned($jsonString)",
          null
        )
      }
    }
  }

  inner class AndroidAudioBridge {
    @JavascriptInterface
    fun scanAudioFiles() {
      runOnUiThread {
        checkAndScanAudio(isSilent = false)
      }
    }

    @JavascriptInterface
    fun isAndroidNative(): Boolean = true
  }
}

data class ScannedTrack(
  val id: String,
  val title: String,
  val artist: String,
  val duration: Double,
  val size: String,
  val url: String,
  val album: String = "Audio"
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NanangPlayerWebView(
  onWebViewCreated: (WebView) -> Unit,
  onOpenFileChooser: (callback: ValueCallback<Array<Uri>>, multiple: Boolean) -> Unit,
  onPageFinished: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  var webViewInstance by remember { mutableStateOf<WebView?>(null) }

  BackHandler(enabled = webViewInstance?.canGoBack() == true) {
    webViewInstance?.goBack()
  }

  AndroidView(
    modifier = modifier
      .fillMaxSize()
      .testTag("nanang_player_webview"),
    factory = { context ->
      WebView(context).apply {
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = WebView.OVER_SCROLL_NEVER

        settings.apply {
          javaScriptEnabled = true
          domStorageEnabled = true
          databaseEnabled = true
          allowFileAccess = true
          allowContentAccess = true
          allowFileAccessFromFileURLs = true
          allowUniversalAccessFromFileURLs = true
          mediaPlaybackRequiresUserGesture = false
          cacheMode = WebSettings.LOAD_DEFAULT
          useWideViewPort = true
          loadWithOverviewMode = true
        }

        webViewClient = object : WebViewClient() {
          override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            onPageFinished()
          }

          override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
          ): WebResourceResponse? {
            val url = request?.url ?: return null

            if (url.host == "nanang-player.local") {
              // Preflight OPTIONS handling
              if (request.method.equals("OPTIONS", ignoreCase = true)) {
                val headers = mapOf(
                  "Access-Control-Allow-Origin" to "*",
                  "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                  "Access-Control-Allow-Headers" to "Range, Origin, Content-Type, Accept",
                  "Access-Control-Max-Age" to "86400"
                )
                return WebResourceResponse(
                  "text/plain",
                  "UTF-8",
                  204,
                  "No Content",
                  headers,
                  java.io.ByteArrayInputStream(ByteArray(0))
                )
              }

              // Serve index.html as same origin
              if (url.path == "/" || url.path == "/index.html") {
                try {
                  val inputStream = context.assets.open("index.html")
                  val headers = mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Content-Type" to "text/html; charset=UTF-8"
                  )
                  return WebResourceResponse(
                    "text/html",
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    inputStream
                  )
                } catch (e: Exception) {
                  e.printStackTrace()
                }
              }

              // Stream audio with HTTP Range support
              if (url.path == "/audio") {
                val idStr = url.getQueryParameter("id")
                if (!idStr.isNullOrEmpty()) {
                  try {
                    val id = idStr.toLong()
                    val contentUri = ContentUris.withAppendedId(
                      MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                      id
                    )
                    val mimeType = context.contentResolver.getType(contentUri) ?: "audio/mpeg"

                    val pfd = context.contentResolver.openFileDescriptor(contentUri, "r")
                    if (pfd != null) {
                      val totalLength = pfd.statSize
                      val rangeHeader = request.requestHeaders?.entries?.firstOrNull {
                        it.key.equals("Range", ignoreCase = true)
                      }?.value

                      if (!rangeHeader.isNullOrEmpty() && rangeHeader.startsWith("bytes=", ignoreCase = true) && totalLength > 0) {
                        val rangeSpec = rangeHeader.substringAfter("=").trim()
                        val parts = rangeSpec.split("-")
                        val start = parts[0].toLongOrNull() ?: 0L
                        val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
                          parts[1].toLongOrNull() ?: (totalLength - 1)
                        } else {
                          totalLength - 1
                        }
                        val validEnd = end.coerceAtMost(totalLength - 1)
                        val contentLength = if (validEnd >= start) (validEnd - start + 1) else 0L

                        val fis = java.io.FileInputStream(pfd.fileDescriptor)
                        if (start > 0) {
                          fis.channel.position(start)
                        }

                        val boundedStream = object : java.io.InputStream() {
                          private var remaining = contentLength
                          override fun read(): Int {
                            if (remaining <= 0) return -1
                            val b = fis.read()
                            if (b != -1) remaining--
                            return b
                          }
                          override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (remaining <= 0) return -1
                            val toRead = Math.min(len.toLong(), remaining).toInt()
                            val read = fis.read(b, off, toRead)
                            if (read > 0) remaining -= read
                            return read
                          }
                          override fun close() {
                            fis.close()
                            pfd.close()
                          }
                        }

                        val responseHeaders = mutableMapOf(
                          "Access-Control-Allow-Origin" to "*",
                          "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                          "Access-Control-Allow-Headers" to "Range, Origin, Content-Type, Accept",
                          "Accept-Ranges" to "bytes",
                          "Content-Range" to "bytes $start-$validEnd/$totalLength",
                          "Content-Length" to contentLength.toString(),
                          "Content-Type" to mimeType
                        )

                        return WebResourceResponse(
                          mimeType,
                          null,
                          206,
                          "Partial Content",
                          responseHeaders,
                          boundedStream
                        )
                      } else {
                        val fis = java.io.FileInputStream(pfd.fileDescriptor)
                        val wrappedStream = object : java.io.FilterInputStream(fis) {
                          override fun close() {
                            super.close()
                            pfd.close()
                          }
                        }
                        val responseHeaders = mutableMapOf(
                          "Access-Control-Allow-Origin" to "*",
                          "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                          "Access-Control-Allow-Headers" to "Range, Origin, Content-Type, Accept",
                          "Accept-Ranges" to "bytes",
                          "Content-Length" to totalLength.toString(),
                          "Content-Type" to mimeType
                        )
                        return WebResourceResponse(
                          mimeType,
                          null,
                          200,
                          "OK",
                          responseHeaders,
                          wrappedStream
                        )
                      }
                    } else {
                      val inputStream = context.contentResolver.openInputStream(contentUri)
                      if (inputStream != null) {
                        val responseHeaders = mutableMapOf(
                          "Access-Control-Allow-Origin" to "*",
                          "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                          "Accept-Ranges" to "bytes"
                        )
                        return WebResourceResponse(
                          mimeType,
                          null,
                          200,
                          "OK",
                          responseHeaders,
                          inputStream
                        )
                      }
                    }
                  } catch (e: Exception) {
                    e.printStackTrace()
                  }
                }
              }
            }

            return super.shouldInterceptRequest(view, request)
          }
        }

        webChromeClient = object : WebChromeClient() {
          override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
          ): Boolean {
            if (filePathCallback != null) {
              val multiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
              onOpenFileChooser(filePathCallback, multiple)
              return true
            }
            return false
          }
        }

        onWebViewCreated(this)
        loadUrl("https://nanang-player.local/index.html")
        webViewInstance = this
      }
    },
    update = { webView ->
      webViewInstance = webView
    }
  )
}

