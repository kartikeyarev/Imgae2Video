package com.example.stillvideo

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Saved(val uri: Uri, val path: String, val bytes: Long)

data class UiState(
    val frame: Bitmap? = null,
    val name: String = "output",
    val minutes: Int = 60,
    val encoding: Boolean = false,
    val progress: Float = 0f,
    val saved: Saved? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val cr get() = getApplication<Application>().contentResolver
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun setName(v: String) = _state.update { it.copy(name = v) }
    fun setMinutes(v: Int) = _state.update { it.copy(minutes = v) }

    fun pick(uri: Uri) {
        viewModelScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { prepare(decodeScaled(cr, uri)) }
                _state.update { it.copy(frame = bmp, saved = null, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Couldn't open that image. Try a JPG, PNG or WebP.") }
            }
        }
    }

    fun cancel() { job?.cancel() }

    fun start() {
        val s = _state.value
        val frame = s.frame ?: return
        if (s.encoding) return

        job = viewModelScope.launch {
            _state.update { it.copy(encoding = true, progress = 0f, saved = null, error = null) }
            var target: Uri? = null
            try {
                val saved = withContext(Dispatchers.Default) {
                    val ctx = currentCoroutineContext()
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, cleanName(s.name) + ".mp4")
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Stillreel")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("Couldn't create the output file")
                    target = uri

                    cr.openFileDescriptor(uri, "rw")!!.use { pfd ->
                        encodeStill(
                            frame = frame,
                            seconds = s.minutes * 60L,
                            out = pfd.fileDescriptor,
                            onProgress = { p -> _state.update { it.copy(progress = p) } },
                            checkCancelled = { ctx.ensureActive() },
                        )
                    }
                    cr.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)

                    val name = cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "video.mp4"
                    val bytes = cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    Saved(uri, "Movies/Stillreel/$name", bytes)
                }
                _state.update { it.copy(encoding = false, progress = 1f, saved = saved) }
            } catch (e: CancellationException) {
                discard(target)
                _state.update { it.copy(encoding = false) }
                throw e
            } catch (e: Exception) {
                discard(target)
                _state.update {
                    it.copy(
                        encoding = false,
                        error = "Couldn't create the video: ${e.message ?: e.javaClass.simpleName}. Try a smaller image.",
                    )
                }
            }
        }
    }

    private fun discard(uri: Uri?) {
        if (uri != null) runCatching { cr.delete(uri, null, null) }
    }

    private fun cleanName(raw: String): String =
        raw.trim()
            .replace(Regex("(?i)\\.mp4$"), "")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { "output" }
}
