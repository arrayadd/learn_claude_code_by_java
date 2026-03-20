# 安全政策

## 报告安全漏洞

如果你发现了安全漏洞，请**不要**在公开的 Issue 中报告。

请通过以下方式私下联系我们：

- 创建一个标记为 "security" 的私密 Issue
- 或发送邮件至项目维护者

我们会尽快回复并处理。

## 已知安全问题

### API Key 保护

**重要**：请勿将你的 API Key 提交到版本控制系统！

- `claude.properties` 已被 `.gitignore` 忽略
- 请使用 `claude.properties.example` 作为配置模板
- 在生产环境中，建议使用环境变量传递敏感配置

```bash
export ANTHROPIC_API_KEY=your-api-key-here
```

## 最佳实践

1. 定期轮换 API Key
2. 为不同环境使用不同的 API Key
3. 限制 API Key 的权限和额度
4. 不要在日志中打印完整的 API Key
