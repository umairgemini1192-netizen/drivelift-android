package com.drivelift.app.feature.intelligence.ocr

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.drivelift.app.feature.intelligence.data.ExtractionStatus
import com.drivelift.app.feature.intelligence.data.IntelligencePrefs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/** A phone file the index could read: a picture or a PDF. */
data class DocumentCandidate(
    val uri: String,
    val displayName: String,
    val folderPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

/** Text read from one document. */
data class ExtractionResult(
    val status: ExtractionStatus,
    val text: String = "",
    val pageCount: Int = 0,
)

/** Lists every document the index should cover. */
interface DocumentSource {
    suspend fun candidates(): List<DocumentCandidate>
}

/** Reads the text of one document. */
interface DocumentTextExtractor {
    suspend fun extract(candidate: DocumentCandidate): ExtractionResult
}

/**
 * Phone pictures from MediaStore (DriveLift already has photo access) plus pictures and PDFs in
 * the folders the user picked with Android's folder picker. Read-only.
 */
class AndroidDocumentSource(
    private val context: Context,
    private val prefs: IntelligencePrefs,
) : DocumentSource {

    override suspend fun candidates(): List<DocumentCandidate> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, DocumentCandidate>()
        runCatching { mediaStoreImages() }.getOrDefault(emptyList()).forEach { out[it.uri] = it }
        for (tree in prefs.documentFolders.value) {
            runCatching { walkTree(Uri.parse(tree)) }.getOrDefault(emptyList()).forEach { out[it.uri] = it }
        }
        out.values.toList()
    }

    private fun mediaStoreImages(): List<DocumentCandidate> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_MODIFIED,
        )
        val base = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val out = ArrayList<DocumentCandidate>()
        context.contentResolver.query(base, projection, null, null, "${MediaStore.Images.Media.DATE_MODIFIED} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iPath = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val iMod = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                out += DocumentCandidate(
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString(),
                    displayName = c.getString(iName) ?: "image_$id",
                    folderPath = c.getString(iPath).orEmpty(),
                    mimeType = c.getString(iMime) ?: "image/*",
                    sizeBytes = c.getLong(iSize),
                    modifiedAt = c.getLong(iMod) * 1000,
                )
            }
        }
        return out
    }

    private fun walkTree(treeUri: Uri): List<DocumentCandidate> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val out = ArrayList<DocumentCandidate>()
        fun visit(dir: DocumentFile, path: String, depth: Int) {
            if (depth > MAX_DEPTH || out.size >= MAX_TREE_FILES) return
            for (child in dir.listFiles()) {
                if (out.size >= MAX_TREE_FILES) return
                val name = child.name ?: continue
                if (child.isDirectory) {
                    if (!name.startsWith(".")) visit(child, "$path$name/", depth + 1)
                } else {
                    val mime = child.type ?: continue
                    if (mime.startsWith("image/") || mime == PDF_MIME) {
                        out += DocumentCandidate(
                            uri = child.uri.toString(), displayName = name, folderPath = path,
                            mimeType = mime, sizeBytes = child.length(), modifiedAt = child.lastModified(),
                        )
                    }
                }
            }
        }
        visit(root, (root.name ?: "Folder") + "/", 0)
        return out
    }

    private companion object {
        const val MAX_DEPTH = 6
        const val MAX_TREE_FILES = 5_000
    }
}

const val PDF_MIME = "application/pdf"

/**
 * Pictures go through [MultiScriptOcrRouter]. PDFs keep their digital Unicode text layer (exact
 * for digital English, Arabic and Urdu PDFs); a page without one is rendered at a bounded size
 * and read by OCR, for at most [MAX_OCR_PAGES] pages per PDF. Every bitmap is recycled.
 */
