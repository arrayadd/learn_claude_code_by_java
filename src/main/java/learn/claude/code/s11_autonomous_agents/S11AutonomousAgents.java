package learn.claude.code.s11_autonomous_agents;

import com.google.gson.*;
import learn.claude.code.common.AnthropicClient;
import learn.claude.code.common.BaseTools;
import learn.claude.code.common.ToolHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * S11 - Autonomous Agents: 自主 Agent 与空闲轮询
 *
 * 核心洞察 / Key Insight:
 *   "The agent finds work itself."
 *   Agent 自己寻找工作。
 *
 * Teammate 生命周期 / Lifecycle:
 *
 *   ┌───────┐    no tools     ┌──────┐  poll 5s x 12  ┌───────────┐
 *   │ WORK  │ ─────────────> │ IDLE │ ──────────────> │ check     │
 *   │       │  (end_turn)     │      │  (60s total)    │ inbox /   │
 *   └───┬───┘                 └──┬───┘                 │ scan tasks│
 *       │                        │                     └─────┬─────┘
 *       │   ┌────────────────────┘                           │
 *       │   │ new message / unclaimed task                   │
 *       │   v                                                │
 *       │ ┌───────┐                                          │
 *       └>│ WORK  │ <───────────────────────────────────────┘
 *         └───────┘       resume with new context
 *                              │ nothing found
 *                              v
 *                         ┌──────────┐
 *                         │ SHUTDOWN │
 *                         └──────────┘
 *
 * 自动认领任务 / Auto-Claiming Tasks:
 *   - scan_unclaimed_tasks(): 扫描 .tasks/ 目录，找 status=pending 且无 owner 且无 blockedBy 的任务
 *   - claim_task(): 设置 owner + status=in_progress
 *
 * 身份再注入 / Identity Re-injection:
 *   当对话上下文变短时，在消息开头重新插入身份信息块。
 *   When conversation context gets short, re-inject identity block at start of messages.
 *
 * 工具清单 / Tool Inventory:
 *   Lead (14): bash, read_file, write_file, edit_file,
 *              spawn_teammate, list_teammates, send_message, read_inbox, broadcast,
 *              shutdown_request, shutdown_status, plan_review,
 *              create_task, list_tasks
 *   Teammate (10): bash, read_file, write_file, edit_file,
 *                   send_message, read_inbox, shutdown_response, plan_submit,
 *                   idle, claim_task
 *
 * 运行 / Run:
 *   mvn exec:java -Dexec.mainClass="learn.claude.code.s11_autonomous_agents.S11AutonomousAgents"
 */
public class S11AutonomousAgents {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 消息类型白名单 (与 S09/S10 一致) */
    private static final Set<String> VALID_MSG_TYPES = new HashSet<String>(Arrays.asList(
            "message", "broadcast", "shutdown_request", "shutdown_response", "plan_approval_response"
    ));

    // 协议追踪器 (与 S10 一致): 通过 request_id 关联请求和响应
    /** 关闭协议追踪器: request_id -> {target, status, decision, reason} */
    private static final ConcurrentHashMap<String, JsonObject> shutdownRequests =
            new ConcurrentHashMap<String, JsonObject>();
    /** 计划审批追踪器: request_id -> {from, plan, status, decision} */
    private static final ConcurrentHashMap<String, JsonObject> planRequests =
            new ConcurrentHashMap<String, JsonObject>();

    // ========================= MessageBus (JSONL 消息总线) =========================
    // 与 S09/S10 相同的 JSONL 文件收件箱机制，支持 request_id 附加字段

    static class MessageBus {
        private final Path inboxDir;

