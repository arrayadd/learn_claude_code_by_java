package learn.claude.code.s07_task_system;

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
 * S07 - 任务系统 (Task System)
 *
 * 核心洞察 / Key Insight:
 *   "State that survives compression — because it's outside the conversation."
 *   "状态存活于压缩之外——因为它在对话之外。"
 *
 * 为什么需要这个 / Why this matters:
 *   上下文压缩（S06）会丢失信息。如果任务状态只存在于对话历史中，
 *   压缩后就丢了。所以我们把任务持久化为 JSON 文件。
 *
 *   Context compaction (S06) loses information. If task state only lives
 *   in conversation history, it's lost after compaction. So we persist
 *   tasks as JSON files.
 *
 * 任务存储在 .tasks/ 目录下，每个任务一个 JSON 文件。
 * Tasks are stored in .tasks/ directory, one JSON file per task.
 *
 * 依赖图 / Dependency graph:
 *   每个任务可以有 blockedBy（被哪些任务阻塞）和 blocks（阻塞哪些任务）。
 *   当一个任务完成时，自动清除它对其他任务的阻塞。
 *   Each task can have blockedBy (blocked by which tasks) and blocks (blocks which tasks).
 *   When a task completes, its blocks on other tasks are automatically cleared.
 */
public class S07TaskSystem {

    /** JSON 序列化工具，启用格式化输出便于调试和文件存储可读性 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ===== 内部类：任务管理器 =====
    //
    // 任务持久化模型的核心设计思想：
    // S06 的上下文压缩会丢失对话历史中的信息。如果任务状态（待办、进行中、已完成）
    // 只记录在对话消息里，压缩后就丢了——Agent 会"忘记"自己还有哪些事没做完。
    //
    // 解决方案：将任务状态存储在文件系统中（.tasks/ 目录，每个任务一个 JSON 文件）。
    // 这样即使对话历史被压缩为摘要，任务信息仍然完好无损，Agent 可以通过
    // task_list/task_get 工具随时读取当前任务状态。
    //
    // 这就是"状态存活于压缩之外"的含义：关键状态不放在对话里，而放在对话之外。

    /**
     * 任务管理器：提供任务的 CRUD 操作和依赖图管理。
     *
     * 每个任务持久化为一个 JSON 文件，存储在 .tasks/ 目录下，文件名格式为 task_N.json。
     *
     * 任务 JSON 结构示例：
     * {
     *   "id": 1,                            // 任务唯一标识符（自增整数）
     *   "subject": "Implement login API",   // 任务主题（简短描述）
     *   "description": "Create REST ...",   // 任务详细描述
     *   "status": "in_progress",            // 状态：pending（待办）| in_progress（进行中）| completed（已完成）
     *   "blockedBy": [2],                   // 被哪些任务阻塞（依赖的前置任务 ID 列表）
     *   "blocks": [3],                      // 阻塞了哪些任务（被本任务阻塞的后续任务 ID 列表）
     *   "owner": "agent",                   // 任务负责人
     *   "createdAt": "2026-03-19T10:00:00", // 创建时间
     *   "updatedAt": "2026-03-19T10:30:00"  // 最后更新时间
     * }
     *
     * 依赖图说明：
     * - blockedBy 和 blocks 构成双向依赖关系
     * - 当任务 A 的 blockedBy 包含任务 B 的 ID 时，表示 A 依赖 B 完成
     * - 当任务 B 标记为 completed 时，自动从所有其他任务的 blockedBy 中移除 B 的 ID
     * - 当一个任务的 blockedBy 变为空时，该任务就被"解锁"了，可以开始工作
     */
    static class TaskManager {

        /** 任务文件存储目录路径 */
        private final String tasksDir;

        /** 下一个可用的任务 ID（自增） */
        private int nextId;

