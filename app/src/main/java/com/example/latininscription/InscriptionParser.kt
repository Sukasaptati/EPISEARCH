package com.example.latininscription

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
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

    // MEMORY FIX: No ArrayLists here. We do not store the file in RAM.

    private val tagRegex = Regex("<(\\p{L}+)=\\p{L}+>")
    private val cleanupRegex = Regex("[\\\\[\\\\])(}{/<>,\\-]")

    private fun cleanRecord(raw: String): String {
        return raw.replace(tagRegex, "$1").replace(cleanupRegex, "")
    }

    // 1. LIGHTWEIGHT LOAD
    // We only check if the file exists. We do NOT read it into memory yet.
    suspend fun loadDatabase(dbType: DatabaseType) = withContext(Dispatchers.IO) {
        // Use filesDir (Internal Storage) for safety
        val file = File(context.filesDir, dbType.filename) 
        
        if (!file.exists()) {
             // Fallback to check external just in case user didn't re-import yet
             val extFile = File(context.getExternalFilesDir(null), dbType.filename)
             if (!extFile.exists()) {
                 throw FileNotFoundException("File not found: ${dbType.filename}\nPlease Import Database from the Menu.")
             }
        }
    }

    // 2. STREAMING SEARCH (The Crash Fix)
    suspend fun search(dbType: DatabaseType, includePatterns: List<String>, excludePatterns: List<String>): List<Inscription> = withContext(Dispatchers.IO) {
        val results = ArrayList<Inscription>()
        
        // Try Internal Storage first, then External
        var file = File(context.filesDir, dbType.filename)
        if (!file.exists()) {
            file = File(context.getExternalFilesDir(null), dbType.filename)
        }
        if (!file.exists()) return@withContext emptyList()

        // Prepare Regex (Compile once for speed)
        val includeRegexes = includePatterns.mapNotNull { try { Pattern.compile(it, Pattern.CASE_INSENSITIVE) } catch (e: Exception) { null } }
        val excludeRegexes = excludePatterns.mapNotNull { try { Pattern.compile(it, Pattern.CASE_INSENSITIVE) } catch (e: Exception) { null } }

        val inputStream = FileInputStream(file)
        val reader = if (file.name.endsWith(".zip")) {
            val zis = ZipInputStream(inputStream)
            zis.nextEntry
            BufferedReader(InputStreamReader(zis))
        } else {
            BufferedReader(InputStreamReader(inputStream), 8192) // 8KB Buffer
        }

        try {
            val currentRecord = StringBuilder()
            var line = reader.readLine()

            while (line != null) {
                if (line.isBlank()) {
                    // End of a record found
                    if (currentRecord.isNotBlank()) {
                        val raw = currentRecord.toString()
                        
                        // CHECK MATCH IMMEDIATELY
                        if (checkMatch(raw, includeRegexes, excludeRegexes)) {
                            // Only parse if it is a match
                            val clean = cleanRecord(raw)
                            results.add(parseSingleRecord(raw, clean, dbType))
                            
                            // SAFETY LIMIT: Stop at 500 results to prevent UI lag
                            if (results.size >= 500) break
                        }
                        
                        // MEMORY FLUSH: Clear the builder immediately
                        currentRecord.setLength(0)
                    }
                } else {
                    currentRecord.append(line).append("\n")
                }
                line = reader.readLine()
            }
            // Check the very last record
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

    // Helper: Fast Regex Check
    private fun checkMatch(raw: String, includes: List<Pattern>, excludes: List<Pattern>): Boolean {
        if (raw.isEmpty()) return false
        
        // Search the raw text directly for speed
        for (p in includes) {
            if (!p.matcher(raw).find()) return false
        }
        for (p in excludes) {
            if (p.matcher(raw).find()) return false
        }
        return true
    }

    private fun parseSingleRecord(raw: String, clean: String, dbType: DatabaseType): Inscription {
        val lines = raw.split("\n")
        
        if (dbType == DatabaseType.LATIN) {
            val n = if (lines.size > 1 && lines[1].contains("Datierung:")) 0 else 1
            
            // STRICT PARSING (Prevents Layout Gaps)
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