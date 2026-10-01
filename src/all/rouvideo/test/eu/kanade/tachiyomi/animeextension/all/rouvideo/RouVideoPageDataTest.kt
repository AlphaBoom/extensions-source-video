package eu.kanade.tachiyomi.animeextension.all.rouvideo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RouVideoPageDataTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String) = RouVideoPageData.parse(Jsoup.parse(body), json)

    @Test
    fun readsReferencesAndEscapedStrings() {
        val document = """<script>
            ${'$'}_TSR.router=(${'$'}R=>${'$'}R[0]={matches:${'$'}R[1]=[
              {l:null},{l:${'$'}R[2]={videos:${'$'}R[3]=[{id:"test",name:"a\"}b\\c"}],
              again:${'$'}R[3],pageNum:1,totalPage:2,enabled:!0,missing:void 0}}
            ]})(${'$'}R["tsr"]);document.currentScript.remove()
        </script>"""
        val result = parse(document)
        assertEquals(result["videos"], result["again"])
        assertEquals("""{"videos":[{"id":"test","name":"a\"}b\\c"}],"again":[{"id":"test","name":"a\"}b\\c"}],"pageNum":1,"totalPage":2,"enabled":true,"missing":null}""", result.toString())
    }

    @Test
    fun readsCombinedStreamScript() {
        val result = parse("""<script>ignored();${'$'}_TSR.router=(${'$'}R=>${'$'}R[0]={matches:[{l:{value:-1.25e2,ok:!1}}]})(${'$'}R["tsr"]);ignored()</script>""")
        assertEquals(json.parseToJsonElement("""{"value":-1.25e2,"ok":false}"""), result)
    }

    @Test
    fun readsLegacyNextData() {
        assertEquals(
            json.parseToJsonElement("""{"videos":[]}"""),
            parse("""<script id="__NEXT_DATA__">{"props":{"pageProps":{"videos":[]}}}</script>"""),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsExecutableLoaderValues() {
        parse("""<script>${'$'}_TSR.router=(${'$'}R=>${'$'}R[0]={matches:[{l:{value:alert(1)}}]})(${'$'}R["tsr"])</script>""")
    }

    @Test(expected = IllegalStateException::class)
    fun reportsMissingData() {
        parse("<html><title>Site Unavailable</title></html>")
    }

    @Test
    fun capturedResponsesMatchWebsiteHydration() {
        val directory = System.getenv("ROUVIDEO_FIXTURES")?.let(::File)
        assumeTrue("Live response artifacts were not provided", directory?.isDirectory == true)
        directory!!
        val pages = listOf("home", "latest-page-1", "latest-page-2", "popular", "categories", "search-home", "search-results", "detail", "ordinary-detail", "tag")
        for (page in pages) {
            val actual = parse(File(directory, "$page.body").readText())
            val expected = json.parseToJsonElement(File(directory, "$page.loader.json").readText())
            assertEquals(page, expected, actual)
            when (page) {
                "home" -> assertTrue(json.decodeFromJsonElement<RouVideoDto.HotVideoList>(actual).toAnimePage(null).animes.isNotEmpty())
                "categories" -> assertTrue(json.decodeFromJsonElement<RouVideoDto.TagList>(actual).toTagList().isNotEmpty())
                "detail", "ordinary-detail" -> {
                    val detail = json.decodeFromJsonElement<RouVideoDto.VideoDetails>(actual)
                    assertTrue(detail.video.id.isNotEmpty())
                    assertTrue(detail.ev != null)
                    assertEquals(detail.video.id, detail.video.toEpisode().url)
                }
                "search-home" -> assertTrue(json.decodeFromJsonElement<RouVideoDto.VideoList>(actual).hotSearches.isNotEmpty())
                else -> assertTrue(json.decodeFromJsonElement<RouVideoDto.VideoList>(actual).toAnimePage().animes.isNotEmpty())
            }
        }
        val first = json.decodeFromJsonElement<RouVideoDto.VideoList>(parse(File(directory, "latest-page-1.body").readText()))
        val second = json.decodeFromJsonElement<RouVideoDto.VideoList>(parse(File(directory, "latest-page-2.body").readText()))
        assertTrue(first.toAnimePage().hasNextPage)
        assertTrue(first.videos.map { it.id }.intersect(second.videos.map { it.id }.toSet()).isEmpty())
    }
}
