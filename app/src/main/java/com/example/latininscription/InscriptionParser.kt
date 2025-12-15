package com.example.latininscription

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

// Data model for a single search result
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

    // 1. EXACT FILENAMES (Must match what you put on the phone)
    enum class DatabaseType(val filename: String, val displayName: String) {
        LATIN("Latin-inscriptions-CIL-AE-JRA.txt", "Latin Inscriptions"),
        GREEK_INS("Greek inscriptions.txt", "Greek Inscriptions"),
        GREEK_PAPY("greek papyrus.txt", "Greek Papyri")
    }

    // In-memory storage for the loaded database
    private var rawRecords: ArrayList<String> = ArrayList()
    private var cleanRecords: ArrayList<String> = ArrayList()
    private var currentType: DatabaseType? = null

    // Regex for cleaning tags (e.g., <w=word>) for searching
    private val tagRegex = Regex("<(\\p{L}+)=\\p{L}+>")
    private val cleanupRegex = Regex("[\\\\[\\\\])(}{/<>,\\-]")

    private fun cleanRecord(raw: String): String {
        return raw.replace(tagRegex, "$1").replace(cleanupRegex, "")
    }

    // 2. LOAD DATABASE FUNCTION
    suspend fun loadDatabase(dbType: DatabaseType) = withContext(Dispatchers.IO) {
        // If this DB is already loaded, do nothing
        if (currentType == dbType && rawRecords.isNotEmpty()) return@withContext

        // Clear RAM before loading new file
        rawRecords.clear(); cleanRecords.clear()
        rawRecords = ArrayList(); cleanRecords = ArrayList()
        System.gc() // Suggest garbage collection

        // 3. TARGET PATH: /Android/data/com.example.latininscription/files/
        val file = File(context.getExternalFilesDir(null), dbType.filename)

        if (!file.exists()) {
            throw FileNotFoundException("File not found!\nPlease copy '${dbType.filename}' to:\n\nAndroid/data/com.example.latininscription/files/")
        }

        val inputStream = FileInputStream(file)

        try {
            // Check if it's a ZIP or just a Text file
            val reader = if (dbType.filename.endsWith(".zip")) {
                val zis = ZipInputStream(inputStream)
                zis.nextEntry
                BufferedReader(InputStreamReader(zis))
            } else {
                // Assume standard text file
                BufferedReader(InputStreamReader(inputStream))
            }

            // Read line by line
            val currentRecord = StringBuilder()
            var line = reader.readLine()

            while (line != null) {
                if (line.isBlank()) {
                    // Empty line means end of a record
                    if (currentRecord.isNotBlank()) {
                        val raw = currentRecord.toString()
                        rawRecords.add(raw)
                        cleanRecords.add(cleanRecord(raw))
                        currentRecord.clear()
                    }
                } else {
                    currentRecord.append(line).append("\n")
                }
                line = reader.readLine()
            }
            // Add the final record if exists
            if (currentRecord.isNotBlank()) {
                val raw = currentRecord.toString()
                rawRecords.add(raw)
                cleanRecords.add(cleanRecord(raw))
            }

            reader.close()
            currentType = dbType

        } catch (e: Exception) {
            e.printStackTrace()
            throw e // Pass error to MainActivity
        }
    }

    // 4. SEARCH FUNCTION
    suspend fun search(dbType: DatabaseType, includePatterns: List<String>, excludePatterns: List<String>): List<Inscription> = withContext(Dispatchers.Default) {
        val result = mutableListOf<Inscription>()
        if (rawRecords.isEmpty()) return@withContext emptyList()

        // Compile regexes once for speed
        val includeRegexes = includePatterns.mapNotNull { try { Regex(it, RegexOption.IGNORE_CASE) } catch (e: Exception) { null } }
        val excludeRegexes = excludePatterns.mapNotNull { try { Regex(it, RegexOption.IGNORE_CASE) } catch (e: Exception) { null } }

        for (i in cleanRecords.indices) {
            val cleanText = cleanRecords[i]
            var match = true

            // Check Includes (Must have ALL)
            for (regex in includeRegexes) {
                if (!regex.containsMatchIn(cleanText)) {
                    match = false; break
                }
            }

            // Check Excludes (Must have NONE)
            if (match && excludeRegexes.isNotEmpty()) {
                for (regex in excludeRegexes) {
                    if (regex.containsMatchIn(cleanText)) {
                        match = false; break
                    }
                }
            }

            if (match) {
                result.add(parseSingleRecord(rawRecords[i], cleanText, dbType))
            }
        }
        return@withContext result
    }

    // Helper to format the text for display
    private fun parseSingleRecord(raw: String, clean: String, dbType: DatabaseType): Inscription {
        val lines = raw.split("\n")

        if (dbType == DatabaseType.LATIN) {
            // Latin logic: Checks for "Datierung" line to shift fields
            val n = if (lines.size > 1 && lines[1].contains("Datierung:")) 0 else 1
            return Inscription(
                id = lines.getOrElse(0) { "" },
                date = if (n == 0) lines.getOrElse(1) { "" } else "",
                location = lines.getOrElse(3 - n) { "" },
                text = lines.getOrElse(4 - n) { "" }.replace("/", "\n"),
                ref1 = lines.getOrElse(5 - n) { "" },
                ref2 = lines.getOrElse(6 - n) { "" },
                searchableText = clean
            )
        } else {
            // Greek/Papyrus logic (Simpler)
            val id = lines.getOrElse(0) { "Item" }
            val fullText = if (lines.size > 1) lines.drop(1).joinToString("\n") else lines.getOrElse(0) { "" }
            return Inscription(id, "", "", fullText, "", "", searchableText = clean)
        }
    }
}