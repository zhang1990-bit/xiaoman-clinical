package dev.xiaoman.agent

import dev.xiaoman.api.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Tool definitions offered to the chat model. */
object AgentTools {
    const val MEMORY_SEARCH = "memory_search"
    const val MEMORY_READ = "memory_read"
    const val MEMORY_WRITE = "memory_write"
    const val MEMORY_FORGET = "memory_forget"
    const val LIST_SKILLS = "list_skills"
    const val REMINDER_SET = "reminder_set"
    const val PHONE_TASK = "phone_task"
    const val TASK_ADD = "task_add"
    const val TASK_LIST = "task_list"
    const val TASK_DONE = "task_done"
    const val TASK_UPDATE = "task_update"

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            MEMORY_SEARCH, "在用户的记忆笔记中全文搜索（支持中文）。",
            schema(listOf("query"), "query" to str("搜索词"), "limit" to int("最多返回条数，默认 5")),
        ),
        ToolSpec(
            MEMORY_READ, "读取一条记忆笔记的全文。",
            schema(listOf("identifier"), "identifier" to str("笔记的 permalink（如 places/家）或标题")),
        ),
        ToolSpec(
            MEMORY_WRITE,
            "新建或更新一条记忆笔记；已有同名笔记时追加观察和关系（相同内容不会重复）。",
            schema(
                listOf("title", "observations"),
                "title" to str("笔记标题，如 家"),
                "permalink" to str("可选，路径形式的唯一键，如 places/家；默认用标题"),
                "type" to str("可选，笔记类型，如 place、person、preference；默认 note"),
                "observations" to arr(
                    "观察条目",
                    schema(
                        listOf("category", "content"),
                        "category" to str("类别，如 address、preference"),
                        "content" to str("内容"),
                        "tags" to arr("标签，不带 #", str("标签")),
                        "context" to str("可选，来源或补充说明"),
                    ),
                ),
                "relations" to arr(
                    "可选，与其他笔记的关系",
                    schema(listOf("type", "target"), "type" to str("关系类型，如 lives_in"), "target" to str("目标笔记标题")),
                ),
            ),
        ),
        ToolSpec(
            MEMORY_FORGET, "删除一条记忆笔记（真正删除文件）。仅在用户明确要求忘记时使用。",
            schema(listOf("identifier"), "identifier" to str("笔记的 permalink 或标题")),
        ),
        ToolSpec(LIST_SKILLS, "列出所有可用的手机操作技能。", schema(emptyList())),
        ToolSpec(
            REMINDER_SET,
            "设置一条定时提醒：到点后小满会在手机上弹出通知。",
            schema(
                listOf("text", "at"),
                "text" to str("提醒内容，如 喝水、带伞"),
                "at" to str("触发时间，ISO-8601 本地时间（设备时区，不带时区后缀），如 2026-11-03T08:00"),
            ),
        ),
        ToolSpec(
            PHONE_TASK, "在用户的手机上完成一个操作任务，返回结果摘要。",
            schema(
                listOf("goal"),
                "goal" to str("具体目标，包含关键参数，如 用高德打车回家（北京市海淀区某某路 1 号），选快车"),
                "skill" to str("可选，使用的技能名"),
            ),
        ),
        ToolSpec(
            TASK_ADD,
            "记录查房待办，一条或一批。用户一句话提到多个患者或几件事时，拆成多条一次传入。"
                + "只记「要确认什么」，不要记医嘱、剂量或检查结果数值。",
            schema(
                listOf("items"),
                "items" to arr(
                    "待办列表",
                    schema(
                        listOf("bed", "name", "item", "until"),
                        "bed" to str("床号，只填数字，如 12"),
                        "name" to str("患者姓名，如 李xx"),
                        "item" to str("要确认什么，如 术前检查 / 会诊结果 / 出院首页"),
                        "until" to str("有效期最后一天，yyyy-MM-dd，过期后不再提醒"),
                        "note" to str("可选，用户原话里的补充，如 胆囊结石；原样保留，不要扩写成诊断"),
                        "rule" to str("可选：daily（每天四个时点，默认）或 monthly"),
                        "days" to arr("rule=monthly 时每月的哪几天，如 [10,20,30]", int("日")),
                        "anchor" to str("可选：surgery（术后第 N 天）、discharge（出院时）、date（指定日期）；不填=立即开始"),
                        "offsetDays" to int("可选，锚点后第几天；术后 3 天填 3，默认 0"),
                        "date" to str("可选，anchor=date 时的日期 yyyy-MM-dd"),
                    ),
                ),
            ),
        ),
        ToolSpec(
            TASK_LIST,
            "查看待办。scope=today 看今天该提醒的，all 看全部未办，expired 看过期未确认，bed 看某位患者。",
            schema(
                emptyList(),
                "scope" to str("today / all / expired / bed，默认 today"),
                "bed" to str("scope=bed 时的床号"),
            ),
        ),
        ToolSpec(
            TASK_DONE,
            "把待办标记为办完，不再提醒。给 id 关一条；给 bed（可加 keyword）关该患者相关的几条。",
            schema(
                emptyList(),
                "id" to str("待办 id，单条关闭时用"),
                "bed" to str("床号，批量关闭时用"),
                "keyword" to str("可选，事项关键词，如 术前；只关命中关键词的"),
                "reason" to str("可选，关闭原因，如 已排手术"),
            ),
        ),
        ToolSpec(
            TASK_UPDATE,
            "修改待办：患者调床、延期、改事项、记录手术或出院已发生、当天不再提醒。",
            schema(
                emptyList(),
                "id" to str("待办 id；不填则按 fromBed 或 bed 批量处理"),
                "fromBed" to str("原床号，改床时用"),
                "bed" to str("新床号；只填 bed 表示按该床批量处理"),
                "until" to str("可选，新的有效期 yyyy-MM-dd"),
                "item" to str("可选，新的事项名称"),
                "anchorDate" to str("可选，手术日或出院日 yyyy-MM-dd；填了表示锚点已发生，等待中的待办开始计时"),
                "state" to str("可选：active / done"),
                "silentToday" to str("可选，填 true 表示用户说「看过了」，今天剩下的时点不再推这条"),
            ),
        ),
    )

    private fun schema(required: List<String>, vararg props: Pair<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { required.forEach { add(it) } }
    }

    private fun str(description: String) = buildJsonObject { put("type", "string"); put("description", description) }
    private fun int(description: String) = buildJsonObject { put("type", "integer"); put("description", description) }
    private fun arr(description: String, items: JsonObject) = buildJsonObject {
        put("type", "array"); put("description", description); put("items", items)
    }
}