        /**
         * 构造任务管理器。
         *
         * 初始化时会扫描 .tasks/ 目录中已有的任务文件，找到最大 ID，
         * 确保新创建的任务 ID 不会与已有任务冲突。
         *
         * @param workDir 工作目录路径
         */
        TaskManager(String workDir) {
            this.tasksDir = workDir + "/.tasks";
            // 确保任务存储目录存在
            new File(tasksDir).mkdirs();
            // 扫描已有任务文件，找到最大 ID，新任务从 maxId + 1 开始
            this.nextId = scanMaxId() + 1;
        }

        /**
         * 扫描 .tasks/ 目录中所有 task_N.json 文件，返回最大的 N 值。
         *
         * 文件名解析逻辑：从 "task_" 后、".json" 前提取数字部分。
         * 如果目录为空或没有合法的任务文件，返回 0。
         *
         * @return 当前最大的任务 ID，未找到任务时返回 0
         */
        private int scanMaxId() {
            int maxId = 0;
            File dir = new File(tasksDir);
            File[] files = dir.listFiles();
            if (files == null) return 0;

            for (File f : files) {
                String name = f.getName();
                if (name.startsWith("task_") && name.endsWith(".json")) {
                    try {
                        int id = Integer.parseInt(
                                name.substring(5, name.length() - 5)); // task_N.json
                        if (id > maxId) maxId = id;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            return maxId;
        }

        /**
         * 创建一个新任务并持久化到文件系统。
         *
         * 新任务初始状态为 pending（待办），没有任何依赖关系（blockedBy 和 blocks 均为空）。
         * 任务 ID 为自增整数，保证唯一性。
         *
         * @param subject     任务主题（简短描述，例如 "Implement login API"）
         * @param description 任务详细描述（可为空字符串）
         * @return 创建成功的提示信息，包含任务 ID 和主题
         */
        String create(String subject, String description) {
            // 分配自增 ID 并生成文件名
            int taskId = nextId++;
            String taskFileName = "task_" + taskId;
            String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date());

            JsonObject task = new JsonObject();
            task.addProperty("id", taskId);
            task.addProperty("subject", subject);
            task.addProperty("description", description);
            task.addProperty("status", "pending");
            task.add("blockedBy", new JsonArray());
            task.add("blocks", new JsonArray());
            task.addProperty("owner", "agent");
            task.addProperty("createdAt", now);
            task.addProperty("updatedAt", now);

            saveTask(taskFileName, task);
            return "Created task #" + taskId + ": " + subject;
        }

        /**
         * 根据任务 ID 获取任务的完整 JSON 详情。
         *
         * @param taskId 任务 ID
         * @return 任务的 JSON 字符串，未找到时返回错误信息
         */
        String get(int taskId) {
            String taskFileName = "task_" + taskId;
            JsonObject task = loadTask(taskFileName);
            if (task == null) {
                return "Error: Task not found: #" + taskId;
            }
            return GSON.toJson(task);
        }

        /**
         * 更新任务的状态和/或依赖关系。
         *
         * 这是任务管理器最复杂的方法，需要处理三种更新：
         * 1. 状态更新：如果新状态为 completed，还需要自动清除依赖关系
         * 2. 添加 blockedBy：建立"我被某任务阻塞"的关系（同时更新对方的 blocks 列表）
         * 3. 添加 blocks：建立"我阻塞某任务"的关系（同时更新对方的 blockedBy 列表）
         *
         * 依赖关系是双向维护的：当 A blockedBy B 时，B 的 blocks 列表也会包含 A。
         * 这样无论从哪个方向查询都能获取完整的依赖信息。
         *
         * @param taskId       要更新的任务 ID
         * @param status       新状态（null 或空字符串表示不更新状态）
         * @param addBlockedBy 要添加的阻塞源任务 ID 列表（null 表示不添加）
         * @param addBlocks    要添加的被阻塞任务 ID 列表（null 表示不添加）
         * @return 更新结果的描述字符串
         */
        String update(int taskId, String status, JsonArray addBlockedBy, JsonArray addBlocks) {
            String taskFileName = "task_" + taskId;
            JsonObject task = loadTask(taskFileName);
            if (task == null) {
                return "Error: Task not found: #" + taskId;
            }

            StringBuilder changes = new StringBuilder();
            String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date());

            // === 处理状态更新 ===
            if (status != null && !status.isEmpty()) {
                String oldStatus = task.get("status").getAsString();
                task.addProperty("status", status);
                changes.append("Status: ").append(oldStatus).append(" -> ").append(status).append(". ");

                // 关键逻辑：当任务标记为 completed 时，自动清除依赖图中的阻塞关系
                // 这使得被本任务阻塞的其他任务能够"解锁"
                if ("completed".equals(status)) {
                    _clearDependency(taskId);
                    changes.append("Cleared dependency blocks. ");
                }
            }

            // === 处理 blockedBy 关系：建立"我被某任务阻塞"的双向关系 ===
            if (addBlockedBy != null && addBlockedBy.size() > 0) {
                JsonArray blockedBy = task.getAsJsonArray("blockedBy");
                for (JsonElement el : addBlockedBy) {
                    int blockerId = el.getAsInt();
                    if (!jsonArrayContainsInt(blockedBy, blockerId)) {
                        blockedBy.add(blockerId);
                        changes.append("Added blockedBy: #").append(blockerId).append(". ");

                        // 双向维护：同时在阻塞源任务的 blocks 列表中添加本任务 ID
                        String blockerFileName = "task_" + blockerId;
                        JsonObject blocker = loadTask(blockerFileName);
                        if (blocker != null) {
                            JsonArray blocks = blocker.getAsJsonArray("blocks");
                            if (!jsonArrayContainsInt(blocks, taskId)) {
                                blocks.add(taskId);
                                blocker.addProperty("updatedAt", now);
                                saveTask(blockerFileName, blocker);
                            }
                        }
                    }
                }
            }

            // === 处理 blocks 关系：建立"我阻塞某任务"的双向关系 ===
            if (addBlocks != null && addBlocks.size() > 0) {
                JsonArray blocks = task.getAsJsonArray("blocks");
                for (JsonElement el : addBlocks) {
                    int blockedId = el.getAsInt();
                    if (!jsonArrayContainsInt(blocks, blockedId)) {
                        blocks.add(blockedId);
                        changes.append("Added blocks: #").append(blockedId).append(". ");

                        // 双向维护：同时在被阻塞任务的 blockedBy 列表中添加本任务 ID
                        String blockedFileName = "task_" + blockedId;
                        JsonObject blocked = loadTask(blockedFileName);
                        if (blocked != null) {
                            JsonArray blockedByOther = blocked.getAsJsonArray("blockedBy");
                            if (!jsonArrayContainsInt(blockedByOther, taskId)) {
                                blockedByOther.add(taskId);
                                blocked.addProperty("updatedAt", now);
                                saveTask(blockedFileName, blocked);
                            }
                        }
                    }
                }
            }

            task.addProperty("updatedAt", now);
            saveTask(taskFileName, task);
            return "Updated #" + taskId + ": " + changes.toString();
        }

        /**
         * 列出所有任务的摘要信息。
         *
         * 输出格式示例：
         *   [ ] #1: Implement login API (blocked by: [2])
         *   [x] #2: Set up database
         *   [ ] #3: Write tests
         *
         * [x] 表示已完成，[ ] 表示未完成。如果任务有 blockedBy 依赖，会显示阻塞源 ID。
         *
         * @return 所有任务的格式化列表，无任务时返回 "No tasks found."
         */
        String listAll() {
            File dir = new File(tasksDir);
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                return "No tasks found.";
            }

            // 按文件名排序，保证任务按 ID 顺序显示
            Arrays.sort(files);

            StringBuilder sb = new StringBuilder();
            for (File f : files) {
                if (!f.getName().endsWith(".json")) continue;
                try {
                    String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    JsonObject task = JsonParser.parseString(content).getAsJsonObject();
                    int id = task.get("id").getAsInt();
                    String subject = task.get("subject").getAsString();
                    String status = task.get("status").getAsString();
                    JsonArray blockedBy = task.getAsJsonArray("blockedBy");

                    // 状态标记：已完成用 [x]，未完成用 [ ]
                    String marker = "completed".equals(status) ? "[x]" : "[ ]";

                    sb.append(marker).append(" #").append(id).append(": ").append(subject);

                    // 如果有阻塞依赖，追加显示 blockedBy 列表
                    if (blockedBy != null && blockedBy.size() > 0) {
                        sb.append(" (blocked by: [");
                        for (int i = 0; i < blockedBy.size(); i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(blockedBy.get(i).getAsInt());
                        }
                        sb.append("])");
                    }

                    sb.append("\n");
                } catch (Exception e) {
                    System.err.println("[TaskManager] Failed to read: " + f + " - " + e.getMessage());
                }
            }

            return sb.toString().trim();
        }

