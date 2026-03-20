package learn.claude.code.s01_agent_loop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import learn.claude.code.common.AnthropicClient;
import learn.claude.code.common.BaseTools;

import java.util.Scanner;

/**
 * ============================================================
 * S01 - The Agent Loop (代理循环)
 * ============================================================
 *
 * THIS IS THE ENTIRE SECRET OF AN AI CODING AGENT.
 * 这就是 AI 编程代理的全部秘密。
 *
 * The agent loop is embarrassingly simple:
 * 代理循环简单得令人尴尬：
 *
 *     while (stop_reason == "tool_use"):
 *         response = call_llm(messages, tools)
 *         execute_tools(response)
 *         append_results(messages)
 *
 * That's it. That's the whole thing.
 * 就这些。这就是全部。
 *
 * <pre>
 *   ┌─────────────────────────────────────────┐
 *   │          THE AGENT LOOP                  │
 *   │                                          │
 *   │   User ──► LLM ──► Tool ──► LLM ──►...  │
 *   │              │                  │        │
 *   │              ▼                  ▼        │
 *   │          "tool_use"        "end_turn"    │
 *   │          (continue)          (stop)      │
 *   └─────────────────────────────────────────┘
 *
 *   It's just a while loop. The LLM decides when to stop.
 *   这只是一个 while 循环。LLM 决定何时停止。
 * </pre>
 *
 * In this lesson we use only ONE tool: bash.
 * 在本课中，我们只用一个工具：bash。
 */
public class S01AgentLoop {

    /** API 客户端 —— 负责与 Anthropic Messages API 通信 */
    private final AnthropicClient client;

    /** 工具实例 —— 提供 bash 命令执行能力 */
    private final BaseTools tools;

    /**
     * 系统提示词（System Prompt）—— 定义 AI 代理的角色和行为准则。
     *
     * ===== 为什么系统提示词很重要？ =====
     * 系统提示词是 LLM 的"人格设定"，决定了它如何理解和回应用户请求。
     * 几个关键设计点：
     *
     * 1. "You are a coding agent" —— 告诉 LLM 它是一个编程代理，而不是聊天机器人
     * 2. "at {workDir}" —— 告诉 LLM 当前工作目录，这样它执行命令时才知道自己在哪里
     * 3. "Act, don't explain" —— 鼓励 LLM 直接行动（执行命令）而不是长篇大论解释
     *
     * Claude Code 的真实系统提示词要复杂得多（几千字），包含了：
     * - 详细的工具使用指南
     * - 安全约束规则
     * - 输出格式要求
     * - 各种边界情况的处理指导
     */
    private static final String SYSTEM_PROMPT =
            "You are a coding agent at " + System.getProperty("user.dir") + ". Use bash to solve tasks. Act, don't explain.";

    public S01AgentLoop() {
        this.client = new AnthropicClient();
        this.tools = new BaseTools();
    }

    /**
     * 构建工具定义列表 —— 本课刻意只注册一个 bash 工具。
     *
     * 为什么只用一个工具？
     * 这是教学设计：S01 的目标是让你理解"代理循环"本身的机制，
     * 工具越少越好，避免被工具的复杂性分散注意力。
     * 到 S02 课程时，会扩展到 4 个工具（bash、read_file、write_file、edit_file），
     * 届时你会发现：循环结构完全没变，只是工具多了而已。
     */
    private static JsonArray buildTools() {
        JsonArray toolDefs = new JsonArray();
        toolDefs.add(BaseTools.bashToolDef());
        return toolDefs;
    }

