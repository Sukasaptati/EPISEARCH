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

    var databaseUris: Map<DatabaseType, Uri> = emptyMap()
    private var currentType: DatabaseType? = null

    // Regex to remove tags for the final clean display
    private val tagRegex = Regex("<(\\p{L}+)=\\p{L}+>")
    private val cleanupRegex = Regex("[\\\\[\\\\])(}{/<>,\\-]")

    private fun cleanRecord(raw: String): String {
        return raw.replace(tagRegex, "$1").replace(cleanupRegex, "")
    }

    // NEW: Converts simple keyword "galliarum" into a smart Regex that matches "[g]alliarum"
    private fun toFuzzyPattern(keyword: String): Pattern {
        val sb = StringBuilder()
        for (char in keyword) {
            // Escape special regex characters if user types them
            if ("[](){}.*+?^$|\\".contains(char)) {
                sb.append("\\").append(char)
            } else {
                sb.append(char)
            }
            // Allow Epigraphic brackets between any letters: [] () <>
            // This means "g" can be followed by garbage brackets before "a"
            sb.append("[\\(\\)\\[\\]<>]*")
        }
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE)
    }

    suspend fun loadDatabase(dbType: DatabaseType) = withContext(Dispatchers.IO) {
        var found = false
        if (databaseUris.containsKey(dbType)) {
            try {
                val uri = databaseUris[dbType]!!
                context.contentResolver.openInputStream(uri)?.close()
                found = true
            } catch (e: Exception) { }
        }
        if (!found) {
            val file = File(context.filesDir, dbType.filename)
            if (file.exists()) found = true
        }
        if (!found) {
            throw FileNotFoundException("Database not found for ${dbType.displayName}.\nPlease use 'Link Database File' in the menu.")
        }
        currentType = dbType
    }

    suspend fun search(dbType: DatabaseType, includePatterns: List<String>, excludePatterns: List<String>): List<Inscription> = withContext(Dispatchers.IO) {
        val results = ArrayList<Inscription>()
        var inputStream: InputStream? = null
        val uri = databaseUris[dbType]
        
        if (uri != null) {
            try { inputStream = context.contentResolver.openInputStream(uri) } catch (e: Exception) {}
        }
        if (inputStream == null) {
            val file = File(context.filesDir, dbType.filename)
            if (file.exists()) inputStream = FileInputStream(file)
        }

        if (inputStream == null) return@withContext emptyList()

        // SPEED FIX: Compile "Smart Patterns" ONCE.
        // We do NOT clean the database text inside the loop anymore (too slow).
        // Instead, we make the search query "smart" enough to handle the raw brackets.
        val includeRegexes = includePatterns.map { toFuzzyPattern(it) }
        val excludeRegexes = excludePatterns.map { toFuzzyPattern(it) }

        val reader = BufferedReader(InputStreamReader(inputStream), 8192)
        try {
            val currentRecord = StringBuilder()
            var line = reader.readLine()

            while (line != null) {
                if (line.isBlank()) {
                    if (currentRecord.isNotBlank()) {
                        val raw = currentRecord.toString()
                        
                        // FAST CHECK on RAW text
                        if (checkMatch(raw, includeRegexes, excludeRegexes)) {
                            // Only clean it IF it's a match (saves massive CPU)
                            val clean = cleanRecord(raw)
                            results.add(parseSingleRecord(raw, clean, dbType))
                            if (results.size >= 500) break
                        }
                        currentRecord.setLength(0) 
                    }
                } else {
                    currentRecord.append(line).append("\n")
                }
                line = reader.readLine()
            }
            if (currentRecord.isNotBlank() && results.size < 500) {
                val raw = currentRecord.toString()
                if (checkMatch(raw, includeRegexes, excludeRegexes)) {
                    val clean = cleanRecord(raw)
                    results.add(parseSingleRecord(raw, clean, dbType))
                }
            }
        } finally {
            reader.close()
        }
        return@withContext results
    }

    private fun checkMatch(raw: String, includes: List<Pattern>, excludes: List<Pattern>): Boolean {
        if (raw.isEmpty()) return false
        
        // Find ALL include patterns
        for (p in includes) {
            if (!p.matcher(raw).find()) return false
        }
        // Find NO exclude patterns
        for (p in excludes) {
            if (p.matcher(raw).find()) return false
        }
        return true
    }

    private fun parseSingleRecord(raw: String, clean: String, dbType: DatabaseType): Inscription {
        val lines = raw.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        
        if (dbType == DatabaseType.LATIN) {
            val id = lines.firstOrNull() ?: ""
            
            val dateLine = lines.find { it.startsWith("Datierung:") } ?: ""
            val provLine = lines.find { it.startsWith("Provinz:") } ?: ""
            val ortLine = lines.find { it.startsWith("Ort:") } ?: ""
            val location = "$provLine $ortLine".trim()

            // Smart Filter: Skip Metadata lines to find real text
            val metadataPrefixes = listOf(
                "Publikation:", "Datierung:", "EDCS-ID:", 
                "Provinz:", "Ort:", "Material:", 
                "Inschriftengattung", "Personenstatus", "Militär", 
                "Religion", "Region"
            )

            val textLines = lines.filterIndexed { index, line ->
                index > 0 && 
                !metadataPrefixes.any { line.startsWith(it) }
            }

            val rawText = textLines.joinToString("\n")
            val formattedText = rawText.replace("/", "\n")
                                       .replace(Regex("\\n+"), "\n")
                                       .trim()

            return Inscription(id, dateLine, location, formattedText, "", "", clean)
        } else {
            val id = lines.firstOrNull() ?: "Item"
            val fullText = if (lines.size > 1) lines.drop(1).joinToString("\n") else ""
            return Inscription(id, "", "", fullText.trim(), "", "", searchableText = clean)
        }
    }
}