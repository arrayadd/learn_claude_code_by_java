package learn.claude.code.s04_subagent;

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
 * S04 - Subagent (子代理)
 * ============================================================
 *
 * 【与前一课的关系 / 从 S03 的演进】
 * S03 通过 todo 工具让模型能追踪进度，但所有工作仍在同一个上下文中进行。
 * 当父任务需要做很多子探索（如读取多个文件、尝试不同方案）时，
 * 这些中间过程会不断膨胀 messages 数组，消耗大量 token 并可能让模型"迷失"。
 * S04 引入了子代理模式，通过上下文隔离来解决这个问题。
 *
 * 【核心洞察 / Core Insight】
 * Key insight: "Process isolation gives context isolation for free."
 * 核心洞察："进程隔离自动带来上下文隔离。"
 * 在 Claude Code 的真实实现中，subagent 通过 Agent tool 实现，
 * 每个 subagent 是一个独立的 Claude 对话，有自己的 messages 和 system prompt。
 *
 * 【上下文隔离的好处】
 * The parent agent can spawn child agents with fresh context.
 * Each child gets its own empty messages=[], runs its own loop,
 * and returns ONLY the final text summary to the parent.
 * 父代理可以生成拥有全新上下文的子代理。
 * 每个子代理有自己的空 messages=[]，运行自己的循环，
 * 只把最终文本摘要返回给父代理。
 *
 * 好处1：父代理的上下文不会被子任务的细节污染（token 节省）
 * 好处2：子代理可以自由探索，失败了也不影响父代理
 * 好处3：子任务的结果经过"摘要"后更精炼，父代理更容易理解
 *
 * 【防止上下文污染】
 * This prevents context pollution - the child's internal tool
 * calls and intermediate results don't clutter the parent's context.
 * 这防止了上下文污染 - 子代理的内部工具调用和中间结果
 * 不会污染父代理的上下文。
 *
 * 【递归防护 / Recursion Guard】
 * 子代理没有 task 工具，因此不能再创建子子代理。
 * 这是通过"工具可见性控制"来实现的递归深度限制。
 * Claude Code 也有类似的设计——subagent 的工具集是父代理工具集的子集。
 *
 * <pre>
 *   ┌──────────────────────────────────────────────┐
 *   │              PARENT AGENT                     │
 *   │                                               │
 *   │   tools: bash, read, write, edit, task         │
 *   │                                               │
 *   │   "task" tool ──► spawn CHILD AGENT           │
 *   │                    │                          │
 *   │                    │  fresh messages=[]        │
 *   │                    │  tools: bash,read,write,  │
 *   │                    │         edit (NO task!)   │
 *   │                    │                          │
 *   │                    │  runs own agent loop     │
 *   │                    │  ...                     │
 *   │                    │                          │
 *   │                    ▼                          │
 *   │              return summary text only          │
 *   │              (context stays isolated)          │
 *   └──────────────────────────────────────────────┘
 *
 *   Child has NO "task" tool → cannot recursively spawn.
 *   子代理没有 "task" 工具 → 不能递归生成子代理。
 * </pre>
 */
public class S04Subagent {

    /** Anthropic API 客户端，父代理和子代理共享同一个客户端实例 */
    private final AnthropicClient client;

    /**
     * 基础工具集合，父代理和子代理共享。
     * 共享 baseTools 是安全的——它只是工具的执行器，不持有对话状态。
     * 上下文隔离发生在 messages 层面，而非工具层面。
     */
    private final BaseTools baseTools;

    /**
     * 父代理的系统提示词。
     * 关键指令："Use the task tool to delegate exploration or subtasks"
     * 引导模型将独立的子任务委派给子代理，而非自己在主上下文中执行。
     */
    private static final String PARENT_SYSTEM_PROMPT =
            "You are a coding agent at " + System.getProperty("user.dir") + ". " +
            "Use the task tool to delegate exploration or subtasks.";

