package learn.claude.code.s06_context_compact;

import com.google.gson.*;
import learn.claude.code.common.AnthropicClient;
import learn.claude.code.common.BaseTools;
import learn.claude.code.common.ToolHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * S06 - 上下文压缩 (Context Compaction)
 *
 * 核心洞察 / Key Insight:
 *   "The agent can forget strategically and keep working forever."
 *   "智能体可以策略性地遗忘，从而永远工作下去。"
 *
 * 三层压缩管道 / Three-layer compression pipeline:
 *
 *   Layer 1 - micro_compact (每轮 / every turn):
 *     替换旧的 tool_result 内容为 "[Previous: used {tool_name}]"
 *     Replace old tool_result content with "[Previous: used {tool_name}]"
 *     效果：防止历史工具输出无限膨胀
 *
 *   Layer 2 - auto_compact (tokens > 50000 时自动触发):
 *     保存完整记录到 .transcripts/ 目录
 *     让 LLM 生成摘要替换消息列表
 *     Save full transcript to .transcripts/, ask LLM to summarize, replace messages
 *
 *   Layer 3 - compact tool (模型主动调用):
 *     模型觉得上下文太长时可以手动触发压缩
 *     Model can manually trigger compression when it feels context is too long
 */
public class S06ContextCompact {

    /** JSON 序列化工具，启用格式化输出便于调试 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ===== 内部类：压缩管理器 =====
    // CompactionManager 是整个三层压缩管道的核心实现。
    // 三层设计理念：
    //   第一层（micro）：每轮自动缩减旧的工具输出，类似人的短期记忆衰退
    //   第二层（auto）：token 超过阈值时，让 LLM 生成摘要替换整个对话历史
    //   第三层（compact tool）：模型自己判断上下文过长时，主动调用压缩工具
    // 这三层从细粒度到粗粒度，形成渐进式的上下文管理策略。

    /**
     * 压缩管理器：实现三层上下文压缩策略。
     *
     * 设计思路：LLM 的上下文窗口有限（例如 200k tokens），而一个长时间运行的 Agent 会话
     * 会不断产生工具调用结果，很快就会撑爆上下文。因此需要一套"策略性遗忘"机制：
     * - 不重要的旧信息（如已完成的工具输出）可以丢弃
     * - 关键上下文（如用户目标、关键决策）必须保留
     *
     * 这就是三层压缩管道的设计目标：在不丢失关键信息的前提下，最大限度地压缩上下文。
     */
    static class CompactionManager {

        /** Anthropic API 客户端，用于调用 LLM 生成摘要（第二层压缩需要） */
        private final AnthropicClient client;

        /** 对话记录归档目录路径，用于在自动压缩时保存完整历史（便于审计和调试） */
        private final String transcriptsDir;

        /**
         * 构造压缩管理器。
         *
         * @param client  Anthropic API 客户端实例
         * @param workDir 工作目录，.transcripts/ 子目录将在此下创建
         */
        CompactionManager(AnthropicClient client, String workDir) {
            this.client = client;
            this.transcriptsDir = workDir + "/.transcripts";
            // 确保 .transcripts/ 归档目录存在，不存在则递归创建
            new File(transcriptsDir).mkdirs();
        }

