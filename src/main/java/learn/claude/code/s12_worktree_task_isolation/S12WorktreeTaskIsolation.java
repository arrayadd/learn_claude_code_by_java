package learn.claude.code.s12_worktree_task_isolation;

import com.google.gson.*;
import learn.claude.code.common.AnthropicClient;
import learn.claude.code.common.BaseTools;
import learn.claude.code.common.ToolHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * S12 - Worktree Task Isolation: 基于 Git Worktree 的目录级隔离
 *
 * 核心洞察 / Key Insight:
 *   "Isolate by directory, coordinate by task ID."
 *   按目录隔离执行，按任务 ID 协调。
 *
 * 双平面架构 / Two-Plane Architecture:
 *
 *   ┌─────────────────────────────────────────────────────────────┐
 *   │                    Control Plane (控制面)                     │
 *   │                                                             │
 *   │   TaskManager: .tasks/task-xxx.json                         │
 *   │   ┌──────────┐  ┌──────────┐  ┌──────────┐                │
 *   │   │ task-001  │  │ task-002  │  │ task-003  │ ...           │
 *   │   │ pending   │  │ in_progress│ │ completed │               │
 *   │   │ worktree: │  │ worktree: │  │ worktree: │               │
 *   │   │  (none)   │  │  wt-abc   │  │  (none)   │               │
 *   │   └──────────┘  └─────┬────┘  └──────────┘                │
 *   │                        │ bind                               │
 *   ├────────────────────────┼────────────────────────────────────┤
 *   │                        v                                    │
 *   │                 Execution Plane (执行面)                     │
 *   │                                                             │
 *   │   WorktreeManager: .worktrees/index.json                    │
 *   │   ┌────────────────────────────┐                            │
 *   │   │ wt-abc/                    │  <- git worktree           │
 *   │   │   src/                     │  独立工作目录 / isolated dir │
 *   │   │   pom.xml                  │  worktree_run 在此执行      │
 *   │   └────────────────────────────┘                            │
 *   │                                                             │
 *   │   EventBus: .worktrees/events.jsonl                         │
 *   │   (append-only lifecycle events for observability)          │
 *   └─────────────────────────────────────────────────────────────┘
 *
 * 工具清单 / Tool Inventory (16 tools):
 *   4 base: bash, read_file, write_file, edit_file
 *   4 task: task_create, task_list, task_get, task_update
 *   2 bind: task_bind_worktree (in task_update)
 *   6 worktree: worktree_create, worktree_list, worktree_status,
 *               worktree_run, worktree_keep, worktree_remove
 *   2 observability: worktree_events (via EventBus)
 *
 * 运行 / Run:
 *   mvn exec:java -Dexec.mainClass="learn.claude.code.s12_worktree_task_isolation.S12WorktreeTaskIsolation"
 */
public class S12WorktreeTaskIsolation {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** 统一的时间戳格式 (注意: SimpleDateFormat 非线程安全，此处仅在 synchronized 方法中使用) */
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

    // ========================= EventBus (生命周期事件总线) =========================
    //
    // 双平面架构的可观测性组件。
    // 使用 JSONL (追加式) 记录所有控制面和执行面的生命周期事件。
    //
    // 事件格式:
    //   { "event": "created|updated|bound|unbound|command|kept|removed",
    //     "ts": 1234567890,
    //     "task": { "id": "1", ... } | null,
    //     "worktree": { "id": "wt-abc", ... } | null }
    //
    // 为什么用 JSONL？
    //   1. 追加式写入: 不会丢失历史事件，支持审计
    //   2. 可观察性: 可以用 tail -f 实时监控事件流
    //   3. 结构化: 支持按 entity_id 过滤，按时间范围查询
    //
    // 存储位置: .worktrees/events.jsonl

    static class EventBus {
        private final Path eventsFile;

        EventBus(String workDir) {
            Path wtDir = Paths.get(workDir, ".worktrees");
            try { Files.createDirectories(wtDir); } catch (IOException e) {
                throw new RuntimeException(e);
            }
            this.eventsFile = wtDir.resolve("events.jsonl");
        }

        /**
         * 记录事件 (向后兼容的包装方法)
         * 将 entityType/entityId/details 转换为结构化的 task/worktree 对象后调用核心 emit
         */
        synchronized void emit(String eventType, String entityType, String entityId, String details) {
            JsonObject taskObj = null;
            JsonObject wtObj = null;
            if ("task".equals(entityType)) {
                taskObj = new JsonObject();
                taskObj.addProperty("id", entityId);
                taskObj.addProperty("details", details);
            } else if ("worktree".equals(entityType)) {
                wtObj = new JsonObject();
                wtObj.addProperty("id", entityId);
                wtObj.addProperty("details", details);
            }
            emit(eventType, taskObj, wtObj);
        }

