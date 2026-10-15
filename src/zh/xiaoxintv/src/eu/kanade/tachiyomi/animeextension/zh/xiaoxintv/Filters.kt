@file:Suppress("LocalVariableName", "PropertyName")

package eu.kanade.tachiyomi.animeextension.zh.xiaoxintv

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// V2: the site renumbered its categories; configs saved under the old ids are stale.
private const val PREF_KEY_FILTER_CONFIG_PREFIX = "STORED_SEARCH_CONFIG_V2"

open class PathFilter(
    name: String,
    private val beans: Array<out SearchBean>,
) : AnimeFilter.Select<String>(name, beans.map { it.name }.toTypedArray()) {
    val selected
        get() = beans[state]
}

class GroupFilter(
    name: String,
    filters: List<PathFilter>,
) : AnimeFilter.Group<PathFilter>(name, filters)

internal enum class FilterType(
    val title: String,
) {
    TYPE("类型"),
    CLASS("分类"),
    YEAR("年份"),
    LANG("语言"),
    SORT("排序"),
    REGION("地区"),
}

interface SearchBean {
    val name: String
    val ignore: Boolean

    fun toPath(): String
}

@Serializable
data class SearchType(
    override val name: String,
    val id: String,
    override val ignore: Boolean = false,
) : SearchBean {
    constructor(name: String, id: Int) : this(name, "$id")

    override fun toPath() = "/id/$id"
}

internal fun SearchType.toFilter(): PathFilter = PathFilter(name, arrayOf(this))

@Serializable
data class SearchSort(
    override val name: String,
    val by: String,
    override val ignore: Boolean = false,
) : SearchBean {
    override fun toPath() = "/by/$by"
}

@Serializable
data class SearchYear(
    override val name: String,
    override val ignore: Boolean = false,
) : SearchBean {
    override fun toPath() = "/year/$name"
}

@Serializable
data class SearchLang(
    override val name: String,
    override val ignore: Boolean = false,
) : SearchBean {
    override fun toPath() = "/lang/$name"
}

@Serializable
data class SearchClass(
    override val name: String,
    override val ignore: Boolean = false,
) : SearchBean {
    override fun toPath() = "/class/$name"
}

@Serializable
data class SearchRegion(
    override val name: String,
    override val ignore: Boolean = false,
) : SearchBean {
    override fun toPath() = "/area/$name"
}

@Serializable
data class SearchFilterConfig(
    val type: List<SearchType>,
    val category: List<SearchClass> = emptyList(),
    val year: List<SearchYear> = emptyList(),
    val lang: List<SearchLang> = emptyList(),
    val region: List<SearchRegion> = emptyList(),
) {
    fun isEmpty() = type.isEmpty() && category.isEmpty() && year.isEmpty() && lang.isEmpty() && region.isEmpty()
}

private inline fun <reified T> c(): Class<T> = T::class.java

private val searchPriority =
    arrayOf(
        c<SearchRegion>(),
        c<SearchSort>(),
        c<SearchClass>(),
        c<SearchType>(),
        c<SearchLang>(),
        c<SearchYear>(),
    )

internal fun Iterable<SearchBean>.toPath(): String = this
    .asSequence()
    .filterNot { it.ignore }
    .groupBy { it::class.java }
    .flatMap { it.value.subList(it.value.size - 1, it.value.size) }
    .sortedBy {
        searchPriority.indexOf(it::class.java)
    }.joinToString(separator = "") { it.toPath() }
    .removePrefix("/")

private val defaultLangList =
    listOf(
        SearchLang("全部", ignore = true),
        SearchLang("汉语普通话"),
        SearchLang("粤语"),
        SearchLang("英语"),
        SearchLang("日语"),
        SearchLang("韩语"),
    )

private val typeAll = SearchType("全部", "-1", ignore = true)
private val yearAll = SearchYear("全部", ignore = true)
private val regionAll = SearchRegion("全部", ignore = true)

// Fallback until the first visit of each category refreshes these from the site's filter panel.
private val defaultSearchFilterConfig =
    mapOf(
        // anime
        "4" to
            SearchFilterConfig(
                type = listOf(
                    typeAll,
                    SearchType("国产动漫", 40),
                    SearchType("日韩动漫", 41),
                    SearchType("欧美动漫", 42),
                    SearchType("港台动漫", 43),
                    SearchType("海外动漫", 44),
                ),
                year = listOf(yearAll),
                lang = defaultLangList,
            ),
        // movie
        "1" to
            SearchFilterConfig(
                type = listOf(
                    typeAll,
                    SearchType("动作片", 24),
                    SearchType("喜剧片", 25),
                    SearchType("科幻片", 26),
                    SearchType("恐怖片", 27),
                    SearchType("爱情片", 28),
                    SearchType("剧情片", 29),
                    SearchType("战争片", 30),
                    SearchType("动画电影", 31),
                ),
                region = listOf(regionAll),
                year = listOf(yearAll),
                lang = defaultLangList,
            ),
        // tv
        "2" to
            SearchFilterConfig(
                type = listOf(
                    typeAll,
                    SearchType("国产剧", 32),
                    SearchType("欧美剧", 33),
                    SearchType("香港剧", 34),
                    SearchType("韩国剧", 35),
                    SearchType("台湾剧", 36),
                    SearchType("日本剧", 37),
                    SearchType("海外剧", 38),
                ),
                year = listOf(yearAll),
                lang = defaultLangList,
            ),
        // variety show
        "3" to
            SearchFilterConfig(
                type = listOf(
                    typeAll,
                    SearchType("大陆综艺", 45),
                    SearchType("港台综艺", 46),
                    SearchType("日韩综艺", 47),
                    SearchType("欧美综艺", 48),
                ),
                year = listOf(yearAll),
                lang = defaultLangList,
            ),
        // documentary
        "21" to
            SearchFilterConfig(
                type = emptyList(),
                region = listOf(regionAll),
                year = listOf(yearAll),
                lang = defaultLangList,
            ),
    )

private fun findDefaultSearchFilterConfig(majorTypeId: String): SearchFilterConfig = defaultSearchFilterConfig.getOrElse(majorTypeId) {
    SearchFilterConfig(
        listOf(typeAll),
    )
}

private fun genFilterConfigKey(majorTypeId: String): String = PREF_KEY_FILTER_CONFIG_PREFIX + "_$majorTypeId"

internal val defaultMajorSearchTypeSet =
    arrayOf(
        SearchType("动漫", 4),
        SearchType("电影", 1),
        SearchType("电视剧", 2),
        SearchType("综艺", 3),
        SearchType("纪录片", 21),
    )

internal val defaultSortTypeSet =
    arrayOf(
        SearchSort("时间", "time", ignore = true),
        SearchSort("人气", "hits"),
        SearchSort("评分", "score"),
    )

fun SharedPreferences.findSearchFilterConfig(
    majorTypeId: String,
    json: Json,
): SearchFilterConfig {
    // check shared preferences
    return getString(genFilterConfigKey(majorTypeId), null)?.let { json.decodeFromString(it) }
        ?: findDefaultSearchFilterConfig(majorTypeId)
}

fun SharedPreferences.saveSearchFilterConfig(
    majorTypeId: String,
    searchFilterConfig: SearchFilterConfig,
    json: Json,
) {
    edit()
        .putString(genFilterConfigKey(majorTypeId), json.encodeToString(searchFilterConfig))
        .apply()
}
