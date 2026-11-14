# 临床秘书（Clinical Secretary）

一个跑在 Android 手机上的**查房待办助手**：你把查房后要盯的事用一句话说出来，它记下来，每天 08:00 / 11:00 / 14:00 / 17:00 各推一条汇总通知，直到你说办完。

> 本项目是开源项目 **[小满 Xiaoman](https://github.com/aduoge/xiaoman)**（作者 [@aduoge](https://github.com/aduoge)，MIT 协议）的进一步修改版，针对临床查房场景重做。
> 上游项目的完整功能说明见 [README.upstream.md](README.upstream.md)。本分支的修改同样以 MIT 协议发布，详见 [NOTICE.md](NOTICE.md)。

![version](https://img.shields.io/badge/version-1.0.0-blue) ![minSdk](https://img.shields.io/badge/minSdk-30-green) ![license](https://img.shields.io/badge/license-MIT-lightgrey)

## 它解决什么问题

查房时脑子里的待办，转头就忘：12 床术前检查还没回、11 床该换药了、1/5/10 床今天出院要整理首页、8 床请的心内科会诊还没看、明天要给 3 床查血……

纸笔会丢，备忘录不会响。这个助手只做一件事：**记住、到点提醒、办完关掉**。

```
你：新入 12 床李xx，胆囊结石，待手术
它：12床 李xx｜术前检查｜每天 4 次，直到排手术    ← 复述一行，你说"对"才写入

你：给 12、11 床换药
它：12床 李xx｜换药｜今天起每天 4 次
    11床 王xx｜换药｜今天起每天 4 次            ← 一句话拆成两条
```

每天四个时点，一条聚合通知列出所有该关注的事。没回应，下一个时点继续响；说「看过了」当天静默、次日恢复；说「办完了」关闭。

## 这个分支加了什么

| 能力 | 说明 |
|---|---|
| **待办数据模型** | `core-api/Tasks.kt`：床号 / 姓名 / 事项 / 提醒规则 / 锚点 / 有效期 / 静默日期 |
| **落盘存储** | `core-tasks/`：JSON 原子写、损坏文件隔离、按月归档成 Markdown |
| **四个时点推送** | `AlarmManager.setAndAllowWhileIdle` + 开机重排，进程被回收也能响；一条聚合通知，不刷屏 |
| **五个工具** | `task_add` / `task_list` / `task_done` / `task_update` / `task_roster` |
| **锚点提醒** | 「术后 3 天提醒早吃利胆药」「出院时提醒」——等手术日/出院日到了才激活 |
| **调床不串人** | `patientKey` 做稳定身份键，换床只改 `bed`；同名同姓、同音不同字会先问你 |
| **HIS 表核对** | 拍一张 HIS 病房表，读出床号+姓名，和待办比对：同名换床直接改，同床异姓/同名多人只报告 |
| **发照片** | 拍自己手写的待办清单，模型拆成结构化条目；发之前弹脱敏确认 |
| **长按试推** | 长按标题立刻推一次，不用等到整点才知道通道通不通 |

## 和原版的主要差异

1. **关掉读屏**。原版靠无障碍服务替你操作其他 App（打车、点外卖）；这个分支不需要，只留聊天 + 工具。省电、省权限，也避免误触发。
2. **只要一个模型**。原版要求聊天模型 + 屏幕操作模型都配；这里只配聊天模型，操作模型留空即关闭 `phone_task`。
3. **默认 DeepSeek**。Base URL `https://api.deepseek.com/v1`，模型 `deepseek-flash`，Temperature 留空按 0.2 发，请求体带 `thinking: disabled`（思考模式会让 temperature 失效且延迟十几秒）。
4. **待办是新的主线**，记忆、技能、MCP、Sentinel 审批等原版能力全部保留。

## 安装

到 [Releases](https://github.com/zhang1990-bit/xiaoman-clinical/releases) 下载 `临床秘书-v1.0.apk`，传到手机安装（首次需允许"安装未知来源应用"）。

- Android 11（API 30）及以上
- 桌面图标叫「小满」，**不要开无障碍服务**
- 装好后在设置里填 DeepSeek API Key（只填聊天模型那一组），点「测试连接」确认通了

API Key 在 <https://platform.deepseek.com/api_keys> 申请。一天说 50 句大概几毛钱。

## 上手四步

1. 说一句「新入 12 床李xx，胆囊结石，待手术」，看它是否复述一行让你确认
2. 说「今天还有什么待办」，确认能列出来
3. 长按顶部标题，确认通知能弹出来
4. 等到 08:00，确认会自动响

详细操作见 [docs/clinical/使用说明.md](docs/clinical/使用说明.md)，代码改动见 [docs/clinical/改造说明.md](docs/clinical/改造说明.md)。

## 隐私与边界

这个工具**不碰医嘱、不碰剂量、不判断检查结果**，也不写回任何病历系统。它记的是你自己的待办，不是患者数据。

但有两件事必须说清楚：

- **发照片 = 上云**。照片会整包发给 DeepSeek，图里有什么模型就看见什么。代码只在发之前弹一句提醒，**不会**自动识别人脸或姓名并打码。手写清单可以拍；病历夹、化验单、带姓名的床头卡不要拍。
- **HIS 表核对和上面这条天生冲突**。核对床号必须带姓名，姓名就一定随图上云。如果这条红线不能碰，别用这个功能，口报床号。要做到「姓名不出手机」，得改成端上 OCR + 端上比对，那是另一条工程路线。

待办数据存在 `/sdcard/Android/data/dev.xiaoman.app/files/tasks/tasks.json`，卸载即删，不联网同步。

**本软件不构成医疗建议，不替代任何临床判断。使用者对自己的临床决策负全责。**

## 构建

需要 JDK 17、Android SDK 35。

```bash
# 在仓库根目录写一行 SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew :app:assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew test :app:testDebugUnitTest
```

Windows 上 `web-server` 模块有两个测试会失败（POSIX 文件权限、可执行位），与 Android 端无关，`web-server` 也不参与 APK 构建。

## 目录结构

```
core-api/        纯 JVM：数据模型、任务到期判断、HIS 表比对（可单测，不依赖 Android）
core-tasks/      纯 JVM：待办落盘 + 按月归档
core-agent/      聊天 Agent：系统提示、五个体办工具、工具循环
core-llm/        OpenAI 兼容客户端（图片走 image_url base64）
core-skills/     SKILL.md 技能加载
core-memory/     Basic Memory Markdown 笔记 + 中文 BM25 检索
core-sentinel/   工具调用的 allow / ask / deny 审批
app/             Android 壳：闹钟、通知、聊天界面、设置
skills/examples/clinical-secretary/   本分支的技能文件（随 APK 打包）
docs/clinical/   使用说明与改造说明
```

## 与上游同步

```bash
git remote add upstream https://github.com/aduoge/xiaoman.git
git fetch upstream && git merge upstream/main
```

待办相关代码集中在 `core-api/Tasks.kt`、`core-api/TaskRoster.kt`、`core-tasks/`，与原版模块解耦，合并冲突通常只出现在 `AppGraph.kt` 和 `XiaomanAgent.kt` 的构造参数上。

## 许可与致谢

- 本项目基于 [aduoge/xiaoman](https://github.com/aduoge/xiaoman)，MIT License，Copyright (c) 2026 aduoge
- 本分支的修改同样以 MIT 协议发布，Copyright (c) 2026 zhang1990-bit
- 完整声明见 [LICENSE](LICENSE) 与 [NOTICE.md](NOTICE.md)

如果你觉得有用，请也给上游点个 star —— 底座是他们做的。
