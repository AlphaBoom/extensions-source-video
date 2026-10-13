package eu.kanade.tachiyomi.animeextension.zh.cycity

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import java.util.Calendar

open class SelectFilter(
    name: String,
    private val options: Array<Pair<String, String>>,
) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val selected get() = options[state].second
}

// Zone ids and categories come from /api/video-zones.
class ZoneFilter : SelectFilter("频道", arrayOf("全部" to "", "TV番组" to "1", "剧场番组" to "2"))

class CategoryFilter :
    SelectFilter(
        "题材",
        arrayOf(
            "全部", "原创", "漫画改", "小说改", "游戏改", "异世界", "特摄", "热血", "穿越",
            "奇幻", "战斗", "搞笑", "日常", "科幻", "治愈", "校园", "泡面", "恋爱",
            "后宫", "少女", "百合", "魔法", "冒险", "历史", "架空", "机战", "运动",
            "励志", "音乐", "推理", "社团", "智斗", "催泪", "美食", "偶像", "乙女",
            "职场",
        ).map { it to if (it == "全部") "" else it }.toTypedArray(),
    )

class YearFilter :
    SelectFilter(
        "年份",
        Calendar.getInstance().get(Calendar.YEAR).let { current ->
            arrayOf("全部" to "") + (current downTo 1980).map { "$it" to "$it" }
        },
    )

class SortFilter : SelectFilter("排序", arrayOf("更新时间" to "update_time", "热度" to "hits", "评分" to "score"))
