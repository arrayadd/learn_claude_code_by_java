<div align="center">

# 🤖 Learn Claude Code by Java

**用 12 节课，从一个 `while` 循环出发，亲手造一个 AI Agent**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-1.8+-orange.svg)](https://www.oracle.com/java/)
[![Stars](https://img.shields.io/github/stars/arrayadd/learn_claude_code_by_java?style=social)](https://github.com/arrayadd/learn_claude_code_by_java/stargazers)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)
[![在线学习](https://img.shields.io/badge/在线学习-📖_可视化教学-blue)](https://arrayadd.github.io/learn_claude_code_by_java/)

[**中文**](#中文) | [**English**](#english)

</div>

---

## 中文

### 💡 这是什么？

很多人用 Claude Code、Cursor、Copilot，却不知道它们是怎么工作的。

这个项目用 **Java 1.8**，把 AI Agent 的核心机制拆解成 12 节递进的课程——从最简单的 while 循环开始，一步步加入工具调用、子 Agent、多 Agent 协作……直到你能自己造一个。

**没有魔法，没有黑箱。** 所有秘密都在这个循环里：

```java
// 这就是 Claude Code 的全部本质
while ("tool_use".equals(stopReason)) {
    response = client.createMessage(system, messages, tools, maxTokens);
    executeTools(response);   // 执行工具
    appendResults(messages);  // 结果追加回对话
}
// 12 节课只是往这个循环里加东西，循环本身从未改变
```

> 本项目是 [shareAI-lab/learn-claude-code](https://github.com/shareAI-lab/learn-claude-code)（Python 版）的 Java 重写，
> 保留原版逻辑，新增**交互式可视化教学页面**，专为 Java 开发者打造。

---

### ✨ 为什么选这个项目？

| | 说明 |
|--|------|
| 🎯 **目标明确** | 不是 AI 应用开发框架，是教你 Agent 底层原理的教程 |
| ☕ **Java 原生** | JDK 1.8，无 lambda，无 Stream API，最广泛的兼容性 |
| 📦 **极简依赖** | 只有 Gson，HTTP 用原生 `HttpURLConnection`，不引入任何 AI SDK |
| 🏃 **每课即跑** | 每个 `SxxXxx.java` 有独立 `main()`，clone 下来就能运行 |
| 📈 **渐进式** | 12 课只加不改，始终看得清每一层在做什么 |
| 🌐 **双语注释** | 所有源码包含中文 + 英文注释，方便国际交流 |
| 🖼️ **可视化** | 配套 14 张交互式 SVG 架构图，每节课一张 |

---

### 🗺️ 课程地图

```
S00  Prerequisites      ──  了解 Anthropic Messages API 的请求/响应结构
 │
S01  Agent Loop         ──  第一个 while 循环 + bash 工具
 │
S02  Tool Use           ──  分派表模式，扩展到 4 个工具（read/write/edit/bash）
 │
S03  TodoWrite          ──  Agent 自我跟踪任务，TodoManager + Nag 提醒机制
 │
S04  Subagent           ──  子 Agent，用全新的 messages=[] 隔离上下文
 │
S05  Skill Loading      ──  技能按需加载，两层注入（目录扫描 → load_skill）
 │
S06  Context Compact    ──  策略性遗忘，三层压缩管道控制 token 消耗
 │
S07  Task System        ──  持久化任务状态，JSON 文件 + 依赖图
 │
S08  Background Tasks   ──  异步并发，线程池 + 通知注入
 │
S09  Agent Teams        ──  多 Agent 协作，JSONL 消息总线
 │
S10  Team Protocols     ──  关闭/审批协议，request_id 关联
 │
S11  Autonomous Agents  ──  自主行为，空闲轮询 + 任务自动认领
 │
S12  Worktree Isolation ──  目录级隔离，Git Worktree + 双平面架构
```

---

### 📁 项目结构

```
learn_claude_code_by_java/
├── pom.xml                              # Maven 配置（JDK 1.8 + Gson）
├── src/main/java/learn/claude/code/
│   ├── common/
│   │   ├── AnthropicClient.java         # API 客户端（原生 HttpURLConnection）
│   │   ├── BaseTools.java               # 4 个基础工具（bash/read/write/edit）
│   │   └── ToolHandler.java             # 工具处理器函数式接口
│   ├── s01_agent_loop/                  # 课程 01 ～
│   ├── s02_tool_use/                    # 课程 02 ～
│   ├── ...                              # 以此类推
│   └── s12_worktree_task_isolation/     # 课程 12
├── src/main/resources/
│   ├── claude.properties.example        # 配置模板（复制后填入 Key）
│   └── skills/                          # S05 技能文件
└── docs/diagrams/                       # 14 张交互式可视化教学页面
    ├── index.html                        # 总览入口
    └── s01_agent_loop.html ～ s12_*.html
```

---

### 🚀 快速开始

**第一步：克隆 & 配置**

```bash
git clone https://github.com/arrayadd/learn_claude_code_by_java.git
cd learn_claude_code_by_java

# 复制配置模板
cp src/main/resources/claude.properties.example \
   src/main/resources/claude.properties
```

编辑 `claude.properties`，填入你的 API Key：

```properties
ANTHROPIC_API_KEY=你的API密钥
ANTHROPIC_BASE_URL=https://api.anthropic.com   # 或第三方兼容地址
MODEL_ID=claude-sonnet-4-20250514
```

> 💡 支持任何兼容 Anthropic Messages API 的服务商，包括智谱 GLM 等第三方代理。

**第二步：编译**

```bash
mvn compile
```

**第三步：运行任意一课**

在 IDE 里直接运行对应课程的 `main()` 方法，或用命令行：

```bash
# 以 S01 为例
mvn exec:java -Dexec.mainClass="learn.claude.code.s01_agent_loop.S01AgentLoop"
```

运行后在终端输入你的指令，开始对话！

**第四步：查看可视化教学**

🌐 **在线直接访问（无需克隆）**：[https://arrayadd.github.io/learn_claude_code_by_java/](https://arrayadd.github.io/learn_claude_code_by_java/)

或本地打开 `docs/diagrams/index.html`，14 张交互式架构图逐课讲解每个概念。

---

### 🆚 与原 Python 版的差异

| 对比项 | 原项目 (Python) | 本项目 (Java) |
|--------|----------------|---------------|
| 语言 | Python 3.x | **Java 1.8** |
| HTTP 客户端 | httpx / requests | HttpURLConnection（原生） |
| JSON 处理 | 内置 json 模块 | Gson |
| 异步模型 | asyncio | Thread + ExecutorService |
| 配置方式 | .env 文件 | claude.properties |
| 可视化教学 | 无 | **14 张交互式 HTML 图表** ✨ |

---

### 🤝 参与贡献

欢迎任何形式的贡献！

- 🐛 **发现 Bug** → [提 Issue](https://github.com/arrayadd/learn_claude_code_by_java/issues)
- 💡 **有新想法** → [发起讨论](https://github.com/arrayadd/learn_claude_code_by_java/discussions)
- 🔧 **想改代码** → Fork → 新建分支 → 提 PR

贡献前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

---

### 📄 开源协议

本项目采用 [MIT License](LICENSE) 开源。

原项目 [shareAI-lab/learn-claude-code](https://github.com/shareAI-lab/learn-claude-code) © 2024 shareAI Lab（MIT）
Java 版本 © 2025 arrayadd（MIT）

---

<div align="center">

如果这个项目对你有帮助，请点一个 ⭐ Star —— 这是对作者最大的鼓励！

</div>

---

## English

### 💡 What is this?

Many developers use Claude Code, Cursor, or Copilot every day — but few understand how they actually work under the hood.

This project deconstructs the core mechanics of an AI Agent into **12 progressive lessons**, starting from a single `while` loop and building up to multi-agent collaboration, autonomous task execution, and worktree isolation — all in **Java 1.8**.

**No magic. No black boxes.** Everything is this loop:

```java
// This is the entire soul of Claude Code
while ("tool_use".equals(stopReason)) {
    response = client.createMessage(system, messages, tools, maxTokens);
    executeTools(response);   // run tools
    appendResults(messages);  // feed results back
}
// All 12 lessons just add layers on top — the loop never changes
```

> This is a Java port of [shareAI-lab/learn-claude-code](https://github.com/shareAI-lab/learn-claude-code) (Python),
> with identical logic, bilingual comments, and an added **interactive visualization layer**.

---

### ✨ Why this project?

| | |
|--|--|
| 🎯 **Pure education** | Not a framework — a teardown of how agents actually work |
| ☕ **Java 1.8 native** | No lambdas, no streams, maximum compatibility |
| 📦 **Minimal deps** | Only Gson. HTTP via plain `HttpURLConnection`. Zero AI SDK. |
| 🏃 **Run any lesson** | Every `SxxXxx.java` has its own `main()` — just run it |
| 📈 **Progressive** | Each lesson adds one concept. The core loop never changes. |
| 🌐 **Bilingual** | All source files have Chinese + English comments |
| 🖼️ **Visual** | 14 interactive SVG diagrams — one per lesson |

---

### 🗺️ Curriculum

| # | Lesson | Core Concept | What's Added |
|---|--------|-------------|--------------|
| S00 | Prerequisites | Anthropic Messages API | Request / response field walkthrough |
| S01 | Agent Loop | The while loop | 1 tool (bash) |
| S02 | Tool Use | Dispatch table pattern | +3 tools (read / write / edit) |
| S03 | TodoWrite | Self-tracking | TodoManager + nag reminder |
| S04 | Subagent | Context isolation | Sub-agent (fresh `messages=[]`) |
| S05 | Skill Loading | On-demand loading | Two-layer injection (scan → load_skill) |
| S06 | Context Compact | Strategic forgetting | Three-stage compression pipeline |
| S07 | Task System | Persistent state | JSON files + dependency graph |
| S08 | Background Tasks | Async execution | Thread pool + notification injection |
| S09 | Agent Teams | Multi-agent collaboration | JSONL message bus |
| S10 | Team Protocols | Protocol patterns | request_id correlation (shutdown / approve) |
| S11 | Autonomous Agents | Self-directed behavior | Idle polling + auto task claiming |
| S12 | Worktree Isolation | Directory-level isolation | Git Worktree + dual-plane architecture |

---

### 🚀 Quick Start

```bash
git clone https://github.com/arrayadd/learn_claude_code_by_java.git
cd learn_claude_code_by_java

# Copy config template
cp src/main/resources/claude.properties.example \
   src/main/resources/claude.properties

# Edit and fill in your API key
# Then build:
mvn compile

# Run any lesson (e.g. S01):
mvn exec:java -Dexec.mainClass="learn.claude.code.s01_agent_loop.S01AgentLoop"
```

🌐 **Online demo (no clone needed)**: [https://arrayadd.github.io/learn_claude_code_by_java/](https://arrayadd.github.io/learn_claude_code_by_java/)

Or open `docs/diagrams/index.html` locally for the interactive lesson diagrams.

---

### 📄 License

[MIT License](LICENSE) — © 2025 arrayadd
Based on [shareAI-lab/learn-claude-code](https://github.com/shareAI-lab/learn-claude-code) © 2024 shareAI Lab (MIT)

---

<div align="center">

Found this useful? A ⭐ Star means a lot!

</div>