        /**
         * 依赖图的核心操作：当任务完成时，从所有其他任务的 blockedBy 列表中移除该任务 ID。
         *
         * 这实现了"完成一个任务会解锁被它阻塞的任务"的语义。
         *
         * 遍历 .tasks/ 目录中的所有任务文件，检查每个任务的 blockedBy 列表：
         * - 如果包含已完成的任务 ID，则将其移除
         * - 如果移除后 blockedBy 变为空，打印解锁日志（该任务现在可以开始了）
         *
         * @param completedId 刚刚完成的任务 ID
         */
        void _clearDependency(int completedId) {
            File dir = new File(tasksDir);
            File[] files = dir.listFiles();
            if (files == null) return;

            String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date());

            for (File f : files) {
                if (!f.getName().endsWith(".json")) continue;
                try {
                    String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    JsonObject task = JsonParser.parseString(content).getAsJsonObject();
                    JsonArray blockedBy = task.getAsJsonArray("blockedBy");

                    if (jsonArrayContainsInt(blockedBy, completedId)) {
                        // 构建新的 blockedBy 列表，排除已完成的任务 ID
                        JsonArray newBlockedBy = new JsonArray();
                        for (JsonElement el : blockedBy) {
                            if (el.getAsInt() != completedId) {
                                newBlockedBy.add(el);
                            }
                        }
                        task.add("blockedBy", newBlockedBy);
                        task.addProperty("updatedAt", now);

                        // 如果所有阻塞依赖都清除了，打印解锁日志
                        // 注意：状态不会自动变为 in_progress，仍保持 pending，由 Agent 决定何时开始
                        if (newBlockedBy.size() == 0) {
                            System.out.println("[TaskManager] Unblocked: #" + task.get("id").getAsInt());
                        }

                        saveTask("task_" + task.get("id").getAsInt(), task);
                    }
                } catch (Exception e) {
                    // 忽略解析错误 / Ignore parse errors
                }
            }
        }