        /**
         * 第一层压缩：微压缩（micro compact）— 每一轮 Agent 循环都会调用。
         *
         * 核心策略：保留最近 3 个 tool_result 的完整内容不变，把更早的 tool_result
         * 替换为简短的占位符文本（例如 "[Previous: used bash]"）。
         *
         * 设计思路：这模拟了人类的记忆衰退模型——最近做过的事情记得很清楚，
         * 而久远的事情只记得"我做过什么"而不记得具体细节。
         * 这样每轮都能小幅度压缩，防止工具输出的历史无限膨胀。
         *
         * 实现细节：
         * 1. 扫描所有消息，收集 tool_result 块的位置（消息索引 + 块索引）
         * 2. 只保留最后 3 个 tool_result 不动，其余超过 100 字符的替换为占位符
         * 3. 通过 tool_use_id 反查工具名称，使占位符有意义
         *
         * @param messages 当前完整的消息列表
         * @return 经过微压缩的消息列表（浅拷贝，被修改的消息会 deepCopy）
         */
        JsonArray microCompact(JsonArray messages) {
            // 消息太少（<= 4 条）时跳过压缩，因为还没有足够的历史值得压缩
            if (messages.size() <= 4) return messages;

            // 第一步：扫描所有消息，收集每个 tool_result 块的精确位置 [消息索引, 块索引]
            // tool_result 块位于 user 角色消息的 content 数组中
            List<int[]> toolResultPositions = new ArrayList<int[]>();
            for (int mi = 0; mi < messages.size(); mi++) {
                JsonObject msg = messages.get(mi).getAsJsonObject();
                if (!"user".equals(msg.get("role").getAsString())) continue;
                JsonElement contentEl = msg.get("content");
                if (contentEl == null || !contentEl.isJsonArray()) continue;
                JsonArray contentArr = contentEl.getAsJsonArray();
                for (int pi = 0; pi < contentArr.size(); pi++) {
                    JsonObject block = contentArr.get(pi).getAsJsonObject();
                    if ("tool_result".equals(block.get("type").getAsString())) {
                        toolResultPositions.add(new int[]{mi, pi});
                    }
                }
            }

            // 保留最后 3 个 tool_result 不压缩（这些是最近的、可能还有参考价值的工具输出）
            int keepCount = 3;
            int compactCount = toolResultPositions.size() - keepCount;
            // 如果需要压缩的数量 <= 0，说明历史工具输出还不多，直接返回
            if (compactCount <= 0) return messages;

            // 构建"需要压缩"的位置集合，用 "消息索引,块索引" 字符串作为 key
            Set<String> toCompact = new HashSet<String>();
            for (int i = 0; i < compactCount; i++) {
                int[] pos = toolResultPositions.get(i);
                toCompact.add(pos[0] + "," + pos[1]);
            }

            // 第二步：遍历所有消息，对需要压缩的 tool_result 进行替换
            // 注意：使用 deepCopy() 避免修改原始消息对象
            JsonArray result = new JsonArray();
            for (int mi = 0; mi < messages.size(); mi++) {
                JsonObject msg = messages.get(mi).getAsJsonObject().deepCopy();

                if ("user".equals(msg.get("role").getAsString())) {
                    JsonElement contentEl = msg.get("content");
                    if (contentEl != null && contentEl.isJsonArray()) {
                        JsonArray contentArr = contentEl.getAsJsonArray();
                        JsonArray newContent = new JsonArray();
                        boolean changed = false;

                        for (int pi = 0; pi < contentArr.size(); pi++) {
                            JsonObject block = contentArr.get(pi).getAsJsonObject();
                            if ("tool_result".equals(block.get("type").getAsString())
                                    && toCompact.contains(mi + "," + pi)) {
                                // 仅当内容超过 100 字符时才替换（短内容压缩收益太小）
                                String contentStr = "";
                                if (block.has("content")) {
                                    JsonElement ce = block.get("content");
                                    contentStr = ce.isJsonPrimitive() ? ce.getAsString() : ce.toString();
                                }
                                if (contentStr.length() > 100) {
                                    // 构造占位符块：保留 type 和 tool_use_id（API 要求），
                                    // 但将实际内容替换为简短的 "[Previous: used 工具名]"
                                    JsonObject placeholder = new JsonObject();
                                    placeholder.addProperty("type", "tool_result");
                                    placeholder.addProperty("tool_use_id",
                                            block.get("tool_use_id").getAsString());
                                    // 通过 tool_use_id 反向查找对应的工具名称
                                    String toolName = findToolName(messages, block.get("tool_use_id").getAsString());
                                    placeholder.addProperty("content",
                                            "[Previous: used " + toolName + "]");
                                    newContent.add(placeholder);
                                    changed = true;
                                } else {
                                    newContent.add(block);
                                }
                            } else {
                                newContent.add(block);
                            }
                        }

                        if (changed) {
                            msg.add("content", newContent);
                        }
                    }
                }
                result.add(msg);
            }

            return result;
        }

