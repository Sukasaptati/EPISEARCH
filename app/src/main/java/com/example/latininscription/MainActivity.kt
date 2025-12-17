package com.example.latininscription

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

class MainActivity : AppCompatActivity() {

    private val parser by lazy { InscriptionParser(this) }
    private lateinit var adapter: InscriptionsAdapter
    private var lastResults: List<Inscription> = emptyList()

    private lateinit var etInclude: EditText
    private lateinit var etExclude: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var layoutOcrResult: LinearLayout
    private lateinit var ivPreview: ImageView
    private lateinit var tvOcrText: TextView
    private lateinit var tvTranslation: TextView 

    private lateinit var tessApi: TessBaseAPI
    private var isTesseractReady = false
    private var useOnlineOcr = false
    private var useOnlineTranslation = false 
    private var currentBitmap: Bitmap? = null
    private lateinit var prefs: SharedPreferences

    // --- NEW: Variable to track which DB we are importing ---
    private var pendingImportFilename: String = "" 

    // --- NEW: File Picker for Database Import ---
    private val importDbLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            importDatabaseFile(it)
        }
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                val bitmap = getBitmapFromUri(it)
                if (bitmap != null) processAndRunOcr(bitmap)
            } catch (e: Exception) {
                Toast.makeText(this, "Error loading image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val bitmap = result.data?.extras?.get("data") as? Bitmap
            if (bitmap != null) processAndRunOcr(bitmap)
        }
    }

    private val saveFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let {
            CoroutineScope(Dispatchers.IO).launch {
                contentResolver.openOutputStream(it)?.use { outputStream ->
                    val sb = StringBuilder()
                    for (item in lastResults) {
                        sb.append("${item.id} | ${item.location}\n${item.text}\nRef: ${item.ref1}\n\n")
                    }
                    outputStream.write(sb.toString().toByteArray())
                }
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Saved!", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("EDCS_PREFS", Context.MODE_PRIVATE)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.menu_main)
        
        useOnlineOcr = prefs.getBoolean("USE_ONLINE_OCR", false)
        useOnlineTranslation = prefs.getBoolean("USE_ONLINE_TRANS", false)
        
        toolbar.menu.findItem(R.id.action_ocr_mode).isChecked = useOnlineOcr
        toolbar.menu.findItem(R.id.action_online_translation).isChecked = useOnlineTranslation

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                // --- NEW: Handle Import Click ---
                R.id.action_import_db -> {
                    showImportSelectionDialog()
                    true
                }
                R.id.action_ocr_mode -> {
                    useOnlineOcr = !useOnlineOcr
                    item.isChecked = useOnlineOcr
                    prefs.edit().putBoolean("USE_ONLINE_OCR", useOnlineOcr).apply()
                    Toast.makeText(this, "OCR: ${if (useOnlineOcr) "Online" else "Offline"}", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.action_online_translation -> {
                    useOnlineTranslation = !useOnlineTranslation
                    item.isChecked = useOnlineTranslation
                    prefs.edit().putBoolean("USE_ONLINE_TRANS", useOnlineTranslation).apply()
                    Toast.makeText(this, "Translation: ${if (useOnlineTranslation) "Online" else "App (Offline)"}", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.action_set_api_key -> {
                    showApiKeyDialog()
                    true
                }
                else -> false
            }
        }

        etInclude = findViewById(R.id.etInclude)
        etExclude = findViewById(R.id.etExclude)
        val btnSearch = findViewById<Button>(R.id.btnSearch)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnOcr = findViewById<Button>(R.id.btnOcr)
        val rvResults = findViewById<RecyclerView>(R.id.rvResults)
        tvStatus = findViewById(R.id.tvStatus)
        val spinner = findViewById<Spinner>(R.id.spinnerDatabase)
        progressBar = findViewById(R.id.progressBar)
        layoutOcrResult = findViewById(R.id.layoutOcrResult)
        ivPreview = findViewById(R.id.ivPreview)
        tvOcrText = findViewById(R.id.tvOcrText)
        tvTranslation = findViewById(R.id.tvTranslation)

        findViewById<ImageButton>(R.id.btnCloseOcr).setOnClickListener {
            layoutOcrResult.visibility = View.GONE
            tvTranslation.visibility = View.GONE
            ivPreview.setImageDrawable(null)
            tvOcrText.text = ""
            currentBitmap = null
        }

        findViewById<ImageButton>(R.id.btnSaveImage).setOnClickListener {
            currentBitmap?.let { bmp -> saveImageToGallery(bmp) } 
                ?: Toast.makeText(this, "No image to save", Toast.LENGTH_SHORT).show()
        }

        findViewById<ImageButton>(R.id.btnTranslate).setOnClickListener {
            val text = tvOcrText.text.toString()
            if (text.isNotBlank() && text != "Scanned text...") {
                translateText(text)
            } else {
                Toast.makeText(this, "No text to translate", Toast.LENGTH_SHORT).show()
            }
        }

        initializeTesseract()

        val dbOptions = InscriptionParser.DatabaseType.values()
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, dbOptions.map { it.displayName })
        spinner.adapter = spinnerAdapter
        rvResults.layoutManager = LinearLayoutManager(this)
        
        adapter = InscriptionsAdapter { fullText ->
            showTextSelectionDialog(fullText)
        }
        rvResults.adapter = adapter

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedDb = dbOptions[position]
                // Only load if file actually exists
                val file = File(getExternalFilesDir(null), selectedDb.filename)
                if (file.exists()) {
                    progressBar.visibility = View.VISIBLE
                    tvStatus.text = "Loading..."
                    btnSearch.isEnabled = false

                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            parser.loadDatabase(selectedDb)
                            progressBar.visibility = View.GONE
                            tvStatus.text = "Ready: ${selectedDb.displayName}"
                            btnSearch.isEnabled = true
                        } catch (e: Exception) {
                            progressBar.visibility = View.GONE
                            tvStatus.text = "Error Loading DB"
                        }
                    }
                } else {
                    tvStatus.text = "DB Missing. Import from Menu."
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnSearch.setOnClickListener {
            val includeText = etInclude.text.toString()
            val excludeText = etExclude.text.toString()
            val includeRaw = includeText.split("&&").map { it.trim() }.filter { it.isNotBlank() }
            val excludeRaw = excludeText.split("&&").map { it.trim() }.filter { it.isNotBlank() }

            if (includeRaw.isEmpty()) return@setOnClickListener

            progressBar.visibility = View.VISIBLE
            val selectedDb = dbOptions[spinner.selectedItemPosition]

            CoroutineScope(Dispatchers.Main).launch {
                val results = parser.search(selectedDb, includeRaw, excludeRaw)
                lastResults = results
                adapter.submitList(results, includeRaw)
                progressBar.visibility = View.GONE
                tvStatus.text = "Found ${results.size} matches"
                btnSave.isEnabled = results.isNotEmpty()
            }
        }

        btnOcr.setOnClickListener {
            val options = arrayOf("Take Photo", "Choose from Gallery")
            AlertDialog.Builder(this)
                .setTitle("Scan Text")
                .setItems(options) { _, which ->
                    if (which == 0) cameraLauncher.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
                    else galleryLauncher.launch("image/*")
                }
                .show()
        }
        btnSave.setOnClickListener { saveFileLauncher.launch("Output.txt") }
    }

    // --- NEW: IMPORT LOGIC ---
    private fun showImportSelectionDialog() {
        val options = arrayOf("Latin Inscriptions", "Greek Inscriptions", "Greek Papyri")
        val filenames = arrayOf(
            "Latin-inscriptions-CIL-AE-JRA.txt",
            "Greek inscriptions.txt",
            "greek papyrus.txt"
        )

        AlertDialog.Builder(this)
            .setTitle("Which database are you importing?")
            .setItems(options) { _, which ->
                // Save the target filename so we rename the import correctly
                pendingImportFilename = filenames[which]
                Toast.makeText(this, "Select the file from your Downloads", Toast.LENGTH_LONG).show()
                // Open file picker
                importDbLauncher.launch("*/*")
            }
            .show()
    }

    private fun importDatabaseFile(sourceUri: Uri) {
        if (pendingImportFilename.isEmpty()) return
        
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "Importing..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Determine target location in internal storage
                val destFile = File(getExternalFilesDir(null), pendingImportFilename)
                
                // Stream Copy
                contentResolver.openInputStream(sourceUri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    tvStatus.text = "Import Successful!"
                    Toast.makeText(this@MainActivity, "Database Imported!", Toast.LENGTH_SHORT).show()
                    // Reload current DB just in case
                    val spinner = findViewById<Spinner>(R.id.spinnerDatabase)
                    val dbOptions = InscriptionParser.DatabaseType.values()
                    val selectedDb = dbOptions[spinner.selectedItemPosition]
                    if (selectedDb.filename == pendingImportFilename) {
                        // Trigger reload
                         parser.loadDatabase(selectedDb)
                         tvStatus.text = "Ready: ${selectedDb.displayName}"
                         findViewById<Button>(R.id.btnSearch).isEnabled = true
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    tvStatus.text = "Import Failed"
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // --- TRANSLATION LOGIC ---
    private fun translateText(text: String) {
        var cleaned = text.replace(Regex("<(\\p{L}+)=\\p{L}+>"), "$1")
        cleaned = cleaned.replace(Regex("[\\(\\)\\[\\]\\{\\}<>]"), "")
        cleaned = cleaned.replace("?", "")

        if (cleaned.isBlank()) {
             Toast.makeText(this, "No text to translate", Toast.LENGTH_SHORT).show()
             return
        }

        val spinner = findViewById<Spinner>(R.id.spinnerDatabase)
        val isGreek = spinner.selectedItem.toString().contains("Greek")
        val sourceLang = if (isGreek) "el" else "la"

        if (useOnlineTranslation) {
            runOnlineTranslation(cleaned, sourceLang)
        } else {
            runOfflineTranslationIntent(cleaned, sourceLang)
        }
    }

    private fun runOfflineTranslationIntent(text: String, lang: String) {
        try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse("http://translate.google.com/m/translate?client=android-translate&q=$encodedText&sl=$lang&tl=en")
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Google Translate App not found.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runOnlineTranslation(text: String, sourceLang: String) {
        val apiKey = prefs.getString("GOOGLE_API_KEY", "") ?: ""
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "API Key missing! Set it in Menu.", Toast.LENGTH_LONG).show()
            return
        }

        val loadingDialog = AlertDialog.Builder(this).setMessage("Translating...").show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val jsonRequest = JSONObject()
                jsonRequest.put("q", text)
                jsonRequest.put("source", sourceLang)
                jsonRequest.put("target", "en")
                jsonRequest.put("format", "text")

                val url = URL("https://translation.googleapis.com/language/translate/v2?key=$apiKey")
                val conn = url.openConnection() as HttpsURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val writer = OutputStreamWriter(conn.outputStream)
                writer.write(jsonRequest.toString())
                writer.flush()
                writer.close()

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    reader.close()
                    val jsonResponse = JSONObject(response)
                    val translatedText = jsonResponse.getJSONObject("data")
                        .getJSONArray("translations")
                        .getJSONObject(0)
                        .getString("translatedText")

                    withContext(Dispatchers.Main) { 
                        loadingDialog.dismiss()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Translation")
                            .setMessage(translatedText)
                            .setPositiveButton("Copy") { _, _ ->
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Translation", translatedText)
                                clipboard.setPrimaryClip(clip)
                            }
                            .setNegativeButton("Close", null)
                            .show()
                    }
                } else {
                    withContext(Dispatchers.Main) { 
                        loadingDialog.dismiss()
                        Toast.makeText(this@MainActivity, "Error: ${conn.responseCode}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    loadingDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showTextSelectionDialog(text: String) {
        val textView = TextView(this)
        textView.text = text
        textView.textSize = 20f 
        textView.setPadding(50, 50, 50, 50)
        textView.setTextColor(Color.BLACK)
        textView.setTextIsSelectable(true) 

        textView.customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                menu.add(0, 100, 0, "EDCS Translate")
                return true
            }
            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                if (item.itemId == 100) {
                    var min = 0
                    var max = textView.text.length
                    if (textView.isFocused) {
                        val selStart = textView.selectionStart
                        val selEnd = textView.selectionEnd
                        min = maxOf(0, minOf(selStart, selEnd))
                        max = maxOf(0, maxOf(selStart, selEnd))
                    }
                    val selectedText = textView.text.subSequence(min, max).toString()
                    if (selectedText.isNotBlank()) {
                        translateText(selectedText)
                    }
                    mode.finish()
                    return true
                }
                return false
            }
            override fun onDestroyActionMode(mode: android.view.ActionMode) {}
        }

        AlertDialog.Builder(this)
            .setTitle("Select Text")
            .setView(textView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showApiKeyDialog() {
        val input = EditText(this)
        input.hint = "Paste Google API Key here"
        input.setText(prefs.getString("GOOGLE_API_KEY", ""))
        AlertDialog.Builder(this)
            .setTitle("Set API Key")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("GOOGLE_API_KEY", input.text.toString().trim()).apply()
                Toast.makeText(this, "Key Saved!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.isMutableRequired = true }
            } else {
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Exception) { null }
    }

    private fun processAndRunOcr(originalBitmap: Bitmap) {
        val scaledBitmap = scaleBitmapDown(originalBitmap, 1024)
        val safeBitmap = scaledBitmap.copy(Bitmap.Config.ARGB_8888, true)
        currentBitmap = safeBitmap

        layoutOcrResult.visibility = View.VISIBLE
        tvTranslation.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        
        if (useOnlineOcr) {
            ivPreview.setImageBitmap(safeBitmap) 
            tvStatus.text = "Sending to Google Cloud..."
            tvOcrText.text = "Uploading..."
            runCloudVision(safeBitmap)
        } else {
            tvStatus.text = "Optimizing Image..."
            CoroutineScope(Dispatchers.Default).launch {
                val binarizedBitmap = applyAdaptiveThreshold(safeBitmap)
                currentBitmap = binarizedBitmap
                withContext(Dispatchers.Main) {
                    ivPreview.setImageBitmap(binarizedBitmap)
                    runTesseract(binarizedBitmap)
                }
            }
        }
    }

    private fun runCloudVision(bitmap: Bitmap) {
        val apiKey = prefs.getString("GOOGLE_API_KEY", "") ?: ""
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Please set API Key in Menu!", Toast.LENGTH_LONG).show()
            tvOcrText.text = "Error: Missing API Key."
            progressBar.visibility = View.GONE
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

                val jsonRequest = """{ "requests": [ { "image": { "content": "$base64Image" }, "features": [ { "type": "DOCUMENT_TEXT_DETECTION" } ] } ] }"""

                val url = URL("https://vision.googleapis.com/v1/images:annotate?key=$apiKey")
                val conn = url.openConnection() as HttpsURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val writer = OutputStreamWriter(conn.outputStream)
                writer.write(jsonRequest)
                writer.flush()
                writer.close()

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    reader.close()
                    val jsonResponse = JSONObject(response)
                    val responsesArray = jsonResponse.getJSONArray("responses")
                    if (responsesArray.length() > 0) {
                        val first = responsesArray.getJSONObject(0)
                        if (first.has("fullTextAnnotation")) {
                            val text = first.getJSONObject("fullTextAnnotation").getString("text")
                            withContext(Dispatchers.Main) {
                                tvOcrText.text = text
                                tvStatus.text = "Google OCR Complete"
                                progressBar.visibility = View.GONE
                            }
                        } else {
                             withContext(Dispatchers.Main) {
                                tvOcrText.text = "No text found."
                                progressBar.visibility = View.GONE
                            }
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        tvOcrText.text = "Error: ${conn.responseCode}"
                        progressBar.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvOcrText.text = "Error: ${e.message}"
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun saveImageToGallery(bitmap: Bitmap) {
        val filename = "OCR_${System.currentTimeMillis()}.jpg"
        var fos: OutputStream? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }
                val imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                fos = imageUri?.let { contentResolver.openOutputStream(it) }
            } else {
                val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val image = File(imagesDir, filename)
                fos = FileOutputStream(image)
            }
            fos?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                Toast.makeText(this, "Image Saved", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) { }
    }

    private fun scaleBitmapDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        var resizedWidth = maxDimension
        var resizedHeight = maxDimension

        if (originalWidth > maxDimension || originalHeight > maxDimension) {
            if (originalWidth > originalHeight) {
                resizedHeight = (originalHeight.toFloat() / originalWidth.toFloat() * resizedWidth).toInt()
            } else {
                resizedWidth = (originalWidth.toFloat() / originalHeight.toFloat() * resizedHeight).toInt()
            }
            return Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, false)
        }
        return bitmap
    }

    private fun applyAdaptiveThreshold(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        val destPixels = IntArray(width * height)
        val windowSize = 20
        val contrastBias = 10 
        val grayPixels = IntArray(width * height)
        
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            grayPixels[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val pixelVal = grayPixels[index]
                var sum = 0
                var count = 0
                for (dy in -windowSize..windowSize step 3) {
                    for (dx in -windowSize..windowSize step 3) {
                        val ny = y + dy
                        val nx = x + dx
                        if (nx in 0 until width && ny in 0 until height) {
                            sum += grayPixels[ny * width + nx]
                            count++
                        }
                    }
                }
                val localMean = sum / count
                destPixels[index] = if (pixelVal < (localMean - contrastBias)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(destPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun initializeTesseract() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                tessApi = TessBaseAPI()
                val dataPath = getExternalFilesDir(null)?.absolutePath + "/tessdata/"
                val dir = File(dataPath)
                if (!dir.exists()) dir.mkdirs()

                copyAssetFile("tessdata/grc.traineddata", dataPath + "grc.traineddata")
                copyAssetFile("tessdata/lat.traineddata", dataPath + "lat.traineddata")

                val success = tessApi.init(getExternalFilesDir(null)?.absolutePath, "grc+lat")
                withContext(Dispatchers.Main) {
                    if (success) isTesseractReady = true
                    else tvStatus.text = "OCR Init Failed"
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun copyAssetFile(assetName: String, destPath: String) {
        val file = File(destPath)
        if (!file.exists()) {
            try {
                assets.open(assetName).use { inputStream ->
                    FileOutputStream(destPath).use { it.write(inputStream.readBytes()) }
                }
            } catch (e: IOException) { e.printStackTrace() }
        }
    }

    private fun runTesseract(bitmap: Bitmap) {
        if (!isTesseractReady) return
        tvOcrText.text = "Reading text..."
        CoroutineScope(Dispatchers.IO).launch {
            try {
                tessApi.setImage(bitmap)
                val result = tessApi.utF8Text
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    tvStatus.text = "Scan Complete"
                    tvOcrText.text = if (result.isBlank()) "No text found." else result
                }
            } catch (e: Exception) { 
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    tvOcrText.text = "Error: ${e.message}"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::tessApi.isInitialized) {
            tessApi.stop()
            tessApi.recycle()
        }
    }
}