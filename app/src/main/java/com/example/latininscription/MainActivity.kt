package com.example.latininscription

import android.app.Activity
import android.content.ClipData 
import android.content.ClipboardManager 
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
import android.text.util.Linkify
import android.util.Base64
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

// GEMINI IMPORT
import com.google.ai.client.generativeai.GenerativeModel
import com.googlecode.tesseract.android.TessBaseAPI

// RETROFIT IMPORTS
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
// OKHTTP IMPORTS (For Timeout Fix)
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import java.util.concurrent.TimeUnit

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
    
    // Using EditText to match your layout
    private lateinit var etOcrText: EditText 
    
    private lateinit var tvTranslation: TextView 

    // BUTTONS
    private lateinit var btnSpecialistScan: ImageButton
    private lateinit var btnRestore: ImageButton
    private lateinit var btnSaveImage: ImageButton
    private lateinit var btnTranslate: ImageButton
    private lateinit var btnCloseOcr: ImageButton

    private lateinit var tessApi: TessBaseAPI
    private var isTesseractReady = false
    private var useOnlineOcr = false
    private var translationEngine = 0 
    
    private var currentBitmap: Bitmap? = null
    private lateinit var prefs: SharedPreferences
    private var pendingImportFilename: String = "" 

    // --- UPDATED API CLIENT (TIMEOUT FIX) ---
    private val api: ApiService by lazy {
        val url = prefs.getString("AENEAS_URL", "https://placeholder.com") ?: "https://placeholder.com"
        val validUrl = if (url.startsWith("http")) url else "https://placeholder.com"
        
        // We create a custom client that waits 90 seconds instead of the default 10
        val client = OkHttpClient.Builder()
            .connectTimeout(90, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()
        
        Retrofit.Builder()
            .baseUrl(validUrl)
            .client(client) // Apply the custom client
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
    // ---------------------------------------

    private val importDbLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val selectedType = InscriptionParser.DatabaseType.values().find { type -> 
                type.filename == pendingImportFilename 
            }
            if (selectedType != null) {
                prefs.edit().putString("URI_${selectedType.name}", it.toString()).apply()
                val currentMap = parser.databaseUris.toMutableMap()
                currentMap[selectedType] = it
                parser.databaseUris = currentMap
                Toast.makeText(this, "Linked ${selectedType.displayName}!", Toast.LENGTH_SHORT).show()
                
                progressBar.visibility = View.VISIBLE
                tvStatus.text = "Loading..."
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        parser.loadDatabase(selectedType)
                        progressBar.visibility = View.GONE
                        tvStatus.text = "Ready: ${selectedType.displayName}"
                        findViewById<Button>(R.id.btnSearch).isEnabled = true
                    } catch (e: Exception) {
                        progressBar.visibility = View.GONE
                        tvStatus.text = "Error"
                        Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
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

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.statusBarColor = Color.parseColor("#6200EE")

        title = ""

        prefs = getSharedPreferences("EDCS_PREFS", Context.MODE_PRIVATE)
        useOnlineOcr = prefs.getBoolean("USE_ONLINE_OCR", false)
        translationEngine = prefs.getInt("TRANS_ENGINE", 0)

        val savedUris = mutableMapOf<InscriptionParser.DatabaseType, Uri>()
        InscriptionParser.DatabaseType.values().forEach { type ->
            val uriString = prefs.getString("URI_${type.name}", null)
            if (uriString != null) {
                val uri = Uri.parse(uriString)
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    savedUris[type] = uri
                } catch (e: Exception) { }
            }
        }
        parser.databaseUris = savedUris

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar) 
        supportActionBar?.setDisplayShowTitleEnabled(false)

        toolbar.navigationIcon = ContextCompat.getDrawable(this, R.drawable.ic_menu_hamburger)
        toolbar.setNavigationOnClickListener { view ->
            showLeftMenu(view)
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
        
        etOcrText = findViewById(R.id.etOcrText) 
        tvTranslation = findViewById(R.id.tvTranslation)

        btnSpecialistScan = findViewById(R.id.btnSpecialistScan)
        btnRestore = findViewById(R.id.btnRestore)
        btnSaveImage = findViewById(R.id.btnSaveImage)
        btnTranslate = findViewById(R.id.btnTranslate)
        btnCloseOcr = findViewById(R.id.btnCloseOcr)

        btnCloseOcr.setOnClickListener {
            layoutOcrResult.visibility = View.GONE
            tvTranslation.visibility = View.GONE
            ivPreview.setImageDrawable(null)
            etOcrText.setText("")
            currentBitmap = null
        }

        btnSaveImage.setOnClickListener {
            currentBitmap?.let { bmp -> saveImageToGallery(bmp) } 
                ?: Toast.makeText(this, "No image to save", Toast.LENGTH_SHORT).show()
        }

        btnTranslate.setOnClickListener {
            val text = etOcrText.text.toString()
            if (text.isNotBlank() && text != "Scanned text...") {
                translateText(text)
            } else {
                Toast.makeText(this, "No text to translate", Toast.LENGTH_SHORT).show()
            }
        }

        btnSpecialistScan.setOnClickListener {
            if (currentBitmap != null) {
                runPapyTwin(currentBitmap!!)
            } else {
                Toast.makeText(this, "No image loaded", Toast.LENGTH_SHORT).show()
            }
        }

        btnRestore.setOnClickListener {
            val text = etOcrText.text.toString()
            if (text.isNotBlank()) {
                val currentUrl = prefs.getString("AENEAS_URL", "") ?: ""
                if (currentUrl.isEmpty()) {
                    Toast.makeText(this, "Please set Server URL in Menu first!", Toast.LENGTH_LONG).show()
                    showAeneasUrlDialog()
                } else {
                    runRestoration(text)
                }
            } else {
                Toast.makeText(this, "No text to restore", Toast.LENGTH_SHORT).show()
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
                if (parser.databaseUris.containsKey(selectedDb) || File(filesDir, selectedDb.filename).exists()) {
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
                    tvStatus.text = "Please Link Database (Menu)"
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

    private fun showLeftMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_main, popup.menu)
        
        popup.menu.findItem(R.id.action_ocr_mode).isChecked = useOnlineOcr
        popup.menu.findItem(R.id.engine_offline).isChecked = (translationEngine == 0)
        popup.menu.findItem(R.id.engine_cloud).isChecked = (translationEngine == 1)
        popup.menu.findItem(R.id.engine_gemini).isChecked = (translationEngine == 2)
        popup.menu.findItem(R.id.engine_chatanywhere).isChecked = (translationEngine == 3)

        popup.setOnMenuItemClickListener { item ->
            handleMenuItem(item)
            true
        }
        popup.show()
    }

    private fun handleMenuItem(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_instructions -> { showInstructionsDialog(); true }
            R.id.action_import_db -> { showImportSelectionDialog(); true }
            
            R.id.action_set_server_url -> { showAeneasUrlDialog(); true }

            R.id.action_ocr_mode -> {
                useOnlineOcr = !useOnlineOcr
                prefs.edit().putBoolean("USE_ONLINE_OCR", useOnlineOcr).apply()
                Toast.makeText(this, "OCR: ${if (useOnlineOcr) "Online" else "Offline"}", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.engine_offline -> { setTranslationEngine(0); true }
            R.id.engine_cloud -> { setTranslationEngine(1); true }
            R.id.engine_gemini -> { setTranslationEngine(2); true }
            R.id.engine_chatanywhere -> { setTranslationEngine(3); true }
            
            R.id.action_set_cloud_key -> { showCloudApiKeyDialog(); true }
            R.id.action_set_gemini_key -> { showGeminiApiKeyDialog(); true }
            R.id.action_set_chatanywhere_key -> { showChatAnywhereApiKeyDialog(); true }
            else -> false
        }
    }

    private fun setTranslationEngine(engine: Int) {
        translationEngine = engine
        prefs.edit().putInt("TRANS_ENGINE", engine).apply()
        val name = when(engine) {
            0 -> "Google App (Offline)"
            1 -> "Google Cloud API"
            2 -> "Gemini 2.5"
            else -> "ChatAnywhere"
        }
        Toast.makeText(this, "Engine set to: $name", Toast.LENGTH_SHORT).show()
    }

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

        when (translationEngine) {
            0 -> runOfflineTranslationIntent(cleaned, sourceLang)
            1 -> runOnlineTranslation(cleaned, sourceLang)
            2 -> runGeminiTranslation(cleaned)
            3 -> runChatAnywhereTranslation(cleaned)
        }
    }

    // 0. OFFLINE
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

    // 1. CLOUD API
    private fun runOnlineTranslation(text: String, sourceLang: String) {
        val apiKey = prefs.getString("GOOGLE_API_KEY", "") ?: ""
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Cloud Key missing! Set it in Menu.", Toast.LENGTH_LONG).show()
            return
        }
        val loadingDialog = AlertDialog.Builder(this).setMessage("Translating (Cloud)...").show()
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
                        showTranslationResult(translatedText, "Google Cloud")
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

    // 2. GEMINI AI
    private fun runGeminiTranslation(text: String) {
        val apiKey = prefs.getString("GEMINI_API_KEY", "") ?: ""
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Gemini API Key missing! Set it in Menu.", Toast.LENGTH_LONG).show()
            return
        }

        val loadingDialog = AlertDialog.Builder(this).setMessage("Asking Gemini (2.5)...").show()

        val generativeModel = GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = apiKey
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prompt = """
                    You are an expert in Ancient Latin and Greek Epigraphy and Papyrology.
                    Translate the following text into English.
                    
                    Notes:
                    - Expand abbreviations (e.g., 'D M' -> 'Dis Manibus').
                    - Text in brackets [] or () is restored; keep the meaning clear.
                    - Provide a formal, academic translation.
                    
                    Text: "$text"
                """.trimIndent()

                val response = generativeModel.generateContent(prompt)
                val translatedText = response.text ?: "No translation generated."

                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    showTranslationResult(translatedText, "Gemini AI")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Gemini Error")
                        .setMessage("Error: ${e.localizedMessage}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    // 3. CHATANYWHERE
    private fun runChatAnywhereTranslation(text: String) {
        val apiKey = prefs.getString("CHATANYWHERE_KEY", "") ?: ""
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "ChatAnywhere Key missing! Set it in Menu.", Toast.LENGTH_LONG).show()
            return
        }

        val loadingDialog = AlertDialog.Builder(this).setMessage("Asking ChatAnywhere...").show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val jsonRequest = JSONObject()
                jsonRequest.put("model", "gpt-3.5-turbo") 
                
                val messages = org.json.JSONArray()
                
                val systemMsg = JSONObject()
                systemMsg.put("role", "system")
                val prompt = """
                    You are an expert in Ancient Latin and Greek Epigraphy.
                    Translate the following text into English.
                    
                    Notes:
                    - Expand abbreviations (e.g., 'D M' -> 'Dis Manibus').
                    - Text in brackets [] or () is restored.
                    - Provide a formal, academic translation.
                """.trimIndent()
                systemMsg.put("content", prompt)
                messages.put(systemMsg)

                val userMsg = JSONObject()
                userMsg.put("role", "user")
                userMsg.put("content", text)
                messages.put(userMsg)

                jsonRequest.put("messages", messages)

                val url = URL("https://api.chatanywhere.tech/v1/chat/completions")
                val conn = url.openConnection() as HttpsURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.doOutput = true
                
                val writer = OutputStreamWriter(conn.outputStream)
                writer.write(jsonRequest.toString())
                writer.flush()
                writer.close()

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val responseText = reader.readText()
                    reader.close()

                    val jsonResponse = JSONObject(responseText)
                    val choices = jsonResponse.getJSONArray("choices")
                    val translatedText = choices.getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")

                    withContext(Dispatchers.Main) {
                        loadingDialog.dismiss()
                        showTranslationResult(translatedText, "ChatAnywhere (GPT)")
                    }
                } else {
                    val reader = BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream))
                    val errorText = reader.readText()
                    reader.close()
                    
                    withContext(Dispatchers.Main) {
                        loadingDialog.dismiss()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("ChatAnywhere Error")
                            .setMessage("Error ${conn.responseCode}:\n$errorText")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Connection Error")
                        .setMessage(e.localizedMessage)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun showTranslationResult(text: String, title: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Translation", text)
                clipboard.setPrimaryClip(clip)
            }
            .setNegativeButton("Close", null)
            .show()
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
                menu.add(0, 100, 0, "EPISEARCH Translate")
                    .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                return true
            }
            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                if (item.itemId == 100) {
                    translateCurrentSelection(textView)
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
            .setPositiveButton("Translate Selection") { _, _ ->
                translateCurrentSelection(textView)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun translateCurrentSelection(textView: TextView) {
        var min = 0
        var max = textView.text.length
        
        if (textView.isFocused && textView.hasSelection()) {
            min = textView.selectionStart
            max = textView.selectionEnd
        }

        val start = maxOf(0, minOf(min, max))
        val end = maxOf(0, maxOf(min, max))

        val selectedText = if (start != end) {
            textView.text.subSequence(start, end).toString()
        } else {
            textView.text.toString()
        }

        if (selectedText.isNotBlank()) {
            translateText(selectedText)
        } else {
            Toast.makeText(this, "No text selected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCloudApiKeyDialog() {
        val input = EditText(this)
        input.hint = "Paste Google Cloud API Key"
        input.setText(prefs.getString("GOOGLE_API_KEY", ""))
        AlertDialog.Builder(this)
            .setTitle("Set Cloud Vision/Translate Key")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("GOOGLE_API_KEY", input.text.toString().trim()).apply()
                Toast.makeText(this, "Cloud Key Saved!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGeminiApiKeyDialog() {
        val input = EditText(this)
        input.hint = "Paste Gemini API Key"
        input.setText(prefs.getString("GEMINI_API_KEY", ""))
        AlertDialog.Builder(this)
            .setTitle("Set Gemini API Key")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("GEMINI_API_KEY", input.text.toString().trim()).apply()
                Toast.makeText(this, "Gemini Key Saved!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showChatAnywhereApiKeyDialog() {
        val input = EditText(this)
        input.hint = "Paste ChatAnywhere Key (sk-...)"
        input.setText(prefs.getString("CHATANYWHERE_KEY", ""))
        AlertDialog.Builder(this)
            .setTitle("Set ChatAnywhere Key")
            .setMessage("Get key from: github.com/chatanywhere/GPT_API_free")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("CHATANYWHERE_KEY", input.text.toString().trim()).apply()
                Toast.makeText(this, "ChatAnywhere Key Saved!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showInstructionsDialog() {
        val message = """
            Android apk for searching Latin and Greek inscriptions and papyri.
            
            Use Link Database Files in the top left menu to link the apk to the databases stored on your device.

            Use the dropdown menu at the top to choose databases.
            
            Use the first search bar to look for inscriptions that contain the keywords you want, and the second one for inscriptions without the keywords. Separate multiple keywords with '&&'. Keywords will be highlighted in red in the search results.
            
            
            The scan button allows you to take a photo or load a local image for OCR. Your can choose between offline OCR, which uses tesseract library, and online OCR, which uses google cloud vision. The offline OCR converts the image to black and white before recogntion so as to reduce background noise. Online recognition uses Google cloud vision, offering much better results, and is free for 1000 times per month. The API can be obtained from Google Cloud console. You can set the api key in the menu.

            Aeneas ai model is integrated to help restore broken inscriptions after OCR. Papytwins model is used to recognize papyri.

            
            TRANSLATION ENGINES:
            1. Google App (Offline): Basic translation.
            2. Google Cloud API (Online): Standard machine translation.
            3. Gemini AI (Smart): Advanced, context-aware. Uses Gemini api key.
            4. GPT: Uses free gpt 3.5/4 with free key by ChatAnywhere.

            Set API Keys in the menu.
        """.trimIndent()
        
        val alert = AlertDialog.Builder(this)
            .setTitle("Instructions")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .create()
        alert.show()
        val messageView = alert.findViewById<TextView>(android.R.id.message)
        messageView?.autoLinkMask = Linkify.WEB_URLS
        messageView?.movementMethod = android.text.method.LinkMovementMethod.getInstance()
    }

    private fun showImportSelectionDialog() {
        val options = arrayOf("Latin Inscriptions", "Greek Inscriptions", "Greek Papyri")
        val types = arrayOf(
            InscriptionParser.DatabaseType.LATIN,
            InscriptionParser.DatabaseType.GREEK_INS,
            InscriptionParser.DatabaseType.GREEK_PAPY
        )
        AlertDialog.Builder(this)
            .setTitle("Link Database File")
            .setItems(options) { _, which ->
                pendingImportFilename = types[which].filename
                Toast.makeText(this, "Select the ${options[which]} file", Toast.LENGTH_LONG).show()
                importDbLauncher.launch(arrayOf("text/plain"))
            }
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
            etOcrText.setText("Uploading...")
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
            Toast.makeText(this, "Cloud Key missing! Set it in Menu.", Toast.LENGTH_LONG).show()
            etOcrText.setText("Error: Missing API Key.")
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
                writer.write(jsonRequest.toString())
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
                                etOcrText.setText(text)
                                tvStatus.text = "Google OCR Complete"
                                progressBar.visibility = View.GONE
                            }
                        } else {
                             withContext(Dispatchers.Main) {
                                etOcrText.setText("No text found.")
                                progressBar.visibility = View.GONE
                            }
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        etOcrText.setText("Error: ${conn.responseCode}")
                        progressBar.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    etOcrText.setText("Error: ${e.message}")
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
                
                val basePath = getExternalFilesDir(null)?.absolutePath
                if (basePath != null) {
                    val success = tessApi.init(basePath, "grc+lat")
                    withContext(Dispatchers.Main) {
                        if (success) isTesseractReady = true
                        else tvStatus.text = "OCR Init Failed"
                    }
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
        etOcrText.setText("Reading text...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                tessApi.setImage(bitmap)
                val result = tessApi.utF8Text
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    tvStatus.text = "Scan Complete"
                    etOcrText.setText(if (result.isBlank()) "No text found." else result)
                }
            } catch (e: Exception) { 
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    etOcrText.setText("Error: ${e.message}")
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

    // --- HELPER METHODS ---

    private fun showAeneasUrlDialog() {
        val input = EditText(this)
        input.hint = "e.g. https://...ngrok-free.dev"
        input.setText(prefs.getString("AENEAS_URL", ""))
        AlertDialog.Builder(this)
            .setTitle("Set Colab Server URL")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                var url = input.text.toString().trim()
                if (url.endsWith("/")) url = url.substring(0, url.length - 1)
                prefs.edit().putString("AENEAS_URL", url).apply()
                Toast.makeText(this, "URL Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setLoading(isLoading: Boolean) {
        if (isLoading) {
            progressBar.visibility = View.VISIBLE
            btnSpecialistScan.isEnabled = false
            btnSpecialistScan.alpha = 0.5f
        } else {
            progressBar.visibility = View.GONE
            btnSpecialistScan.isEnabled = true
            btnSpecialistScan.alpha = 1.0f
        }
    }

    private fun convertBitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun runPapyTwin(bitmap: Bitmap) {
        setLoading(true)
        val base64Image = convertBitmapToBase64(bitmap)
        
        api.scanPapyTwin(OcrRequest(base64Image)).enqueue(object : Callback<OcrResponse> {
            override fun onResponse(call: Call<OcrResponse>, response: Response<OcrResponse>) {
                setLoading(false)
                if (response.isSuccessful && response.body() != null) {
                    etOcrText.setText(response.body()!!.text)
                    Toast.makeText(this@MainActivity, "PapyTwin Complete", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Server Error: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onFailure(call: Call<OcrResponse>, t: Throwable) {
                setLoading(false)
                Toast.makeText(this@MainActivity, "Connection Failed", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun runRestoration(text: String) {
        setLoading(true)
        // 1. Change Callback type to ResponseBody
        api.restoreText(RestoreRequest(text)).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                setLoading(false)
                if (response.isSuccessful && response.body() != null) {
                    try {
                        // 2. Get the RAW string from the server
                        val rawResponse = response.body()!!.string()

                        // 3. Check if it's JSON {"prediction": "..."} or just "..."
                        val resultText = if (rawResponse.trim().startsWith("{")) {
                            // It is JSON -> Parse it
                            val jsonObject = JSONObject(rawResponse)
                            jsonObject.getString("prediction")
                        } else {
                            // It is a plain String (or quoted string) -> Clean it
                            rawResponse.replace("\"", "") // Remove quotes if present
                        }

                        // 4. Show Result
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Restoration Result")
                            .setMessage(resultText)
                            .setPositiveButton("Replace") { _, _ -> etOcrText.setText(resultText) }
                            .setNegativeButton("Copy") { _, _ ->
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Restored", resultText)
                                clipboard.setPrimaryClip(clip)
                            }
                            .setNeutralButton("Cancel", null)
                            .show()

                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Parse Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Server Error: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                setLoading(false)
                Toast.makeText(this@MainActivity, "Connection Failed: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }
}

// --- API INTERFACE ---

interface ApiService {

    @POST("/papytwin")
    fun scanPapyTwin(@Body request: OcrRequest): Call<OcrResponse>

    @POST("/restore")
    fun restoreText(@Body request: RestoreRequest): Call<ResponseBody>
}

data class OcrRequest(val image: String)
data class OcrResponse(val text: String)
data class RestoreRequest(val text: String)
data class RestoreResponse(val prediction: String)