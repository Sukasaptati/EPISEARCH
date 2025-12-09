package com.example.latininscription

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

data class Inscription(
    val id: String, val date: String, val location: String,
    val text: String, val ref1: String, val ref2: String,
    val searchableText: String
)

class InscriptionParser(private val context: Context) {

    // 1. UPDATED FILENAMES to match your new .zip files
    enum class DatabaseType(val filename: String, val displayName: String) {
        LATIN("latin_data.zip", "Latin Inscriptions"),
        GREEK_INS("greek_data.zip", "Greek Inscriptions"),
        GREEK_PAPY("papyri_data.zip", "Greek Papyri")
    }

    // --- RAM OPTIMIZATION: PARALLEL ARRAYS ---
    private var rawRecords: ArrayList<String> = ArrayList()
    private var cleanRecords: ArrayList<String> = ArrayList()
    private var currentType: DatabaseType? = null

    // Pre-compiled regex (Huge speed boost)
    private val tagRegex = Regex("<(\\p{L}+)=\\p{L}+>")
    private val cleanupRegex = Regex("[\\\\[\\\\])(}{/<>,\\-]")

    private fun cleanRecord(raw: String): String {
        return raw.replace(tagRegex, "$1").replace(cleanupRegex, "")
    }

    suspend fun loadDatabase(dbType: DatabaseType) = withContext(Dispatchers.IO) {
        // If already loaded, do nothing
        if (currentType == dbType && rawRecords.isNotEmpty()) return@withContext

        // 1. FORCE CLEANUP (Prevents RAM Leaks)
        rawRecords.clear()
        cleanRecords.clear()
        // Re-allocate to ensure old memory is released
        rawRecords = ArrayList()
        cleanRecords = ArrayList()
        System.gc() // Tell Android to clean RAM immediately

        try {
            val inputStream = context.assets.open(dbType.filename)

            // 2. AUTO-DETECT ZIP FILES
            // If the file ends in .zip, we unzip it on the fly.
            val reader = if (dbType.filename.endsWith(".zip")) {
                val zis = ZipInputStream(inputStream)
                zis.nextEntry // Advance to the first file inside the zip
                BufferedReader(InputStreamReader(zis))
            } else {
                // Fallback for .txt files
                BufferedReader(InputStreamReader(inputStream))
            }

            val currentRecord = StringBuilder()
            var line = reader.readLine()

            // 3. LOAD DATA
            while (line != null) {
                if (line.isBlank()) {
                    if (currentRecord.isNotBlank()) {
                        val raw = currentRecord.toString()
                        rawRecords.add(raw)
                        cleanRecords.add(cleanRecord(raw)) // Store clean version for regex search
                        currentRecord.clear()
                    }
                } else {
                    currentRecord.append(line).append("\n")
                }
                line = reader.readLine()
            }
            // Process last entry
            if (currentRecord.isNotBlank()) {
                val raw = currentRecord.toString()
                rawRecords.add(raw)
                cleanRecords.add(cleanRecord(raw))
            }
            reader.close()
            currentType = dbType

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 4. SEARCH (Lazy Parsing)
    suspend fun search(dbType: DatabaseType, includePatterns: List<String>, excludePatterns: List<String>): List<Inscription> = withContext(Dispatchers.Default) {
        val result = mutableListOf<Inscription>()

        if (rawRecords.isEmpty()) return@withContext emptyList()

        val includeRegexes = includePatterns.mapNotNull { try { Regex(it, RegexOption.IGNORE_CASE) } catch (e: Exception) { null } }
        val excludeRegexes = excludePatterns.mapNotNull { try { Regex(it, RegexOption.IGNORE_CASE) } catch (e: Exception) { null } }

        // Loop through the CLEAN strings (Fast)
        for (i in cleanRecords.indices) {
            val cleanText = cleanRecords[i]
            var match = true

            for (regex in includeRegexes) {
                if (!regex.containsMatchIn(cleanText)) {
                    match = false; break
                }
            }

            if (match && excludeRegexes.isNotEmpty()) {
                for (regex in excludeRegexes) {
                    if (regex.containsMatchIn(cleanText)) {
                        match = false; break
                    }
                }
            }

            // 5. PARSE ON DEMAND (Only if matched)
            if (match) {
                val raw = rawRecords[i]
                result.add(parseSingleRecord(raw, cleanText, dbType))
            }
        }
        return@withContext result
    }

    private fun parseSingleRecord(raw: String, clean: String, dbType: DatabaseType): Inscription {
        val lines = raw.split("\n")
        if (dbType == DatabaseType.LATIN) {
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
            val id = lines.getOrElse(0) { "Item" }
            val fullText = if (lines.size > 1) lines.drop(1).joinToString("\n") else lines.getOrElse(0) { "" }
            return Inscription(id, "", "", fullText, "", "", searchableText = clean)
        }
    }
}