        /** 记录事件 (结构化接口): 直接传入 task 和 worktree 对象 */
        synchronized void emit(String eventType, JsonObject task, JsonObject worktree) {
            emit(eventType, null, null, task, worktree);
        }

        /**
         * 记录事件 (完整版): 核心实现方法
         * 构造 JSONL 事件行: {event, ts, task, worktree}
         * 压缩为单行后追加写入 events.jsonl
         */
        synchronized void emit(String eventType, String entityType, String entityId, JsonObject task, JsonObject worktree) {
            JsonObject event = new JsonObject();
            event.addProperty("event", eventType);
            event.addProperty("ts", System.currentTimeMillis());
            if (task != null) {
                event.add("task", task);
            } else {
                event.add("task", JsonNull.INSTANCE);
            }
            if (worktree != null) {
                event.add("worktree", worktree);
            } else {
                event.add("worktree", JsonNull.INSTANCE);
            }
            try (BufferedWriter w = Files.newBufferedWriter(eventsFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(GSON.toJson(event).replace("\n", " "));
                w.newLine();
            } catch (IOException e) {
                System.err.println("[EventBus] Write error: " + e.getMessage());
            }
        }

        /** 读取所有事件 / Read all events */
        String readAll() {
            if (!Files.exists(eventsFile)) return "No events.";
            try {
                List<String> lines = Files.readAllLines(eventsFile, StandardCharsets.UTF_8);
                if (lines.isEmpty()) return "No events.";
                JsonArray events = new JsonArray();
                for (String line : lines) {
                    String t = line.trim();
                    if (!t.isEmpty()) events.add(JsonParser.parseString(t));
                }
                return events.size() == 0 ? "No events." : GSON.toJson(events);
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }

        /** 返回最近 N 条事件 / Return last N events */
        String listRecent(int limit) {
            if (!Files.exists(eventsFile)) return "No events.";
            try {
                List<String> lines = Files.readAllLines(eventsFile, StandardCharsets.UTF_8);
                if (lines.isEmpty()) return "No events.";
                JsonArray events = new JsonArray();
                int start = Math.max(0, lines.size() - limit);
                for (int i = start; i < lines.size(); i++) {
                    String t = lines.get(i).trim();
                    if (!t.isEmpty()) events.add(JsonParser.parseString(t));
                }
                return events.size() == 0 ? "No events." : GSON.toJson(events);
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }

        /** 按 entity_id 过滤事件 / Filter events by entity_id */
        String readByEntity(String entityId) {
            if (!Files.exists(eventsFile)) return "No events.";
            try {
                List<String> lines = Files.readAllLines(eventsFile, StandardCharsets.UTF_8);
                JsonArray filtered = new JsonArray();
                for (String line : lines) {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    JsonObject ev = JsonParser.parseString(t).getAsJsonObject();
                    // Check in task or worktree objects for matching id
                    boolean match = false;
                    if (ev.has("task") && !ev.get("task").isJsonNull()) {
                        JsonObject task = ev.getAsJsonObject("task");
                        if (task.has("id") && entityId.equals(task.get("id").getAsString())) {
                            match = true;
                        }
                    }
                    if (ev.has("worktree") && !ev.get("worktree").isJsonNull()) {
                        JsonObject wt = ev.getAsJsonObject("worktree");
                        if (wt.has("id") && entityId.equals(wt.get("id").getAsString())) {
                            match = true;
                        }
                    }
                    if (match) filtered.add(ev);
                }
                return filtered.size() == 0 ? "No events for " + entityId : GSON.toJson(filtered);
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }
    }

    // ========================= TaskManager (控制面: 任务管理器) =========================
    //
    // 双平面架构中的"控制面"核心组件。
    // 职责:
    //   1. 任务的 CRUD (创建、查询、更新)
    //   2. 任务与 Worktree 的绑定/解绑 (控制面 <-> 执行面 的桥梁)
    //   3. 任务状态流转的校验 (只允许 pending / in_progress / completed)
    //
    // 持久化格式:
    //   .tasks/task_1.json:
    //   {
    //     "id": 1,
    //     "title": "实现登录功能",
    //     "description": "...",
    //     "status": "pending|in_progress|completed",
    //     "owner": "",
    //     "blockedBy": [],
    //     "worktree": "wt-abc" (绑定后才有),
    //     "created": "2024-01-01T12:00:00"
    //   }
    //
    // 绑定关系:
    //   task.worktree = "wt-abc" 表示该任务的代码在 wt-abc 这个 worktree 中执行
    //   这是控制面(任务)和执行面(worktree)之间的唯一关联点

    static class TaskManager {
        private final Path tasksDir;
        private final EventBus eventBus;
        private int nextId;

        TaskManager(String workDir, EventBus eventBus) {
            this.tasksDir = Paths.get(workDir, ".tasks");
            this.eventBus = eventBus;
            try { Files.createDirectories(tasksDir); } catch (IOException e) {
                throw new RuntimeException(e);
            }
            // 从已有的任务文件中计算下一个 ID (支持重启后 ID 不重复)
            // 扫描 task_*.json 文件，取最大编号 + 1
            this.nextId = 1;
            try {
                DirectoryStream<Path> stream = Files.newDirectoryStream(tasksDir, "task_*.json");
                try {
                    for (Path p : stream) {
                        String fname = p.getFileName().toString();
                        String numStr = fname.replace("task_", "").replace(".json", "");
                        try {
                            int num = Integer.parseInt(numStr);
                            if (num >= nextId) nextId = num + 1;
                        } catch (NumberFormatException ignore) { }
                    }
                } finally {
                    stream.close();
                }
            } catch (IOException ignore) { }
        }

        /** 创建任务 / Create task */
        synchronized String create(String title, String description) {
            int id = nextId++;
            String idStr = String.valueOf(id);
            JsonObject task = new JsonObject();
            task.addProperty("id", id);
            task.addProperty("title", title);
            task.addProperty("description", description);
            task.addProperty("status", "pending");
            task.addProperty("owner", "");
            task.add("blockedBy", new JsonArray());
            task.addProperty("created", DATE_FMT.format(new Date()));
            save(idStr, task);
            eventBus.emit("created", null, null, task, null);
            return "Created task_" + idStr + ": " + title;
        }

        private static final Set<String> VALID_STATUSES = new HashSet<String>(
                Arrays.asList("pending", "in_progress", "completed"));

        /** 列出全部任务 / List all tasks */
        String list() {
            JsonArray arr = new JsonArray();
            try {
                DirectoryStream<Path> stream = Files.newDirectoryStream(tasksDir, "task_*.json");
                try {
                    for (Path p : stream) {
                        String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                        arr.add(JsonParser.parseString(json));
                    }
                } finally {
                    stream.close();
                }
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
            return arr.size() == 0 ? "No tasks." : GSON.toJson(arr);
        }

        /** 获取单个任务 / Get single task */
        String get(String id) {
            Path p = tasksDir.resolve("task_" + id + ".json");
            if (!Files.exists(p)) return "Task not found: " + id;
            try {
                return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }

        /**
         * 更新任务的指定字段
         * 包含状态校验: status 只能是 pending, in_progress, completed
         * 每次更新都会发射 "updated" 事件到 EventBus
         */
        synchronized String update(String id, String field, String value) {
            Path p = tasksDir.resolve("task_" + id + ".json");
            if (!Files.exists(p)) return "Task not found: " + id;
            // 状态验证 / Status validation
            if ("status".equals(field) && !VALID_STATUSES.contains(value)) {
                return "Error: invalid status '" + value + "'. Allowed: pending, in_progress, completed";
            }
            try {
                String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                JsonObject task = JsonParser.parseString(json).getAsJsonObject();
                task.addProperty(field, value);
                save(id, task);
                JsonObject taskCopy = task.deepCopy();
                eventBus.emit("updated", taskCopy, null);
                return "Updated task_" + id + "." + field + " = " + value;
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }

        /**
         * 绑定 Worktree 到任务 (双平面桥梁操作)
         *
         * 这是控制面和执行面的关联点:
         *   控制面 (task) ---worktree字段---> 执行面 (worktree)
         *
         * 绑定时自动将任务状态设为 in_progress，并发射 "bound" 事件
         * 一个任务只能绑定一个 worktree，一个 worktree 也只应绑定一个任务
         */
        synchronized String bindWorktree(String taskId, String worktreeId) {
            Path p = tasksDir.resolve("task_" + taskId + ".json");
            if (!Files.exists(p)) return "Task not found: " + taskId;
            try {
                String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                JsonObject task = JsonParser.parseString(json).getAsJsonObject();
                task.addProperty("worktree", worktreeId);
                task.addProperty("status", "in_progress");
                save(taskId, task);
                JsonObject taskCopy = task.deepCopy();
                JsonObject wtObj = new JsonObject();
                wtObj.addProperty("id", worktreeId);
                eventBus.emit("bound", taskCopy, wtObj);
                return "Bound " + worktreeId + " to task_" + taskId;
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }

        /** 解绑 worktree / Unbind worktree from task */
        synchronized String unbindWorktree(String taskId) {
            Path p = tasksDir.resolve("task_" + taskId + ".json");
            if (!Files.exists(p)) return "Task not found: " + taskId;
            try {
                String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                JsonObject task = JsonParser.parseString(json).getAsJsonObject();
                task.remove("worktree");
                save(taskId, task);
                JsonObject taskCopy = task.deepCopy();
                eventBus.emit("unbound", taskCopy, null);
                return "Unbound worktree from task_" + taskId;
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }

        /**
         * 根据 worktree 找关联的任务 / Find task bound to a worktree
         */
        String findByWorktree(String worktreeId) {
            try {
                DirectoryStream<Path> stream = Files.newDirectoryStream(tasksDir, "task_*.json");
                try {
                    for (Path p : stream) {
                        String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                        JsonObject task = JsonParser.parseString(json).getAsJsonObject();
                        if (task.has("worktree") && !task.get("worktree").isJsonNull()
                                && worktreeId.equals(task.get("worktree").getAsString())) {
                            return task.get("id").getAsString();
                        }
                    }
                } finally {
                    stream.close();
                }
            } catch (IOException e) { /* ignore */ }
            return null;
        }

        private void save(String id, JsonObject task) {
            try {
                Files.write(tasksDir.resolve("task_" + id + ".json"),
                        GSON.toJson(task).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                System.err.println("Failed to save task: " + e.getMessage());
            }
        }
    }

    // ========================= WorktreeManager (执行面: Worktree 管理器) =========================
    //
    // 双平面架构中的"执行面"核心组件。
    // 职责:
    //   1. 创建 Git Worktree (隔离的工作目录 + 独立的 Git 分支)
    //   2. 在 Worktree 中执行命令 (目录级隔离，互不干扰)
    //   3. 管理 Worktree 生命周期 (create -> active -> kept/removed)
    //
    // 为什么用 Git Worktree？
    //   - 每个 Worktree 是独立的工作目录，有自己的文件系统视图
    //   - 多个任务可以并行执行，互相不影响
    //   - 每个 Worktree 在独立分支上工作，Git 历史清晰
    //   - 完成后可以合并分支，或直接移除 Worktree
    //
    // Worktree 索引: .worktrees/index.json
    //   {
    //     "wt-abc": {
    //       "id": "wt-abc",
    //       "path": "/absolute/path/to/.worktrees/wt-abc",
    //       "branch": "wt/wt-abc",
    //       "status": "active|kept|removed",
    //       "created": "2024-01-01T12:00:00"
    //     }
    //   }
    //
    // 操作:
    //   create -> git worktree add (创建隔离目录 + 分支)
    //   run    -> 在 worktree 目录执行命令 (核心隔离机制)
    //   keep   -> 标记 worktree 为保留状态 (不会被自动清理)
    //   remove -> git worktree remove (清理目录，可选同时完成绑定的任务)
    //   list   -> 列出所有 worktree
    //   status -> 查看单个 worktree 的 git 状态

    static class WorktreeManager {
        /** 危险命令黑名单: worktree_run 会拒绝包含这些子串的命令 */
        private static final List<String> DANGEROUS_COMMANDS = Arrays.asList(
                "rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"
        );
        /** Worktree 名称正则: 只允许字母、数字、点、下划线、连字符，最长 40 字符 */
        private static final String NAME_PATTERN = "[A-Za-z0-9._-]{1,40}";

        private final String workDir;
        private final String repoRoot;
        private final boolean gitAvailable;
        private final Path indexPath;
        private final EventBus eventBus;
        private final Map<String, JsonObject> worktrees = new LinkedHashMap<String, JsonObject>();

        WorktreeManager(String workDir, EventBus eventBus) {
            this.workDir = workDir;
            this.eventBus = eventBus;
            // 检测 git 仓库根目录: Worktree 操作需要在 git 仓库中
            // 如果不在 git 仓库中，所有 worktree 操作会返回错误
            this.repoRoot = detectRepoRoot(workDir);
            this.gitAvailable = (this.repoRoot != null);
            String baseDir = gitAvailable ? repoRoot : workDir;
            this.indexPath = Paths.get(baseDir, ".worktrees", "index.json");
            try { Files.createDirectories(indexPath.getParent()); } catch (IOException ignore) { }
            loadIndex();
        }

        /** 检测 git 仓库根目录 / Detect git repo root via git rev-parse */
        private static String detectRepoRoot(String dir) {
            try {
                ProcessBuilder pb = new ProcessBuilder();
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    pb.command("cmd", "/c", "git rev-parse --show-toplevel");
                } else {
                    pb.command("sh", "-c", "git rev-parse --show-toplevel");
                }
                pb.directory(new File(dir));
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }
                int exitCode = proc.waitFor();
                if (exitCode == 0) {
                    String root = sb.toString().trim();
                    return root.isEmpty() ? null : root;
                }
            } catch (Exception ignore) { }
            return null;
        }

        private void loadIndex() {
            if (Files.exists(indexPath)) {
                try {
                    String json = new String(Files.readAllBytes(indexPath), StandardCharsets.UTF_8);
                    JsonObject idx = JsonParser.parseString(json).getAsJsonObject();
                    for (Map.Entry<String, JsonElement> e : idx.entrySet()) {
                        worktrees.put(e.getKey(), e.getValue().getAsJsonObject());
                    }
                } catch (IOException e) { /* ignore */ }
            }
        }

        private void saveIndex() {
            try {
                Files.createDirectories(indexPath.getParent());
                JsonObject idx = new JsonObject();
                for (Map.Entry<String, JsonObject> e : worktrees.entrySet()) {
                    idx.add(e.getKey(), e.getValue());
                }
                Files.write(indexPath, GSON.toJson(idx).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                System.err.println("[WorktreeManager] Index save error: " + e.getMessage());
            }
        }

        /**
         * 创建 Git Worktree (执行面的核心操作)
         *
         * 流程:
         *   1. 生成或校验 worktree 名称
         *   2. 执行 git worktree add -b wt/<name> <path> <baseRef>
         *   3. 注册到 worktree 索引 (index.json)
         *   4. 发射 "created" 事件
         *   5. 如果提供了 taskId，自动绑定任务 (控制面 <-> 执行面)
         *
         * @param name      可选的 worktree 名称，为空时自动生成 "wt-xxxxxxxx"
         * @param taskId    可选的任务 ID，大于 0 时自动调用 bindWorktree
         * @param baseRef   基准 git ref (默认 "HEAD")，新分支从此处创建
         * @param taskManager 任务管理器，用于自动绑定
         * @return 创建结果描述
         */
        synchronized String create(String name, int taskId, String baseRef, TaskManager taskManager) {
            if (!gitAvailable) {
                return "Error: not in a git repository. Worktree operations require git.";
            }
            if (name == null || name.isEmpty()) {
                name = "wt-" + UUID.randomUUID().toString().substring(0, 8);
            }
            // 名称验证 / Name validation
            if (!name.matches(NAME_PATTERN)) {
                return "Error: invalid worktree name '" + name + "'. Must match " + NAME_PATTERN;
            }
            if (worktrees.containsKey(name)) {
                return "Worktree '" + name + "' already exists.";
            }

            String baseDir = repoRoot != null ? repoRoot : workDir;
            Path wtPath = Paths.get(baseDir, ".worktrees", name);
            String branch = "wt/" + name;
            if (baseRef == null || baseRef.isEmpty()) {
                baseRef = "HEAD";
            }

            // 执行 git worktree add / Execute git worktree add
            String cmd = "git worktree add -b " + branch + " " + wtPath.toAbsolutePath().toString() + " " + baseRef;
            String output = runCmd(cmd, baseDir);
            if (output.contains("Error") || output.contains("fatal")) {
                return "Failed to create worktree: " + output;
            }

            JsonObject wt = new JsonObject();
            wt.addProperty("id", name);
            wt.addProperty("path", wtPath.toAbsolutePath().toString());
            wt.addProperty("branch", branch);
            wt.addProperty("status", "active");
            wt.addProperty("created", DATE_FMT.format(new Date()));
            worktrees.put(name, wt);
            saveIndex();

            JsonObject wtEvent = wt.deepCopy();
            eventBus.emit("created", null, wtEvent);

            StringBuilder result = new StringBuilder();
            result.append("Created worktree '").append(name).append("' at ").append(wtPath.toAbsolutePath().toString());

            // 自动绑定任务 / Auto-bind task if task_id provided
            if (taskId > 0 && taskManager != null) {
                String bindResult = taskManager.bindWorktree(String.valueOf(taskId), name);
                result.append("\n").append(bindResult);
            }

            return result.toString();
        }

        /** 列出所有 worktree / List all worktrees */
        String list() {
            if (worktrees.isEmpty()) return "No worktrees.";
            JsonArray arr = new JsonArray();
            for (JsonObject wt : worktrees.values()) {
                arr.add(wt);
            }
            return GSON.toJson(arr);
        }

        /** 查看 worktree 的实际 git 状态 / View worktree git status */
        String status(String name) {
            JsonObject wt = worktrees.get(name);
            if (wt == null) return "Worktree not found: " + name;
            if (!gitAvailable) {
                return "Error: not in a git repository.";
            }
            String path = wt.get("path").getAsString();
            if (!Files.isDirectory(Paths.get(path))) {
                return "Worktree directory missing: " + path;
            }
            // 运行实际 git status / Run actual git status
            String gitOutput = runCmd("git status --short --branch", path);
            return "=== worktree_status [" + name + "] ===\n" + gitOutput;
        }

        /**
         * 在 Worktree 目录中执行命令 (目录级隔离的核心)
         *
         * 这是双平面架构中"执行面"的关键操作:
         *   - 命令在 worktree 的工作目录中执行 (ProcessBuilder.directory)
         *   - 每个 worktree 是独立的 git 工作目录，有自己的文件视图
         *   - 在 wt-abc 中修改文件不会影响 wt-def 或主仓库
         *   - 包含危险命令检查 (rm -rf /, sudo 等)
         *
         * @param name    worktree 名称
         * @param command 要执行的 shell 命令
         * @return 命令输出
         */
        String run(String name, String command) {
            JsonObject wt = worktrees.get(name);
            if (wt == null) return "Worktree not found: " + name;
            String path = wt.get("path").getAsString();
            if (!Files.isDirectory(Paths.get(path))) {
                return "Worktree directory missing: " + path;
            }

            // 危险命令检查 / Dangerous command check
            for (String dangerous : DANGEROUS_COMMANDS) {
                if (command.contains(dangerous)) {
                    return "Error: Dangerous command blocked";
                }
            }

            JsonObject wtEvent = wt.deepCopy();
            eventBus.emit("command", null, wtEvent);
            String output = runCmd(command, path);
            return "=== worktree_run [" + name + "] ===\n" + output;
        }

        /** 标记 worktree 为保留 / Mark worktree as kept with timestamp */
        synchronized String keep(String name) {
            JsonObject wt = worktrees.get(name);
            if (wt == null) return "Worktree not found: " + name;
            wt.addProperty("status", "kept");
            wt.addProperty("kept_at", DATE_FMT.format(new Date()));
            saveIndex();
            JsonObject wtEvent = wt.deepCopy();
            eventBus.emit("kept", null, wtEvent);
            return "Worktree '" + name + "' marked as kept.";
        }

        /**
         * 移除 Worktree (执行面清理 + 可选控制面收尾)
         *
         * 流程:
         *   1. 如果 completeTask=true，找到绑定的任务并标记为 completed + 解绑
         *   2. 执行 git worktree remove (清理目录)
         *   3. 在索引中标记为 removed (保留记录而非删除，支持审计)
         *   4. 发射 "removed" 事件
         *
         * 注意: 分支不会被删除 (git worktree remove 不删分支)，
         * 方便后续查看历史或合并到主分支
         *
         * @param force        强制移除 (即使有未提交的更改)
         * @param completeTask 是否同时将绑定的任务标记为 completed
         * @param taskManager  任务管理器 (用于完成任务和解绑)
         */
        synchronized String remove(String name, boolean force, boolean completeTask, TaskManager taskManager) {
            JsonObject wt = worktrees.get(name);
            if (wt == null) return "Worktree not found: " + name;

            String path = wt.get("path").getAsString();
            StringBuilder result = new StringBuilder();

            // 如果需要完成关联任务 / Optionally complete bound task
            if (completeTask && taskManager != null) {
                String taskId = taskManager.findByWorktree(name);
                if (taskId != null) {
                    taskManager.update(taskId, "status", "completed");
                    taskManager.unbindWorktree(taskId);
                    result.append("Completed task ").append(taskId).append(". ");
                }
            }

            // git worktree remove (不删除分支 / Do NOT delete the branch)
            String forceFlag = force ? " --force" : "";
            runCmd("git worktree remove " + path + forceFlag, repoRoot != null ? repoRoot : workDir);

            // 标记为 removed 而非从索引删除 / Mark as removed instead of deleting from index
            wt.addProperty("status", "removed");
            wt.addProperty("removed_at", DATE_FMT.format(new Date()));
            saveIndex();

            JsonObject wtEvent = wt.deepCopy();
            eventBus.emit("removed", null, wtEvent);
            result.append("Removed worktree '").append(name).append("'.");
            return result.toString();
        }

        /** 执行 shell 命令 / Run shell command in specified directory */
        private String runCmd(String command, String dir) {
            try {
                ProcessBuilder pb = new ProcessBuilder();
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    pb.command("cmd", "/c", command);
                } else {
                    pb.command("sh", "-c", command);
                }
                pb.directory(new File(dir));
                pb.redirectErrorStream(true);

                Process proc = pb.start();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                }
                proc.waitFor(120, TimeUnit.SECONDS);
                String result = sb.toString().trim();
                return result.isEmpty() ? "(no output)" : result;
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }
    }

    // ========================= main (单 Agent + REPL) =========================
    //
    // S12 不同于 S09-S11 的多 Agent 团队架构，它是单 Agent 架构。
    // 核心创新不在于多 Agent，而在于"双平面"隔离:
    //
    //   控制面 (Control Plane):
    //     - TaskManager: 管理任务状态 (what to do)
    //     - 工具: task_create, task_list, task_get, task_update, task_bind_worktree
    //
    //   执行面 (Execution Plane):
    //     - WorktreeManager: 管理隔离的工作目录 (where to do it)
    //     - 工具: worktree_create, worktree_run, worktree_list, worktree_status,
    //             worktree_keep, worktree_remove
    //
    //   桥梁:
    //     - task_bind_worktree: 将任务绑定到 worktree (task.worktree = "wt-abc")
    //     - worktree_remove(complete_task=true): 移除 worktree 时完成绑定的任务
    //
    //   可观测性:
    //     - EventBus: 记录所有生命周期事件到 events.jsonl
    //     - worktree_events: 查询事件历史
    //
    // 典型工作流:
    //   task_create -> worktree_create -> task_bind_worktree
    //   -> worktree_run (多次) -> worktree_remove(complete_task=true)

    public static void main(String[] args) throws Exception {
        String workDir = System.getProperty("user.dir");
        System.out.println("=== S12 Worktree Task Isolation ===");
        System.out.println("Work dir: " + workDir);
        System.out.println("Key Insight: Isolate by directory, coordinate by task ID.\n");

        // 初始化双平面核心组件
        final AnthropicClient client = new AnthropicClient();
        final EventBus eventBus = new EventBus(workDir);                         // 可观测性
        final TaskManager taskManager = new TaskManager(workDir, eventBus);      // 控制面
        final WorktreeManager worktreeManager = new WorktreeManager(workDir, eventBus); // 执行面
        final BaseTools baseTools = new BaseTools(workDir);                      // 基础工具

        // 工具处理器 (16 tools): 4 base + 5 task + 6 worktree + 1 observability
        final Map<String, ToolHandler> handlers = new LinkedHashMap<String, ToolHandler>();

        // 4 base tools
        handlers.put("bash", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                return baseTools.runBash(input.get("command").getAsString());
            }
        });
        handlers.put("read_file", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                int limit = input.has("limit") ? input.get("limit").getAsInt() : 0;
                return baseTools.runRead(input.get("path").getAsString(), limit);
            }
        });
        handlers.put("write_file", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                return baseTools.runWrite(input.get("path").getAsString(), input.get("content").getAsString());
            }
        });
        handlers.put("edit_file", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                return baseTools.runEdit(input.get("path").getAsString(),
                        input.get("old_text").getAsString(), input.get("new_text").getAsString());
            }
        });

