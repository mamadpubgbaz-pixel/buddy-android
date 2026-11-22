package com.sepand.ai.memory

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln

/** Local hybrid lexical retrieval plus optional on-device Spark semantic reranking. */
object ObsidianMemory {
    private data class Note(val uri: String, val title: String, val text: String, val terms: List<String>, val modified: Long, val size: Long)
    private val cache = LinkedHashMap<String, Note>(128, .75f, true)
    private const val MAX_CACHE_NOTES = 350

    suspend fun search(
        context: Context,
        treeUri: String,
        query: String,
        expandedQueries: String = "",
        maxNotes: Int = 5,
        semanticReranker: (suspend (String) -> String)? = null
    ): String = withContext(Dispatchers.IO) {
        if (treeUri.isBlank() || query.isBlank()) return@withContext ""
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext ""
        val queryText = "$query\n$expandedQueries"
        val queryTerms = tokenize(queryText).distinct().take(100)
        if (queryTerms.isEmpty()) return@withContext ""

        val notes = mutableListOf<Note>()
        val queue = ArrayDeque<DocumentFile>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 2500 && notes.size < 350) {
            val dir = queue.removeFirst()
            for (file in runCatching { dir.listFiles() }.getOrDefault(emptyArray())) {
                visited++
                if (file.isDirectory) {
                    val name = file.name.orEmpty()
                    if (!name.startsWith(".") && name !in setOf(".trash", ".obsidian", "node_modules", ".git") && queue.size < 800) queue.add(file)
                } else if (file.name?.endsWith(".md", true) == true && file.length() in 1L..256_000L) {
                    val key = file.uri.toString()
                    val modified = file.lastModified()
                    val size = file.length()
                    val cached = synchronized(cache) { cache[key]?.takeIf { it.modified == modified && it.size == size } }
                    val note = cached ?: runCatching {
                        val text = context.contentResolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                            val out = StringBuilder()
                            val buffer = CharArray(4096)
                            var total = 0
                            while (total < 48_000) {
                                val count = reader.read(buffer, 0, minOf(buffer.size, 48_000 - total))
                                if (count <= 0) break
                                out.append(buffer, 0, count); total += count
                            }
                            out.toString()
                        }.orEmpty()
                        Note(key, file.name.orEmpty(), text, tokenize(text), modified, size)
                    }.getOrNull() ?: continue
                    if (cached == null) synchronized(cache) {
                        cache[key] = note
                        while (cache.size > MAX_CACHE_NOTES) cache.remove(cache.keys.first())
                    }
                    notes += note
                }
                if (visited >= 2500 || notes.size >= 350) break
            }
        }
        if (notes.isEmpty()) return@withContext "No Markdown notes were found in the selected vault."

        val docFreq = HashMap<String, Int>()
        notes.forEach { note -> note.terms.toSet().forEach { term -> docFreq[term] = (docFreq[term] ?: 0) + 1 } }
        val avgLength = notes.sumOf { it.terms.size }.toDouble() / notes.size.coerceAtLeast(1)
        val ranked = notes.mapNotNull { note ->
            val frequencies = note.terms.groupingBy { it }.eachCount()
            var score = 0.0
            for (term in queryTerms) {
                val tf = frequencies[term] ?: continue
                val df = docFreq[term] ?: 0
                val idf = ln(1.0 + (notes.size - df + 0.5) / (df + 0.5))
                val lengthNorm = 1.2 * (1.0 - 0.75 + 0.75 * note.terms.size / avgLength.coerceAtLeast(1.0))
                score += idf * (tf * 2.2) / (tf + lengthNorm)
            }
            val normalizedTitle = tokenize(note.title).toSet()
            score += queryTerms.count { it in normalizedTitle } * 1.4
            val phrase = query.trim().lowercase().takeIf { it.length >= 5 }
            if (phrase != null && note.text.lowercase().contains(phrase)) score += 2.0
            if (score > 0) Triple(score, note, noteSnippet(note.text, queryTerms)) else null
        }.sortedByDescending { it.first }.take(12)

        if (ranked.isEmpty()) return@withContext "No strongly matching notes found in the selected Obsidian vault."

        // The compact model judges meaning after BM25 narrows the search space. The model never
        // receives vault-wide content and cannot execute instructions from note text.
        val candidates = ranked.mapIndexed { index, (_, note, snippet) ->
            "[${index + 1}] ${note.title}\n${snippet.take(700)}"
        }.joinToString("\n\n")
        val rerankOutput = runCatching { semanticReranker?.invoke(candidates).orEmpty() }.getOrDefault("")
        val selectedIndexes = Regex("\\d+").findAll(rerankOutput).mapNotNull { it.value.toIntOrNull()?.minus(1) }
            .filter { it in ranked.indices }.distinct().take(maxNotes).toList()
        val selected = if (selectedIndexes.isNotEmpty()) selectedIndexes.map { ranked[it] } else ranked.take(maxNotes)
        selected.joinToString("\n\n---\n\n") { (score, note, snippet) ->
            "[${note.title} · lexical ${"%.2f".format(score)}]\n$snippet"
        }
    }

    private fun tokenize(value: String): List<String> {
        val normalized = value.lowercase()
            .replace('ي', 'ی').replace('ك', 'ک').replace('ۀ', 'ه').replace('ة', 'ه')
        return Regex("[\\p{L}\\p{N}_]{2,}").findAll(normalized)
            .map { it.value.take(48) }
            .filterNot { it in STOP_WORDS }
            .toList()
    }

    private fun noteSnippet(text: String, terms: List<String>): String {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        val scored = lines.map { line -> line to terms.count { term -> line.contains(term, ignoreCase = true) } }
            .sortedByDescending { it.second }
        val best = scored.take(4).filter { it.second > 0 }.map { it.first.trim() }
        return (best.ifEmpty { listOf(text.take(700)) }).joinToString("\n").take(1100)
    }

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "that", "this", "from", "your", "you", "are", "was", "were", "have", "has", "had", "not", "but", "what", "when", "where", "how", "why", "can", "could", "should", "would", "please", "about", "into", "their", "them", "then", "than", "there", "here", "هم", "برای", "این", "آن", "های", "هایى", "است", "بود", "هست", "شود", "کرد", "کنم", "کنید", "چرا", "چطور", "چه", "من", "تو", "شما", "یک", "به", "از", "را", "در", "با", "که", "و", "یا", "اما"
    )
}
