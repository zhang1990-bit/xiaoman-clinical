# NOTICE

## 上游项目

本仓库是开源项目 **小满（Xiaoman）** 的衍生作品（derivative work）：

- 项目：https://github.com/aduoge/xiaoman
- 作者：aduoge
- 协议：MIT License
- 版权：Copyright (c) 2026 aduoge

上游 `LICENSE` 文件原样保留在本仓库根目录，未做任何修改。上游的 README 保留为 `README.upstream.md`。

## 本分支的修改

自上游 commit `891056d` 起，本分支（临床查房秘书方向）的修改包括但不限于：

- 新增待办数据模型与领域逻辑：`core-api/src/main/kotlin/dev/xiaoman/api/Tasks.kt`
- 新增 HIS 病房表比对：`core-api/src/main/kotlin/dev/xiaoman/api/TaskRoster.kt`
- 新增落盘存储模块：`core-tasks/`
- 新增五个体办工具及其在 `core-agent` 中的实现
- 新增四个时点的闹钟推送、开机重排、聚合通知
- 新增聊天发图（压缩、脱敏确认、本轮生效）
- 新增临床秘书技能：`skills/examples/clinical-secretary/SKILL.md`
- 调整默认模型配置与请求参数（DeepSeek；关闭深度思考；temperature 默认 0.2）
- 关闭读屏路径的默认启用（不注入操作模型时不提供 `phone_task`）

修改部分的版权：Copyright (c) 2026 zhang1990-bit，同样以 MIT License 发布。

## MIT License 摘要

允许商业使用、修改、分发、私有使用；必须保留版权声明与许可声明；作者不承担任何责任。
完整条款见 [LICENSE](LICENSE)。

## 商标与免责

「小满」「Xiaoman」指向上游项目，本项目不是上游官方发行版，上游作者不对本分支的任何问题负责。

本软件是个人效率工具，**不是医疗器械，不提供诊断或治疗建议**，不替代任何临床判断。
