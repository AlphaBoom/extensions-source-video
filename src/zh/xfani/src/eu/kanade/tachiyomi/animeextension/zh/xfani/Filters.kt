package eu.kanade.tachiyomi.animeextension.zh.xfani

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import java.util.Calendar

abstract class SelectFilter(name: String, private val options: Array<Pair<String, String>>) :
    AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val selected
        get() = options[state].second
}

class FormatFilter :
    SelectFilter(
        "形式",
        arrayOf(
            "全部" to "",
            "TV 动画" to "tv",
            "剧场版" to "movie",
            "OVA" to "ova",
            "OAD" to "oad",
            "Web 动画" to "web",
            "其他" to "other",
        ),
    )

class StatusFilter :
    SelectFilter(
        "状态",
        arrayOf(
            "全部" to "",
            "连载中" to "false",
            "完结" to "true",
        ),
    )

class YearFilter :
    SelectFilter(
        "年份",
        buildList {
            add("全部" to "")
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            for (year in currentYear downTo OLDEST_LISTED_YEAR) {
                add("$year" to "eq.$year")
            }
            add("更早" to "lt.$OLDEST_LISTED_YEAR")
        }.toTypedArray(),
    ) {
    companion object {
        const val OLDEST_LISTED_YEAR = 2007
    }
}

class TagFilter :
    SelectFilter(
        "标签",
        arrayOf(
            "全部", "漫画改", "小说改", "奇幻", "战斗", "原创", "恋爱", "校园", "日常", "百合",
            "后宫", "科幻", "游戏改", "悬疑", "冒险", "穿越", "音乐", "运动", "机战", "喜剧",
            "少年向", "推理", "少女向", "职场", "美食", "青年向",
        ).mapIndexed { index, tag -> tag to if (index == 0) "" else tag }.toTypedArray(),
    )

class SortFilter(state: Int = 0) :
    SelectFilter(
        "排序",
        arrayOf(
            "热度" to "view_count",
            "最近更新" to "updated_at",
            "评分" to "bangumi_score",
            "最新上映" to "release_date",
            "标题" to "title",
        ),
    ) {
    init {
        this.state = state
    }
}
