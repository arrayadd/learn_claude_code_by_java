# 更新日志

本项目的所有重要更改都将记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
并且本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.0] - 2026-03-22

### 新增
- Java 1.8 完整实现，对应原 Python 版 12 节课程
- 原生 HttpURLConnection 实现 API 调用，零第三方依赖（除 Gson）
- 交互式可视化教学页面 (`docs/diagrams/`)
- 中英双语源码注释
- 支持任何兼容 Anthropic Messages API 的服务商

### 变更（相对于原项目）
- 语言：Python → Java 1.8
- HTTP 客户端：httpx/requests → HttpURLConnection
- JSON 处理：内置 json → Gson
- 异步模型：asyncio → Thread + ExecutorService
- 配置方式：.env → claude.properties

### 致谢
- 原项目：[shareAI-lab/learn-claude-code](https://github.com/shareAI-lab/learn-claude-code)
