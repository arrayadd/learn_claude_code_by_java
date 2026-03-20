package learn.claude.code.s09_agent_teams;

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
 * S09 - Agent Teams: 多 Agent 团队协作
 *
 * 核心洞察 / Key Insight:
 *   "Teammates that can talk to each other."
 *   队友之间可以直接通信。
 *
 * 架构 / Architecture:
 *
 *   +-----------+     JSONL Inbox      +-----------+
 *   |   Lead    | ------------------> | Teammate A |
 *   |  (main    | <------------------ |  (thread)  |
 *   |   thread) |     JSONL Inbox      +-----------+
 *   |           | ------------------> +-----------+
 *   |           | <------------------ | Teammate B |
 *   +-----------+     JSONL Inbox      |  (thread)  |
 *                                      +-----------+
 *
 *   每个 teammate 都有独立的 JSONL 收件箱文件，消息通过文件系统传递。
 *   Each teammate has its own JSONL inbox file; messages flow via filesystem.
 *
 * 消息类型 / Message Types:
 *   - message: 普通消息 / normal message
 *   - broadcast: 广播 / broadcast to all
 *   - shutdown_request: 关闭请求 / request to shutdown
 *   - shutdown_response: 关闭响应 / response to shutdown request
 *   - plan_approval_response: 计划审批响应 / plan approval response
 *
 * 工具清单 / Tool Inventory:
 *   Lead  (9): bash, read_file, write_file, edit_file,
 *               spawn_teammate, list_teammates, send_message, read_inbox, broadcast
 *   Teammate (6): bash, read_file, write_file, edit_file,
 *                  send_message, read_inbox
 *
 * 运行 / Run:
 *   mvn exec:java -Dexec.mainClass="learn.claude.code.s09_agent_teams.S09AgentTeams"
 */
public class S09AgentTeams {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ========================= 有效消息类型 =========================
    // JSONL 消息总线支持的消息类型白名单。所有不在此集合中的类型都会被 MessageBus.send() 拒绝。
    // 消息类型说明:
    //   - message: 普通点对点消息，Lead 或 Teammate 之间直接通信
    //   - broadcast: 广播消息，发送给除发送者外的所有 Teammate
    //   - shutdown_request: 关闭请求，Lead 请求 Teammate 优雅关闭
    //   - shutdown_response: 关闭响应，Teammate 回复 Lead 是否同意关闭
    //   - plan_approval_response: 计划审批响应，用于 S10 的计划审批协议
    private static final Set<String> VALID_MSG_TYPES = new HashSet<String>(Arrays.asList(
            "message", "broadcast", "shutdown_request", "shutdown_response", "plan_approval_response"
    ));

    // ========================= MessageBus (JSONL 消息总线) =========================
    //
    // 核心设计思想:
    //   JSONL (JSON Lines) 是一种每行一个 JSON 对象的文件格式。每个 Agent (Lead 或 Teammate)
    //   拥有独立的 .jsonl 收件箱文件。发送消息 = 向目标的 .jsonl 文件追加一行；
    //   读取消息 = 读取并清空自己的 .jsonl 文件。
    //
    // 为什么用 JSONL 而非内存队列？
    //   1. 持久化: 进程崩溃后消息不丢失
    //   2. 可观察性: 可以用 cat/tail 直接查看消息流
    //   3. 简单性: 不需要引入消息中间件
    //   4. 跨进程: 未来可扩展为多进程通信
    //
    // 目录结构:
    //   .team/
    //   +-- inbox/
    //   |   +-- lead.jsonl       <- Lead 的收件箱 (Teammate -> Lead 的消息)
    //   |   +-- alice.jsonl      <- Teammate alice 的收件箱 (Lead/其他 Teammate -> alice)
    //   |   +-- bob.jsonl        <- Teammate bob 的收件箱 (Lead/其他 Teammate -> bob)
    //   +-- config.json          <- 团队配置: {"team_name":"default","members":[...]}
    //
    // 消息 JSON 格式:
    //   { "from": "lead", "to": "alice", "content": "...", "type": "message",
    //     "timestamp": "2024-01-01T12:00:00", ...extra_fields }

    static class MessageBus {
        /** 团队根目录 (.team/) */
        private final Path teamDir;
        /** 收件箱目录 (.team/inbox/) */
        private final Path inboxDir;

