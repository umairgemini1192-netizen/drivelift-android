package com.drivelift.app.feature.intelligence.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.drivelift.app.feature.intelligence.data.OcrLanguageMode
import com.drivelift.app.feature.intelligence.nlsearch.ArabicUrduTextNormalizer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/** Reads Latin-script text (English) from a picture, on the phone. */
interface LatinOcrEngine {
    suspend fun recognize(bitmap: Bitmap): String
}

/** Reads Arabic and Urdu text from a picture, on the phone. */
interface ArabicUrduOcrEngine {
    fun isAvailable(): Boolean
    suspend fun recognize(bitmap: Bitmap): String
}

/** Google ML Kit's bundled Latin text recognizer. The model ships inside the app: no download. */
class MlKitLatinOcrEngine : LatinOcrEngine {
    private val lock = Mutex()
    private var recognizer: TextRecognizer? = null

    override suspend fun recognize(bitmap: Bitmap): String = lock.withLock {
        val client = recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
        client.process(InputImage.fromBitmap(bitmap, 0)).await().text
    }

    fun close() {
        recognizer?.close()
        recognizer = null
    }
}

/**
 * Tesseract (Tesseract4Android, standard non-OpenMP build) with the `tessdata_fast` Arabic and
 * Urdu models. A fresh engine is created and recycled for every picture so native memory is
 * always released, and calls are serialized.
 */
class TesseractArabicUrduOcrEngine(private val tessdata: TessdataManager) : ArabicUrduOcrEngine {
    private val lock = Mutex()

    override fun isAvailable(): Boolean = tessdata.isInstalled()

    override suspend fun recognize(bitmap: Bitmap): String = lock.withLock {
        withContext(Dispatchers.Default) {
            if (!tessdata.ensureInstalled()) return@withContext ""
            val api = TessBaseAPI()
            try {
                if (!api.init(tessdata.dataPath, TessdataManager.LANGUAGES)) return@withContext ""
                api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                api.setImage(bitmap)
                api.getUTF8Text().orEmpty()
            } finally {
                api.recycle()
            }
        }
    }
}

/**
 * Runs the right engines for the chosen [OcrLanguageMode] and merges their text. ML Kit always
 * runs for English. Tesseract runs for Arabic and Urdu when its models are installed and either
 * every picture is read with both engines, or (smart mode) ML Kit found little Latin text or the
 * picture already shows Arabic-script characters.
 */
class MultiScriptOcrRouter(
    private val latin: LatinOcrEngine,
    private val arabicUrdu: ArabicUrduOcrEngine,
    private val mode: () -> OcrLanguageMode,
) {
    suspend fun recognize(bitmap: Bitmap): String {
        val latinText = runCatching { latin.recognize(bitmap) }.getOrDefault("")
        val current = mode()
        if (!shouldRunArabicUrdu(current, latinText, arabicUrdu.isAvailable())) return latinText.trim()
        val arabicText = runCatching { arabicUrdu.recognize(bitmap) }.getOrDefault("")
        return mergeOcrText(latinText, arabicText)
    }

    companion object {
        /** Fewer Latin letters than this counts as "little English found". */
        const val SPARSE_LATIN_LETTERS = 40

        fun shouldRunArabicUrdu(mode: OcrLanguageMode, latinText: String, available: Boolean): Boolean {
            if (!mode.arabicUrduEnabled || !available) return false
            if (mode == OcrLanguageMode.ALL_SCRIPTS) return true
            val latinLetters = latinText.count { it in 'a'..'z' || it in 'A'..'Z' }
            return latinLetters < SPARSE_LATIN_LETTERS || ArabicUrduTextNormalizer.containsArabicScript(latinText)
        }

        /**
         * ML Kit's lines first, then the Tesseract lines that are mostly Arabic script (Tesseract's
         * Arabic models turn Latin text into noise, and ML Kit already read the Latin text).
         */
        fun mergeOcrText(latinText: String, arabicText: String): String {
            val latinLines = latinText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val arabicLines = arabicText.lines().map { it.trim() }.filter { line ->
                val letters = line.count { it.isLetter() }
                val arabic = line.count { ArabicUrduTextNormalizer.isArabicScript(it) && it.isLetter() }
                letters > 0 && arabic * 2 >= letters
            }
            val seen = HashSet<String>()
            return (latinLines + arabicLines).filter { seen.add(it) }.joinToString("\n")
        }
    }
}

/** Arabic and Urdu `tessdata_fast` models kept in `filesDir/tessdata/`. */
class TessdataManager(private val context: Context) {
    private val dir = File(context.filesDir, "tessdata")
    private val _installed = MutableStateFlow(isInstalled())
    /** Whether both Arabic and Urdu models are ready for offline OCR. */
    val installed: StateFlow<Boolean> = _installed.asStateFlow()

    /** Parent folder of `tessdata/`, as Tesseract expects. */
    val dataPath: String get() = context.filesDir.absolutePath

    fun isInstalled(): Boolean = REQUIRED.all { File(dir, "$it.traineddata").let { f -> f.isFile && f.length() > 0 } }

    /** True when the models bundled in `assets/tessdata/` exist in the app. */
    fun bundled(): Boolean = REQUIRED.all { lang ->
        runCatching { context.assets.open("$ASSET_DIR/$lang.traineddata").use { true } }.getOrDefault(false)
    }

    /**
     * Copies the bundled models into `filesDir/tessdata/` the first time. Returns whether both
     * are ready. Never touches the network.
     */
    @Synchronized
    fun ensureInstalled(): Boolean {
        if (isInstalled()) return true.also { _installed.value = true }
        dir.mkdirs()
        for (lang in REQUIRED) {
            val target = File(dir, "$lang.traineddata")
            if (target.isFile && target.length() > 0) continue
            val copied = runCatching {
                val tmp = File(dir, "$lang.traineddata.part")
                context.assets.open("$ASSET_DIR/$lang.traineddata").use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                tmp.renameTo(target)
            }.getOrDefault(false)
            if (!copied) break
        }
        return isInstalled().also { _installed.value = it }
    }

    /**
     * Installs model files the user picked with Android's file picker (for builds without the
     * bundled pack). A file counts when its name starts with `ara` or `urd` and ends in
     * `.traineddata`. Returns the languages installed.
     */
    fun importFrom(uris: List<Uri>): List<String> {
        dir.mkdirs()
        val done = ArrayList<String>()
        for (uri in uris) {
            val name = displayName(uri)?.lowercase() ?: continue
            if (!name.endsWith(".traineddata")) continue
            val lang = REQUIRED.firstOrNull { name.startsWith(it) } ?: continue
            val ok = runCatching {
                val tmp = File(dir, "$lang.traineddata.part")
                context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.length() > 0 && tmp.renameTo(File(dir, "$lang.traineddata"))
            }.getOrDefault(false)
            if (ok) done += lang
        }
        _installed.value = isInstalled()
        return done
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    companion object {
        const val ASSET_DIR = "tessdata"
        val REQUIRED = listOf("ara", "urd")
        /** Urdu first: it shares the Arabic script and adds the Urdu letters. */
        const val LANGUAGES = "urd+ara"
    }
}
