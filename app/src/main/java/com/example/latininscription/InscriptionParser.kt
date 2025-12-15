package com.example.latininscription

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

data class Inscription(
    val id: String, val date: String, val location: String,
    val text: String, val ref1: String, val ref2: String,
    val searchableText: String
)

class InscriptionParser(private val context: Context) {

    // REVERTED: Pointing back to .txt files
    enum class DatabaseType(val filename: String, val displayName: String) {
        LATIN("Latin-inscriptions-CIL-AE-JRA.txt", "Latin Inscriptions"),
        GREEK_INS("Greek inscriptions.txt", "Greek Inscriptions"),
        GREEK_PAPY("greek papyrus.txt", "Greek Papyri")
    }

    // RAM OPTIMIZATION: Store raw strings, not objects
    private var rawRecords: ArrayList<String> = ArrayList()
    private var cleanRecords: ArrayList<String> = ArrayList()
    private var currentType: DatabaseType? = null

    private val tagRegex = Regex("<(\\p{L}+)=\\p{L}+>")
    private val cleanupRegex = Regex("[\\\\[\\\\])(}{/<>,\\-]")

    private fun cleanRecord(raw: String): String {
        return raw.replace(tagRegex, "$1").replace(cleanupRegex, "")
    }

    suspend fun loadDatabase(dbType: DatabaseType) = withContext(Dispatchers.IO) {
        if (currentType == dbType && rawRecords.isNotEmpty()) return@withContext

        // Cleanup RAM
        rawRecords.clear(); cleanRecords.clear()
        rawRecords = ArrayList(); cleanRecords = ArrayList()
        System.gc()

        try {
            // STANDARD FILE LOADING (No Zip)
            val inputStream = context.assets.open(dbType.filename)
            val reader = BufferedReader(InputStreamReader(inputStream))

            val currentRecord = StringBuilder()
            var line = reader.readLine()

            while (line != null) {
                if (line.isBlank()) {
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

    suspend fun search(dbType: DatabaseType, includePatterns: List<String>, excludePatterns: List<String>): List<Inscription> = withContext(Dispatchers.Default) {
        val result = mutableListOf<Inscription>()
        if (rawRecords.isEmpty()) return@withContext emptyList()

        val includeRegexes = includePatterns.mapNotNull { try { Regex(it, RegexOption.IGNORE_CASE) } catch (e: Exception) { null } }
        val excludeRegexes = excludePatterns.mapNotNull { try { Regex(it, RegexOption.IGNORE_CASE) } catch (e: Exception) { null } }

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
            if (match) {
                result.add(parseSingleRecord(rawRecords[i], cleanText, dbType))
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