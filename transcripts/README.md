# AI 会话原始记录说明

本目录是考题要求提交的 **"与 AI 沟通的原始会话记录"**，完整、未删减。

| 文件 | 内容 | 大小 |
|---|---|---|
| `main-session-raw.jsonl` | 主会话完整记录：需求澄清 → 技术栈决策 → 后端/前端实现 → 测试 → 评审修复 → 文档交付的全部提示词与工具调用输出 | ~5.5 MB |
| `frontend-agent-raw.jsonl` | 前端子 Agent 的完整独立会话（含 dataviz skill 加载、实现过程与自检） | ~828 KB |

## 格式与查看方式

- 格式：Claude Code 原生会话记录（JSONL，每行一条消息/工具调用事件），**原始未加工**——包含成功的尝试、失败的尝试与修复过程，评审官可完整追溯决策链
- 查看：`claude --resume` 导入；或任意 JSONL 解析工具逐行查看（关键字段：`type`=user/assistant、`message.content`、工具名与参数）

## 与 docs/05 的对应关系

`docs/05-AI协作过程.md` 是方法论提炼（Skill/Agent 分工、提示词策略、翻车修复记录）；本目录是**原始证据**。两相对照即完整的"AI Coding 过程材料"。

> 说明：本项目建设过程使用的 AI 工具为 Claude Code（本仓库即其产物）；`docs/05` 中记录的两个 Skill（dataviz / code-review）与两个 Agent（前端子 Agent / 评审 fork）均在本记录中有对应的完整工具调用轨迹。