class AndroidDocumentTextExtractor(
    private val context: Context,
    private val ocr: MultiScriptOcrRouter,
) : DocumentTextExtractor {

    @Volatile private var pdfBoxReady = false

    override suspend fun extract(candidate: DocumentCandidate): ExtractionResult = withContext(Dispatchers.IO) {
        val uri = Uri.parse(candidate.uri)
        when {
            candidate.mimeType == PDF_MIME || candidate.displayName.endsWith(".pdf", ignoreCase = true) ->
                if (candidate.sizeBytes > MAX_PDF_BYTES) ExtractionResult(ExtractionStatus.SKIPPED) else extractPdf(uri)
            candidate.mimeType.startsWith("image/") ->
                if (candidate.sizeBytes > MAX_IMAGE_BYTES) ExtractionResult(ExtractionStatus.SKIPPED) else extractImage(uri)
            else -> ExtractionResult(ExtractionStatus.SKIPPED)
        }
    }

    private suspend fun extractImage(uri: Uri): ExtractionResult {
        val bitmap = decodeBounded(uri) ?: return ExtractionResult(ExtractionStatus.FAILED)
        return try {
            ExtractionResult(ExtractionStatus.INDEXED, ocr.recognize(bitmap), pageCount = 1)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeBounded(uri: Uri): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val longest = max(w, h)
            if (longest > MAX_BITMAP_DIM) {
                val scale = MAX_BITMAP_DIM.toFloat() / longest
                decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
            }
        }
        if (decoded.config == Bitmap.Config.ARGB_8888) decoded
        else decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
    }.getOrNull()

    private suspend fun extractPdf(uri: Uri): ExtractionResult {
        if (!pdfBoxReady) {
            PDFBoxResourceLoader.init(context.applicationContext)
            pdfBoxReady = true
        }
        val pageTexts = ArrayList<String>()
        var pageCount = 0
        val layerRead = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input).use { doc ->
                    pageCount = doc.numberOfPages
                    val stripper = PDFTextStripper()
                    for (page in 1..minOf(pageCount, MAX_TEXT_PAGES)) {
                        coroutineContext.ensureActive()
                        stripper.startPage = page
                        stripper.endPage = page
                        pageTexts += runCatching { stripper.getText(doc) }.getOrDefault("")
                    }
                }
                true
            } ?: false
        } catch (e: InvalidPasswordException) {
            return ExtractionResult(ExtractionStatus.SKIPPED)
        } catch (e: OutOfMemoryError) {
            return ExtractionResult(ExtractionStatus.SKIPPED)
        } catch (e: Exception) {
            false
        }

        // Pages with no usable text layer are scanned pages: render and OCR the first few.
        val scanned = if (layerRead) {
            pageTexts.indices.filter { pageTexts[it].trim().length < MIN_LAYER_CHARS }
        } else {
            emptyList()
        }
        val ocrPages = HashMap<Int, String>()
        val needRenderAll = !layerRead
        if (needRenderAll || scanned.isNotEmpty()) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        if (needRenderAll) pageCount = renderer.pageCount
                        val targets = (if (needRenderAll) (0 until renderer.pageCount).toList() else scanned).take(MAX_OCR_PAGES)
                        for (index in targets) {
                            coroutineContext.ensureActive()
                            renderer.openPage(index).use { page ->
                                val longest = max(page.width, page.height).coerceAtLeast(1)
                                val scale = (MAX_BITMAP_DIM.toFloat() / longest).coerceAtMost(PDF_MAX_SCALE)
                                val bitmap = Bitmap.createBitmap(
                                    max(1, (page.width * scale).roundToInt()),
                                    max(1, (page.height * scale).roundToInt()),
                                    Bitmap.Config.ARGB_8888,
                                )
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    ocrPages[index] = ocr.recognize(bitmap)
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!layerRead && ocrPages.isEmpty()) return ExtractionResult(ExtractionStatus.FAILED)
        val count = maxOf(pageCount, pageTexts.size)
        val text = (0 until count).mapNotNull { i ->
            ocrPages[i]?.takeIf { it.isNotBlank() } ?: pageTexts.getOrNull(i)?.takeIf { it.isNotBlank() }
        }.joinToString("\n")
        return ExtractionResult(ExtractionStatus.INDEXED, text, count)
    }

    companion object {
        const val MAX_OCR_PAGES = 15
        const val MAX_TEXT_PAGES = 400
        const val MAX_BITMAP_DIM = 2000
        const val PDF_MAX_SCALE = 3f
        const val MIN_LAYER_CHARS = 16
        const val MAX_PDF_BYTES = 80L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 40L * 1024 * 1024
    }
}
