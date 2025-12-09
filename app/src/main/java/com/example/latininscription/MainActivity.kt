package com.example.latininscription

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val parser by lazy { InscriptionParser(this) }
    private lateinit var adapter: InscriptionsAdapter
    private var lastResults: List<Inscription> = emptyList()

    private val saveFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let {
            CoroutineScope(Dispatchers.IO).launch {
                contentResolver.openOutputStream(it)?.use { outputStream ->
                    val sb = StringBuilder()
                    for (item in lastResults) {
                        sb.append("${item.id} | ${item.location}\n")
                        sb.append("${item.text}\n")
                        sb.append("Ref: ${item.ref1} ${item.ref2}\n")
                        sb.append("========================================\n\n")
                    }
                    outputStream.write(sb.toString().toByteArray())
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "File Saved Successfully!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etInclude = findViewById<EditText>(R.id.etInclude)
        val etExclude = findViewById<EditText>(R.id.etExclude)
        val btnSearch = findViewById<Button>(R.id.btnSearch)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val rvResults = findViewById<RecyclerView>(R.id.rvResults)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val spinner = findViewById<Spinner>(R.id.spinnerDatabase)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar) // New Progress Bar

        val dbOptions = InscriptionParser.DatabaseType.values()
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, dbOptions.map { it.displayName })
        spinner.adapter = spinnerAdapter

        rvResults.layoutManager = LinearLayoutManager(this)
        adapter = InscriptionsAdapter()
        rvResults.adapter = adapter

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedDb = dbOptions[position]
                
                // Show loading UI
                tvStatus.text = "Loading ${selectedDb.displayName}..."
                progressBar.visibility = View.VISIBLE
                btnSearch.isEnabled = false
                btnSave.isEnabled = false

                CoroutineScope(Dispatchers.Main).launch {
                    // This will be fast if already cached
                    parser.loadDatabase(selectedDb)
                    
                    // Hide loading UI
                    progressBar.visibility = View.GONE
                    tvStatus.text = "Ready to search ${selectedDb.displayName}"
                    btnSearch.isEnabled = true
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnSearch.setOnClickListener {
            val includeText = etInclude.text.toString()
            val excludeText = etExclude.text.toString()

            val includeRaw = includeText.split("&&").map { it.trim() }.filter { it.isNotBlank() }
            val excludeRaw = excludeText.split("&&").map { it.trim() }.filter { it.isNotBlank() }

            if (includeRaw.isEmpty()) {
                tvStatus.text = "Please enter search keywords"
                return@setOnClickListener
            }

            // UI Feedback
            tvStatus.text = "Searching..."
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

        btnSave.setOnClickListener {
            saveFileLauncher.launch("Inscriptions_Output.txt")
        }
    }
}