        // ===== 辅助方法 =====

        /**
         * 从文件系统加载指定的任务 JSON 对象。
         *
         * @param taskId 任务文件名前缀（如 "task_1"），实际文件为 taskId + ".json"
         * @return 任务的 JsonObject，文件不存在或解析失败时返回 null
         */
        private JsonObject loadTask(String taskId) {
            try {
                Path path = Paths.get(tasksDir, taskId + ".json");
                if (!Files.exists(path)) return null;
                String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                return JsonParser.parseString(content).getAsJsonObject();
            } catch (Exception e) {
                return null;
            }
        }

        /**
         * 将任务 JSON 对象持久化到文件系统。
         *
         * @param taskId 任务文件名前缀（如 "task_1"）
         * @param task   任务的 JsonObject
         */
        private void saveTask(String taskId, JsonObject task) {
            try {
                Path path = Paths.get(tasksDir, taskId + ".json");
                Files.write(path, GSON.toJson(task).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                System.err.println("[TaskManager] Failed to save: " + taskId + " - " + e.getMessage());
            }
        }

        /** 检查 JsonArray 中是否包含指定的整数值（遍历比较） */
        private boolean jsonArrayContainsInt(JsonArray arr, int value) {
            for (JsonElement el : arr) {
                if (el.getAsInt() == value) return true;
            }
            return false;
        }
    }

