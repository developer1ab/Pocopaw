package com.atombits.pocopaw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SearchAugmentationSnippet(
    val title: String,
    val url: String,
    val snippet: String,
    val sourceQuery: String? = null
)

data class SearchAugmentationQueryResult(
    val query: String,
    val snippets: List<SearchAugmentationSnippet>
)

data class SearchAugmentationResult(
    val provider: String,
    val query: String,
    val snippets: List<SearchAugmentationSnippet>,
    val queryResults: List<SearchAugmentationQueryResult> = listOf(
        SearchAugmentationQueryResult(
            query = query,
            snippets = snippets.map { snippet ->
                if (snippet.sourceQuery.isNullOrBlank()) {
                    snippet.copy(sourceQuery = query)
                } else {
                    snippet
                }
            }
        )
    )
) {
    val queries: List<String>
        get() = queryResults.map { result -> result.query }

    fun toPromptSection(): String {
        return buildString {
            appendLine("provider=$provider")
            appendLine("query=$query")
            appendLine("query_count=${queryResults.size}")
            appendLine("result_count=${snippets.size}")
            queryResults.forEachIndexed { queryIndex, queryResult ->
                append("- query_rank=")
                append(queryIndex + 1)
                append(" | query=")
                appendLine(queryResult.query)
                queryResult.snippets.forEachIndexed { index, snippet ->
                    append("  - rank=")
                    append(index + 1)
                    append(" | title=")
                    append(snippet.title)
                    append(" | url=")
                    append(snippet.url)
                    append(" | snippet=")
                    appendLine(snippet.snippet)
                }
            }
        }.trim()
    }

    fun toChatAttribution(maxSources: Int = 5): SearchAttribution {
        return SearchAttribution(
            provider = provider,
            query = query,
            sources = snippets.take(maxSources).map { snippet ->
                SearchAttributionSource(
                    title = snippet.title,
                    url = snippet.url,
                    snippet = snippet.snippet
                )
            }
        )
    }
}

internal fun buildSearchDetailContent(
    queries: List<String>,
    searchScope: List<String>,
    queryResults: List<SearchAugmentationQueryResult> = emptyList(),
    failureMessage: String? = null
): String {
    val listSeparator = UiStrings.resolve(R.string.search_detail_list_separator, ", ")
    val normalizedQueries = queries
        .map { query -> query.trim() }
        .filter { query -> query.isNotBlank() }
    val normalizedScope = searchScope
        .map { scope -> scope.trim() }
        .filter { scope -> scope.isNotBlank() }
    val normalizedFailureMessage = failureMessage
        ?.trim()
        ?.takeIf { message -> message.isNotBlank() }
    val normalizedQueryResults = queryResults.mapNotNull { queryResult ->
        val normalizedQuery = queryResult.query.trim()
        if (normalizedQuery.isBlank()) {
            null
        } else {
            queryResult.copy(
                query = normalizedQuery,
                snippets = queryResult.snippets.filter { snippet ->
                    snippet.title.isNotBlank()
                }
            )
        }
    }

    return buildString {
        if (normalizedQueries.isNotEmpty()) {
            appendLine(UiStrings.resolve(R.string.search_detail_queries_label, "Search queries:"))
            normalizedQueries.forEachIndexed { index, query ->
                append(index + 1)
                appendLine(". $query")
            }
        }

        if (normalizedScope.isNotEmpty()) {
            if (isNotEmpty()) {
                appendLine()
            }
            append(UiStrings.resolve(R.string.search_detail_scope_label, "Validation scope:"))
            append(' ')
            append(normalizedScope.joinToString(listSeparator))
        }

        if (normalizedQueryResults.isNotEmpty()) {
            if (isNotEmpty()) {
                appendLine()
                appendLine()
            }
            appendLine(UiStrings.resolve(R.string.search_detail_progress_label, "Search progress:"))
            normalizedQueryResults.forEach { queryResult ->
                val titles = queryResult.snippets
                    .map { snippet -> snippet.title.trim() }
                    .filter { title -> title.isNotBlank() }
                    .distinct()
                    .take(3)
                append("- ")
                append(queryResult.query)
                append(": ")
                if (titles.isEmpty()) {
                    appendLine(
                        UiStrings.resolve(
                            R.string.search_detail_result_none,
                            "searched, but no stable titles were extracted yet"
                        )
                    )
                } else {
                    append(UiStrings.resolve(R.string.search_detail_result_prefix, "Top hits "))
                    appendLine(titles.joinToString(listSeparator))
                }
            }
        }

        if (normalizedFailureMessage != null) {
            if (isNotEmpty()) {
                appendLine()
                appendLine()
            }
            append(UiStrings.resolve(R.string.search_detail_status_label, "Search status:"))
            append(' ')
            append(normalizedFailureMessage)
        }
    }.trim()
}

