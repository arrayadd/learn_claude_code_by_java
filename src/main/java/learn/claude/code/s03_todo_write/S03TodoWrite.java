package learn.claude.code.s03_todo_write;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import learn.claude.code.common.AnthropicClient;
import learn.claude.code.common.BaseTools;
import learn.claude.code.common.ToolHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * ============================================================
 * S03 - TodoWrite (Todo 追踪)
 * ============================================================
 *
 * 【与前一课的关系 / 从 S02 的演进】
 * S02 实现了基础的 agentic loop（代理循环），模型可以调用工具并循环执行。
 * 但 S02 的模型是"无状态"的——它不会主动追踪自己完成了哪些步骤。
 * S03 在 S02 的基础上新增了 todo 工具，让模型能够自我管理进度。
 * 这对应了 Claude Code 真实实现中的 TodoWrite / TaskCreate / TaskUpdate 工具。
 *
 * 【核心概念 / Core Concept】
 * New concept: The model tracks its own progress.
 * 新概念：让模型跟踪自己的进度。
 *
 * 【为什么需要 Todo 追踪？】
 * 在复杂的多步骤任务中，如果模型不记录进度，它可能会：
 * 1. 遗忘已完成的步骤，重复执行同样的操作
 * 2. 跳过关键步骤，导致任务不完整
 * 3. 在长对话中迷失方向，失去对整体目标的把控
 * Todo 列表为模型提供了一个"外部记忆"，帮助它保持结构化的工作流程。
 *
 * 【设计模式 / Design Pattern】
 * The TodoManager lets the LLM maintain a list of tasks.
 * It updates them as it works, giving it a sense of progress.
 * TodoManager 让 LLM 维护一个任务列表。
 * 它在工作时更新任务，给它一种进度感。
 *
 * 【Nag 提醒模式 / Nag Pattern】
 * Bonus: if the model hasn't updated its todo list in 3 rounds,
 * we inject a reminder. This is the "nag" pattern.
 * 额外功能：如果模型 3 轮没有更新 todo，我们注入一个提醒。
 * 这是 Claude Code 中真实存在的模式——系统会通过注入 system-reminder
 * 来"催促"模型更新进度。这种注入不是用户消息，而是作为 tool_result
 * 中额外的 text 块插入，确保模型能感知到提醒但不会混淆消息来源。
 *
 * <pre>
 *   ┌─────────────────────────────────────────┐
 *   │        TODO-DRIVEN AGENT                 │
 *   │                                          │
 *   │   ┌──────────┐   ┌──────────────────┐   │
 *   │   │ TodoList  │   │  Agent Loop      │   │
 *   │   │           │   │                  │   │
 *   │   │ [ ] task1 │◄──│  todo tool call  │   │
 *   │   │ [~] task2 │   │                  │   │
 *   │   │ [x] task3 │   │  3 rounds with   │   │
 *   │   │           │──►│  no update?      │   │
 *   │   │           │   │  INJECT REMINDER │   │
 *   │   └──────────┘   └──────────────────┘   │
 *   │                                          │
 *   │   Status: pending → in_progress → done   │
 *   └─────────────────────────────────────────┘
 * </pre>
 */
public class S03TodoWrite {

    /** Anthropic API 客户端，用于与 Claude 模型通信 */
    private final AnthropicClient client;

    /** 基础工具集合（bash/read/write/edit），复用 S01/S02 中的实现 */
    private final BaseTools baseTools;

    /**
     * 工具分派表（dispatch table）：工具名 -> 处理器。
     * 这是 S02 中引入的模式：用 Map 来路由工具调用，避免大量 if-else。
     * S03 在此基础上增加了 "todo" 这个新工具。
     */
    private final Map<String, ToolHandler> dispatch;

    /**
     * Todo 管理器实例，持有整个 agent 生命周期内的任务列表。
     * 这是 S03 相比 S02 新增的核心组件。
     */
    private final TodoManager todoManager;

    /**
     * 系统提示词。
     * 相比 S02 新增了 todo 工具的使用指导：
     * - "Use the todo tool to plan multi-step tasks" 引导模型主动使用 todo
     * - "Mark in_progress before starting, completed when done" 教模型正确的状态流转
     * - "Prefer tools over prose" 让模型倾向于调用工具而非纯文字回复
     */
    private static final String SYSTEM_PROMPT =
            "You are a coding agent at " + System.getProperty("user.dir") + ".\n" +
            "Use the todo tool to plan multi-step tasks. Mark in_progress before starting, completed when done.\n" +
            "Prefer tools over prose.";