        /**
         * 辅助方法：从消息历史中根据 tool_use_id 反查工具名称。
         *
         * 原理：Anthropic API 中，assistant 消息包含 tool_use 块（带有 id 和 name），
         * 而 user 消息中包含对应的 tool_result 块（带有 tool_use_id）。
         * 通过匹配 id == tool_use_id 来找到工具名称。
         *
         * @param messages  完整的消息历史
         * @param toolUseId 要查找的 tool_use_id
         * @return 工具名称，找不到时返回 "unknown_tool"
         */
        private String findToolName(JsonArray messages, String toolUseId) {
            for (JsonElement msgEl : messages) {
                JsonObject msg = msgEl.getAsJsonObject();
                if (!"assistant".equals(msg.get("role").getAsString())) continue;

                JsonElement contentEl = msg.get("content");
                if (contentEl == null || !contentEl.isJsonArray()) continue;

                for (JsonElement blockEl : contentEl.getAsJsonArray()) {
                    JsonObject block = blockEl.getAsJsonObject();
                    if ("tool_use".equals(block.get("type").getAsString())
                            && toolUseId.equals(block.get("id").getAsString())) {
                        return block.get("name").getAsString();
                    }
                }
            }
            return "unknown_tool";
        }

        /**
         * 第二层压缩：自动压缩（auto compact）— 当估算 token 数超过 50000 时触发。
         *
         * 这是一次"大规模压缩"：整个消息历史被 LLM 生成的摘要替换。
         *
         * 执行步骤：
         * 1. 将完整对话记录保存到 .transcripts/ 目录（用于审计和调试，不可逆操作前先备份）
         * 2. 调用 LLM 生成对话摘要（提取关键信息：用户目标、已完成工作、待办事项、文件路径）
         * 3. 用摘要替换整个消息列表（压缩为仅 2 条消息：摘要 + 确认）
         *
         * 压缩后消息列表从可能的几百条变成只有 2 条，token 数大幅下降。
         * 代价是丢失了具体的对话细节，但关键信息通过摘要保留。
         *
         * @param messages 当前完整的消息列表
         * @return 压缩后的消息列表（仅包含摘要和确认两条消息）
         */
        JsonArray autoCompact(JsonArray messages) {
            System.out.println("[Compact] Auto-compact triggered! Saving transcript and summarizing...");

            // 步骤 1：保存完整对话记录到文件（不可逆压缩前的备份）
            saveTranscript(messages);

            // 步骤 2：调用 LLM 对整个对话生成摘要
            String summary = generateSummary(messages);
            System.out.println("[Compact] Summary: " + summary.substring(0, Math.min(200, summary.length())) + "...");

            // 步骤 3：构造压缩后的消息列表
            // 仅包含 2 条消息：一条 user 消息承载摘要内容，一条 assistant 消息表示确认
            // 这样 LLM 在后续调用中能看到之前的上下文摘要，并继续工作
            JsonArray compacted = new JsonArray();
            compacted.add(AnthropicClient.userMessage(
                    "[Context was compacted. Previous conversation summary:]\n" + summary
                            + "\n\n[Continue helping the user from where we left off.]"));
            JsonArray assistantContent = new JsonArray();
            JsonObject textBlock = new JsonObject();
            textBlock.addProperty("type", "text");
            textBlock.addProperty("text", "Understood. I have the context from the summary. Continuing.");
            assistantContent.add(textBlock);
            compacted.add(AnthropicClient.assistantMessage(assistantContent));
            return compacted;
        }

