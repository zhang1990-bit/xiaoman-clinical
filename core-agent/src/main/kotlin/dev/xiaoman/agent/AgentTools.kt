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
    const val TASK_ROSTER = "task_roster"
    const val RECORD_SAVE = "record_save"
    const val TEMPLATE_READ = "template_read"
    const val TEMPLATE_SAVE = "template_save"
    const val PATIENT_LIST = "patient_list"
    const val PATIENT_UPDATE = "patient_update"
    const val PATIENT_ARCHIVE = "patient_archive"
    const val PATIENT_ADMIT = "patient_admit"
    const val DAY_CLOSE = "day_close"
    const val RECORD_LIST = "record_list"
    const val RECORD_READ = "record_read"

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            MEMORY_SEARCH,
            "在用户的记忆笔记中全文搜索（支持中文）。默认只返回仍在有效期内的条目；"
                + "过期条目不注入提示词，需要翻旧账时才传 includeExpired。",
            schema(
                listOf("query"),
                "query" to str("搜索词"),
                "limit" to int("最多返回条数，默认 5"),
                "includeExpired" to str("填 true 时连过期条目一起返回，默认 false"),
            ),
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
                "kind" to str(
                    "记忆层级：patient（患者相关，默认 30 天后自动失效）/ preference（个人偏好，永久）/ "
                        + "howto（操作经验，永久）/ template（模板，永久）。患者信息一律用 patient。",
                ),
                "keepDays" to int("可选，多少天后不再注入提示词；0 表示永久。不填时按 kind 取默认（patient=30，其余永久）"),
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
        ToolSpec(
            TASK_ROSTER,
            "查房前读 HIS 病房表：把图里的床号+姓名全部传进来，会存成今天的病号名单，"
                + "并与上一次相比报出新入、出院、调床，同时把待办的床号对齐。"
                + "不传 beds 时返回已存的名单，用来回答「12 床是谁」「现在管几个病号」。"
                + "只传床号和姓名，绝不要传病案号、登记号、年龄、诊断。"
                + "apply=true 时把能确定的调床直接改掉（同一个人换了床）；同床不同姓、同名多人只报告不动。",
            schema(
                emptyList(),
                "beds" to arr(
                    "HIS 表里读到的床位，一行一个；留空表示只查已存的名单",
                    obj(
                        "床号 + 姓名",
                        "bed" to str("床号，如 12 或 A63"),
                        "name" to str("姓名，照原样抄，屏幕上的星号保留，如 李**"),
                    ),
                ),
                "apply" to bool("true 表示把能确定的调床直接改掉，默认 false 只报告"),
            ),
        ),
        ToolSpec(
            RECORD_SAVE,
            "把已经定稿的手术记录正文写成 TXT 存到手机上。"
                + "必须等用户明确说「出 / 保存 / 可以」之后才调用；用户还在看稿、还在改的时候，"
                + "把正文直接贴在聊天里给他看，不要落文件。存完把路径告诉用户，不要再把全文贴一遍。",
            schema(
                listOf("title", "content"),
                "title" to str("文件名主体，如 20261118_开腹肝癌切除；不要带 .txt"),
                "content" to str("手术记录正文，段落之间用一个空行分隔"),
            ),
        ),
        ToolSpec(
            DAY_CLOSE,
            "收工清算。用户说「收工」「今天还有什么没弄」时先不带参数调用，把清单原样报给他；"
                + "他逐条答复后，再带 decisions 落实。每条必须归到办完 / 延后 / 不需要，不许放着不管。",
            schema(
                emptyList(),
                "decisions" to arr(
                    "逐条处理结果；不带则只列清单",
                    obj(
                        "一条决定",
                        "id" to str("任务 id，从清单里拿"),
                        "action" to str("done=办完 / later=今天先不推 / skip=不需要"),
                    ),
                ),
            ),
        ),
        ToolSpec(
            TEMPLATE_SAVE,
            "把用户给的术式模板存进模板库（或更新已有模板）。"
                + "用户发模板时，先整理成「术式名 → 常规步骤 + 常用句式」的 markdown，再调用本工具。"
                + "同名模板再存会自动升版本，旧版留 .bak。别名（LC、胆囊切除）一并存进去，下次按任一叫法都能命中。",
            schema(
                listOf("name", "content"),
                "name" to str("术式名，如 解剖性肝切除；就是文件名"),
                "content" to str("模板正文（markdown），不要带 frontmatter，工具会自己加"),
                "aliases" to str("可选，别名，逗号分隔，如 LC,胆囊切除,腹腔镜胆囊切除术"),
            ),
        ),
        ToolSpec(
            PATIENT_LIST,
            "列出患者。默认列在院患者（含术前、术后），说「归档的」时列已归档的。"
                + "每行带 id、床号、姓名、阶段、诊断、缺口数，缺口明细用 patient_get 或直接问。",
            schema(emptyList(), "scope" to str("留空=在院；填「归档」=已归档")),
        ),
        ToolSpec(
            PATIENT_UPDATE,
            "新建或更新一位患者。按 id 或床号定位；找不到且有姓名+床号时才新建。"
                + "用户口述「12床今天做了胆囊切除」「19床是肿瘤，要上MDT」「检查齐了」时用这个。",
            schema(
                emptyList(),
                "id" to str("患者 id，如 p-0007；不知道就传床号"),
                "bed" to str("床号，如 12"),
                "name" to str("姓名（新建时必填）"),
                "diagnosis" to str("诊断"),
                "stage" to str("阶段：admitted / preop / postop"),
                "surgeryDate" to str("手术日期 yyyy-MM-dd，填了自动进术后"),
                "surgeryName" to str("术式"),
                "tumor" to str("填 true/false，是否肿瘤（决定要不要推 MDT）"),
                "mdt" to str("MDT 状态：待排 / 已排 11-22 / 已讨论"),
                "conservative" to str("填 true 表示改保守治疗：不进工作台的「待手术」；填 false 恢复"),
                "preopDone" to str("术前检查是否完善，填 true 关闭该提醒"),
                "consult" to str("要请的会诊科室，如 心内科；会生成「请心内科会诊」待办，做完点掉才算已提请"),
                "plannedSurgeryDate" to str("排期手术日 yyyy-MM-dd，用于工作台「待手术/明日手术」"),
                "plannedSurgeryName" to str("计划术式"),
                "plannedOrder" to str("台次，如 第1台"),
                "plannedDischarge" to str("拟出院日 yyyy-MM-dd"),
            ),
        ),
        ToolSpec(
            PATIENT_ADMIT,
            "把一张「今日入院患者」照片里读到的人批量建档。已存在（同名+同床）的只补诊断，不重复建。"
                + "只传床号和姓名，不要传病案号、登记号、年龄。",
            schema(
                listOf("beds"),
                "beds" to arr(
                    "入院患者，一人一条",
                    obj(
                        "床号 + 姓名",
                        "bed" to str("床号，如 12 或 加65"),
                        "name" to str("姓名"),
                        "diagnosis" to str("诊断，可选"),
                    ),
                ),
            ),
        ),
        ToolSpec(
            PATIENT_ARCHIVE,
            "把患者移出在院列表（归档）。查房单里没再出现、或用户说「X 床出院了」时用。"
                + "归档后默认隐藏，可恢复。",
            schema(
                emptyList(),
                "id" to str("患者 id"),
                "bed" to str("床号"),
                "reason" to str("归档原因，如 出院 / 名单中未出现"),
            ),
        ),
        ToolSpec(
            RECORD_LIST,
            "列出手机里已经生成的手术记录（按时间倒序）。开工前先看一眼：今天做了几台、上一台叫什么，"
                + "避免把两台的信息串在一起。对话历史会被裁剪，磁盘上的记录不会。",
            schema(emptyList()),
        ),
        ToolSpec(
            RECORD_READ,
            "读回一份已生成的手术记录全文。用户问「上一台怎么写的」「刚才那台改哪了」时用它，"
                + "不要凭对话记忆复述。",
            schema(listOf("name"), "name" to str("文件名，如 20261119_1_开腹肝癌切除，可省略 .txt")),
        ),
        ToolSpec(
            TEMPLATE_READ,
            "查手机里的手术记录模板。不传 name 时返回有哪些模板；传 name 时返回该模板全文。"
                + "写记录之前先查一次，看有没有这个术式的骨架可以套。",
            schema(
                emptyList(),
                "name" to str("模板名，如 解剖性肝切除；留空表示列出全部"),
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
    private fun bool(description: String) = buildJsonObject { put("type", "boolean"); put("description", description) }
    private fun arr(description: String, items: JsonObject) = buildJsonObject {
        put("type", "array"); put("description", description); put("items", items)
    }

    private fun obj(description: String, vararg props: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object"); put("description", description)
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { props.forEach { (k, _) -> add(k) } }
    }
}