class SearchAugmentationClient {

    private val httpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun currentProviderId(): String {
        return when (ProviderProfileRuntime.current().search.provider) {
            SearchProviderKind.GOOGLE_CSE -> GOOGLE_CSE_PROVIDER
            SearchProviderKind.ALIYUN_OPENSEARCH -> OPENSEARCH_PROVIDER
        }
    }

    suspend fun search(query: String): SearchAugmentationResult = search(listOf(query))

    suspend fun search(queries: List<String>): SearchAugmentationResult = withContext(Dispatchers.IO) {
        val runtimeConfig = ProviderProfileRuntime.current().search
        val normalizedQueries = normalizeQueries(queries)
        if (normalizedQueries.isEmpty()) {
            throw IOException("Search augmentation failed: search query was empty.")
        }
        if (!isConfigured(runtimeConfig)) {
            throw IOException("Search augmentation failed: ${runtimeConfig.provider.name.lowercase()} is not configured.")
        }

        val failures = mutableListOf<String>()
        val queryResults = buildList {
            normalizedQueries.forEach { query ->
                runCatching {
                    searchSingleQuery(runtimeConfig, query)
                }.onSuccess { snippets ->
                    if (snippets.isEmpty()) {
                        failures += "$query: no web results were returned"
                    } else {
                        add(
                            SearchAugmentationQueryResult(
                                query = query,
                                snippets = snippets.map { snippet -> snippet.copy(sourceQuery = query) }
                            )
                        )
                    }
                }.onFailure { throwable ->
                    failures += "$query: ${throwable.message ?: throwable::class.java.simpleName}"
                }
            }
        }

        val mergedSnippets = mergeQueryResults(queryResults)
        if (mergedSnippets.isEmpty()) {
            val failureSummary = failures.joinToString("; ").ifBlank { "no web results were returned." }
            throw IOException("Search augmentation failed: $failureSummary")
        }

        SearchAugmentationResult(
            provider = providerId(runtimeConfig.provider),
            query = summarizeQueries(normalizedQueries),
            snippets = mergedSnippets,
            queryResults = queryResults
        )
    }

    private fun searchSingleQuery(
        config: SearchProviderRuntimeConfig,
        query: String
    ): List<SearchAugmentationSnippet> {
        return when (config.provider) {
            SearchProviderKind.ALIYUN_OPENSEARCH -> searchSingleQueryAliyun(config, query)
            SearchProviderKind.GOOGLE_CSE -> searchSingleQueryGoogle(config, query)
        }
    }

