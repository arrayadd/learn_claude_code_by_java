package learn.claude.code.s02_tool_use;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import learn.claude.code.common.AnthropicClient;
import learn.claude.code.common.BaseTools;
import learn.claude.code.common.ToolHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * ============================================================
 * S02 - Tool Use (工具使用)
 * ============================================================
 *
 * Key insight: THE LOOP DIDN'T CHANGE. I just added tools.
 * 核心洞察：循环没有变。我只是加了工具。
 *
 * Going from S01 to S02 is just:
 * - More tool definitions (告诉 LLM 有什么工具)
 * - A dispatch map (根据名字找到对应的处理函数)
 *
 * <pre>
 *   ┌─────────────────────────────────────────┐
 *   │          TOOL DISPATCH                   │
 *   │                                          │
 *   │   LLM says: "use tool X with input Y"    │
 *   │                                          │
 *   │   dispatch_map = {                       │
 *   │     "bash":       runBash(cmd),          │
 *   │     "read_file":  runRead(path),         │
 *   │     "write_file": runWrite(path,content),│
 *   │     "edit_file":  runEdit(path,old,new), │
 *   │   }                                      │
 *   │                                          │
 *   │   result = dispatch_map[X](Y)            │
 *   │                                          │
 *   │   That's it. Same loop, more tools.      │
 *   └─────────────────────────────────────────┘
 * </pre>
 */
public class S02ToolUse {

    /** API 客户端 */
    private final AnthropicClient client;

    /** 基础工具实例 —— 提供 bash、read、write、edit 四种操作 */
    private final BaseTools baseTools;

    /**
     * 工具分派表（Tool Dispatch Table）—— 从工具名到处理函数的映射。
     *
     * ===== 这是本课的核心概念 =====
     * S01 中用 if/else 判断工具名：
     *   if ("bash".equals(toolName)) { runBash(...); }
     *   else if ("read_file".equals(toolName)) { runRead(...); }
     *   else { "Unknown tool"; }
     *
     * S02 改用 Map 分派：
     *   dispatch.get(toolName).execute(input);
     *
     * ===== 为什么分派表更好？ =====
     * 1. 开放-封闭原则：新增工具只需 dispatch.put()，不修改循环代码
     * 2. 代码更简洁：无论有多少工具，执行逻辑都是一行代码
     * 3. 易于测试：可以独立测试每个 ToolHandler
     * 4. 动态扩展：运行时可以动态注册/注销工具
     *
     * ===== 对应 Python 版 =====
     * TOOL_HANDLERS = {
     *     "bash": lambda **kw: run_bash(**kw),
     *     "read_file": lambda **kw: run_read(**kw),
     *     ...
     * }
     * result = TOOL_HANDLERS[tool_name](**tool_input)
     *
     * Java 没有 dict + lambda 那么简洁，但 Map + ToolHandler 接口是等价的模式。
     */
    private final Map<String, ToolHandler> dispatch;

    /**
     * 系统提示词 —— 和 S01 完全相同。
     * 循环没变，提示词也没变，唯一的变化是工具更多了。
     */
    private static final String SYSTEM_PROMPT =
            "You are a coding agent at " + System.getProperty("user.dir") + ". Use bash to solve tasks. Act, don't explain.";