        /**
         * 构造消息总线，自动创建目录结构
         * @param workDir 工作目录 (项目根目录)
         */
        MessageBus(String workDir) {
            this.teamDir = Paths.get(workDir, ".team");
            this.inboxDir = teamDir.resolve("inbox");
            try {
                Files.createDirectories(inboxDir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create inbox dir: " + e.getMessage(), e);
            }
        }

        /**
         * 发送消息到指定队友的收件箱 (无附加字段的简化版本)
         *
         * @param sender  发送者名称 (如 "lead", "alice")
         * @param to      接收者名称
         * @param content 消息正文
         * @param msgType 消息类型 (必须在 VALID_MSG_TYPES 中)
         * @return 操作结果描述
         */
        synchronized String send(String sender, String to, String content, String msgType) {
            return send(sender, to, content, msgType, new HashMap<String, String>());
        }

        /**
         * 发送消息到指定队友的 JSONL 收件箱 (完整版本)
         *
         * 实现细节:
         *   1. 校验消息类型是否合法
         *   2. 构造 JSON 消息对象 (from, to, content, type, timestamp, ...extra)
         *   3. 将 JSON 压缩为单行 (JSONL 格式要求每条消息占一行)
         *   4. 追加写入目标的 .jsonl 文件
         *
         * 线程安全: synchronized 保证多个 Teammate 线程同时发消息时不会交错写入
         *
         * @param extra 附加字段 (如 request_id)
         */
        synchronized String send(String sender, String to, String content, String msgType, Map<String, String> extra) {
            if (!VALID_MSG_TYPES.contains(msgType)) {
                return "Error: invalid message type '" + msgType + "'. Valid: " + VALID_MSG_TYPES;
            }
            try {
                // 构造消息 JSON 对象
                JsonObject msg = new JsonObject();
                msg.addProperty("from", sender);
                msg.addProperty("to", to);
                msg.addProperty("content", content);
                msg.addProperty("type", msgType);
                msg.addProperty("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date()));
                // 合并附加字段 (如 request_id 等协议字段)
                if (extra != null) {
                    for (Map.Entry<String, String> e : extra.entrySet()) {
                        msg.addProperty(e.getKey(), e.getValue());
                    }
                }

                // JSONL 核心: 将 JSON 压缩为单行并追加到收件箱文件
                // replace("\n", " ") 确保不会跨行，每条消息严格一行
                Path inbox = inboxDir.resolve(to + ".jsonl");
                try (BufferedWriter w = Files.newBufferedWriter(inbox, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(GSON.toJson(msg).replace("\n", " "));
                    w.newLine();
                }
                return "Sent " + msgType + " from " + sender + " to " + to;
            } catch (IOException e) {
                return "Error sending message: " + e.getMessage();
            }
        }

        /**
         * 读取并清空指定队友的收件箱 (drain 语义)
         *
         * 实现"读取即消费"模式:
         *   1. 读取 .jsonl 文件的所有行
         *   2. 立即清空文件内容 (Files.write(inbox, new byte[0]))
         *   3. 将每行 JSON 解析后放入 JsonArray 返回
         *
         * 为什么是 drain 而非 peek？
         *   - 避免消息重复处理: Agent 的对话上下文已经包含了消息内容
         *   - 简化状态管理: 不需要维护"已读"标记
         *
         * @param name 队友名称
         * @return JSON 数组字符串，无消息时返回 "[]"
         */
        synchronized String readInbox(String name) {
            Path inbox = inboxDir.resolve(name + ".jsonl");
            if (!Files.exists(inbox)) {
                return "[]";
            }
            try {
                List<String> lines = Files.readAllLines(inbox, StandardCharsets.UTF_8);
                if (lines.isEmpty()) {
                    return "[]";
                }
                // drain: 清空收件箱，确保消息不会被重复消费
                Files.write(inbox, new byte[0]);
                // 逐行解析 JSONL，每行是一个独立的 JSON 对象
                JsonArray messages = new JsonArray();
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        messages.add(JsonParser.parseString(trimmed));
                    }
                }
                return messages.size() == 0 ? "[]" : GSON.toJson(messages);
            } catch (IOException e) {
                return "Error reading inbox: " + e.getMessage();
            }
        }

        /**
         * 广播消息给所有队友 (排除发送者自己)
         *
         * 广播的代价: 每次广播会向每个 Teammate 的收件箱各写一条消息，
         * 代价与团队规模线性增长，应谨慎使用。
         *
         * @param sender    发送者 (会被排除)
         * @param content   广播内容
         * @param teammates 所有队友名称列表
         * @return 每条发送结果的汇总
         */
        String broadcast(String sender, String content, List<String> teammates) {
            StringBuilder sb = new StringBuilder();
            for (String name : teammates) {
                if (!name.equals(sender)) {
                    sb.append(send(sender, name, content, "broadcast")).append("\n");
                }
            }
            return sb.toString().trim();
        }
    }