        /**
         * 将完整对话记录持久化到 .transcripts/ 目录。
         *
         * 文件命名格式：transcript_yyyyMMdd_HHmmss.json
         * 这样在自动压缩丢失对话细节后，仍可通过归档文件回溯完整历史。
         *
         * @param messages 要归档的完整消息列表
         */
        private void saveTranscript(JsonArray messages) {
            try {
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                String filename = transcriptsDir + "/transcript_" + timestamp + ".json";
                Files.write(Paths.get(filename),
                        GSON.toJson(messages).getBytes(StandardCharsets.UTF_8));
                System.out.println("[Compact] Transcript saved to: " + filename);
            } catch (IOException e) {
                System.err.println("[Compact] Failed to save transcript: " + e.getMessage());
            }
        }

        /**
         * 调用 LLM 生成对话摘要。
         *
         * 摘要 prompt 要求 LLM 聚焦三个维度：
         * (1) 用户的原始需求是什么
         * (2) 目前已完成了什么
         * (3) 还有哪些待办事项或重要上下文
         * 并且要求保留文件路径和关键决策信息（这些是恢复工作状态的关键）。
         *
         * 如果 LLM 调用失败，会降级为一个简单的手动摘要（包含消息数量）。
         *
         * @param messages 要摘要的完整消息列表
         * @return 摘要字符串
         */
        private String generateSummary(JsonArray messages) {
            // 摘要 prompt：指导 LLM 提取最关键的信息
            String summaryPrompt = "Summarize this conversation concisely. "
                    + "Focus on: (1) what the user asked for, (2) what was accomplished, "
                    + "(3) any pending tasks or important context. "
                    + "Keep file paths and key decisions.";

            JsonArray summaryMessages = new JsonArray();
            summaryMessages.add(AnthropicClient.userMessage(
                    summaryPrompt + "\n\nConversation:\n" + GSON.toJson(messages)));

            try {
                JsonObject response = client.createMessage(
                        "You are a conversation summarizer.", summaryMessages, null, 2048);
                return AnthropicClient.extractText(AnthropicClient.getContent(response));
            } catch (Exception e) {
                // 降级策略：LLM 摘要失败时，生成一个包含基本信息的兜底摘要
                return "Previous conversation had " + messages.size() + " messages. "
                        + "(Summary generation failed: " + e.getMessage() + ")";
            }
        }

        /**
         * 粗略估算消息列表的 token 数量。
         *
         * token 估算方法：将消息序列化为 JSON 字符串，然后除以 4。
         * 经验法则：英文文本约 1 token ≈ 4 字符，中文文本约 1 token ≈ 2 字符。
         * 这里取英文的估算比例（偏保守），JSON 格式本身也会有额外的标点开销。
         *
         * 这个估算用于判断是否需要触发第二层自动压缩（阈值 50000 tokens）。
         * 精确的 token 计数需要使用 tokenizer，但对于触发判断来说粗略估算已经足够。
         *
         * @param messages 当前消息列表
         * @return 估算的 token 数量
         */
        int estimateTokens(JsonArray messages) {
            String json = GSON.toJson(messages);
            // 粗略估算：JSON 总字符数 / 4
            return json.length() / 4;
        }
    }

    // ===== 工具定义 =====