    // ===== 工具定义 =====
    // 以下四个方法定义了暴露给 LLM 的任务管理工具（符合 Anthropic tool schema 格式）。
    // 每个工具定义包含：名称、描述、参数 schema（JSON Schema 格式）。

    /**
     * task_create 工具定义：创建新任务。
     * 必需参数：subject（任务主题），可选参数：description（详细描述）。
     */
    private static JsonObject taskCreateToolDef() {
        return AnthropicClient.toolDef("task_create",
                "Create a new task with a subject and optional description.",
                AnthropicClient.schema(
                        "subject", "string", "true",
                        "description", "string", "false"));
    }

    /**
     * task_update 工具定义：更新任务状态和依赖关系。
     * 必需参数：task_id（任务 ID），可选参数：status, add_blocked_by, add_blocks。
     * 参数 schema 较复杂，需要手动构建（包含整数数组类型）。
     */
    private static JsonObject taskUpdateToolDef() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();

        JsonObject taskIdProp = new JsonObject();
        taskIdProp.addProperty("type", "integer");
        props.add("task_id", taskIdProp);

        JsonObject statusProp = new JsonObject();
        statusProp.addProperty("type", "string");
        props.add("status", statusProp);

        JsonObject addBlockedByProp = new JsonObject();
        addBlockedByProp.addProperty("type", "array");
        JsonObject intItemsA = new JsonObject();
        intItemsA.addProperty("type", "integer");
        addBlockedByProp.add("items", intItemsA);
        props.add("add_blocked_by", addBlockedByProp);

        JsonObject addBlocksProp = new JsonObject();
        addBlocksProp.addProperty("type", "array");
        JsonObject intItemsB = new JsonObject();
        intItemsB.addProperty("type", "integer");
        addBlocksProp.add("items", intItemsB);
        props.add("add_blocks", addBlocksProp);

        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("task_id");
        schema.add("required", required);