    // ========================= TeammateManager (队友管理器) =========================
    //
    // 职责:
    //   1. 管理 Teammate 的生命周期 (创建、运行、关闭)
    //   2. 维护团队配置 (持久化到 config.json)
    //   3. 跟踪每个 Teammate 的运行状态 (working / idle / shutdown)
    //
    // 每个 Teammate 在独立的 Java Thread 中运行自己的 Agent Loop:
    //   用户消息 -> LLM 推理 -> 工具调用 -> 结果反馈 -> 循环
    //
    // 线程模型:
    //   - Lead Agent: 在 main 线程中运行
    //   - Teammate A: 在 daemon 线程 "teammate-A" 中运行
    //   - Teammate B: 在 daemon 线程 "teammate-B" 中运行
    //   - 各线程通过 MessageBus (JSONL 文件) 异步通信

    static class TeammateManager {
        /** 队友名称 -> 运行线程 */
        private final Map<String, Thread> threads = new ConcurrentHashMap<String, Thread>();
        /** 队友名称 -> 当前状态 (working / idle / shutdown / not_started) */
        private final Map<String, String> statuses = new ConcurrentHashMap<String, String>();
        /** 队友名称 -> 配置信息 {name, role, prompt} */
        private final Map<String, JsonObject> configs = new ConcurrentHashMap<String, JsonObject>();
        private final MessageBus bus;
        private final AnthropicClient client;
        private final String workDir;
        /** 团队配置文件路径: .team/config.json */
        private final Path configPath;

        TeammateManager(MessageBus bus, AnthropicClient client, String workDir) {
            this.bus = bus;
            this.client = client;
            this.workDir = workDir;
            this.configPath = Paths.get(workDir, ".team", "config.json");
            // 从磁盘恢复之前的团队配置 (支持断点续跑)
            loadConfig();
        }

        /**
         * 加载持久化的团队配置
         * 格式: {"team_name":"default","members":[{name,role,prompt}, ...]}
         * 从 members 数组恢复 configs Map，以便重启后能识别已有的 Teammate
         */
        private void loadConfig() {
            if (Files.exists(configPath)) {
                try {
                    String json = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
                    JsonObject cfg = JsonParser.parseString(json).getAsJsonObject();
                    if (cfg.has("members")) {
                        JsonArray members = cfg.getAsJsonArray("members");
                        for (JsonElement el : members) {
                            JsonObject member = el.getAsJsonObject();
                            configs.put(member.get("name").getAsString(), member);
                        }
                    }
                } catch (IOException e) {
                    System.err.println("[TeammateManager] Failed to load config: " + e.getMessage());
                }
            }
        }

