# Learn Claude Code by Java

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-1.8+-orange.svg)](https://www.oracle.com/java/)

> **用 12 节课从零拆解 AI Agent 的秘密，从零打造一个自己的 Claude Code**
>
> Java (JDK 1.8) 实现，保持逻辑完全一致，方便 Java 开发者学习。

**[English](#english-version)** | **中文**

## 项目简介

本项目是对 [shareAI-lab/learn-claude-code](https://github.com/shareAI-lab/learn-claude-code) 的 Java 重写版本，旨在帮助 Java 开发者深入理解 AI Agent 的工作原理。

### 原项目致谢

本项目基于以下开源项目改造：

- **原项目**: [shareAI-lab/learn-claude-code](https://github.com/shareAI-lab/learn-claude-code)
- **原作者**: shareAI Lab
- **原项目协议**: MIT License

本仓库遵循相同的 MIT 开源协议，在保留原作者版权声明的基础上进行了 Java 重写和功能扩展。

## 核心理念

```
模型是驾驶者，Harness 是载具。

Agent 是模型。不是框架。不是提示词链。不是拖拽式工作流。
```

Claude Code 的本质就是一个 **while 循环**：

```java
while ("tool_use".equals(stopReason)) {
    response = client.createMessage(system, messages, tools, maxTokens);
    executeTools(response);
    appendResults(messages);
}
```

12 节课逐步增加能力，但循环本身从未改变。

## 可视化教学

打开 `docs/diagrams/index.html` 查看交互式可视化教学页面，包含：

- 架构全景图
- 每节课的流程图和代码对照
- Anthropic Messages API 完整字段说明

## 课程目录

| 编号 | 课程 | 核心概念 | 新增内容 |
|------|------|----------|----------|
| S00 | Prerequisites | Anthropic Messages API | 请求/响应字段说明 |
| S01 | Agent Loop | while 循环 | 1 个工具 (bash) |
| S02 | Tool Use | 分派表模式 | +3 个工具 (read/write/edit) |
| S03 | TodoWrite | 自我跟踪 | TodoManager + Nag 提醒 |
| S04 | Subagent | 上下文隔离 | 子代理 (fresh messages=[]) |
| S05 | Skill Loading | 按需加载 | 两层注入 (目录→load_skill) |
| S06 | Context Compact | 策略性遗忘 | 三层压缩管道 |
| S07 | Task System | 持久化状态 | JSON 文件 + 依赖图 |
| S08 | Background Tasks | 异步执行 | 线程池 + 通知注入 |
| S09 | Agent Teams | 多 Agent 协作 | JSONL 消息总线 |
| S10 | Team Protocols | 协议模式 | request_id 关联 (关闭/审批) |
| S11 | Autonomous Agents | 自主行为 | 空闲轮询 + 自动认领 |
| S12 | Worktree Isolation | 目录级隔离 | Git Worktree + 双平面架构 |

## 项目结构

```
learn_claude_code_by_java/
├── pom.xml                          # Maven 配置 (JDK 1.8 + Gson)
├── src/main/java/learn/claude/code/
│   ├── common/
│   │   ├── AnthropicClient.java     # API 客户端 (原生 HttpURLConnection)
│   │   ├── BaseTools.java           # 4 个基础工具 (bash/read/write/edit)
│   │   └── ToolHandler.java         # 工具处理器函数式接口
│   ├── s01_agent_loop/              # 课程 01: Agent Loop
│   ├── s02_tool_use/                # 课程 02: Tool Use
│   ├── s03_todo_write/              # 课程 03: TodoWrite
│   ├── s04_subagent/                # 课程 04: Subagent
│   ├── s05_skill_loading/           # 课程 05: Skill Loading
│   ├── s06_context_compact/         # 课程 06: Context Compact
│   ├── s07_task_system/             # 课程 07: Task System
│   ├── s08_background_tasks/        # 课程 08: Background Tasks
│   ├── s09_agent_teams/             # 课程 09: Agent Teams
│   ├── s10_team_protocols/          # 课程 10: Team Protocols
│   ├── s11_autonomous_agents/       # 课程 11: Autonomous Agents
│   └── s12_worktree_task_isolation/ # 课程 12: Worktree Isolation
├── src/main/resources/
│   ├── claude.properties            # API 配置文件
│   └── skills/                      # S05 的技能文件
└── docs/diagrams/                   # 可视化教学页面 (HTML)
```

## 快速开始

### 1. 环境要求

- JDK 1.8+
- Maven 3.x
- 一个兼容 Anthropic Messages API 的 API Key

### 2. 配置 API

编辑 `src/main/resources/claude.properties`：

```properties
ANTHROPIC_API_KEY=你的API密钥
ANTHROPIC_BASE_URL=https://api.anthropic.com
MODEL_ID=claude-sonnet-4-20250514
```

> 支持任何兼容 Anthropic Messages API 的服务商（如智谱 GLM、第三方代理等）。

### 3. 编译

```bash
mvn compile
```

### 4. 运行任意课程

每个课程都有独立的 `main` 方法，可直接运行，然后对话。

## 设计原则

| 原则 | 说明 |
|------|------|
| **JDK 1.8 兼容** | 不用 `var`、`List.of()`、lambda（用匿名内部类） |
| **零第三方依赖** | 仅 Gson (JSON)，HTTP 用原生 `HttpURLConnection` |
| **每课独立可运行** | 每个 `SxxXxx.java` 有自己的 `main` 方法 |
| **渐进式复杂度** | 后续课程在前面基础上只增不改核心循环 |
| **中英双语注释** | 所有源码包含详细的中文 + 英文注释 |

## 与原项目的差异

| 项目 | 原项目 (Python) | 本项目 (Java) |
|------|----------------|---------------|
| 语言 | Python 3.x | Java 1.8 |
| HTTP 客户端 | httpx / requests | HttpURLConnection |
| JSON 库 | 内置 json | Gson |
| 异步 | asyncio | Thread + ExecutorService |
| 配置 | .env | claude.properties |
| 可视化 | 无 | HTML 交互式教学页面 |

## 贡献指南

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

## 开源协议

本项目采用 [MIT License](LICENSE) 开源协议。

### 版权声明

| 项目 | 版权所有者 | 年份 |
|------|-----------|------|
| 原项目 (Python) | shareAI Lab | 2024 |
| Java 版本 | arrayadd | 2025 |

本项目基于 [shareAI-lab/learn-claude-code](https://github.com/shareAI-lab/learn-claude-code) 进行 Java 重写，
遵循 MIT 协议，保留原作者版权声明。

### 第三方依赖

| 依赖 | 协议 | 用途 |
|------|------|------|
| [Gson](https://github.com/google/gson) | Apache 2.0 | JSON 序列化 |

## 致谢

- 原版 Python 教程：[shareAI-lab/learn-claude-code](https://github.com/shareAI-lab/learn-claude-code)
- Anthropic Claude: [anthropic.com](https://www.anthropic.com)

---

如果这个项目对你有帮助，欢迎 Star ⭐

---

## English Version

> **12 lessons to deconstruct the secrets of AI Agents from scratch, build your own Claude Code**
>
> Java (JDK 1.8) implementation, keeping logic fully consistent for Java developers.

### About This Project

This is a Java rewrite of [shareAI-lab/learn-claude-code](https://github.com/shareAI-lab/learn-claude-code),
designed to help Java developers deeply understand how AI Agents work.

### Key Features

- **JDK 1.8 Compatible**: No modern Java features, maximum compatibility
- **Minimal Dependencies**: Only Gson for JSON, native HttpURLConnection for HTTP
- **Bilingual Comments**: Chinese + English comments in all source files
- **Interactive Visualization**: HTML-based teaching diagrams in `docs/diagrams/`

### Quick Start

1. **Requirements**: JDK 1.8+, Maven 3.x
2. **Configure**: Copy `claude.properties.example` to `claude.properties` and add your API key
3. **Build**: `mvn compile`
4. **Run**: Each lesson has its own `main()` method

### License

This project is licensed under the [MIT License](LICENSE).

### Acknowledgments

- Original Python tutorial: [shareAI-lab/learn-claude-code](https://github.com/shareAI-lab/learn-claude-code)