        return AnthropicClient.toolDef("task_update",
                "Update a task's status or dependencies. "
                        + "Status values: pending, in_progress, completed. "
                        + "When set to 'completed', blocked tasks are automatically unblocked.",
                schema);
    }

    /** task_list 工具定义：列出所有任务（无参数） */
    private static JsonObject taskListToolDef() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return AnthropicClient.toolDef("task_list",
                "List all tasks with their status and dependencies.", schema);
    }

    /** task_get 工具定义：获取单个任务详情，必需参数：task_id */
    private static JsonObject taskGetToolDef() {
        return AnthropicClient.toolDef("task_get",
                "Get full details of a specific task.",
                AnthropicClient.schema("task_id", "integer", "true"));
    }

    // ===== Agent 主循环 =====

    /**
     * Agent 主循环：REPL 模式，支持 8 个工具（4 基础 + 4 任务）。
     *
     * 与 S06 不同，这里没有上下文压缩——但因为任务状态持久化在文件系统中，
     * 即使将来加上压缩，任务信息也不会丢失。Agent 随时可以通过 task_list
     * 恢复对任务全局的了解。
     *
     * @param workDir 工作目录路径
     */
    private static void agentLoop(String workDir) {
        AnthropicClient client = new AnthropicClient();
        BaseTools baseTools = new BaseTools(workDir);
        final TaskManager taskMgr = new TaskManager(workDir);

        // 注册工具处理器：将工具名映射到具体的执行逻辑
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
        // 任务管理工具处理器
        handlers.put("task_create", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                String description = input.has("description") ? input.get("description").getAsString() : "";
                return taskMgr.create(
                        input.get("subject").getAsString(),
                        description);
            }
        });
        handlers.put("task_update", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                int taskId = input.get("task_id").getAsInt();
                String status = input.has("status") ? input.get("status").getAsString() : null;
                JsonArray addBlockedBy = input.has("add_blocked_by")
                        ? input.getAsJsonArray("add_blocked_by") : null;
                JsonArray addBlocks = input.has("add_blocks")
                        ? input.getAsJsonArray("add_blocks") : null;
                return taskMgr.update(taskId, status, addBlockedBy, addBlocks);
            }
        });
        handlers.put("task_list", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return taskMgr.listAll();
            }
        });
        handlers.put("task_get", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return taskMgr.get(input.get("task_id").getAsInt());
            }
        });

        // 组装工具列表：4 个基础工具 + 4 个任务管理工具 = 共 8 个
        JsonArray tools = BaseTools.allToolDefs();
        tools.add(taskCreateToolDef());
        tools.add(taskUpdateToolDef());
        tools.add(taskListToolDef());
        tools.add(taskGetToolDef());

        String systemPrompt = "You are a helpful coding assistant with a task management system.\n\n"
                + "## Task System\n"
                + "Tasks persist as JSON files in .tasks/ directory. They survive context compression.\n"
                + "Use tasks to track work items, especially for multi-step projects.\n\n"
                + "### Workflow\n"
                + "1. Break complex requests into tasks with task_create\n"
                + "2. Track dependencies with add_blocked_by / add_blocks\n"
                + "3. Update status as you work: pending -> in_progress -> completed\n"
                + "4. When a task is completed, blocked tasks are auto-unblocked\n"
                + "5. Use task_list to review progress\n\n"
                + "### Status Values\n"
                + "- pending: Not started\n"
                + "- in_progress: Currently working on it\n"
                + "- completed: Finished\n\n"
                + "## Tools\n"
                + "You have bash, read_file, write_file, edit_file, "
                + "task_create, task_update, task_list, task_get available.";

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

            // 内层 Agent 循环：处理工具调用链（最多 30 次迭代）
            int maxIterations = 30;
            for (int i = 0; i < maxIterations; i++) {
                System.out.println("[Agent] Calling LLM (iteration " + (i + 1) + ")...");
                JsonObject response = client.createMessage(systemPrompt, messages, tools, 8192);

                String stopReason = AnthropicClient.getStopReason(response);
                JsonArray content = AnthropicClient.getContent(response);

                String textOutput = AnthropicClient.extractText(content);
                if (!textOutput.isEmpty()) {
                    System.out.println("\n[Assistant] " + textOutput);
                }

                if (!"tool_use".equals(stopReason)) {
                    System.out.println("[Agent] Done. (stop_reason=" + stopReason + ")");
                    break;
                }

                // 处理工具调用：将 assistant 回复加入消息列表，执行每个工具，收集结果
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
        System.out.println("║  S07 - Task System (任务系统)                       ║");
        System.out.println("║                                                      ║");
        System.out.println("║  Key Insight:                                        ║");
        System.out.println("║  State that survives compression —                   ║");
        System.out.println("║  because it's outside the conversation.              ║");
        System.out.println("║  状态存活于压缩之外——因为它在对话之外。              ║");
        System.out.println("║                                                      ║");
        System.out.println("║  Tasks persist as .tasks/*.json files.               ║");
        System.out.println("║  Dependencies auto-resolve when tasks complete.      ║");
        System.out.println("╚══════════════════════════════════════════════════════╝\n");

        String workDir = System.getProperty("user.dir");
        if (args.length > 0 && !args[0].startsWith("--")) {
            workDir = args[0];
        }

        // 确保 .tasks/ 目录存在（TaskManager 构造时也会创建，这里是双重保险）
        new File(workDir + "/.tasks").mkdirs();

        agentLoop(workDir);
    }
}