    /**
     * THE AGENT LOOP - 代理循环，核心中的核心。
     *
     * ===== 这就是 AI 编程代理的全部秘密 =====
     * 整个函数做的事情（伪代码）：
     *   while (true):
     *       response = call_llm(messages)        // 1. 调用 LLM
     *       messages.append(response)             // 2. 保存回复到对话历史
     *       if response.stop_reason != "tool_use":
     *           return                            // 3. LLM 说"完了" → 退出
     *       for tool_call in response:
     *           result = execute(tool_call)        // 4. 执行工具
     *           messages.append(result)            // 5. 把结果追加到对话历史
     *       // 回到 1，再次调用 LLM...
     *
     * ===== 为什么 messages 是传入参数而不是内部创建？ =====
     * 因为 messages 需要在多轮对话中保持（REPL 中用户可以连续提问），
     * 每轮对话的上下文都累积在同一个 messages 数组中。
     * 这就是"多轮对话"的实现原理 —— LLM 能看到之前所有的对话历史。
     *
     * ===== 数据流详解 =====
     * 用户输入 "list files" →
     *   messages: [{role:"user", content:"list files"}]
     *   → 调用 LLM → 返回 {tool_use: bash, input: {command: "ls"}}
     *   messages: [user, {role:"assistant", content:[{type:"tool_use",...}]}]
     *   → 执行 bash("ls") → 得到 "file1.txt\nfile2.java"
     *   messages: [user, assistant, {role:"user", content:[{type:"tool_result",...}]}]
     *   → 再次调用 LLM → 返回 {text: "当前目录有以下文件：..."}，stop_reason="end_turn"
     *   messages: [user, assistant, user, {role:"assistant", content:[{type:"text",...}]}]
     *   → 循环结束，返回
     *
     * @param messages 共享的消息列表（调用前需追加用户消息）
     */
    public void agentLoop(JsonArray messages) {
        JsonArray toolDefs = buildTools();

        // ============================================
        // THE LOOP - 这就是全部秘密
        // 一个 while(true) 循环，LLM 自己决定何时退出
        // ============================================
        while (true) {
            // ---- Step 1: 调用 LLM ----
            // 将完整的对话历史 + 系统提示词 + 工具定义发给 API
            // LLM 会综合所有信息做出决策：是回复文本还是调用工具
            System.out.println("[agent] Calling LLM "+client.getModel()+"...");
            JsonObject response = client.createMessage(SYSTEM_PROMPT, messages, toolDefs, 8000);

            // 从响应中提取 content（回复内容）和 stop_reason（停止原因）
            JsonArray content = AnthropicClient.getContent(response);
            String stopReason = AnthropicClient.getStopReason(response);

            // ---- Step 2: 把 assistant 的回复追加到消息列表 ----
            // 这一步至关重要：必须把 LLM 的回复（包括 tool_use 请求）原样保存，
            // 这样下一轮调用时 LLM 能看到自己之前说了什么、用了什么工具。
            messages.add(AnthropicClient.assistantMessage(content));

            // ---- Step 3: 检查停止原因 ----
            // stop_reason == "end_turn" → LLM 认为任务已完成，主动结束
            // stop_reason == "tool_use" → LLM 想调用工具，需要我们执行后继续
            // 其他情况（如 "max_tokens"）也视为结束
            if (!"tool_use".equals(stopReason)) {
                return; // LLM 决定停止了，退出循环
            }

            // ---- Step 4: 执行工具调用，收集结果 ----
            // content 数组中可能包含多个 block（text + tool_use），
            // 我们只处理 type="tool_use" 的 block
            JsonArray toolResults = new JsonArray();
            for (JsonElement el : content) {
                JsonObject block = el.getAsJsonObject();
                if (!"tool_use".equals(block.get("type").getAsString())) {
                    continue; // 跳过 text block
                }
                String toolName = block.get("name").getAsString();  // 工具名称
                String toolId = block.get("id").getAsString();      // 工具调用 ID（用于关联结果）
                JsonObject input = block.getAsJsonObject("input");  // 工具参数

                System.out.println("[agent] Tool call: " + toolName);

                // 本课只有 bash 一个工具，直接用 if/else 判断
                // （到 S02 课程时，会改用更优雅的分派表模式）
                String result;
                if ("bash".equals(toolName)) {
                    String command = input.get("command").getAsString();
                    System.out.println("[agent] $ " + command);
                    result = tools.runBash(command);
                } else {
                    result = "Unknown tool: " + toolName;
                }

                System.out.println("[agent] Result: " + truncate(result, 200));
                // 构建 tool_result，必须带上 toolId 以关联到对应的 tool_use 请求
                toolResults.add(AnthropicClient.toolResult(toolId, result));
            }

            // ---- Step 5: 把工具结果作为 user 消息追加 ----
            // 为什么是 user 角色？因为 Anthropic API 的设计是：
            // 工具在"用户侧"执行，结果通过 user 消息传回给 LLM。
            // 这维持了 user/assistant 严格交替的消息结构。
            messages.add(AnthropicClient.userMessage(toolResults));

            // 然后回到 Step 1，再次调用 LLM...
            // LLM 看到工具执行结果后，会决定：
            // - 继续调用更多工具（stop_reason = "tool_use"）
            // - 生成最终回复（stop_reason = "end_turn"）
            // The loop continues. That's the whole agent.
        }
    }