        // 控制面工具: 4 个任务管理工具
        handlers.put("task_create", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                return taskManager.create(input.get("title").getAsString(),
                        input.has("description") ? input.get("description").getAsString() : "");
            }
        });
        handlers.put("task_list", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                return taskManager.list();
            }
        });
        handlers.put("task_get", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                return taskManager.get(input.get("task_id").getAsString());
            }
        });
        handlers.put("task_update", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                return taskManager.update(input.get("task_id").getAsString(),
                        input.get("field").getAsString(), input.get("value").getAsString());
            }
        });

        // 控制面 <-> 执行面 桥梁: 将 worktree 绑定到任务
        handlers.put("task_bind_worktree", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                return taskManager.bindWorktree(input.get("task_id").getAsString(),
                        input.get("worktree_id").getAsString());
            }
        });

        // 执行面工具: 6 个 worktree 管理工具
        handlers.put("worktree_create", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                String name = input.has("name") ? input.get("name").getAsString() : null;
                int taskId = input.has("task_id") ? input.get("task_id").getAsInt() : 0;
                String baseRef = input.has("base_ref") ? input.get("base_ref").getAsString() : "HEAD";
                return worktreeManager.create(name, taskId, baseRef, taskManager);
            }
        });
        handlers.put("worktree_list", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                return worktreeManager.list();
            }
        });
        handlers.put("worktree_status", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                return worktreeManager.status(input.get("name").getAsString());
            }
        });
        handlers.put("worktree_run", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                return worktreeManager.run(input.get("name").getAsString(),
                        input.get("command").getAsString());
            }
        });
        handlers.put("worktree_keep", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                return worktreeManager.keep(input.get("name").getAsString());
            }
        });
        handlers.put("worktree_remove", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                boolean force = input.has("force") && input.get("force").getAsBoolean();
                boolean completeTask = input.has("complete_task")
                        && input.get("complete_task").getAsBoolean();
                return worktreeManager.remove(input.get("name").getAsString(), force, completeTask, taskManager);
            }
        });

        // 可观测性工具: 查询生命周期事件历史
        // 支持按 entity_id 过滤、按数量限制、或返回全部事件
        handlers.put("worktree_events", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                if (input.has("entity_id")) {
                    return eventBus.readByEntity(input.get("entity_id").getAsString());
                }
                if (input.has("limit")) {
                    return eventBus.listRecent(input.get("limit").getAsInt());
                }
                return eventBus.readAll();
            }
        });

        // 工具定义 (16 tools) / Tool definitions
        JsonArray toolDefs = BaseTools.allToolDefs();

        // Task tools
        toolDefs.add(AnthropicClient.toolDef("task_create", "Create a new task.",
                AnthropicClient.schema("title", "string", "true",
                        "description", "string", "false")));
        toolDefs.add(AnthropicClient.toolDef("task_list", "List all tasks.",
                AnthropicClient.schema()));
        toolDefs.add(AnthropicClient.toolDef("task_get", "Get a specific task by ID.",
                AnthropicClient.schema("task_id", "string", "true")));
        toolDefs.add(AnthropicClient.toolDef("task_update", "Update a task field.",
                AnthropicClient.schema("task_id", "string", "true",
                        "field", "string", "true", "value", "string", "true")));
        toolDefs.add(AnthropicClient.toolDef("task_bind_worktree",
                "Bind a worktree to a task. Sets task to in_progress.",
                AnthropicClient.schema("task_id", "string", "true",
                        "worktree_id", "string", "true")));

        // Worktree tools
        toolDefs.add(AnthropicClient.toolDef("worktree_create",
                "Create a new git worktree for isolated execution.",
                AnthropicClient.schema("name", "string", "false",
                        "task_id", "integer", "false",
                        "base_ref", "string", "false")));
        toolDefs.add(AnthropicClient.toolDef("worktree_list",
                "List all worktrees.",
                AnthropicClient.schema()));
        toolDefs.add(AnthropicClient.toolDef("worktree_status",
                "Get status of a specific worktree.",
                AnthropicClient.schema("name", "string", "true")));
        toolDefs.add(AnthropicClient.toolDef("worktree_run",
                "Run a command inside a worktree directory (isolated execution).",
                AnthropicClient.schema("name", "string", "true",
                        "command", "string", "true")));
        toolDefs.add(AnthropicClient.toolDef("worktree_keep",
                "Mark a worktree as kept (won't be auto-cleaned).",
                AnthropicClient.schema("name", "string", "true")));
        toolDefs.add(AnthropicClient.toolDef("worktree_remove",
                "Remove a worktree. Optionally force and complete the bound task.",
                AnthropicClient.schema("name", "string", "true",
                        "force", "boolean", "false",
                        "complete_task", "boolean", "false")));

        // Observability
        toolDefs.add(AnthropicClient.toolDef("worktree_events",
                "View lifecycle events. Optionally filter by entity_id or limit to last N.",
                AnthropicClient.schema("entity_id", "string", "false",
                        "limit", "integer", "false")));

        String systemPrompt = "You are an agent that uses git worktrees for task isolation.\n\n"
                + "Architecture:\n"
                + "- CONTROL PLANE: Tasks are tracked in .tasks/ (task_create, task_list, task_get, task_update)\n"
                + "- EXECUTION PLANE: Worktrees provide isolated directories (worktree_create, worktree_run)\n"
                + "- BINDING: task_bind_worktree connects a task to a worktree\n"
                + "- OBSERVABILITY: worktree_events shows lifecycle history\n\n"
                + "Workflow:\n"
                + "1. Create a task (task_create)\n"
                + "2. Create a worktree (worktree_create)\n"
                + "3. Bind the worktree to the task (task_bind_worktree)\n"
                + "4. Execute work in isolation (worktree_run)\n"
                + "5. When done, remove worktree + complete task (worktree_remove with complete_task=true)\n\n"
                + "Key: worktree_run executes commands INSIDE the worktree directory, providing isolation.";

        // ===== REPL (交互式命令行) =====
        // 特殊命令: /tasks(任务列表), /worktrees(worktree列表), /events(事件日志), /quit(退出)
        JsonArray messages = new JsonArray();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\nYou (/tasks, /worktrees, /events, /quit): ");
            if (!scanner.hasNextLine()) break;
            String userInput = scanner.nextLine().trim();
            if (userInput.isEmpty()) continue;

            if ("/quit".equals(userInput)) break;
            if ("/tasks".equals(userInput)) { System.out.println(taskManager.list()); continue; }
            if ("/worktrees".equals(userInput)) { System.out.println(worktreeManager.list()); continue; }
            if ("/events".equals(userInput)) { System.out.println(eventBus.readAll()); continue; }

            messages.add(AnthropicClient.userMessage(userInput));

            // Agent loop
            while (true) {
                JsonObject response = client.createMessage(systemPrompt, messages, toolDefs, 8000);
                JsonArray content = AnthropicClient.getContent(response);
                String stopReason = AnthropicClient.getStopReason(response);
                messages.add(AnthropicClient.assistantMessage(content));

                String text = AnthropicClient.extractText(content);
                if (!text.isEmpty()) System.out.println("\nAgent: " + text);

                if (!"tool_use".equals(stopReason)) break;

                JsonArray toolResults = new JsonArray();
                for (JsonElement el : content) {
                    JsonObject block = el.getAsJsonObject();
                    if ("tool_use".equals(block.get("type").getAsString())) {
                        String toolName = block.get("name").getAsString();
                        String toolId = block.get("id").getAsString();
                        JsonObject toolInput = block.getAsJsonObject("input");

                        ToolHandler handler = handlers.get(toolName);
                        String result = (handler != null)
                                ? handler.execute(toolInput)
                                : "Error: unknown tool '" + toolName + "'";
                        System.out.println("[Tool] " + toolName + " -> "
                                + (result.length() > 150 ? result.substring(0, 150) + "..." : result));
                        toolResults.add(AnthropicClient.toolResult(toolId, result));
                    }
                }
                messages.add(AnthropicClient.userMessage(toolResults));
            }
        }

        scanner.close();
        System.out.println("Goodbye from S12 Worktree Task Isolation!");
    }
}
