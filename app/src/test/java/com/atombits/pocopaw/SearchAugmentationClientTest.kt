package com.atombits.pocopaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAugmentationClientTest {

    @Test
    fun parseAliyunOpenSearchResponse_extractsTopResults() {
        val client = SearchAugmentationClient()
        val response = """
            {
              "result": {
                "search_result": [
                  {
                    "title": "Alpha result",
                    "link": "https://example.com/alpha",
                    "snippet": "Alpha snippet from the web."
                  },
                  {
                    "title": "Beta result",
                    "link": "https://example.com/beta",
                    "snippet": "Beta snippet from the web."
                  }
                ]
              }
            }
        """.trimIndent()

        val snippets = client.parseAliyunOpenSearchResponse(response)

        assertEquals(2, snippets.size)
        assertEquals("Alpha result", snippets[0].title)
        assertEquals("https://example.com/alpha", snippets[0].url)
        assertEquals("Alpha snippet from the web.", snippets[0].snippet)
    }

    @Test
    fun searchAugmentationResult_toPromptSection_serializesProviderQueryAndSnippets() {
        val section = SearchAugmentationResult(
            provider = "aliyun_opensearch_web_search",
            query = "latest flashlight prices",
            snippets = listOf(
                SearchAugmentationSnippet(
                    title = "Alpha result",
                    url = "https://example.com/alpha",
                    snippet = "Alpha snippet"
                )
            )
        ).toPromptSection()

        assertTrue(section.contains("provider=aliyun_opensearch_web_search"))
        assertTrue(section.contains("query=latest flashlight prices"))
    assertTrue(section.contains("query_count=1"))
        assertTrue(section.contains("rank=1 | title=Alpha result"))
    }

  @Test
  fun searchAugmentationResult_toChatAttribution_limitsSources() {
        val attribution = SearchAugmentationResult(
      provider = "aliyun_opensearch_web_search",
      query = "latest flashlight prices",
      snippets = listOf(
        SearchAugmentationSnippet("Alpha result", "https://example.com/alpha", "Alpha snippet"),
        SearchAugmentationSnippet("Beta result", "https://example.com/beta", "Beta snippet"),
        SearchAugmentationSnippet("Gamma result", "https://example.com/gamma", "Gamma snippet"),
        SearchAugmentationSnippet("Delta result", "https://example.com/delta", "Delta snippet")
      )
        ).toChatAttribution(maxSources = 3)

        assertEquals("aliyun_opensearch_web_search", attribution.provider)
        assertEquals("latest flashlight prices", attribution.query)
        assertEquals(3, attribution.sources.size)
        assertEquals("Alpha result", attribution.sources.first().title)
        assertEquals("Gamma result", attribution.sources.last().title)
  }

  @Test
  fun searchAugmentationResult_toChatAttribution_defaultsToFiveSources() {
        val attribution = SearchAugmentationResult(
      provider = "aliyun_opensearch_web_search",
      query = "latest flashlight prices",
      snippets = listOf(
        SearchAugmentationSnippet("Alpha result", "https://example.com/alpha", "Alpha snippet"),
        SearchAugmentationSnippet("Beta result", "https://example.com/beta", "Beta snippet"),
        SearchAugmentationSnippet("Gamma result", "https://example.com/gamma", "Gamma snippet"),
        SearchAugmentationSnippet("Delta result", "https://example.com/delta", "Delta snippet"),
        SearchAugmentationSnippet("Epsilon result", "https://example.com/epsilon", "Epsilon snippet"),
        SearchAugmentationSnippet("Zeta result", "https://example.com/zeta", "Zeta snippet")
      )
        ).toChatAttribution()

        assertEquals(5, attribution.sources.size)
        assertEquals("Alpha result", attribution.sources.first().title)
        assertEquals("Epsilon result", attribution.sources.last().title)
  }

  @Test
  fun normalizeQueries_trimsDeduplicatesAndCapsList() {
    val client = SearchAugmentationClient()

    val queries = client.normalizeQueries(
      listOf(
        "  alpha  ",
        "ALPHA",
        "beta",
        "gamma",
        "delta"
      )
    )

    assertEquals(listOf("alpha", "beta", "gamma"), queries)
  }

  @Test
  fun mergeQueryResults_deduplicatesUrlsAndCapsTotalResults() {
    val client = SearchAugmentationClient()

    val merged = client.mergeQueryResults(
      queryResults = listOf(
        SearchAugmentationQueryResult(
          query = "alpha",
          snippets = listOf(
            SearchAugmentationSnippet("Alpha 1", "https://example.com/shared", "A1"),
            SearchAugmentationSnippet("Alpha 2", "https://example.com/unique-a", "A2")
          )
        ),
        SearchAugmentationQueryResult(
          query = "beta",
          snippets = listOf(
            SearchAugmentationSnippet("Beta 1", "https://example.com/shared/", "B1"),
            SearchAugmentationSnippet("Beta 2", "https://example.com/unique-b", "B2")
          )
        )
      ),
      maxTotalResults = 3
    )

    assertEquals(3, merged.size)
    assertEquals(
      listOf(
        "https://example.com/shared",
        "https://example.com/unique-a",
        "https://example.com/unique-b"
      ),
      merged.map { snippet -> snippet.url }
    )
    assertEquals(listOf("alpha", "alpha", "beta"), merged.map { snippet -> snippet.sourceQuery })
  }

  @Test
  fun searchAugmentationResult_toPromptSection_groupsQueryResults() {
    val section = SearchAugmentationResult(
      provider = "aliyun_opensearch_web_search",
      query = "alpha | beta",
      snippets = listOf(
        SearchAugmentationSnippet("Alpha result", "https://example.com/alpha", "Alpha snippet", sourceQuery = "alpha"),
        SearchAugmentationSnippet("Beta result", "https://example.com/beta", "Beta snippet", sourceQuery = "beta")
      ),
      queryResults = listOf(
        SearchAugmentationQueryResult(
          query = "alpha",
          snippets = listOf(
            SearchAugmentationSnippet("Alpha result", "https://example.com/alpha", "Alpha snippet", sourceQuery = "alpha")
          )
        ),
        SearchAugmentationQueryResult(
          query = "beta",
          snippets = listOf(
            SearchAugmentationSnippet("Beta result", "https://example.com/beta", "Beta snippet", sourceQuery = "beta")
          )
        )
      )
    ).toPromptSection()

    assertTrue(section.contains("query_count=2"))
    assertTrue(section.contains("query_rank=1 | query=alpha"))
    assertTrue(section.contains("query_rank=2 | query=beta"))
  }

  @Test
  fun buildSearchDetailContent_includesQueriesScopeAndQueryTitles() {
    val detail = buildSearchDetailContent(
      queries = listOf(" 手机A 评测 ", "手机B 评测"),
      searchScope = listOf("价格", "续航", "拍照"),
      queryResults = listOf(
        SearchAugmentationQueryResult(
          query = "手机A 评测",
          snippets = listOf(
            SearchAugmentationSnippet("Alpha review", "https://example.com/a", "A"),
            SearchAugmentationSnippet("Battery comparison", "https://example.com/b", "B")
          )
        ),
        SearchAugmentationQueryResult(
          query = "手机B 评测",
          snippets = listOf(
            SearchAugmentationSnippet("Camera comparison", "https://example.com/c", "C")
          )
        )
      )
    )

    assertTrue(detail.contains("搜索查询："))
    assertTrue(detail.contains("1. 手机A 评测"))
    assertTrue(detail.contains("2. 手机B 评测"))
    assertTrue(detail.contains("核验范围：价格、续航、拍照"))
    assertTrue(detail.contains("- 手机A 评测：优先看到 Alpha review、Battery comparison"))
    assertTrue(detail.contains("- 手机B 评测：优先看到 Camera comparison"))
  }

  @Test
  fun buildSearchDetailContent_reportsFailureWithoutResults() {
    val detail = buildSearchDetailContent(
      queries = listOf("手机A 评测"),
      searchScope = listOf("续航"),
      failureMessage = "Search augmentation failed: no web results were returned."
    )

    assertTrue(detail.contains("搜索查询："))
    assertTrue(detail.contains("核验范围：续航"))
    assertTrue(detail.contains("搜索状态：Search augmentation failed: no web results were returned."))
  }
}