    /**
     * 子代理的系统提示词。
     * 与父代理不同的两点：
     * 1. 自称 "subagent" 而非 "agent"——帮助模型理解自己的角色
     * 2. "summarize your findings" 引导子代理在完成后给出精炼的摘要
     *    （而非冗长的过程描述），这对父代理的 token 效率至关重要
     */
    private static final String CHILD_SYSTEM_PROMPT =
            "You are a coding subagent at " + System.getProperty("user.dir") + ". " +
            "Complete the given task, then summarize your findings.";

    public S04Subagent() {
        this.client = new AnthropicClient();
        this.baseTools = new BaseTools();
    }

    // =================================================================
    // 子代理 - 隔离的上下文
    // =================================================================

    /**
     * 运行子代理 - S04 的核心方法。
     *
     * 【设计要点】
     * 1. 全新的 messages=[] -> 这就是上下文隔离的全部秘密！
     *    子代理的 messages 和父代理的 messages 完全独立，
     *    子代理执行过程中产生的所有工具调用、中间结果都不会进入父代理的上下文。
     *
     * 2. 只有 4 个基础工具，没有 task -> 递归防护。
     *    如果子代理也有 task 工具，它就能无限递归地创建子子代理，
     *    可能导致无限循环和资源耗尽。通过限制工具集来断开递归。
     *
     * 3. 只返回最终文本 -> 信息压缩。
     *    子代理可能执行了 10 次工具调用，但父代理只看到一段摘要文本。
     *    这是一种"有损压缩"：丢弃了过程细节，保留了最终结论。
     *    代价是父代理无法追问子代理的中间步骤（上下文已丢弃）。
     *
     * 【与 Claude Code 的对应】
     * 在 Claude Code 中，Agent tool 就是这样实现的——
     * 创建一个新的 Claude 对话，传入 prompt，等待完成后取回 summary。
     *
     * @param prompt 子任务描述，会作为子代理的第一条 user message
     * @return 子代理的最终文本回复（摘要）
     */
    private String runSubagent(String prompt) {
        System.out.println("[subagent] Starting with prompt: " + truncate(prompt, 100));

        // -- 全新的消息列表：这一行代码就实现了"上下文隔离" --
        // 父代理的 messages 不会传给子代理，子代理从零开始
        JsonArray messages = new JsonArray();
        messages.add(AnthropicClient.userMessage(prompt));

        // 子代理只有 4 个基础工具（刻意排除 task 工具，防止递归生成子代理）
        JsonArray toolDefs = BaseTools.allToolDefs();

        // 构建子代理独立的工具分派表
        // 注意：虽然共享同一个 baseTools 实例，但这没有问题，
        // 因为 baseTools 是无状态的工具执行器，上下文隔离发生在 messages 层面
        Map<String, ToolHandler> childDispatch = new HashMap<String, ToolHandler>();
        childDispatch.put("bash", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return baseTools.runBash(input.get("command").getAsString());
            }
        });
        childDispatch.put("read_file", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                String path = input.get("path").getAsString();
                int limit = input.has("limit") ? input.get("limit").getAsInt() : 0;
                return baseTools.runRead(path, limit);
            }
        });
        childDispatch.put("write_file", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return baseTools.runWrite(
                        input.get("path").getAsString(),
                        input.get("content").getAsString()
                );
            }
        });
        childDispatch.put("edit_file", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return baseTools.runEdit(
                        input.get("path").getAsString(),
                        input.get("old_text").getAsString(),
                        input.get("new_text").getAsString()
                );
            }
        });

        // -- 子代理自己的 agent loop，结构和父代理完全相同 --
        // 限制最多 30 轮迭代，防止子代理陷入无限循环。
        // 这是一个安全阈值：正常子任务不应需要 30 轮工具调用。
        // Claude Code 中也有类似的迭代上限机制。
        for (int iter = 0; iter < 30; iter++) {
            System.out.println("[subagent] Calling LLM...");
            JsonObject response = client.createMessage(CHILD_SYSTEM_PROMPT, messages, toolDefs, 8000);

            JsonArray content = AnthropicClient.getContent(response);
            String stopReason = AnthropicClient.getStopReason(response);

            messages.add(AnthropicClient.assistantMessage(content));

            // 当 stop_reason 不是 tool_use 时，子代理完成了任务
            // 此时提取文本作为摘要返回给父代理
            // 这就是"信息压缩"的关键点：子代理的所有中间过程被丢弃，只保留最终文本
            if (!"tool_use".equals(stopReason)) {
                String result = AnthropicClient.extractText(content);
                if (result == null || result.isEmpty()) {
                    result = "(no summary)";
                }
                System.out.println("[subagent] Done. Summary: " + truncate(result, 100));
                return result;  // 只有这个 string 会进入父代理的上下文
            }

            JsonArray toolResults = new JsonArray();
            for (JsonElement el : content) {
                JsonObject block = el.getAsJsonObject();
                if (!"tool_use".equals(block.get("type").getAsString())) {
                    continue;
                }

                String toolName = block.get("name").getAsString();
                String toolId = block.get("id").getAsString();
                JsonObject input = block.getAsJsonObject("input");

                System.out.println("[subagent] Tool: " + toolName);

                ToolHandler handler = childDispatch.get(toolName);
                String result;
                if (handler != null) {
                    result = handler.execute(input);
                } else {
                    result = "Unknown tool: " + toolName;
                }

                System.out.println("[subagent] Result: " + truncate(result, 150));
                toolResults.add(AnthropicClient.toolResult(toolId, result));
            }

            messages.add(AnthropicClient.userMessage(toolResults));
        }
        // 安全兜底：如果 30 轮后子代理仍未完成，强制返回错误信息
        // 父代理会看到这个错误，可以决定重试或放弃
        return "(subagent reached iteration limit)";
    }

    // =================================================================
    // 父代理工具定义
    // =================================================================

    /**
     * 构建父代理的工具列表：4 个基础工具 + task 工具。
     *
     * 【task 工具的设计】
     * task 工具是本课的核心新增，它是父代理"委派"子代理的接口。
     * - prompt（必填）：传给子代理的任务描述，会成为子代理的第一条 user message
     * - description（选填）：可读的任务描述，仅用于日志输出
     *
     * 【工具描述的措辞很重要】
     * "runs in isolation with its own context" 告诉模型子代理是独立运行的，
     * "returns a text summary" 告诉模型只能拿回摘要，不能追问子代理的过程。
     * "Use this for independent sub-tasks" 引导模型只把独立的子任务委派出去，
     * 而不是把需要主上下文信息的任务也委派（那样子代理无法完成）。
     */
    private static JsonArray buildParentTools() {
        JsonArray toolDefs = BaseTools.allToolDefs();

        // task 工具定义：prompt 是必填的（子代理的任务），description 是选填的（日志用）
        JsonObject taskSchema = AnthropicClient.schema(
                "prompt", "string", "true",
                "description", "string", "false"
        );
        toolDefs.add(AnthropicClient.toolDef("task",
                "Delegate a task to a sub-agent. The sub-agent runs in isolation with its own context " +
                "and returns a text summary. Use this for independent sub-tasks that don't need your context.",
                taskSchema));

        return toolDefs;
    }

    // =================================================================
    // 父代理循环
    // =================================================================

    /**
     * 父代理的 agent loop。
     *
     * 【与 S02/S03 的对比】
     * 循环结构与 S02/S03 完全相同（LLM 调用 -> stop_reason 检查 -> 工具执行 -> 循环）。
     * 不同之处在于工具集：S04 多了 task 工具。
     * 当模型调用 task 时，我们不是执行一个简单的操作，而是启动 runSubagent()，
     * 这相当于在工具执行阶段"嵌套"了一个完整的 agent loop。
     *
     * 【注意】
     * 父代理的循环是 while(true) 无限循环（由 stop_reason 控制退出），
     * 而子代理的循环有 30 轮上限。这是因为父代理由用户控制退出，
     * 而子代理需要自动终止的安全保障。
     */
    public String agentLoop(JsonArray messages) {
        JsonArray toolDefs = buildParentTools();

        // 父代理的工具分派表：4 个基础工具 + task 工具
        // 注意 task 工具的处理器会调用 runSubagent()，这是嵌套 agent loop 的入口
        Map<String, ToolHandler> parentDispatch = new HashMap<String, ToolHandler>();
        parentDispatch.put("bash", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return baseTools.runBash(input.get("command").getAsString());
            }
        });
        parentDispatch.put("read_file", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                String path = input.get("path").getAsString();
                int limit = input.has("limit") ? input.get("limit").getAsInt() : 0;
                return baseTools.runRead(path, limit);
            }
        });
        parentDispatch.put("write_file", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return baseTools.runWrite(
                        input.get("path").getAsString(),
                        input.get("content").getAsString()
                );
            }
        });
        parentDispatch.put("edit_file", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return baseTools.runEdit(
                        input.get("path").getAsString(),
                        input.get("old_text").getAsString(),
                        input.get("new_text").getAsString()
                );
            }
        });

        // task 工具 -> 启动子代理（S04 的核心逻辑）
        // 当模型调用 task 工具时，我们创建一个全新的 agent loop（子代理）
        // runSubagent() 内部会：
        //   1. 创建空的 messages=[]（上下文隔离）
        //   2. 使用子代理的 system prompt（角色定义）
        //   3. 运行完整的 agent loop（最多 30 轮）
        //   4. 返回最终文本摘要（信息压缩）
        parentDispatch.put("task", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                String prompt = input.get("prompt").getAsString();
                String desc = input.has("description") ? input.get("description").getAsString() : "subtask";
                System.out.println("[parent] Delegating: " + desc);
                // runSubagent 是同步阻塞的：父代理会等待子代理完成
                // 在真实的 Claude Code 中，subagent 也是同步的
                return runSubagent(prompt);
            }
        });

        while (true) {
            System.out.println("[parent] Calling LLM...");
            JsonObject response = client.createMessage(PARENT_SYSTEM_PROMPT, messages, toolDefs, 8000);

            JsonArray content = AnthropicClient.getContent(response);
            String stopReason = AnthropicClient.getStopReason(response);

            messages.add(AnthropicClient.assistantMessage(content));

            if (!"tool_use".equals(stopReason)) {
                return AnthropicClient.extractText(content);
            }

            JsonArray toolResults = new JsonArray();
            for (JsonElement el : content) {
                JsonObject block = el.getAsJsonObject();
                if (!"tool_use".equals(block.get("type").getAsString())) {
                    continue;
                }

                String toolName = block.get("name").getAsString();
                String toolId = block.get("id").getAsString();
                JsonObject input = block.getAsJsonObject("input");

                System.out.println("[parent] Tool: " + toolName);

                ToolHandler handler = parentDispatch.get(toolName);
                String result;
                if (handler != null) {
                    result = handler.execute(input);
                } else {
                    result = "Unknown tool: " + toolName;
                }

                System.out.println("[parent] Result: " + truncate(result, 200));
                toolResults.add(AnthropicClient.toolResult(toolId, result));
            }

            messages.add(AnthropicClient.userMessage(toolResults));
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    public static void main(String[] args) {
        System.out.println("=== S04: Subagent ===");
        System.out.println("Parent agent can spawn child agents with fresh, isolated context.");
        System.out.println("Key insight: Process isolation gives context isolation for free.");
        System.out.println("Type 'quit' to exit.\n");

        S04Subagent agent = new S04Subagent();
        Scanner scanner = new Scanner(System.in);
        JsonArray messages = new JsonArray();

        while (true) {
            System.out.print("You> ");
            if (!scanner.hasNextLine()) break;
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;
            if ("quit".equalsIgnoreCase(input) || "exit".equalsIgnoreCase(input)) break;

            try {
                messages.add(AnthropicClient.userMessage(input));
                String response = agent.agentLoop(messages);
                // Extract and print last assistant text
                JsonObject lastMsg = messages.get(messages.size() - 1).getAsJsonObject();
                String lastText = AnthropicClient.extractText(lastMsg.getAsJsonArray("content"));
                System.out.println("\nAssistant> " + lastText + "\n");
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        }
        scanner.close();
        System.out.println("Goodbye!");
    }
}