        /**
         * 持久化团队配置到 .team/config.json
         * 格式与 S09/S10/S11 保持一致: {"team_name":"default","members":[...]}
         */
        private void saveConfig() {
            try {
                JsonObject cfg = new JsonObject();
                cfg.addProperty("team_name", "default");
                JsonArray members = new JsonArray();
                for (Map.Entry<String, JsonObject> e : configs.entrySet()) {
                    members.add(e.getValue());
                }
                cfg.add("members", members);
                Files.createDirectories(configPath.getParent());
                Files.write(configPath, GSON.toJson(cfg).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                System.err.println("[TeammateManager] Failed to save config: " + e.getMessage());
            }
        }

        /**
         * 创建并启动一个 Teammate Agent 线程
         *
         * 流程:
         *   1. 检查是否已存在同名运行中的 Teammate (防止重复创建)
         *   2. 持久化配置到 config.json
         *   3. 构建 Teammate 专属的工具集 (6 tools): 4 base + send_message + read_inbox
         *   4. 在新的 daemon 线程中启动 Agent Loop
         *
         * Teammate Agent Loop 的核心循环:
         *   while (未中断 && 状态为 working) {
         *     1. 调用 LLM 获取响应
         *     2. 如果 end_turn: 等待 3 秒后检查收件箱，有消息则继续，无消息则标记 idle
         *     3. 如果 tool_use: 执行工具调用，将结果反馈给 LLM
         *     4. 其他 stopReason: 退出循环
         *   }
         *
         * @param name   Teammate 名称 (也是收件箱文件名)
         * @param role   角色描述 (如 "code reviewer", "test writer")
         * @param prompt 自定义提示词 (补充角色行为指导)
         * @return 创建结果描述
         */
        String spawn(String name, String role, String prompt) {
            if (threads.containsKey(name) && threads.get(name).isAlive()) {
                return "Teammate '" + name + "' is already running.";
            }

            // 持久化 Teammate 配置，确保团队信息不丢失
            JsonObject cfg = new JsonObject();
            cfg.addProperty("name", name);
            cfg.addProperty("role", role);
            cfg.addProperty("prompt", prompt);
            configs.put(name, cfg);
            saveConfig();

            statuses.put(name, "working");

            // Teammate 的工具集 (6 tools): 4 base + send_message + read_inbox
            // Lead 比 Teammate 多 3 个管理工具: spawn_teammate, list_teammates, broadcast
            final BaseTools tools = new BaseTools(workDir);
            final Map<String, ToolHandler> handlers = new LinkedHashMap<String, ToolHandler>();
            handlers.put("bash", new ToolHandler() {
                @Override public String execute(JsonObject input) {
                    return tools.runBash(input.get("command").getAsString());
                }
            });
            handlers.put("read_file", new ToolHandler() {
                @Override public String execute(JsonObject input) {
                    int limit = input.has("limit") ? input.get("limit").getAsInt() : 0;
                    return tools.runRead(input.get("path").getAsString(), limit);
                }
            });
            handlers.put("write_file", new ToolHandler() {
                @Override public String execute(JsonObject input) {
                    return tools.runWrite(input.get("path").getAsString(), input.get("content").getAsString());
                }
            });
            handlers.put("edit_file", new ToolHandler() {
                @Override public String execute(JsonObject input) {
                    return tools.runEdit(input.get("path").getAsString(),
                            input.get("old_text").getAsString(), input.get("new_text").getAsString());
                }
            });
            handlers.put("send_message", new ToolHandler() {
                @Override public String execute(JsonObject input) {
                    return bus.send(name, input.get("to").getAsString(),
                            input.get("content").getAsString(),
                            input.has("msg_type") ? input.get("msg_type").getAsString() : "message");
                }
            });
            handlers.put("read_inbox", new ToolHandler() {
                @Override public String execute(JsonObject input) {
                    return bus.readInbox(name);
                }
            });

            // 构建工具定义 / Build tool definitions
            final JsonArray toolDefs = BaseTools.allToolDefs();
            toolDefs.add(AnthropicClient.toolDef("send_message", "Send a message to another teammate.",
                    AnthropicClient.schema("to", "string", "true",
                            "content", "string", "true",
                            "msg_type", "string", "false")));
            toolDefs.add(AnthropicClient.toolDef("read_inbox", "Read your inbox messages.",
                    AnthropicClient.schema()));

            // 系统提示: 定义 Teammate 的身份、能力和行为规范
            // 包含: 名称、角色、自定义提示词、可用工具说明、关闭请求处理指南
            final String systemPrompt = "You are '" + name + "', a team member with role: " + role + ".\n"
                    + prompt + "\n\n"
                    + "You can communicate with the lead and other teammates via send_message and read_inbox.\n"
                    + "When you receive a shutdown_request, respond with a shutdown_response if ready.\n"
                    + "Check your inbox periodically.";

            // 启动 Teammate 的 Agent Loop 线程
            // 使用 daemon 线程: 当 main 线程 (Lead) 退出时，Teammate 线程会自动终止
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    System.out.println("[Teammate:" + name + "] Started with role: " + role);
                    JsonArray messages = new JsonArray();
                    messages.add(AnthropicClient.userMessage(
                            "You have been spawned as teammate '" + name + "'. Start working on your role: " + role));

                    try {
                        // ===== Teammate Agent Loop =====
                        // 循环条件: 线程未中断 且 状态为 working
                        // 退出条件: 状态变为 shutdown、idle、或 LLM 返回非 tool_use 的 stopReason
                        while (!Thread.currentThread().isInterrupted()
                                && "working".equals(statuses.get(name))) {
                            // 调用 Anthropic API 进行推理
                            JsonObject response = client.createMessage(systemPrompt, messages, toolDefs, 8000);
                            JsonArray content = AnthropicClient.getContent(response);
                            String stopReason = AnthropicClient.getStopReason(response);
                            // 将 LLM 的回复加入对话历史
                            messages.add(AnthropicClient.assistantMessage(content));

                            // 打印 LLM 生成的文本内容到控制台 (用于调试/观察)
                            String text = AnthropicClient.extractText(content);
                            if (!text.isEmpty()) {
                                System.out.println("[Teammate:" + name + "] " + text);
                            }

                            if ("end_turn".equals(stopReason)) {
                                // LLM 认为当前轮次结束，没有工具调用
                                // 等待 3 秒后检查收件箱，看是否有新消息需要处理
                                Thread.sleep(3000);
                                String inbox = bus.readInbox(name);
                                if (!"[]".equals(inbox)) {
                                    // 有新消息: 注入对话上下文，继续 Agent Loop
                                    messages.add(AnthropicClient.userMessage(
                                            "New inbox messages:\n" + inbox));
                                } else {
                                    // 无新消息: 标记为空闲状态
                                    statuses.put(name, "idle");
                                }
                                continue;
                            }

                            if (!"tool_use".equals(stopReason)) {
                                // 非预期的 stopReason (如 max_tokens)，退出循环
                                break;
                            }

                            // LLM 请求调用工具: 遍历 content 中的 tool_use 块，逐个执行
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
                                    System.out.println("[Teammate:" + name + "] Tool " + toolName + " -> "
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

            return "Spawned teammate '" + name + "' with role: " + role;
        }

        /**
         * 列出所有队友及状态
         * 返回格式: {"team_name":"default","members":[{name,role,status}, ...]}
         * status 取值: working(工作中) / idle(空闲) / shutdown(已关闭) / not_started(未启动)
         */
        String listTeammates() {
            if (configs.isEmpty()) {
                return "No teammates.";
            }
            JsonObject result = new JsonObject();
            result.addProperty("team_name", "default");
            JsonArray members = new JsonArray();
            for (Map.Entry<String, JsonObject> e : configs.entrySet()) {
                JsonObject info = new JsonObject();
                info.addProperty("name", e.getKey());
                if (e.getValue().has("role")) {
                    info.addProperty("role", e.getValue().get("role").getAsString());
                }
                info.addProperty("status", statuses.containsKey(e.getKey())
                        ? statuses.get(e.getKey()) : "not_started");
                members.add(info);
            }
            result.add("members", members);
            return GSON.toJson(result);
        }

        /** 获取所有队友名 / Get all teammate names */
        List<String> getNames() {
            return new ArrayList<String>(configs.keySet());
        }
    }

    // ========================= main (Lead Agent + REPL) =========================
    //
    // 程序入口，启动 Lead Agent 的交互循环:
    //   1. 初始化基础设施: AnthropicClient, MessageBus, TeammateManager
    //   2. 注册 Lead 的 9 个工具 (4 base + 5 team)
    //   3. 启动 REPL 循环: 读取用户输入 -> Lead Agent Loop -> 输出结果
    //
    // Lead 与 Teammate 的工具集差异:
    //   Lead (9 tools): bash, read_file, write_file, edit_file,
    //                    spawn_teammate, list_teammates, send_message, read_inbox, broadcast
    //   Teammate (6 tools): bash, read_file, write_file, edit_file,
    //                        send_message, read_inbox
    //   Lead 独有: spawn_teammate(创建队友), list_teammates(查看团队), broadcast(广播)

    public static void main(String[] args) throws Exception {
        String workDir = System.getProperty("user.dir");
        System.out.println("=== S09 Agent Teams ===");
        System.out.println("Work dir: " + workDir);
        System.out.println("Key Insight: Teammates that can talk to each other.\n");

        // 初始化核心组件
        final AnthropicClient client = new AnthropicClient();
        final MessageBus bus = new MessageBus(workDir);          // JSONL 消息总线
        final TeammateManager manager = new TeammateManager(bus, client, workDir); // 队友管理器
        final BaseTools baseTools = new BaseTools(workDir);      // 基础工具 (bash/read/write/edit)

        // Lead 的工具处理器 (9 tools): 每个工具名映射到一个 ToolHandler 实现
        final Map<String, ToolHandler> handlers = new LinkedHashMap<String, ToolHandler>();
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
                List<String> names = manager.getNames();
                return bus.broadcast("lead", input.get("content").getAsString(), names);
            }
        });

        // Lead 的工具定义 (9 tools) / Lead tool definitions
        JsonArray leadToolDefs = BaseTools.allToolDefs();
        leadToolDefs.add(AnthropicClient.toolDef("spawn_teammate",
                "Create a new teammate that runs in its own thread.",
                AnthropicClient.schema("name", "string", "true",
                        "role", "string", "true",
                        "prompt", "string", "true")));
        leadToolDefs.add(AnthropicClient.toolDef("list_teammates",
                "List all teammates and their status.",
                AnthropicClient.schema()));
        leadToolDefs.add(AnthropicClient.toolDef("send_message",
                "Send a message to a specific teammate.",
                AnthropicClient.schema("to", "string", "true",
                        "content", "string", "true",
                        "msg_type", "string", "false")));
        leadToolDefs.add(AnthropicClient.toolDef("read_inbox",
                "Read messages in lead's inbox.",
                AnthropicClient.schema()));
        leadToolDefs.add(AnthropicClient.toolDef("broadcast",
                "Send a message to all teammates.",
                AnthropicClient.schema("content", "string", "true")));

        String systemPrompt = "You are the Lead agent coordinating a team of AI teammates.\n"
                + "You can spawn teammates, send them messages, and read your inbox.\n"
                + "Each teammate runs in its own thread with its own agent loop.\n"
                + "Use spawn_teammate to create helpers, then coordinate via messaging.\n"
                + "Check your inbox regularly for responses from teammates.";

        // ===== REPL (Read-Eval-Print Loop) =====
        // 交互式命令行循环:
        //   - 用户输入自然语言指令 -> Lead Agent 处理 (可能创建 Teammate、发消息等)
        //   - 特殊命令: /team(查看团队), /inbox(查看收件箱), /quit(退出)
        //   - Lead Agent Loop 会在每次 LLM 调用前检查收件箱，确保及时收到 Teammate 的消息
        JsonArray messages = new JsonArray();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\nYou (/team, /inbox, /quit): ");
            if (!scanner.hasNextLine()) break;
            String userInput = scanner.nextLine().trim();
            if (userInput.isEmpty()) continue;

            // 特殊 REPL 命令: 直接在 main 线程中处理，不经过 LLM
            if ("/quit".equals(userInput)) {
                System.out.println("Shutting down teammates...");
                for (String name : manager.getNames()) {
                    bus.send("lead", name, "Please shut down.", "shutdown_request");
                }
                break;
            }
            if ("/team".equals(userInput)) {
                System.out.println(manager.listTeammates());
                continue;
            }
            if ("/inbox".equals(userInput)) {
                System.out.println(bus.readInbox("lead"));
                continue;
            }

            messages.add(AnthropicClient.userMessage(userInput));

            // ===== Lead Agent Loop =====
            // Lead 的内层循环: 持续处理 LLM 响应直到 LLM 不再请求工具调用
            while (true) {
                // 重要: 在每次 LLM 调用前检查 Lead 的收件箱
                // 这确保 Teammate 发来的消息能被 Lead 及时看到并纳入决策
                // 收件箱内容包装在 <inbox> 标签中，方便 LLM 识别
                String inbox = bus.readInbox("lead");
                if (inbox != null && !inbox.equals("[]") && !inbox.startsWith("No messages")) {
                    messages.add(AnthropicClient.userMessage("<inbox>" + inbox + "</inbox>"));
                    messages.add(AnthropicClient.assistantMessage("Noted inbox messages."));
                }
                JsonObject response = client.createMessage(systemPrompt, messages, leadToolDefs, 8000);
                JsonArray content = AnthropicClient.getContent(response);
                String stopReason = AnthropicClient.getStopReason(response);
                messages.add(AnthropicClient.assistantMessage(content));

                String text = AnthropicClient.extractText(content);
                if (!text.isEmpty()) {
                    System.out.println("\nLead: " + text);
                }

                if (!"tool_use".equals(stopReason)) break;

                // 处理工具调用 / Handle tool calls
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
                        System.out.println("[Lead Tool] " + toolName + " -> "
                                + (result.length() > 120 ? result.substring(0, 120) + "..." : result));
                        toolResults.add(AnthropicClient.toolResult(toolId, result));
                    }
                }
                messages.add(AnthropicClient.userMessage(toolResults));
            }
        }

        scanner.close();
        System.out.println("Goodbye from S09 Agent Teams!");
    }
}