    public S03TodoWrite() {
        this.client = new AnthropicClient();
        this.baseTools = new BaseTools();
        this.todoManager = new TodoManager();

        // 5 个工具：4 个基础工具（bash/read_file/write_file/edit_file）+ 1 个 todo 工具
        // 相比 S02 的 4 个基础工具，新增了 todo 工具用于进度追踪
        this.dispatch = new HashMap<String, ToolHandler>();

        dispatch.put("bash", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return baseTools.runBash(input.get("command").getAsString());
            }
        });

        dispatch.put("read_file", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                String path = input.get("path").getAsString();
                int limit = input.has("limit") ? input.get("limit").getAsInt() : 0;
                return baseTools.runRead(path, limit);
            }
        });

        dispatch.put("write_file", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return baseTools.runWrite(
                        input.get("path").getAsString(),
                        input.get("content").getAsString()
                );
            }
        });

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

        // todo 工具 - S03 的核心新增
        // 设计要点：模型每次调用 todo 工具时，传入的是完整的任务列表（而非增量更新）。
        // 这是 Claude Code 的真实设计选择——用"全量替换"而非"增量操作"简化了状态管理。
        // 好处：避免了 add/remove/update 等多个子命令的复杂性，模型只需维护一个数组。
        // 代价：每次调用都传输完整列表，但 todo 列表通常很小（限制 20 条），所以开销可接受。
        dispatch.put("todo", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                JsonArray items = input.getAsJsonArray("items");
                // update() 返回 null 表示成功，返回字符串表示错误信息
                String error = todoManager.update(items);
                if (error != null) {
                    return error;  // 将错误信息直接返回给模型，让它自行修正
                }
                // 成功后返回渲染后的 todo 列表，让模型能"看到"当前状态
                return todoManager.render();
            }
        });
    }

    // =================================================================
    // TodoManager - 内部类，管理 todo 列表
    // =================================================================

    /**
     * Todo 管理器 - S03 的核心数据结构。
     *
     * 【状态模型】
     * 每个 item 有: id, text, status (pending / in_progress / completed)
     * 状态流转：pending -> in_progress -> completed
     * 这对应 Claude Code 中 TaskCreate/TaskUpdate 的状态机。
     *
     * 【约束条件】
     * - 最多 20 个 todo（防止模型创建无限多的任务，浪费 token）
     * - 同时只能有 1 个 in_progress（强制模型专注于单一任务，避免多线程思维混乱）
     * - text 不能为空（保证每个任务都有明确的描述）
     *
     * 【Nag 机制】
     * roundsSinceUpdate 计数器追踪模型上次更新 todo 后经过了多少轮。
     * 超过 3 轮未更新，agentLoop 会注入提醒。这是一种"软约束"——
     * 不是强制模型更新，而是温和地提醒它，让模型自行决定是否需要更新。
     *
     * 模型通过 todo 工具来更新列表。
     */
    static class TodoManager {
        /** 当前的 todo 列表，由模型通过 todo 工具全量更新 */
        private final List<TodoItem> items;

        /**
         * 自上次 todo 更新以来经过的轮数。
         * 这是 Nag 提醒机制的核心：当此值 >= 3 时触发提醒注入。
         * 每次模型调用 todo 工具时重置为 0，每轮不调用 todo 则 +1。
         */
        private int roundsSinceUpdate;

        TodoManager() {
            this.items = new ArrayList<TodoItem>();
            this.roundsSinceUpdate = 0;
        }

        /**
         * 模型调用 todo 工具时，用传入的列表全量替换当前列表。
         *
         * 【全量替换 vs 增量更新】
         * 这里采用"全量替换"策略：每次调用都传入完整的 items 数组。
         * 优点：实现简单，不需要 add/remove/update 等子命令
         * 缺点：每次都传输完整列表（但 todo 通常很少，开销可忽略）
         *
         * 【验证先于应用 / Validate-before-apply】
         * 先解析验证所有 item，全部通过后才 clear + addAll。
         * 如果验证失败，返回错误字符串，原有列表不受影响。
         * 这是一种"事务性"更新——要么全部成功，要么全部失败。
         *
         * @return null 表示成功；非 null 为错误信息，原列表不变
         */
        String update(JsonArray newItems) {
            // 限制最大 20 条，防止模型创建过多任务浪费 token
            if (newItems.size() > 20) {
                return "Error: Max 20 todos allowed";
            }
            // 验证阶段：先解析所有 item，检查约束条件
            List<TodoItem> parsed = new ArrayList<TodoItem>();
            int inProgressCount = 0;
            for (JsonElement el : newItems) {
                JsonObject obj = el.getAsJsonObject();
                String id = obj.get("id").getAsString();
                String text = obj.get("text").getAsString();
                if (text.isEmpty()) {
                    return "Error: Todo item '" + id + "' has empty text";
                }
                // 默认状态为 pending，这符合直觉：新建的任务应该是"待办"状态
                String status = obj.has("status") ? obj.get("status").getAsString() : "pending";
                if ("in_progress".equals(status)) {
                    inProgressCount++;
                }
                parsed.add(new TodoItem(id, text, status));
            }
            // 同一时间只允许 1 个 in_progress 任务
            // 这个约束强制模型专注：先完成当前任务，再开始下一个
            if (inProgressCount > 1) {
                return "Error: Only one task can be in_progress at a time";
            }
            // 应用阶段：验证通过后才替换列表
            items.clear();
            items.addAll(parsed);
            roundsSinceUpdate = 0; // 重置 nag 计数器，因为模型刚更新了 todo
            return null; // null 表示没有错误
        }

        /**
         * 渲染 todo 列表为人类可读的文本格式。
         *
         * 返回值会作为 tool_result 发送给模型，让模型能"看到"当前的任务状态。
         * 使用图标来直观表示状态：[ ] 待办、[>] 进行中、[x] 已完成。
         * 末尾附带完成进度（如 "2/5 completed"），帮助模型掌握整体进展。
         */
        String render() {
            if (items.isEmpty()) {
                return "(empty todo list)";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("=== TODO LIST ===\n");
            for (TodoItem item : items) {
                String icon;
                if ("completed".equals(item.status)) {
                    icon = "[x]";
                } else if ("in_progress".equals(item.status)) {
                    icon = "[>]";
                } else {
                    icon = "[ ]";
                }
                sb.append(icon).append(" ").append(item.id).append(": ").append(item.text).append("\n");
            }
            int done = 0;
            for (TodoItem item : items) {
                if ("completed".equals(item.status)) {
                    done++;
                }
            }
            sb.append("\n(").append(done).append("/").append(items.size()).append(" completed)");
            return sb.toString().trim();
        }

        /**
         * 每轮 agent loop 结束时调用，如果该轮模型没有调用 todo 工具，计数 +1。
         * 这是 Nag 机制的"滴答"：每 tick 一次就离提醒阈值更近一步。
         */
        void tick() {
            roundsSinceUpdate++;
        }

        /**
         * 判断是否需要注入提醒。
         * 条件：todo 列表非空（有任务在跟踪）且已有 3 轮未更新。
         * 如果列表为空，说明模型还没开始规划，此时提醒没有意义。
         */
        boolean needsReminder() {
            return !items.isEmpty() && roundsSinceUpdate >= 3;
        }
    }

    /**
     * Todo 项 - 不可变的值对象。
     * 采用 final 字段确保一旦创建就不可修改，所有更新都通过全量替换实现。
     * 在 JDK 1.8 中没有 record，所以用传统的 POJO 表示。
     */
    static class TodoItem {
        /** 任务唯一标识，由模型自行分配（如 "1", "step-a" 等） */
        final String id;
        /** 任务描述文本 */
        final String text;
        /** 状态：pending（待办）/ in_progress（进行中）/ completed（已完成） */
        final String status;

        TodoItem(String id, String text, String status) {
            this.id = id;
            this.text = text;
            this.status = status;
        }
    }

    // =================================================================
    // 工具定义
    // =================================================================

    /**
     * 构建所有 5 个工具的 JSON Schema 定义，发送给 Anthropic API。
     *
     * 工具定义是告诉模型"你有哪些工具可用"的方式。
     * 4 个基础工具来自 BaseTools.allToolDefs()，和 S02 完全一致。
     * 本课新增的 todo 工具需要手动构建 schema，因为它的结构较复杂（嵌套数组）。
     */
    private static JsonArray buildTools() {
        // 复用 S02 的 4 个基础工具定义
        JsonArray toolDefs = BaseTools.allToolDefs();

        // todo 工具的 JSON Schema 定义
        // 结构：{ items: [{ id, text, status }] }
        // items 是一个数组，每个元素有 id, text, status 三个必填字段
        JsonObject todoSchema = new JsonObject();
        todoSchema.addProperty("type", "object");
        JsonObject props = new JsonObject();

        // items 属性 - 类型为 array
        JsonObject itemsProp = new JsonObject();
        itemsProp.addProperty("type", "array");
        JsonObject itemSchema = new JsonObject();
        itemSchema.addProperty("type", "object");
        JsonObject itemProps = new JsonObject();
        JsonObject idProp = new JsonObject();
        idProp.addProperty("type", "string");
        itemProps.add("id", idProp);
        JsonObject textProp = new JsonObject();
        textProp.addProperty("type", "string");
        itemProps.add("text", textProp);
        JsonObject statusProp = new JsonObject();
        statusProp.addProperty("type", "string");
        statusProp.addProperty("description", "One of: pending, in_progress, completed");
        itemProps.add("status", statusProp);
        itemSchema.add("properties", itemProps);
        JsonArray itemRequired = new JsonArray();
        itemRequired.add("id");
        itemRequired.add("text");
        itemRequired.add("status");
        itemSchema.add("required", itemRequired);
        itemsProp.add("items", itemSchema);
        props.add("items", itemsProp);

        todoSchema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("items");
        todoSchema.add("required", required);

        toolDefs.add(AnthropicClient.toolDef("todo",
                "Update your todo list. Use this to plan and track your progress on complex tasks.",
                todoSchema));

        return toolDefs;
    }

    /**
     * 代理循环 - 在 S02 基础上增加了 todo 提醒注入（Nag Pattern）。
     *
     * 【与 S02 的区别】
     * 核心循环结构与 S02 完全相同（调用 LLM -> 检查 stop_reason -> 执行工具 -> 循环），
     * 但在工具执行后、发送 tool_result 之前，增加了两个新逻辑：
     * 1. 追踪本轮是否调用了 todo 工具（updatedTodo 标记）
     * 2. 如果 3 轮没更新 todo，在 tool_result 中注入 reminder 文本块
     *
     * 【Nag 注入的实现方式】
     * 提醒是作为 user message 中的额外 text 块注入的（与 tool_result 并列）。
     * 这样模型会在下一轮看到提醒，但不会把它误认为是某个工具的输出。
     * Claude Code 中的 system-reminder 也采用类似的注入策略。
     */
    public String agentLoop(JsonArray messages) {
        JsonArray toolDefs = buildTools();

        while (true) {
            // -- Nag 机制：如果太久没更新 todo，注入提醒 --
            // (handled below after tool results are collected)

            System.out.println("[agent] Calling LLM...");
            JsonObject response = client.createMessage(SYSTEM_PROMPT, messages, toolDefs, 8000);

            JsonArray content = AnthropicClient.getContent(response);
            String stopReason = AnthropicClient.getStopReason(response);

            messages.add(AnthropicClient.assistantMessage(content));

            if (!"tool_use".equals(stopReason)) {
                return AnthropicClient.extractText(content);
            }

            // 执行工具调用，同时追踪是否调用了 todo 工具
            JsonArray toolResults = new JsonArray();
            boolean updatedTodo = false;  // S03 新增：标记本轮是否更新了 todo

            for (JsonElement el : content) {
                JsonObject block = el.getAsJsonObject();
                if (!"tool_use".equals(block.get("type").getAsString())) {
                    continue;
                }

                String toolName = block.get("name").getAsString();
                String toolId = block.get("id").getAsString();
                JsonObject input = block.getAsJsonObject("input");

                System.out.println("[agent] Tool: " + toolName);

                ToolHandler handler = dispatch.get(toolName);
                String result;
                if (handler != null) {
                    result = handler.execute(input);
                } else {
                    result = "Unknown tool: " + toolName;
                }

                if ("todo".equals(toolName)) {
                    updatedTodo = true;
                }

                System.out.println("[agent] Result: " + truncate(result, 200));
                toolResults.add(AnthropicClient.toolResult(toolId, result));
            }

            // -- Nag 注入：在收集完工具结果后，在 toolResults 头部插入提醒文本 --
            // 【为什么放在头部？】
            // 放在头部确保模型在看到工具结果之前先看到提醒，
            // 这样更可能在处理结果时同时更新 todo。
            // 【为什么用 <reminder> 标签？】
            // 使用 XML 标签包裹让模型能清晰地区分提醒和工具输出，
            // 这也是 Claude Code 中注入系统提醒的常见模式。
            if (todoManager.needsReminder()) {
                JsonObject reminder = new JsonObject();
                reminder.addProperty("type", "text");
                reminder.addProperty("text", "<reminder>Update your todos.</reminder>");
                // Gson 的 JsonArray 不支持 add(index, element)，所以需要构建新数组
                JsonArray withReminder = new JsonArray();
                withReminder.add(reminder);  // 提醒放在最前面
                for (JsonElement el2 : toolResults) {
                    withReminder.add(el2);    // 后面是正常的工具结果
                }
                toolResults = withReminder;
                System.out.println("[agent] Injected todo reminder (no update for "
                        + todoManager.roundsSinceUpdate + " rounds)");
            }

            messages.add(AnthropicClient.userMessage(toolResults));

            // 如果这轮模型没有调用 todo 工具，增加 nag 计数器
            // 连续 3 轮不更新 -> 下一轮注入提醒
            if (!updatedTodo) {
                todoManager.tick();
            }
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    public static void main(String[] args) {
        System.out.println("=== S03: TodoWrite ===");
        System.out.println("The model now tracks its own progress with a todo list.");
        System.out.println("If it forgets to update, we remind it (nag pattern).");
        System.out.println("Type 'quit' to exit.\n");

        S03TodoWrite agent = new S03TodoWrite();
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