    /**
     * 截断长文本用于日志输出 —— 防止控制台被超长的工具输出刷屏。
     * 只用于日志显示，不影响传给 LLM 的数据（LLM 会收到完整的工具结果）。
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * REPL（Read-Eval-Print Loop）—— 交互式读取-求值-输出循环。
     *
     * ===== REPL 的结构 =====
     * 外层循环：REPL（用户持续输入，直到 quit）
     * 内层循环：Agent Loop（LLM 持续调用工具，直到 end_turn）
     *
     * 这两层循环的嵌套关系：
     *   REPL循环 {
     *       读取用户输入
     *       Agent循环 {
     *           调用 LLM
     *           执行工具（0 次或多次）
     *       }
     *       打印最终回复
     *   }
     *
     * ===== 为什么 messages 在 REPL 循环外创建？ =====
     * 这样每轮对话的上下文都会累积，LLM 能记住之前的对话内容。
     * 如果每轮都创建新的 messages，LLM 就会"失忆"。
     * 这就是"多轮对话"和"单轮对话"的区别。
     *
     * ===== 在 Claude Code 真实实现中 =====
     * Claude Code 的 REPL 更复杂，支持：
     * - 流式输出（打字机效果）
     * - 上下文管理（太长时会自动压缩）
     * - 权限确认（危险操作前要求用户同意）
     * - 多种退出方式（Ctrl+C、/exit 等）
     */
    public static void main(String[] args) {
        System.out.println("=== S01: The Agent Loop ===");
        System.out.println("The ENTIRE secret of an AI coding agent: a while loop.");
        System.out.println("Type 'quit' to exit.\n");

        S01AgentLoop agent = new S01AgentLoop();
        Scanner scanner = new Scanner(System.in);
        // messages 在循环外创建 —— 累积多轮对话的上下文
        JsonArray messages = new JsonArray();

        // ===== REPL 外层循环 =====
        while (true) {
            System.out.print("You> ");
            if (!scanner.hasNextLine()) break; // EOF（管道输入结束）
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue; // 忽略空行
            if ("quit".equalsIgnoreCase(input) || "exit".equalsIgnoreCase(input)) break;

            try {
                // 将用户输入追加到消息列表
                messages.add(AnthropicClient.userMessage(input));
                // 启动代理循环 —— LLM 会反复调用工具直到任务完成
                agent.agentLoop(messages);
                // 循环结束后，messages 最后一条消息就是 assistant 的最终回复
                // 从中提取文本内容并打印给用户
                JsonObject lastMsg = messages.get(messages.size() - 1).getAsJsonObject();
                JsonArray content = lastMsg.getAsJsonArray("content");
                String text = AnthropicClient.extractText(content);
                System.out.println("\nAssistant> " + text + "\n");
            } catch (Exception e) {
                // 捕获异常但不退出 REPL —— 让用户可以继续使用
                System.err.println("Error: " + e.getMessage());
            }
        }
        scanner.close();
        System.out.println("Goodbye!");
    }
}
