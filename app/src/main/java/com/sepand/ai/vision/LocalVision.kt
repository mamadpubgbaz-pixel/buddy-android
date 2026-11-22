package com.sepand.ai.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local-only image and document processing. OCR packs are downloaded once; recognition remains on-device. */
object LocalVision {
    private val languageFiles = mapOf(
        "eng" to "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/eng.traineddata",
        "fas" to "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/fas.traineddata"
    )

    suspend fun analyzeImage(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val bitmap = decodeSampledBitmap(context, uri, 1600)
            ?: return@withContext "The image could not be decoded by the local vision pipeline."
        try { analyzeBitmapInternal(context, bitmap) } finally { bitmap.recycle() }
    }

    /** Samples at most three video frames. It does not claim to transcribe the audio track. */
    suspend fun analyzeVideo(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val positions = listOf(0L, durationMs / 2, (durationMs - 100).coerceAtLeast(0L)).distinct().take(3)
            val out = StringBuilder()
            positions.forEachIndexed { index, position ->
                val frame = retriever.getScaledFrameAtTime(
                    position * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    1280,
                    1280
                ) ?: return@forEachIndexed
                try {
                    out.append("\n[Video frame ${index + 1} at ${position / 1000.0}s]\n")
                    out.append(analyzeBitmapInternal(context, frame).take(3500)).append('\n')
                } finally { frame.recycle() }
            }
            out.toString().ifBlank { "No video frames could be extracted. The audio track was not transcribed." }
        } catch (e: Exception) {
            "Video analysis failed: ${e.message}. The audio track was not transcribed."
        } finally { runCatching { retriever.release() } }
    }

    private fun analyzeBitmapInternal(context: Context, bitmap: Bitmap): String {
        val sections = mutableListOf<String>()
        val scaled = downscale(bitmap, 1600)
        try {
            val labels = runCatching {
                val client = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
                try {
                    val image = InputImage.fromBitmap(scaled, 0)
                    Tasks.await(client.process(image)).sortedByDescending { it.confidence }
                        .take(8).joinToString(", ") { "${it.text} (${(it.confidence * 100).toInt()}%)" }
                } finally { client.close() }
            }.getOrNull().orEmpty()
            if (labels.isNotBlank()) sections += "On-device image labels: $labels"
            val text = runCatching { ocrBitmap(context, scaled) }.getOrElse { "OCR unavailable: ${it.message}" }
            if (text.isNotBlank()) sections += "Extracted text (English/Persian OCR):\n${text.take(12000)}"
            sections.ifEmpty { listOf("Local image classification and OCR found no usable labels or text. Spark-X2.6 does not receive image pixels.") }
                .joinToString("\n\n")
        } finally { if (scaled !== bitmap) scaled.recycle() }
    }

    private fun decodeSampledBitmap(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxEdge * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    suspend fun extractPdfText(context: Context, uri: Uri, maxPages: Int = 8): String = withContext(Dispatchers.IO) {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext "PDF could not be opened."
        fd.use { parcel ->
            val renderer = android.graphics.pdf.PdfRenderer(parcel)
            try {
                val pageCount = renderer.pageCount
                val out = StringBuilder()
                for (index in 0 until minOf(pageCount, maxPages)) {
                    renderer.openPage(index).use { page ->
                        val width = minOf(page.width, 1500)
                        val scale = width.toFloat() / page.width
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val text = runCatching { ocrBitmap(context, bitmap) }.getOrDefault("")
                        bitmap.recycle()
                        if (text.isNotBlank()) out.append("\n[PDF page ${index + 1}]\n").append(text).append('\n')
                    }
                }
                if (pageCount > maxPages) out.append("\n[Only first $maxPages of $pageCount pages processed]")
                out.toString().ifBlank { "No text extracted from the first ${minOf(pageCount, maxPages)} PDF pages." }
            } finally { renderer.close() }
        }
    }

    fun extractOfficeText(context: Context, uri: Uri, maxChars: Int = 24000): String = runCatching {
        val wanted = setOf("word/document.xml", "word/footnotes.xml", "word/endnotes.xml", "xl/sharedStrings.xml")
        val out = StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && out.length < maxChars) {
                    val name = entry.name
                    val include = wanted.contains(name) || (name.startsWith("ppt/slides/slide") && name.endsWith(".xml"))
                    if (!entry.isDirectory && include) {
                        val xmlBytes = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(4096)
                        var readTotal = 0
                        while (readTotal < 24000) {
                            val count = zip.read(chunk, 0, minOf(chunk.size, 24000 - readTotal))
                            if (count <= 0) break
                            xmlBytes.write(chunk, 0, count); readTotal += count
                        }
                        val xml = xmlBytes.toString(Charsets.UTF_8.name()).take(maxChars)
                        val decoded = xml.replace(Regex("<[^>]+>"), " ")
                            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                            .replace("&quot;", "\"").replace("&apos;", "'")
                            .replace(Regex("\\s+"), " ").trim()
                        if (decoded.isNotBlank()) out.append(decoded).append('\n')
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        out.toString().take(maxChars).ifBlank { "No text extracted from this Office document." }
    }.getOrElse { "Office document extraction failed: ${it.message}" }

    /** Extracts a bounded set of text entries from ZIP/EPUB archives without unpacking files to storage. */
    fun extractArchiveText(context: Context, uri: Uri, maxChars: Int = 30000): String = runCatching {
        val allowed = setOf(".md", ".txt", ".csv", ".json", ".xml", ".html", ".htm", ".yaml", ".yml", ".log", ".srt", ".vtt")
        val out = StringBuilder()
        var entries = 0
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && entries < 30 && out.length < maxChars) {
                    val name = entry.name.replace('\\', '/')
                    val safe = !name.startsWith("/") && name.split('/').none { it == ".." }
                    if (!entry.isDirectory && safe && allowed.any { name.lowercase().endsWith(it) }) {
                        val bytes = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        var total = 0
                        while (total < 12000) {
                            val count = zip.read(buffer, 0, minOf(buffer.size, 12000 - total))
                            if (count <= 0) break
                            bytes.write(buffer, 0, count); total += count
                        }
                        val text = bytes.toString(Charsets.UTF_8.name()).filter { it == '\n' || it == '\r' || it == '\t' || it >= ' ' }
                        if (text.isNotBlank()) {
                            out.append("\n[Archive entry: ").append(name.take(180)).append("]\n")
                                .append(text.take(5000)).append('\n')
                            entries++
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        out.toString().take(maxChars).ifBlank { "No supported text entries were found in this archive." }
    }.getOrElse { "Archive extraction failed: ${it.message}" }

    private fun ocrBitmap(context: Context, bitmap: Bitmap): String {
        val tessdata = File(context.filesDir, "tesseract/tessdata").apply { mkdirs() }
        ensureLanguageData(tessdata)
        val api = TessBaseAPI()
        return try {
            check(api.init(tessdata.parentFile!!.absolutePath, "eng+fas")) { "Tesseract could not load English/Persian language packs" }
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api.setImage(bitmap)
            api.getUTF8Text().orEmpty().trim()
        } finally { api.recycle() }
    }

    private fun ensureLanguageData(folder: File) {
        for ((language, address) in languageFiles) {
            val target = File(folder, "$language.traineddata")
            if (target.exists() && target.length() > 20_000) continue
            val connection = (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 45000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SepandAI/0.2 Android")
            }
            try {
                check(connection.responseCode == 200) { "Could not download $language OCR data (HTTP ${connection.responseCode})" }
                val tmp = File(folder, "$language.traineddata.part")
                connection.inputStream.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
                check(tmp.length() > 20_000) { "Downloaded $language OCR data is incomplete" }
                if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            } finally { connection.disconnect() }
        }
    }

    private fun downscale(source: Bitmap, maxEdge: Int): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= maxEdge) return source
        val factor = maxEdge.toFloat() / largest
        val matrix = Matrix().apply { postScale(factor, factor) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