        MessageBus(String workDir) {
            this.inboxDir = Paths.get(workDir, ".team", "inbox");
            try { Files.createDirectories(inboxDir); } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        synchronized String send(String sender, String to, String content, String msgType) {
            return send(sender, to, content, msgType, null);
        }

        synchronized String send(String sender, String to, String content, String msgType, String requestId) {
            if (!VALID_MSG_TYPES.contains(msgType)) {
                return "Error: invalid message type '" + msgType + "'";
            }
            try {
                JsonObject msg = new JsonObject();
                msg.addProperty("from", sender);
                msg.addProperty("to", to);
                msg.addProperty("content", content);
                msg.addProperty("type", msgType);
                msg.addProperty("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date()));
                if (requestId != null) msg.addProperty("request_id", requestId);

                Path inbox = inboxDir.resolve(to + ".jsonl");
                try (BufferedWriter w = Files.newBufferedWriter(inbox, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(GSON.toJson(msg).replace("\n", " "));
                    w.newLine();
                }
                return "Sent " + msgType + " to " + to;
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }

        synchronized String readInbox(String name) {
            Path inbox = inboxDir.resolve(name + ".jsonl");
            if (!Files.exists(inbox)) return "[]";
            try {
                List<String> lines = Files.readAllLines(inbox, StandardCharsets.UTF_8);
                if (lines.isEmpty()) return "[]";
                Files.write(inbox, new byte[0]);
                JsonArray messages = new JsonArray();
                for (String line : lines) {
                    String t = line.trim();
                    if (!t.isEmpty()) messages.add(JsonParser.parseString(t));
                }
                return messages.size() == 0 ? "[]" : GSON.toJson(messages);
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }

        String broadcast(String sender, String content, List<String> teammates) {
            StringBuilder sb = new StringBuilder();
            for (String n : teammates) {
                if (!n.equals(sender)) sb.append(send(sender, n, content, "broadcast")).append("\n");
            }
            return sb.toString().trim();
        }
    }

    // ========================= TaskStore (任务存储) =========================
    //
    // S11 新增的核心组件: 文件系统持久化的任务板。
    // 这是自主 Agent 的"工作来源"，Teammate 在空闲时会自动扫描并认领未分配的任务。
    //
    // 目录结构:
    //   .tasks/
    //   +-- task-a1b2c3d4.json   { id, title, description, status, owner, blockedBy, created }
    //   +-- task-e5f6g7h8.json
    //
    // 任务状态流转:
    //   pending (待认领) -> in_progress (已认领/进行中) -> completed (已完成)
    //
    // 自动认领条件 (scanUnclaimed):
    //   status == "pending" && owner 为空 && blockedBy 为空
    //
    // 线程安全: synchronized 方法保证多个 Teammate 不会同时认领同一任务

    static class TaskStore {
        private final Path tasksDir;

        TaskStore(String workDir) {
            this.tasksDir = Paths.get(workDir, ".tasks");
            try { Files.createDirectories(tasksDir); } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        /** 创建任务 / Create a new task */
        synchronized String createTask(String title, String description) {
            String id = "task-" + UUID.randomUUID().toString().substring(0, 8);
            JsonObject task = new JsonObject();
            task.addProperty("id", id);
            task.addProperty("title", title);
            task.addProperty("description", description);
            task.addProperty("status", "pending");
            task.addProperty("created", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date()));
            // owner 和 blockedBy 暂不设置 / owner and blockedBy not set initially
            saveTask(id, task);
            return "Created task " + id + ": " + title;
        }

        /** 列出所有任务 / List all tasks */
        String listTasks() {
            JsonArray list = new JsonArray();
            try {
                DirectoryStream<Path> stream = Files.newDirectoryStream(tasksDir, "task-*.json");
                try {
                    for (Path p : stream) {
                        String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                        list.add(JsonParser.parseString(json));
                    }
                } finally {
                    stream.close();
                }
            } catch (IOException e) {
                return "Error listing tasks: " + e.getMessage();
            }
            return list.size() == 0 ? "No tasks." : GSON.toJson(list);
        }

        /**
         * 扫描未认领任务 / Scan for unclaimed tasks
         * 条件: status=pending, 无 owner, 无 blockedBy (或 blockedBy 为空)
         * Criteria: status=pending, no owner, no blockedBy (or empty blockedBy)
         */
        synchronized String scanUnclaimed() {
            JsonArray unclaimed = new JsonArray();
            try {
                DirectoryStream<Path> stream = Files.newDirectoryStream(tasksDir, "task-*.json");
                try {
                    for (Path p : stream) {
                        String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                        JsonObject task = JsonParser.parseString(json).getAsJsonObject();
                        String status = task.has("status") ? task.get("status").getAsString() : "";
                        boolean hasOwner = task.has("owner") && !task.get("owner").isJsonNull()
                                && !task.get("owner").getAsString().isEmpty();
                        boolean blocked = task.has("blockedBy") && !task.get("blockedBy").isJsonNull()
                                && !task.get("blockedBy").getAsString().isEmpty();

                        if ("pending".equals(status) && !hasOwner && !blocked) {
                            unclaimed.add(task);
                        }
                    }
                } finally {
                    stream.close();
                }
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
            return unclaimed.size() == 0 ? "No unclaimed tasks." : GSON.toJson(unclaimed);
        }

        /**
         * 认领任务 / Claim a task: set owner + status=in_progress
         */
        synchronized String claimTask(String taskId, String owner) {
            Path p = tasksDir.resolve(taskId + ".json");
            if (!Files.exists(p)) return "Error: task " + taskId + " not found.";
            try {
                String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                JsonObject task = JsonParser.parseString(json).getAsJsonObject();
                if (task.has("owner") && !task.get("owner").isJsonNull()
                        && !task.get("owner").getAsString().isEmpty()) {
                    return "Error: task " + taskId + " already claimed by " + task.get("owner").getAsString();
                }
                task.addProperty("owner", owner);
                task.addProperty("status", "in_progress");
                saveTask(taskId, task);
                return "Task " + taskId + " claimed by " + owner;
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }

        private void saveTask(String id, JsonObject task) {
            try {
                Files.write(tasksDir.resolve(id + ".json"),
                        GSON.toJson(task).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                System.err.println("Failed to save task " + id + ": " + e.getMessage());
            }
        }
    }

    // ========================= TeammateManager (自主队友管理器) =========================
    //
    // S11 与 S09/S10 的核心区别: Teammate 拥有自主生命周期
    //
    // 自主生命周期 (Autonomous Lifecycle):
    //
    //   WORK (工作阶段)
    //     |-- LLM 推理 + 工具调用
    //     |-- end_turn 或 idle 工具触发 -> 进入 IDLE
    //     |-- 工作期间也会检查收件箱 (检测 shutdown_request)
    //     v
    //   IDLE (空闲轮询阶段)
    //     |-- 每 5 秒轮询一次，最多 12 次 (总计 60 秒)
    //     |-- 每次轮询: 检查收件箱 -> 扫描未认领任务
    //     |-- 找到工作 -> 回到 WORK
    //     |-- 60 秒内无工作 -> 进入 SHUTDOWN
    //     v
    //   SHUTDOWN (关闭)
    //     |-- 线程终止，状态标记为 "shutdown"
    //
    // 新增机制:
    //   1. idle 工具: Teammate 主动调用，表示当前工作已完成
    //   2. claim_task 工具: Teammate 主动认领任务 (也可在空闲轮询中自动认领)
    //   3. 身份再注入: 当对话上下文较短时，在开头插入身份信息防止 LLM "忘记"自己是谁

    static class TeammateManager {
        /** 队友名称 -> 运行线程 */
        private final Map<String, Thread> threads = new ConcurrentHashMap<String, Thread>();
        /** 队友名称 -> 当前状态 (working / idle / shutdown) */
        private final Map<String, String> statuses = new ConcurrentHashMap<String, String>();
        /** 队友名称 -> 配置信息 {name, role, prompt} */
        private final Map<String, JsonObject> configs = new ConcurrentHashMap<String, JsonObject>();
        private final MessageBus bus;
        private final AnthropicClient client;
        private final String workDir;
        private final TaskStore taskStore;
        private final Path configPath;

        // 身份再注入阈值: 当对话消息数少于此值时，会在开头插入身份信息
        // 防止 LLM 在上下文较短时"忘记"自己的角色和任务
        private static final int IDENTITY_REINJECT_THRESHOLD = 6;

        TeammateManager(MessageBus bus, AnthropicClient client, String workDir, TaskStore taskStore) {
            this.bus = bus;
            this.client = client;
            this.workDir = workDir;
            this.taskStore = taskStore;
            this.configPath = Paths.get(workDir, ".team", "config.json");
            loadConfig();
        }

        private void loadConfig() {
            if (Files.exists(configPath)) {
                try {
                    String json = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
                    JsonObject cfg = JsonParser.parseString(json).getAsJsonObject();
                    // 与 S09/S10 保持一致，从 "members" 数组中读取配置
                    // Consistent with S09/S10: read from "members" array
                    if (cfg.has("members")) {
                        JsonArray members = cfg.getAsJsonArray("members");
                        for (JsonElement el : members) {
                            JsonObject member = el.getAsJsonObject();
                            configs.put(member.get("name").getAsString(), member);
                        }
                    }
                } catch (IOException e) { /* ignore */ }
            }
        }

        private void saveConfig() {
            try {
                // 与 S09/S10 保持一致的配置格式: {"team_name":"default","members":[...]}
                // Consistent with S09/S10 format: {"team_name":"default","members":[...]}
                JsonObject cfg = new JsonObject();
                cfg.addProperty("team_name", "default");
                JsonArray members = new JsonArray();
                for (Map.Entry<String, JsonObject> e : configs.entrySet()) {
                    members.add(e.getValue());
                }
                cfg.add("members", members);
                Files.createDirectories(configPath.getParent());
                Files.write(configPath, GSON.toJson(cfg).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) { /* ignore */ }
        }

        /**
         * 创建自主 teammate / Spawn autonomous teammate
         *
         * 自主行为：WORK -> IDLE (轮询 60s) -> 检查收件箱/扫描任务 -> 恢复或关闭
         * Autonomous behavior: WORK -> IDLE (poll 60s) -> check inbox/scan tasks -> resume or shutdown
         *
         * Teammate 有 10 个工具: 4 base + send_message + read_inbox + shutdown_response
         *                       + plan_submit + idle + claim_task
         */
        String spawn(final String name, final String role, final String prompt) {
            if (threads.containsKey(name) && threads.get(name).isAlive()) {
                return "Teammate '" + name + "' is already running.";
            }

            JsonObject cfg = new JsonObject();
            cfg.addProperty("name", name);
            cfg.addProperty("role", role);
            cfg.addProperty("prompt", prompt);
            configs.put(name, cfg);
            saveConfig();
            statuses.put(name, "working");

            final BaseTools tools = new BaseTools(workDir);
            final Map<String, ToolHandler> tHandlers = new LinkedHashMap<String, ToolHandler>();

            // 4 base tools
            tHandlers.put("bash", new ToolHandler() {
                @Override public String execute(JsonObject input) {
                    return tools.runBash(input.get("command").getAsString());
                }
            });
            tHandlers.put("read_file", new ToolHandler() {
                @Override public String execute(JsonObject input) {
                    int limit = input.has("limit") ? input.get("limit").getAsInt() : 0;
                    return tools.runRead(input.get("path").getAsString(), limit);
                }
            });
            tHandlers.put("write_file", new ToolHandler() {
                @Override public String execute(JsonObject input) {
                    return tools.runWrite(input.get("path").getAsString(), input.get("content").getAsString());
                }
            });
            tHandlers.put("edit_file", new ToolHandler() {
                @Override public String execute(JsonObject input) {
                    return tools.runEdit(input.get("path").getAsString(),
                            input.get("old_text").getAsString(), input.get("new_text").getAsString());
                }
            });

            // 通信 / Communication
            tHandlers.put("send_message", new ToolHandler() {
                @Override public String execute(JsonObject input) {
                    return bus.send(name, input.get("to").getAsString(),
                            input.get("content").getAsString(),
                            input.has("msg_type") ? input.get("msg_type").getAsString() : "message");
                }
            });
            tHandlers.put("read_inbox", new ToolHandler() {
                @Override public String execute(JsonObject input) {
                    return bus.readInbox(name);
                }
            });

            // 关闭协议 / Shutdown protocol
            // 与 S10 保持一致: 使用 approve(boolean) + reason(string) 参数，而非 decision(string)
            // Consistent with S10: use approve(boolean) + reason(string) instead of decision(string)
            tHandlers.put("shutdown_response", new ToolHandler() {
                @Override public String execute(JsonObject input) {
                    String reqId = input.get("request_id").getAsString();
                    boolean approve = input.get("approve").getAsBoolean();
                    String reason = input.has("reason") ? input.get("reason").getAsString() : "";
                    String decision = approve ? "approve" : "reject";

                    JsonObject tracker = shutdownRequests.get(reqId);
                    if (tracker == null) return "Error: unknown request_id";
                    tracker.addProperty("status", "resolved");
                    tracker.addProperty("decision", decision);
                    tracker.addProperty("reason", reason);
                    bus.send(name, "lead",
                            "Shutdown " + decision + " for request " + reqId + ". " + reason,
                            "shutdown_response", reqId);
                    if (approve) statuses.put(name, "shutdown");
                    return "Shutdown " + decision + " sent to lead.";
                }
            });

            // 计划提交 / Plan submission
            tHandlers.put("plan_submit", new ToolHandler() {
                @Override public String execute(JsonObject input) {
                    String plan = input.get("plan").getAsString();
                    String reqId = "plan-" + UUID.randomUUID().toString().substring(0, 8);
                    JsonObject tracker = new JsonObject();
                    tracker.addProperty("from", name);
                    tracker.addProperty("plan", plan);
                    tracker.addProperty("status", "pending");
                    planRequests.put(reqId, tracker);
                    bus.send(name, "lead", "Plan [" + reqId + "]: " + plan, "message", reqId);
                    return "Plan submitted: " + reqId;
                }
            });

            // 空闲工具 (S11 新增): Teammate 主动声明进入空闲状态
            // 触发后，Agent Loop 会进入空闲轮询阶段 (5s x 12 = 60s)
            // 在轮询期间自动检查收件箱和扫描未认领任务
            tHandlers.put("idle", new ToolHandler() {
                @Override public String execute(JsonObject input) {
                    statuses.put(name, "idle");
                    return "Entered idle state. Will poll for work.";
                }
            });

            // 认领任务工具 (S11 新增): Teammate 主动认领指定 ID 的任务
            // 内部调用 TaskStore.claimTask()，设置 owner + status=in_progress
            tHandlers.put("claim_task", new ToolHandler() {
                @Override public String execute(JsonObject input) {
                    return taskStore.claimTask(input.get("task_id").getAsString(), name);
                }
            });

            // 工具定义 (10 tools) / Tool definitions
            final JsonArray toolDefs = BaseTools.allToolDefs();
            toolDefs.add(AnthropicClient.toolDef("send_message", "Send a message to a teammate.",
                    AnthropicClient.schema("to", "string", "true",
                            "content", "string", "true", "msg_type", "string", "false")));
            toolDefs.add(AnthropicClient.toolDef("read_inbox", "Read your inbox.",
                    AnthropicClient.schema()));
            toolDefs.add(AnthropicClient.toolDef("shutdown_response",
                    "Respond to a shutdown request with approve or reject.",
                    AnthropicClient.schema("request_id", "string", "true",
                            "approve", "boolean", "true",
                            "reason", "string", "false")));
            toolDefs.add(AnthropicClient.toolDef("plan_submit",
                    "Submit a plan for lead approval.",
                    AnthropicClient.schema("plan", "string", "true")));
            toolDefs.add(AnthropicClient.toolDef("idle",
                    "Signal that you are idle and looking for work.",
                    AnthropicClient.schema()));
            toolDefs.add(AnthropicClient.toolDef("claim_task",
                    "Claim an unclaimed task by ID.",
                    AnthropicClient.schema("task_id", "string", "true")));

            // 身份信息块: 用于身份再注入时插入对话上下文开头
            // 包含 Teammate 的名称、角色和自定义提示词
            final String identityBlock = "[IDENTITY] You are '" + name + "', role: " + role + ". " + prompt;
            final String teamName = "default";

            final String systemPrompt = "You are '" + name + "', an autonomous teammate. Role: " + role + ".\n"
                    + prompt + "\n\n"
                    + "Autonomous behavior:\n"
                    + "1. Work on assigned tasks until done.\n"
                    + "2. When done, use 'idle' tool to enter idle state.\n"
                    + "3. During idle, the system will poll for new messages and unclaimed tasks.\n"
                    + "4. If an unclaimed task is found, use claim_task to take it.\n"
                    + "5. If nothing to do after 60s idle, you will shut down.\n"
                    + "Before major work, submit a plan via plan_submit.";

            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    System.out.println("[Teammate:" + name + "] Started (autonomous).");
                    JsonArray messages = new JsonArray();
                    messages.add(AnthropicClient.userMessage(
                            "You are autonomous teammate '" + name + "'. Begin working."));
                    try {
                        // ===== 自主 Teammate Agent Loop =====
                        // 与 S09/S10 的 Agent Loop 的关键区别:
                        //   1. 支持身份再注入 (防止 LLM 忘记角色)
                        //   2. end_turn 后进入空闲轮询 (而非简单等待)
                        //   3. 空闲轮询时自动扫描和认领任务
                        //   4. 工作阶段也会检查收件箱 (检测 shutdown_request)
                        while (!Thread.currentThread().isInterrupted()
                                && !"shutdown".equals(statuses.get(name))) {

                            // 身份再注入机制:
                            // 当对话消息数少于阈值 (6 条) 时，在消息列表开头插入一对
                            // user+assistant 消息，提醒 LLM 自己的身份和角色。
                            // 这在 Teammate 刚从空闲恢复时特别重要，因为新注入的任务信息
                            // 可能只有 1-2 条消息，LLM 容易"忘记"自己是谁。
                            if (messages.size() > 0 && messages.size() < IDENTITY_REINJECT_THRESHOLD) {
                                JsonArray newMessages = new JsonArray();
                                newMessages.add(AnthropicClient.userMessage("<identity>You are '" + name + "', role: " + role + ", team: " + teamName + ". Continue your work.</identity>"));
                                newMessages.add(AnthropicClient.assistantMessage("I am " + name + ". Continuing."));
                                for (JsonElement el : messages) {
                                    newMessages.add(el);
                                }
                                messages = newMessages;
                            }

                            JsonObject response = client.createMessage(systemPrompt, messages, toolDefs, 8000);
                            JsonArray content = AnthropicClient.getContent(response);
                            String stopReason = AnthropicClient.getStopReason(response);
                            messages.add(AnthropicClient.assistantMessage(content));

                            String text = AnthropicClient.extractText(content);
                            if (!text.isEmpty()) {
                                System.out.println("[Teammate:" + name + "] " + text);
                            }

                            if ("end_turn".equals(stopReason) || "idle".equals(statuses.get(name))) {
                                // ===== 空闲轮询阶段 =====
                                // 触发条件: LLM 自然结束 (end_turn) 或 Teammate 调用了 idle 工具
                                // 轮询策略: 每 5 秒检查一次，最多 12 次 (总计 60 秒)
                                // 每次轮询做两件事:
                                //   1. 检查收件箱: 是否有 Lead 或其他 Teammate 发来的消息
                                //   2. 扫描任务: 是否有未认领的 pending 任务可以自动认领
                                System.out.println("[Teammate:" + name + "] Entering idle poll...");
                                boolean foundWork = false;
                                for (int i = 0; i < 12; i++) {
                                    Thread.sleep(5000);

                                    // 检查收件箱 / Check inbox
                                    String inbox = bus.readInbox(name);
                                    if (!"[]".equals(inbox)) {
                                        messages.add(AnthropicClient.userMessage(
                                                "New inbox messages:\n" + inbox));
                                        statuses.put(name, "working");
                                        foundWork = true;
                                        System.out.println("[Teammate:" + name + "] Got inbox messages, resuming.");
                                        break;
                                    }

                                    // 扫描未认领任务并自动认领 (自主行为的核心)
                                    // 自动认领策略: 取第一个未认领任务 (FIFO)
                                    // 认领后将任务信息注入对话上下文，让 LLM 开始工作
                                    String unclaimed = taskStore.scanUnclaimed();
                                    if (!"No unclaimed tasks.".equals(unclaimed)) {
                                        JsonArray unclaimedArr = JsonParser.parseString(unclaimed).getAsJsonArray();
                                        if (unclaimedArr.size() > 0) {
                                            JsonObject firstTask = unclaimedArr.get(0).getAsJsonObject();
                                            String taskId = firstTask.get("id").getAsString();
                                            String claimResult = taskStore.claimTask(taskId, name);
                                            System.out.println("[Teammate:" + name + "] Auto-claimed " + taskId + ": " + claimResult);
                                            String taskTitle = firstTask.has("title") ? firstTask.get("title").getAsString() : "";
                                            String taskDesc = firstTask.has("description") ? firstTask.get("description").getAsString() : "";
                                            messages.add(AnthropicClient.userMessage(
                                                    "Auto-claimed task " + taskId + ": " + taskTitle + "\nDescription: " + taskDesc
                                                            + "\nPlease begin working on this task."));
                                            statuses.put(name, "working");
                                            foundWork = true;
                                            break;
                                        }
                                    }
                                }
                                if (!foundWork) {
                                    // 60 秒内未找到任何工作 -> 自动关闭
                                    // 这避免了空闲 Teammate 无限期占用资源
                                    System.out.println("[Teammate:" + name + "] No work found after 60s idle. Shutting down.");
                                    statuses.put(name, "shutdown");
                                }
                                continue;
                            }

                            if (!"tool_use".equals(stopReason)) break;

                            // 工作阶段的收件箱检查 (S11 新增):
                            // 在处理工具调用之前，先检查收件箱是否收到 shutdown_request
                            // 这使得 Lead 可以在 Teammate 工作期间强制要求关闭
                            String workInbox = bus.readInbox(name);
                            if (!"[]".equals(workInbox)) {
                                JsonArray inboxMsgs = JsonParser.parseString(workInbox).getAsJsonArray();
                                boolean shutdownDetected = false;
                                for (JsonElement inboxEl : inboxMsgs) {
                                    JsonObject inboxMsg = inboxEl.getAsJsonObject();
                                    if (inboxMsg.has("type") && "shutdown_request".equals(inboxMsg.get("type").getAsString())) {
                                        System.out.println("[Teammate:" + name + "] Shutdown request received during work. Stopping.");
                                        statuses.put(name, "shutdown");
                                        shutdownDetected = true;
                                        break;
                                    }
                                }
                                if (shutdownDetected) break;
                            }

                            // 处理工具调用 / Process tool calls
                            JsonArray toolResults = new JsonArray();
                            for (JsonElement el : content) {
                                JsonObject block = el.getAsJsonObject();
                                if ("tool_use".equals(block.get("type").getAsString())) {
                                    String toolName = block.get("name").getAsString();
                                    String toolId = block.get("id").getAsString();
                                    JsonObject toolInput = block.getAsJsonObject("input");

                                    ToolHandler handler = tHandlers.get(toolName);
                                    String result = (handler != null)
                                            ? handler.execute(toolInput)
                                            : "Error: unknown tool '" + toolName + "'";
                                    System.out.println("[Teammate:" + name + "] " + toolName + " -> "
                                            + (result.length() > 100 ? result.substring(0, 100) + "..." : result));
                                    toolResults.add(AnthropicClient.toolResult(toolId, result));
                                }
                            }
                            messages.add(AnthropicClient.userMessage(toolResults));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        System.err.println("[Teammate:" + name + "] Error: " + e.getMessage());
                    }
                    statuses.put(name, "shutdown");
                    System.out.println("[Teammate:" + name + "] Stopped.");
                }
            }, "teammate-" + name);
            t.setDaemon(true);
            t.start();
            threads.put(name, t);
            return "Spawned autonomous teammate '" + name + "' with role: " + role;
        }

        String listTeammates() {
            if (configs.isEmpty()) return "No teammates.";
            JsonArray list = new JsonArray();
            for (Map.Entry<String, JsonObject> e : configs.entrySet()) {
                JsonObject info = e.getValue().deepCopy();
                info.addProperty("status", statuses.containsKey(e.getKey())
                        ? statuses.get(e.getKey()) : "not_started");
                list.add(info);
            }
            return GSON.toJson(list);
        }

        List<String> getNames() {
            return new ArrayList<String>(configs.keySet());
        }
    }

    // ========================= main (Lead Agent + REPL) =========================
    //
    // S11 的 Lead Agent 比 S10 多了 2 个任务管理工具:
    //   - create_task: 创建任务 (Teammate 可自动认领)
    //   - list_tasks: 查看所有任务
    //
    // Lead 工具总数: 14 = S10 的 12 + create_task + list_tasks
    //
    // 工作流程:
    //   1. Lead 通过 create_task 创建任务
    //   2. Lead 通过 spawn_teammate 创建自主 Teammate
    //   3. Teammate 在空闲轮询时自动发现并认领任务
    //   4. Lead 可通过 list_tasks 和 list_teammates 监控进度

    public static void main(String[] args) throws Exception {
        String workDir = System.getProperty("user.dir");
        System.out.println("=== S11 Autonomous Agents ===");
        System.out.println("Work dir: " + workDir);
        System.out.println("Key Insight: The agent finds work itself.\n");

        // 初始化核心组件
        final AnthropicClient client = new AnthropicClient();
        final MessageBus bus = new MessageBus(workDir);          // JSONL 消息总线
        final TaskStore taskStore = new TaskStore(workDir);      // 任务存储 (S11 新增)
        final TeammateManager manager = new TeammateManager(bus, client, workDir, taskStore);
        final BaseTools baseTools = new BaseTools(workDir);

        // Lead 工具处理器 (14 tools): 4 base + 5 team + 3 protocol + 2 task
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

        // 团队管理 / Team management
        handlers.put("spawn_teammate", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                return manager.spawn(input.get("name").getAsString(),
                        input.get("role").getAsString(), input.get("prompt").getAsString());
            }
        });
        handlers.put("list_teammates", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                return manager.listTeammates();
            }
        });
        handlers.put("send_message", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                return bus.send("lead", input.get("to").getAsString(),
                        input.get("content").getAsString(),
                        input.has("msg_type") ? input.get("msg_type").getAsString() : "message");
            }
        });
        handlers.put("read_inbox", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                return bus.readInbox("lead");
            }
        });
        handlers.put("broadcast", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                return bus.broadcast("lead", input.get("content").getAsString(), manager.getNames());
            }
        });

        // 关闭协议 / Shutdown protocol
        handlers.put("shutdown_request", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                String target = input.get("target").getAsString();
                String reqId = "shutdown-" + UUID.randomUUID().toString().substring(0, 8);
                JsonObject tracker = new JsonObject();
                tracker.addProperty("target", target);
                tracker.addProperty("status", "pending");
                shutdownRequests.put(reqId, tracker);
                bus.send("lead", target, "Shutdown requested. request_id=" + reqId,
                        "shutdown_request", reqId);
                return "Shutdown request sent: " + reqId;
            }
        });
        handlers.put("shutdown_status", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                String reqId = input.get("request_id").getAsString();
                JsonObject tracker = shutdownRequests.get(reqId);
                return tracker == null ? "Unknown request_id" : GSON.toJson(tracker);
            }
        });

        // 计划审批 / Plan approval
        handlers.put("plan_review", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                String reqId = input.get("request_id").getAsString();
                String decision = input.get("decision").getAsString();
                String feedback = input.has("feedback") ? input.get("feedback").getAsString() : "";
                JsonObject tracker = planRequests.get(reqId);
                if (tracker == null) return "Unknown request_id";
                tracker.addProperty("status", "resolved");
                tracker.addProperty("decision", decision);
                String from = tracker.get("from").getAsString();
                bus.send("lead", from, "Plan " + decision + ". " + feedback,
                        "plan_approval_response", reqId);
                return "Plan " + decision + " sent to " + from;
            }
        });

        // 任务管理 (S11 新增): Lead 创建任务，Teammate 自动认领
        handlers.put("create_task", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                return taskStore.createTask(input.get("title").getAsString(),
                        input.has("description") ? input.get("description").getAsString() : "");
            }
        });
        handlers.put("list_tasks", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                return taskStore.listTasks();
            }
        });

        // Lead 工具定义 (14 tools) / Lead tool definitions
        JsonArray leadToolDefs = BaseTools.allToolDefs();
        leadToolDefs.add(AnthropicClient.toolDef("spawn_teammate", "Spawn an autonomous teammate.",
                AnthropicClient.schema("name", "string", "true",
                        "role", "string", "true", "prompt", "string", "true")));
        leadToolDefs.add(AnthropicClient.toolDef("list_teammates", "List teammates.",
                AnthropicClient.schema()));
        leadToolDefs.add(AnthropicClient.toolDef("send_message", "Send message to teammate.",
                AnthropicClient.schema("to", "string", "true",
                        "content", "string", "true", "msg_type", "string", "false")));
        leadToolDefs.add(AnthropicClient.toolDef("read_inbox", "Read lead's inbox.",
                AnthropicClient.schema()));
        leadToolDefs.add(AnthropicClient.toolDef("broadcast", "Broadcast to all.",
                AnthropicClient.schema("content", "string", "true")));
        leadToolDefs.add(AnthropicClient.toolDef("shutdown_request", "Request teammate shutdown.",
                AnthropicClient.schema("target", "string", "true")));
        leadToolDefs.add(AnthropicClient.toolDef("shutdown_status", "Check shutdown request status.",
                AnthropicClient.schema("request_id", "string", "true")));
        leadToolDefs.add(AnthropicClient.toolDef("plan_review", "Approve/reject teammate plan.",
                AnthropicClient.schema("request_id", "string", "true",
                        "decision", "string", "true", "feedback", "string", "false")));
        leadToolDefs.add(AnthropicClient.toolDef("create_task", "Create a task for teammates.",
                AnthropicClient.schema("title", "string", "true", "description", "string", "false")));
        leadToolDefs.add(AnthropicClient.toolDef("list_tasks", "List all tasks.",
                AnthropicClient.schema()));

        String systemPrompt = "You are the Lead agent with autonomous teammates.\n"
                + "Teammates can find work on their own by scanning unclaimed tasks.\n"
                + "Use create_task to add tasks. Teammates will auto-claim them.\n"
                + "Use spawn_teammate to create helpers. They will idle-poll for work.\n"
                + "Protocols: shutdown_request/shutdown_status, plan_review.\n"
                + "Check your inbox regularly.";

        // ===== REPL (交互式命令行) =====
        // 特殊命令: /team(团队状态), /inbox(收件箱), /tasks(任务列表), /quit(退出)
        JsonArray messages = new JsonArray();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\nYou (/team, /inbox, /tasks, /quit): ");
            if (!scanner.hasNextLine()) break;
            String userInput = scanner.nextLine().trim();
            if (userInput.isEmpty()) continue;

            if ("/quit".equals(userInput)) break;
            if ("/team".equals(userInput)) { System.out.println(manager.listTeammates()); continue; }
            if ("/inbox".equals(userInput)) { System.out.println(bus.readInbox("lead")); continue; }
            if ("/tasks".equals(userInput)) { System.out.println(taskStore.listTasks()); continue; }

            messages.add(AnthropicClient.userMessage(userInput));

            while (true) {
                // 在 LLM 调用前检查收件箱 / Check inbox before LLM call
                String leadInbox = bus.readInbox("lead");
                if (!"[]".equals(leadInbox)) {
                    messages.add(AnthropicClient.userMessage("Inbox messages received:\n" + leadInbox));
                    messages.add(AnthropicClient.assistantMessage("I see new inbox messages. Let me process them."));
                }

                JsonObject response = client.createMessage(systemPrompt, messages, leadToolDefs, 8000);
                JsonArray content = AnthropicClient.getContent(response);
                String stopReason = AnthropicClient.getStopReason(response);
                messages.add(AnthropicClient.assistantMessage(content));

                String text = AnthropicClient.extractText(content);
                if (!text.isEmpty()) System.out.println("\nLead: " + text);

                if (!"tool_use".equals(stopReason)) break;

                JsonArray toolResults = new JsonArray();
                for (JsonElement el : content) {
                    JsonObject block = el.getAsJsonObject();
                    if ("tool_use".equals(block.get("type").getAsString())) {
                        String toolName = block.get("name").getAsString();
                        String toolId = block.get("id").getAsString();
                        JsonObject toolInput = block.getAsJsonObject("input");
                        ToolHandler handler = handlers.get(toolName);
                        String result = (handler != null) ? handler.execute(toolInput)
                                : "Error: unknown tool '" + toolName + "'";
                        System.out.println("[Lead Tool] " + toolName + " -> "
                                + (result.length() > 120 ? result.substring(0, 120) + "..." : result));
                        toolResults.add(AnthropicClient.toolResult(toolId, result));
                    }
                }
                messages.add(AnthropicClient.userMessage(toolResults));
            }
        }

        scanner.close();
        System.out.println("Goodbye from S11 Autonomous Agents!");
    }
}