    /**
     * 构造器 —— 初始化客户端、工具实例，并构建工具分派表。
     *
     * ===== 分派表的构建 =====
     * 每个 dispatch.put() 注册一个工具：
     * - Key: 工具名（和工具定义中的 name 一致）
     * - Value: ToolHandler 匿名类，负责从 JsonObject 中提取参数并调用对应方法
     *
     * ===== 参数提取模式 =====
     * 每个 ToolHandler 都需要从 JsonObject 中提取 LLM 传入的参数。
     * 例如 bash 工具：input.get("command").getAsString()
     * 这里的 "command" 必须和工具定义的 input_schema 中的参数名一致。
     *
     * ===== 可选参数的处理 =====
     * read_file 的 limit 是可选参数：
     *   int limit = input.has("limit") ? input.get("limit").getAsInt() : 0;
     * 先用 has() 检查是否存在，不存在则使用默认值。
     * 这是处理 JSON 可选字段的标准模式。
     *
     * 注意：这里使用匿名内部类而非 Lambda 表达式，是为了兼容 JDK 1.8 之前的代码风格，
     * 同时让 Java 初学者更容易理解函数式接口的工作方式。
     */
    public S02ToolUse() {
        this.client = new AnthropicClient();
        this.baseTools = new BaseTools();

        // -- 构建分派表 --
        // 每个 entry 将工具名映射到具体的执行逻辑
        this.dispatch = new HashMap<String, ToolHandler>();

        // bash 工具：执行 shell 命令
        dispatch.put("bash", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return baseTools.runBash(input.get("command").getAsString());
            }
        });

        // read_file 工具：读取文件内容，支持可选的行数限制
        dispatch.put("read_file", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                String path = input.get("path").getAsString();
                // limit 是可选参数，不传时默认为 0（不限制）
                int limit = input.has("limit") ? input.get("limit").getAsInt() : 0;
                return baseTools.runRead(path, limit);
            }
        });

        // write_file 工具：写入文件（整体覆盖）
        dispatch.put("write_file", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return baseTools.runWrite(
                        input.get("path").getAsString(),
                        input.get("content").getAsString()
                );
            }
        });

        // edit_file 工具：精确文本替换（只替换第一次出现）
        dispatch.put("edit_file", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return baseTools.runEdit(
                        input.get("path").getAsString(),
                        input.get("old_text").getAsString(),
                        input.get("new_text").getAsString()
                );
            }
        });
    }

    /**
     * 执行工具调用 —— 通过分派表查找并执行。
     *
     * ===== 这是本课的关键抽象 =====
     * 对比 S01 的实现：
     *   S01: if ("bash".equals(name)) { ... } else if (...) { ... }
     *   S02: dispatch.get(name).execute(input);
     *
     * 一行代码替代了所有 if/else。无论有 4 个工具还是 40 个工具，
     * 这个方法都不需要修改。
     *
     * ===== 未知工具的处理 =====
     * 如果 LLM 请求了一个不存在的工具（理论上不应该发生，
     * 但 LLM 偶尔会"幻觉"出不存在的工具名），
     * 返回 "Unknown tool" 错误信息，LLM 会根据这个反馈调整行为。
     *
     * @param toolName 工具名称（来自 LLM 的 tool_use 请求）
     * @param input    工具参数（来自 LLM 的 tool_use 请求）
     * @return 工具执行结果
     */
    private String executeTool(String toolName, JsonObject input) {
        ToolHandler handler = dispatch.get(toolName);
        if (handler == null) {
            return "Unknown tool: " + toolName;
        }
        return handler.execute(input);
    }

    /**
     * 代理循环 —— 和 S01 的结构一模一样！
     *
     * ===== THE LOOP DIDN'T CHANGE（循环没有变）=====
     * 这是本课最重要的洞察。对比 S01 和 S02 的代理循环，你会发现：
     * - while(true) 结构 → 一样
     * - 调用 LLM → 一样
     * - 检查 stop_reason → 一样
     * - 遍历 tool_use block → 一样
     * - 收集 tool_result → 一样
     *
     * 唯一的区别：
     * - S01: toolDefs 只有 1 个（bash），工具执行用 if/else
     * - S02: toolDefs 有 4 个，工具执行用 dispatch.get(name).execute(input)
     *
     * 这说明代理循环是一个稳定的框架，扩展能力完全通过"添加工具"实现，
     * 而不是修改循环本身。这也是为什么 Claude Code 能不断添加新工具而核心逻辑不变。
     */
    public void agentLoop(JsonArray messages) {
        // 获取所有 4 个工具定义 —— 告诉 LLM 它现在有 bash、read、write、edit 四个工具可用
        JsonArray toolDefs = BaseTools.allToolDefs();

        // 代理循环 —— 结构和 S01 完全相同
        while (true) {
            System.out.println("[agent] Calling LLM...");
            JsonObject response = client.createMessage(SYSTEM_PROMPT, messages, toolDefs, 8000);

            JsonArray content = AnthropicClient.getContent(response);
            String stopReason = AnthropicClient.getStopReason(response);

            // 保存 assistant 回复到对话历史
            messages.add(AnthropicClient.assistantMessage(content));

            // 不是 tool_use → LLM 决定结束，退出循环
            if (!"tool_use".equals(stopReason)) {
                return;
            }

            // 执行所有工具调用 —— 一次回复中可能包含多个 tool_use
            JsonArray toolResults = new JsonArray();
            for (JsonElement el : content) {
                JsonObject block = el.getAsJsonObject();
                if (!"tool_use".equals(block.get("type").getAsString())) {
                    continue; // 跳过 text block
                }

                String toolName = block.get("name").getAsString();
                String toolId = block.get("id").getAsString();
                JsonObject input = block.getAsJsonObject("input");

                System.out.println("[agent] Tool: " + toolName);

                // 通过分派表执行 —— 这是和 S01 唯一不同的地方
                // 不再需要 if/else 判断工具名，一行代码搞定
                String result = executeTool(toolName, input);
                System.out.println("[agent] Result: " + truncate(result, 200));

                toolResults.add(AnthropicClient.toolResult(toolId, result));
            }

            // 工具结果作为 user 消息追加，然后继续循环
            messages.add(AnthropicClient.userMessage(toolResults));
            // 继续循环 —— LLM 会看到工具结果，决定下一步...
        }
    }

    /** 截断长文本用于日志输出，避免控制台被刷屏 */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * REPL 入口 —— 和 S01 的 main 方法结构完全相同。
     *
     * ===== S01 到 S02 的变化总结 =====
     * 1. 工具从 1 个变为 4 个（bash、read_file、write_file、edit_file）
     * 2. 工具执行从 if/else 变为分派表（Map<String, ToolHandler>）
     * 3. 代理循环结构 —— 没有变
     * 4. REPL 结构 —— 没有变
     *
     * 这证明了一个重要结论：AI 编程代理的能力扩展，
     * 主要靠"添加工具"而不是"改写核心逻辑"。
     * Claude Code 从最初版本到现在，核心循环几乎没变过，
     * 但工具集从几个扩展到了十几个（Bash、Read、Write、Edit、Glob、Grep、
     * WebSearch、NotebookEdit 等等）。
     */
    public static void main(String[] args) {
        System.out.println("=== S02: Tool Use ===");
        System.out.println("Same loop as S01, but now with 4 tools: bash, read, write, edit.");
        System.out.println("Key insight: The loop didn't change. I just added tools.");
        System.out.println("Type 'quit' to exit.\n");

        S02ToolUse agent = new S02ToolUse();
        Scanner scanner = new Scanner(System.in);
        // 多轮对话共享的消息列表 —— 累积上下文
        JsonArray messages = new JsonArray();

        // REPL 循环 —— 和 S01 完全相同
        while (true) {
            System.out.print("You> ");
            if (!scanner.hasNextLine()) break;
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;
            if ("quit".equalsIgnoreCase(input) || "exit".equalsIgnoreCase(input)) break;

            try {
                // 追加用户消息 → 启动代理循环 → 提取最终回复
                messages.add(AnthropicClient.userMessage(input));
                agent.agentLoop(messages);
                // 代理循环结束后，最后一条消息是 assistant 的回复
                JsonObject lastMsg = messages.get(messages.size() - 1).getAsJsonObject();
                JsonArray content = lastMsg.getAsJsonArray("content");
                String text = AnthropicClient.extractText(content);
                System.out.println("\nAssistant> " + text + "\n");
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        }
        scanner.close();
        System.out.println("Goodbye!");
    }
}
