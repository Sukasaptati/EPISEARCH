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

        rawRecords.clear(); cleanRecords.clear()
        System.gc()

        val file = File(context.getExternalFilesDir(null), dbType.filename)

        if (!file.exists()) {
            throw FileNotFoundException("File not found!\nPlease copy '${dbType.filename}' to:\nAndroid/data/com.example.latininscription/files/")
        }

        val inputStream = FileInputStream(file)

        try {
            val reader = if (dbType.filename.endsWith(".zip")) {
                val zis = ZipInputStream(inputStream)
                zis.nextEntry
                BufferedReader(InputStreamReader(zis))
            } else {
                BufferedReader(InputStreamReader(inputStream))
            }

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
            throw e
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
            // Restore Original Logic: Check line 1 for "Datierung"
            val n = if (lines.size > 1 && lines[1].contains("Datierung:")) 0 else 1
            
            // TRIM EACH FIELD INDIVIDUALLY to remove the huge gaps
            val id = lines.getOrElse(0) { "" }.trim()
            val date = if (n == 0) lines.getOrElse(1) { "" }.trim() else ""
            val location = lines.getOrElse(3 - n) { "" }.trim()
            
            // Clean Text: Replace slashes, remove duplicate newlines
            val rawText = lines.getOrElse(4 - n) { "" }
            val formattedText = rawText.replace("/", "\n")
                                       .replace(Regex("\\n+"), "\n") // Collapse multiple blank lines
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