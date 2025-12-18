package com.example.latininscription

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.regex.Pattern
import java.util.zip.ZipInputStream

data class Inscription(
    val id: String, 
    val date: String, 
    val location: String,
    val text: String, 
    val ref1: String, 
    val ref2: String,
    val searchableText: String
)

class InscriptionParser(private val context: Context) {

    enum class DatabaseType(val filename: String, val displayName: String) {
        LATIN("Latin-inscriptions-CIL-AE-JRA.txt", "Latin Inscriptions"),
        GREEK_INS("Greek inscriptions.txt", "Greek Inscriptions"),
        GREEK_PAPY("greek papyrus.txt", "Greek Papyri")
    }

    // URI Map (Required for MainActivity compatibility)
    var databaseUris: Map<DatabaseType, Uri> = emptyMap()

    // HIGH PERFORMANCE: Store everything in RAM
    private var rawRecords: ArrayList<String> = ArrayList()
    private var cleanRecords: ArrayList<String> = ArrayList()
    private var currentType: DatabaseType? = null

    private val tagRegex = Regex("<(\\p{L}+)=\\p{L}+>")
    private val cleanupRegex = Regex("[\\\\[\\\\])(}{/<>,\\-]")

    private fun cleanRecord(raw: String): String {
        return raw.replace(tagRegex, "$1").replace(cleanupRegex, "")
    }

    suspend fun loadDatabase(dbType: DatabaseType) = withContext(Dispatchers.IO) {
        // If this exact DB is already loaded in RAM, skip loading (Instant Switch)
        if (currentType == dbType && rawRecords.isNotEmpty()) return@withContext

        // Clear old memory
        rawRecords.clear()
        cleanRecords.clear()
        System.gc() // Suggest garbage collection to free up space

        var inputStream: InputStream? = null

        // 1. Try Loading from Linked URI (Priority)
        val uri = databaseUris[dbType]
        if (uri != null) {
            try {
                inputStream = context.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                // If URI fails (e.g., file moved), fall through to internal storage
            }
        }

        // 2. Try Internal Storage (Fallback)
        if (inputStream == null) {
            val file = File(context.filesDir, dbType.filename)
            if (file.exists()) {
                inputStream = FileInputStream(file)
            }
        }

        if (inputStream == null) {
            throw FileNotFoundException("Database not found for ${dbType.displayName}.\nPlease use 'Link Database File' in the menu.")
        }

        try {
            val reader = BufferedReader(InputStreamReader(inputStream), 8192) // 8KB Buffer

            val currentRecord = StringBuilder()
            var line = reader.readLine()

            // READ EVERYTHING INTO RAM
            while (line != null) {
                if (line.isBlank()) {
                    if (currentRecord.isNotBlank()) {
                        val raw = currentRecord.toString()
                        rawRecords.add(raw)
                        cleanRecords.add(cleanRecord(raw))
                        currentRecord.setLength(0) 
                    }
                } else {
                    currentRecord.append(line).append("\n")
                }
                line = reader.readLine()
            }
            // Add the last record
            if (currentRecord.isNotBlank()) {
                val raw = currentRecord.toString()
                rawRecords.add(raw)
                cleanRecords.add(cleanRecord(raw))
            }
            reader.close()
            currentType = dbType

        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    suspend fun search(dbType: DatabaseType, includePatterns: List<String>, excludePatterns: List<String>): List<Inscription> = withContext(Dispatchers.Default) {
        val result = mutableListOf<Inscription>()
        
        if (rawRecords.isEmpty()) return@withContext emptyList()

        // Compile regexes once for speed
        val includeRegexes = includePatterns.mapNotNull { try { Pattern.compile(it, Pattern.CASE_INSENSITIVE) } catch (e: Exception) { null } }
        val excludeRegexes = excludePatterns.mapNotNull { try { Pattern.compile(it, Pattern.CASE_INSENSITIVE) } catch (e: Exception) { null } }

        // FAST RAM LOOP
        for (i in cleanRecords.indices) {
            val cleanText = cleanRecords[i]
            var match = true
            
            // Check Includes
            for (p in includeRegexes) {
                if (!p.matcher(cleanText).find()) {
                    match = false; break
                }
            }
            // Check Excludes
            if (match && excludeRegexes.isNotEmpty()) {
                for (p in excludeRegexes) {
                    if (p.matcher(cleanText).find()) {
                        match = false; break
                    }
                }
            }
            
            if (match) {
                result.add(parseSingleRecord(rawRecords[i], cleanText, dbType))
                // Optional: Limit results to avoid UI lag if user searches for "e"
                // if (result.size >= 2000) break 
            }
        }
        return@withContext result
    }

    private fun parseSingleRecord(raw: String, clean: String, dbType: DatabaseType): Inscription {
        val lines = raw.split("\n")
        
        if (dbType == DatabaseType.LATIN) {
            val n = if (lines.size > 1 && lines[1].contains("Datierung:")) 0 else 1
            
            val id = lines.getOrElse(0) { "" }.trim()
            val date = if (n == 0) lines.getOrElse(1) { "" }.trim() else ""
            val location = lines.getOrElse(3 - n) { "" }.trim()
            
            val rawText = lines.getOrElse(4 - n) { "" }
            val formattedText = rawText.replace("/", "\n")
                                       .replace(Regex("\\n+"), "\n")
                                       .trim()

            val ref1 = lines.getOrElse(5 - n) { "" }.trim()
            val ref2 = lines.getOrElse(6 - n) { "" }.trim()

            return Inscription(id, date, location, formattedText, ref1, ref2, clean)
        } else {
            val id = lines.getOrElse(0) { "Item" }.trim()
            val fullText = if (lines.size > 1) {
                lines.drop(1).joinToString("\n")
            } else {
                lines.getOrElse(0) { "" }
            }
            return Inscription(id, "", "", fullText.trim(), "", "", searchableText = clean)
        }
    }
}