    /**
     * 第三层压缩的工具定义：compact 工具。
     *
     * 这是暴露给 LLM 的工具，让模型自己判断何时需要压缩上下文。
     * 参数 focus 允许模型指定摘要时应重点保留的内容。
     *
     * 第三层与第二层使用相同的压缩逻辑（autoCompact），区别在于触发方式：
     * - 第二层：token 数量超过阈值时自动触发
     * - 第三层：模型主动调用（模型觉得上下文太长或有冗余时）
     *
     * @return compact 工具的 JSON 定义（符合 Anthropic tool schema 格式）
     */
    private static JsonObject compactToolDef() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonObject focusProp = new JsonObject();
        focusProp.addProperty("type", "string");
        focusProp.addProperty("description", "What to preserve in the summary");
        properties.add("focus", focusProp);
        schema.add("properties", properties);
        return AnthropicClient.toolDef(
                "compact",
                "Compact the conversation context to free up space. "
                        + "Use this when the conversation is getting too long "
                        + "or you notice repeated context.",
                schema
        );
    }

    // ===== Agent 主循环 =====

    /**
     * Agent 主循环：REPL（读取-评估-打印-循环）模式。
     *
     * 循环结构：
     * - 外层循环：等待用户输入
     * - 内层循环：处理工具调用链（最多 25 次迭代，防止无限循环）
     *
     * 每次内层循环迭代中，按顺序执行三层压缩：
     * 1. microCompact — 缩减旧的工具输出
     * 2. 检查 token 数，必要时触发 autoCompact
     * 3. 检查模型是否请求了手动 compact
     *
     * @param workDir 工作目录路径
     */
    private static void agentLoop(String workDir) {
        AnthropicClient client = new AnthropicClient();
        BaseTools baseTools = new BaseTools(workDir);
        CompactionManager compactor = new CompactionManager(client, workDir);

        // 使用数组包装 boolean，因为匿名内部类中不能修改外部局部变量（JDK 1.8 限制）
        // 当模型调用 compact 工具时，此标记被设为 true
        final boolean[] compactRequested = {false};

        // 注册工具处理器（工具名 -> 处理逻辑的映射）
        final Map<String, ToolHandler> handlers = new HashMap<String, ToolHandler>();
        handlers.put("bash", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return baseTools.runBash(input.get("command").getAsString());
            }
        });
        handlers.put("read_file", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                int limit = input.has("limit") ? input.get("limit").getAsInt() : 0;
                return baseTools.runRead(input.get("path").getAsString(), limit);
            }
        });
        handlers.put("write_file", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return baseTools.runWrite(
                        input.get("path").getAsString(),
                        input.get("content").getAsString());
            }
        });
        handlers.put("edit_file", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return baseTools.runEdit(
                        input.get("path").getAsString(),
                        input.get("old_text").getAsString(),
                        input.get("new_text").getAsString());
            }
        });
        // 第三层压缩工具处理器：模型调用 compact 时，仅设置标记，
        // 实际压缩在下一轮内层循环迭代开始时执行
        handlers.put("compact", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                compactRequested[0] = true;
                return "Compaction will be applied before the next LLM call.";
            }
        });

        // 共 5 个工具：4 个基础工具（bash, read_file, write_file, edit_file）+ compact
        JsonArray tools = BaseTools.allToolDefs();
        tools.add(compactToolDef());

        String systemPrompt = "You are a helpful coding assistant.\n\n"
                + "## Context Management\n"
                + "You have a 'compact' tool available. Use it when:\n"
                + "- The conversation feels very long\n"
                + "- You notice you're repeating context from earlier\n"
                + "- You want to free up context space for new work\n\n"
                + "The system also automatically manages context:\n"
                + "- Old tool results are summarized automatically (Layer 1)\n"
                + "- If context gets too large, automatic compaction occurs (Layer 2)\n\n"
                + "## Tools\n"
                + "You have bash, read_file, write_file, edit_file, and compact available.";

        // 持久消息列表：跨多轮用户输入保持，是三层压缩操作的对象
        JsonArray messages = new JsonArray();

        Scanner scanner = new Scanner(System.in);
        System.out.print("You: ");
        while (scanner.hasNextLine()) {
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                System.out.print("You: ");
                continue;
            }
            if ("quit".equalsIgnoreCase(input) || "exit".equalsIgnoreCase(input)) {
                System.out.println("Goodbye!");
                break;
            }

            messages.add(AnthropicClient.userMessage(input));

            // 内层循环：处理工具调用链，每次迭代先执行三层压缩再调用 LLM
            int maxIterations = 25;
            for (int i = 0; i < maxIterations; i++) {

                // ===== 三层压缩管道 =====

                // 第一层：微压缩 — 每轮都执行，缩减旧的 tool_result 内容
                messages = compactor.microCompact(messages);

                // 第二层：自动压缩 — 当估算 token 数超过 50000 时触发全量摘要压缩
                int estimatedTokens = compactor.estimateTokens(messages);
                System.out.println("[Context] Estimated tokens: " + estimatedTokens);
                if (estimatedTokens > 50000) {
                    messages = compactor.autoCompact(messages);
                }

                // 第三层：手动压缩 — 模型主动调用 compact 工具后，在此处执行
                // 复用 autoCompact 的压缩逻辑（保存归档 + 生成摘要 + 替换消息）
                if (compactRequested[0]) {
                    compactRequested[0] = false;
                    System.out.println("[Compact] Manual compact requested by model.");
                    messages = compactor.autoCompact(messages);
                }

                System.out.println("[Agent] Calling LLM (iteration " + (i + 1) + ")...");
                JsonObject response = client.createMessage(systemPrompt, messages, tools, 8192);

                String stopReason = AnthropicClient.getStopReason(response);
                JsonArray content = AnthropicClient.getContent(response);

                // 打印 LLM 回复中的文本部分（非工具调用部分）
                String textOutput = AnthropicClient.extractText(content);
                if (!textOutput.isEmpty()) {
                    System.out.println("\n[Assistant] " + textOutput);
                }

                // 如果模型结束对话（stop_reason 不是 "tool_use"），退出内层循环
                if (!"tool_use".equals(stopReason)) {
                    System.out.println("[Agent] Done. (stop_reason=" + stopReason + ")");
                    break;
                }

                // 处理工具调用：将 assistant 回复加入消息列表，执行工具，收集结果
                messages.add(AnthropicClient.assistantMessage(content));
                JsonArray toolResults = new JsonArray();

                for (JsonElement el : content) {
                    JsonObject block = el.getAsJsonObject();
                    if (!"tool_use".equals(block.get("type").getAsString())) continue;

                    String toolName = block.get("name").getAsString();
                    String toolId = block.get("id").getAsString();
                    JsonObject toolInput = block.getAsJsonObject("input");

                    System.out.println("[Tool] " + toolName + " <- " + toolInput);

                    ToolHandler handler = handlers.get(toolName);
                    String result;
                    if (handler != null) {
                        result = handler.execute(toolInput);
                    } else {
                        result = "Error: Unknown tool '" + toolName + "'";
                    }

                    if (result.length() > 10000) {
                        result = result.substring(0, 10000) + "\n... (truncated)";
                    }
                    System.out.println("[Tool] " + toolName + " -> "
                            + result.substring(0, Math.min(200, result.length())));

                    toolResults.add(AnthropicClient.toolResult(toolId, result));
                }

                messages.add(AnthropicClient.userMessage(toolResults));
            }

            System.out.print("\nYou: ");
        }
        scanner.close();
    }

    // ===== 程序入口 =====

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  S06 - Context Compaction (上下文压缩)              ║");
        System.out.println("║                                                      ║");
        System.out.println("║  Key Insight:                                        ║");
        System.out.println("║  The agent can forget strategically                  ║");
        System.out.println("║  and keep working forever.                           ║");
        System.out.println("║  智能体可以策略性地遗忘，从而永远工作下去。          ║");
        System.out.println("║                                                      ║");
        System.out.println("║  Three layers:                                       ║");
        System.out.println("║    L1: micro_compact  - shrink old tool results      ║");
        System.out.println("║    L2: auto_compact   - LLM summarizes when full     ║");
        System.out.println("║    L3: compact tool   - model requests compression   ║");
        System.out.println("╚══════════════════════════════════════════════════════╝\n");

        String workDir = System.getProperty("user.dir");
        if (args.length > 0 && !args[0].startsWith("--")) {
            workDir = args[0];
        }

        agentLoop(workDir);
    }
}