    private fun searchSingleQueryAliyun(
        config: SearchProviderRuntimeConfig,
        query: String
    ): List<SearchAugmentationSnippet> {
        val payload = JSONObject()
            .put("query", query)
            .put("query_rewrite", true)
            .put("top_k", 5)
            .put("content_type", "snippet")
            .toString()
        val normalizedEndpoint = config.endpoint.trim().trimEnd('/')
        val request = Request.Builder()
            .url("$normalizedEndpoint/v3/openapi/workspaces/${config.workspace}/web-search/${config.serviceId}")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(payload.toRequestBody(MEDIA_TYPE_JSON))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Search augmentation failed: ${response.code} ${body.take(200)}")
                }
                return parseAliyunOpenSearchResponse(body)
            }
        } catch (ioException: IOException) {
            if (ioException.message.orEmpty().contains("Search augmentation failed", ignoreCase = true)) {
                throw ioException
            }
            throw IOException(
                "Search augmentation failed: ${ioException.message ?: ioException::class.java.simpleName}",
                ioException
            )
        }
    }

    private fun searchSingleQueryGoogle(
        config: SearchProviderRuntimeConfig,
        query: String
    ): List<SearchAugmentationSnippet> {
        val baseUrl = config.endpoint.toHttpUrlOrNull()
            ?: throw IOException("Search augmentation failed: Google search endpoint is invalid.")
        val requestUrl = baseUrl.newBuilder()
            .addQueryParameter("key", config.apiKey)
            .addQueryParameter("cx", config.engineId)
            .addQueryParameter("q", query)
            .addQueryParameter("num", "5")
            .addQueryParameter("hl", config.hl)
            .addQueryParameter("safe", config.safe)
            .addQueryParameter("gl", config.gl)
            .build()

        val request = Request.Builder()
            .url(requestUrl)
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Search augmentation failed: ${response.code} ${body.take(200)}")
                }
                return parseGoogleCseResponse(body)
            }
        } catch (ioException: IOException) {
            if (ioException.message.orEmpty().contains("Search augmentation failed", ignoreCase = true)) {
                throw ioException
            }
            throw IOException(
                "Search augmentation failed: ${ioException.message ?: ioException::class.java.simpleName}",
                ioException
            )
        }
    }

    private fun isConfigured(config: SearchProviderRuntimeConfig): Boolean {
        return when (config.provider) {
            SearchProviderKind.ALIYUN_OPENSEARCH -> {
                config.apiKey.isNotBlank() &&
                    config.endpoint.isNotBlank() &&
                    config.workspace.isNotBlank() &&
                    config.serviceId.isNotBlank()
            }

            SearchProviderKind.GOOGLE_CSE -> {
                config.apiKey.isNotBlank() &&
                    config.endpoint.isNotBlank() &&
                    config.engineId.isNotBlank()
            }
        }
    }

    internal fun parseAliyunOpenSearchResponse(responseBody: String): List<SearchAugmentationSnippet> {
        val root = JSONObject(responseBody)
        val resultArray = root
            .optJSONObject("result")
            ?.optJSONArray("search_result")
            ?: return emptyList()
        return buildList {
            for (index in 0 until resultArray.length()) {
                val item = resultArray.optJSONObject(index) ?: continue
                val title = item.optString("title").trim()
                val url = item.optString("link").trim()
                val snippet = item.optString("snippet").trim().ifBlank {
                    item.optString("content").trim()
                }
                if (title.isBlank() || url.isBlank() || snippet.isBlank()) {
                    continue
                }
                add(
                    SearchAugmentationSnippet(
                        title = title,
                        url = url,
                        snippet = snippet
                    )
                )
                if (size >= 5) {
                    break
                }
            }
        }
    }

    internal fun parseGoogleCseResponse(responseBody: String): List<SearchAugmentationSnippet> {
        val root = JSONObject(responseBody)
        val items = root.optJSONArray("items") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val title = item.optString("title").trim()
                val url = item.optString("link").trim()
                val snippet = item.optString("snippet").trim()
                if (title.isBlank() || url.isBlank() || snippet.isBlank()) {
                    continue
                }
                add(
                    SearchAugmentationSnippet(
                        title = title,
                        url = url,
                        snippet = snippet
                    )
                )
            }
        }
    }

    internal fun mergeQueryResults(
        queryResults: List<SearchAugmentationQueryResult>,
        maxTotalResults: Int = 8
    ): List<SearchAugmentationSnippet> {
        val seenUrls = linkedSetOf<String>()
        val merged = mutableListOf<SearchAugmentationSnippet>()
        for (queryResult in queryResults) {
            for (snippet in queryResult.snippets) {
                val normalizedUrl = normalizeUrl(snippet.url)
                if (!seenUrls.add(normalizedUrl)) {
                    continue
                }
                merged += if (snippet.sourceQuery.isNullOrBlank()) {
                    snippet.copy(sourceQuery = queryResult.query)
                } else {
                    snippet
                }
                if (merged.size >= maxTotalResults) {
                    return merged
                }
            }
        }
        return merged
    }

    internal fun normalizeQueries(
        queries: List<String>,
        maxQueries: Int = 3
    ): List<String> {
        return queries
            .map { query -> query.trim() }
            .filter { query -> query.isNotBlank() }
            .distinctBy { query -> query.lowercase() }
            .take(maxQueries)
    }

    private fun summarizeQueries(queries: List<String>): String {
        if (queries.isEmpty()) {
            return ""
        }
        if (queries.size == 1) {
            return queries.first()
        }
        return queries.joinToString(" | ")
    }

    private fun normalizeUrl(url: String): String {
        return url.trim().trimEnd('/').lowercase()
    }

    private fun providerId(kind: SearchProviderKind): String {
        return when (kind) {
            SearchProviderKind.ALIYUN_OPENSEARCH -> OPENSEARCH_PROVIDER
            SearchProviderKind.GOOGLE_CSE -> GOOGLE_CSE_PROVIDER
        }
    }

    companion object {
        private const val OPENSEARCH_PROVIDER = "aliyun_opensearch_web_search"
        private const val GOOGLE_CSE_PROVIDER = "google_cse_web_search"
        private val MEDIA_TYPE_JSON = "application/json; charset=utf-8".toMediaType()
    }
}