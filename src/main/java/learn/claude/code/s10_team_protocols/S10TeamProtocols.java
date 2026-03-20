package learn.claude.code.s10_team_protocols;

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
 * S10 - Team Protocols: 关闭协议 + 计划审批协议
 *
 * 核心洞察 / Key Insight:
 *   "Same request_id correlation pattern, two domains."
 *   同一个 request_id 关联模式，用于两个不同领域。
 *
 * 协议状态机 / Protocol FSMs:
 *
 *   Shutdown Protocol:
 *   ┌──────┐  shutdown_request   ┌──────────┐  shutdown_response   ┌──────────┐
 *   │ Lead │ ─────────────────> │ Teammate │ ─────────────────── > │ Lead     │
 *   │      │  (request_id)      │          │  (approve/reject)     │ (check)  │
 *   └──────┘                    └──────────┘                       └──────────┘
 *
 *   Plan Approval Protocol:
 *   ┌──────────┐  plan (submit)     ┌──────┐  plan_approval_response  ┌──────────┐
 *   │ Teammate │ ────────────────> │ Lead │ ───────────────────────> │ Teammate │
 *   │          │  (request_id)      │      │  (approve/reject)        │ (check)  │
 *   └──────────┘                    └──────┘                          └──────────┘
 *
 * request_id 关联模式 / request_id Correlation Pattern:
 *   1. 发起方生成唯一 request_id / Initiator generates unique request_id
 *   2. 通过消息发送给对方 / Sends message to counterpart
 *   3. 对方处理后用同一 request_id 回复 / Counterpart replies with same request_id
 *   4. 发起方通过 request_id 查询结果 / Initiator checks result by request_id
 *
 * 工具清单 / Tool Inventory:
 *   Lead (12): bash, read_file, write_file, edit_file,
 *              spawn_teammate, list_teammates, send_message, read_inbox, broadcast,
 *              shutdown_request, shutdown_status, plan_review
 *   Teammate (8): bash, read_file, write_file, edit_file,
 *                  send_message, read_inbox, shutdown_response, plan_submit
 *
 * 运行 / Run:
 *   mvn exec:java -Dexec.mainClass="learn.claude.code.s10_team_protocols.S10TeamProtocols"
 */
public class S10TeamProtocols {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 消息类型白名单 (与 S09 一致) */
    private static final Set<String> VALID_MSG_TYPES = new HashSet<String>(Arrays.asList(
            "message", "broadcast", "shutdown_request", "shutdown_response", "plan_approval_response"
    ));

    // ========================= Protocol Trackers (协议追踪器) =========================
    //
    // request_id 关联模式详解:
    //   协议双方通过一个唯一的 request_id 建立"请求-响应"关联。
    //   这类似于 HTTP 的 Request-Response 模式，但是异步的、基于消息的。
    //
    //   流程:
    //     1. 发起方生成唯一 request_id (如 "shutdown-a1b2c3d4")
    //     2. 在追踪器 Map 中创建条目: request_id -> {status:"pending", ...}
    //     3. 通过 MessageBus 发送请求消息 (携带 request_id)
    //     4. 对方收到消息，处理后通过 MessageBus 回复 (携带同一 request_id)
    //     5. 对方同时更新追踪器中的条目: status -> "resolved"
    //     6. 发起方可通过 request_id 查询追踪器获取结果
    //
    //   为什么需要追踪器而不只靠消息？
    //     - Lead 可以主动查询状态 (不必等消息到达)
    //     - 支持超时检测和重试
    //     - 提供全局可见性

    /**
     * 关闭协议追踪器: request_id -> {target, status, decision, reason}
     * status 取值: "pending"(等待响应) / "resolved"(已收到响应)
     * decision 取值: "approve"(同意关闭) / "reject"(拒绝关闭)
     */
    private static final ConcurrentHashMap<String, JsonObject> shutdownRequests =
            new ConcurrentHashMap<String, JsonObject>();

    /**
     * 计划审批追踪器: request_id -> {from, plan, status, decision, feedback}
     * status 取值: "pending"(等待审批) / "resolved"(已审批)
     * decision 取值: "approve"(批准) / "reject"(驳回)
     */
    private static final ConcurrentHashMap<String, JsonObject> planRequests =
            new ConcurrentHashMap<String, JsonObject>();

    // ========================= MessageBus (JSONL 消息总线) =========================
    //
    // 与 S09 相同的 JSONL 文件收件箱机制。
    // S10 新增: 支持 request_id 参数，用于协议消息的请求-响应关联。
    // send() 方法有 3 个重载版本:
    //   1. send(sender, to, content, msgType)              - 无附加字段
    //   2. send(sender, to, content, msgType, requestId)   - 附加 request_id
    //   3. send(sender, to, content, msgType, extra)       - 附加任意字段

    static class MessageBus {
        private final Path inboxDir;

        MessageBus(String workDir) {
            this.inboxDir = Paths.get(workDir, ".team", "inbox");
            try {
                Files.createDirectories(inboxDir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create inbox dir", e);
            }
        }

        /** 简化版发送: 无附加字段 */
        synchronized String send(String sender, String to, String content, String msgType) {
            return send(sender, to, content, msgType, (String) null);
        }

        /** 协议版发送: 附加 request_id，用于关闭协议和计划审批协议 */
        synchronized String send(String sender, String to, String content, String msgType, String requestId) {
            Map<String, String> extra = new HashMap<String, String>();
            if (requestId != null) {
                extra.put("request_id", requestId);
            }
            return send(sender, to, content, msgType, extra);
        }

        /**
         * 完整版发送: 支持任意附加字段
         * 与 S09 的主要区别: 返回值中包含 request_id 信息，方便 Agent 追踪协议状态
         */
        synchronized String send(String sender, String to, String content, String msgType, Map<String, String> extra) {
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
                if (extra != null) {
                    for (Map.Entry<String, String> e : extra.entrySet()) {
                        msg.addProperty(e.getKey(), e.getValue());
                    }
                }

                Path inbox = inboxDir.resolve(to + ".jsonl");
                try (BufferedWriter w = Files.newBufferedWriter(inbox, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(GSON.toJson(msg).replace("\n", " "));
                    w.newLine();
                }
                String requestId = (extra != null) ? extra.get("request_id") : null;
                return "Sent " + msgType + " from " + sender + " to " + to
                        + (requestId != null ? " [request_id=" + requestId + "]" : "");
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
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        messages.add(JsonParser.parseString(trimmed));
                    }
                }
                return messages.size() == 0 ? "[]" : GSON.toJson(messages);
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }

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
    // 与 S09 的队友管理器相比，S10 的主要变化:
    //   - Teammate 工具集从 6 个增加到 8 个 (新增 shutdown_response + plan_submit)
    //   - Teammate 能参与协议: 收到 shutdown_request 后用 shutdown_response 回复
    //   - Teammate 能提交计划: 用 plan_submit 提交工作计划，等待 Lead 审批

    static class TeammateManager {
        private final Map<String, Thread> threads = new ConcurrentHashMap<String, Thread>();
        private final Map<String, String> statuses = new ConcurrentHashMap<String, String>();
        private final Map<String, JsonObject> configs = new ConcurrentHashMap<String, JsonObject>();
        private final MessageBus bus;
        private final AnthropicClient client;
        private final String workDir;
        private final Path configPath;

        TeammateManager(MessageBus bus, AnthropicClient client, String workDir) {
            this.bus = bus;
            this.client = client;
            this.workDir = workDir;
            this.configPath = Paths.get(workDir, ".team", "config.json");
            loadConfig();
        }

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
                } catch (IOException e) { /* ignore */ }
            }
        }

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
            } catch (IOException e) { /* ignore */ }
        }

        /**
         * 创建带协议工具的 Teammate
         *
         * Teammate 工具集 (8 tools):
         *   - 4 base: bash, read_file, write_file, edit_file (基础操作)
         *   - 2 communication: send_message, read_inbox (消息通信)
         *   - 1 shutdown protocol: shutdown_response (关闭协议响应)
         *   - 1 plan protocol: plan_submit (计划提交)
         *
         * 关闭协议 (Teammate 端):
         *   1. Lead 发送 shutdown_request (携带 request_id)
         *   2. Teammate 收到后调用 shutdown_response 工具
         *   3. shutdown_response 内部: 更新追踪器 + 发送响应消息 + (如同意)设置 shutdown 状态
         *
         * 计划审批协议 (Teammate 端):
         *   1. Teammate 调用 plan_submit 提交计划
         *   2. plan_submit 内部: 生成 request_id + 创建追踪器 + 发送计划消息给 Lead
         *   3. Lead 审批后回复 plan_approval_response
         *   4. Teammate 通过 read_inbox 收到审批结果
         */
        String spawn(final String name, String role, String prompt) {
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
            final Map<String, ToolHandler> handlers = new LinkedHashMap<String, ToolHandler>();

            // 4 base tools
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

            // 通信工具 / Communication tools
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

            // 关闭协议 (Teammate 端): 收到 shutdown_request 后用此工具回复
            // 参数: request_id(关联请求), approve(是否同意,boolean), reason(原因,可选)
            // 内部流程:
            //   1. 通过 request_id 找到追踪器
            //   2. 更新追踪器: status=resolved, decision=approve/reject
            //   3. 通过 MessageBus 发送 shutdown_response 消息给 Lead
            //   4. 如果 approve=true，设置 Teammate 状态为 shutdown (终止 Agent Loop)
            handlers.put("shutdown_response", new ToolHandler() {
                @Override public String execute(JsonObject input) {
                    String reqId = input.get("request_id").getAsString();
                    boolean approve = input.get("approve").getAsBoolean();
                    String reason = input.has("reason") ? input.get("reason").getAsString() : "";
                    // 将布尔值转为 "approve"/"reject" 字符串，用于追踪器和消息
                    String decision = approve ? "approve" : "reject";

                    JsonObject tracker = shutdownRequests.get(reqId);
                    if (tracker == null) {
                        return "Error: unknown request_id '" + reqId + "'";
                    }

                    tracker.addProperty("status", "resolved");
                    tracker.addProperty("decision", decision);
                    tracker.addProperty("reason", reason);

                    // 通知 lead / Notify lead
                    bus.send(name, "lead",
                            "Shutdown " + decision + " for request " + reqId + ". " + reason,
                            "shutdown_response", reqId);

                    if (approve) {
                        statuses.put(name, "shutdown");
                    }
                    return "Shutdown " + decision + " sent to lead.";
                }
            });

            // 计划审批协议 (Teammate 端): Teammate 提交工作计划，等待 Lead 审批
            // 内部流程:
            //   1. 生成唯一 request_id (前缀 "plan-")
            //   2. 创建追踪器: {from, plan, status:"pending"}
            //   3. 发送计划消息给 Lead (携带 request_id)
            //   4. Teammate 后续通过 read_inbox 获取审批结果
            handlers.put("plan_submit", new ToolHandler() {
                @Override public String execute(JsonObject input) {
                    String plan = input.get("plan").getAsString();
                    String reqId = "plan-" + UUID.randomUUID().toString().substring(0, 8);

                    JsonObject tracker = new JsonObject();
                    tracker.addProperty("from", name);
                    tracker.addProperty("plan", plan);
                    tracker.addProperty("status", "pending");
                    planRequests.put(reqId, tracker);

                    bus.send(name, "lead",
                            "Plan for approval [request_id=" + reqId + "]:\n" + plan,
                            "message", reqId);
                    return "Plan submitted with request_id=" + reqId + ". Wait for lead's response.";
                }
            });

            // 构建工具定义 (8 tools) / Build tool definitions
            final JsonArray toolDefs = BaseTools.allToolDefs();
            toolDefs.add(AnthropicClient.toolDef("send_message", "Send a message to another teammate.",
                    AnthropicClient.schema("to", "string", "true",
                            "content", "string", "true",
                            "msg_type", "string", "false")));
            toolDefs.add(AnthropicClient.toolDef("read_inbox", "Read your inbox messages.",
                    AnthropicClient.schema()));
            toolDefs.add(AnthropicClient.toolDef("shutdown_response",
                    "Respond to a shutdown request with approve or reject.",
                    AnthropicClient.schema("request_id", "string", "true",
                            "approve", "boolean", "true",
                            "reason", "string", "false")));
            toolDefs.add(AnthropicClient.toolDef("plan_submit",
                    "Submit a plan for lead approval before executing it.",
                    AnthropicClient.schema("plan", "string", "true")));

            final String systemPrompt = "You are '" + name + "', a team member with role: " + role + ".\n"
                    + prompt + "\n\n"
                    + "Protocols:\n"
                    + "1. SHUTDOWN: When you receive a shutdown_request, use shutdown_response to approve/reject.\n"
                    + "2. PLAN APPROVAL: Before major work, use plan_submit to get lead approval.\n"
                    + "Check your inbox periodically for messages and protocol responses.";

            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    System.out.println("[Teammate:" + name + "] Started.");
                    JsonArray messages = new JsonArray();
                    messages.add(AnthropicClient.userMessage(
                            "You have been spawned as '" + name + "'. Begin your work."));
                    try {
                        while (!Thread.currentThread().isInterrupted()
                                && !"shutdown".equals(statuses.get(name))) {
                            JsonObject response = client.createMessage(systemPrompt, messages, toolDefs, 8000);
                            JsonArray content = AnthropicClient.getContent(response);
                            String stopReason = AnthropicClient.getStopReason(response);
                            messages.add(AnthropicClient.assistantMessage(content));

                            String text = AnthropicClient.extractText(content);
                            if (!text.isEmpty()) {
                                System.out.println("[Teammate:" + name + "] " + text);
                            }

                            if ("end_turn".equals(stopReason)) {
                                Thread.sleep(3000);
                                String inbox = bus.readInbox(name);
                                if (!"[]".equals(inbox)) {
                                    messages.add(AnthropicClient.userMessage("New inbox messages:\n" + inbox));
                                } else {
                                    statuses.put(name, "idle");
                                }
                                continue;
                            }
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
            return "Spawned teammate '" + name + "' with role: " + role;
        }

        String listTeammates() {
            if (configs.isEmpty()) return "No teammates.";
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

        List<String> getNames() {
            return new ArrayList<String>(configs.keySet());
        }
    }

    // ========================= main (Lead Agent + REPL) =========================
    //
    // S10 的 Lead Agent 比 S09 多了 3 个协议工具:
    //   - shutdown_request: 发起关闭请求 (生成 request_id，创建追踪器)
    //   - shutdown_status: 查询关闭请求状态 (通过 request_id 查追踪器)
    //   - plan_review: 审批 Teammate 提交的计划 (approve/reject)
    //
    // 工具总数: Lead 12 = S09 的 9 + shutdown_request + shutdown_status + plan_review

    public static void main(String[] args) throws Exception {
        String workDir = System.getProperty("user.dir");
        System.out.println("=== S10 Team Protocols ===");
        System.out.println("Work dir: " + workDir);
        System.out.println("Key Insight: Same request_id correlation pattern, two domains.\n");

        final AnthropicClient client = new AnthropicClient();
        final MessageBus bus = new MessageBus(workDir);
        final TeammateManager manager = new TeammateManager(bus, client, workDir);
        final BaseTools baseTools = new BaseTools(workDir);

        // Lead 工具处理器 (12 tools): 4 base + 5 team + 3 protocol
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

        // 团队管理工具 / Team management tools
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

        // 关闭协议 (Lead 端): 发起关闭请求
        // request_id 关联流程:
        //   1. 生成唯一 request_id (前缀 "shutdown-")
        //   2. 创建追踪器: {target, status:"pending"}
        //   3. 发送 shutdown_request 消息给目标 Teammate (携带 request_id)
        //   4. Lead 可通过 shutdown_status 查询追踪器获取结果
        handlers.put("shutdown_request", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                String target = input.get("target").getAsString();
                String reqId = "shutdown-" + UUID.randomUUID().toString().substring(0, 8);

                JsonObject tracker = new JsonObject();
                tracker.addProperty("target", target);
                tracker.addProperty("status", "pending");
                shutdownRequests.put(reqId, tracker);

                bus.send("lead", target,
                        "Shutdown requested. Please respond with shutdown_response. request_id=" + reqId,
                        "shutdown_request", reqId);
                return "Shutdown request sent to " + target + " with request_id=" + reqId;
            }
        });
        // 关闭状态查询: 通过 request_id 查询追踪器，获取当前状态和结果
        handlers.put("shutdown_status", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                String reqId = input.get("request_id").getAsString();
                JsonObject tracker = shutdownRequests.get(reqId);
                if (tracker == null) return "Unknown request_id: " + reqId;
                return GSON.toJson(tracker);
            }
        });

        // 计划审批 (Lead 端): Lead 审核 Teammate 提交的计划
        // 内部流程:
        //   1. 通过 request_id 找到追踪器
        //   2. 更新追踪器: status=resolved, decision=approve/reject, feedback
        //   3. 发送 plan_approval_response 消息给提交者 (携带同一 request_id)
        handlers.put("plan_review", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                String reqId = input.get("request_id").getAsString();
                String decision = input.get("decision").getAsString(); // "approve" or "reject"
                String feedback = input.has("feedback") ? input.get("feedback").getAsString() : "";

                JsonObject tracker = planRequests.get(reqId);
                if (tracker == null) return "Unknown request_id: " + reqId;

                tracker.addProperty("status", "resolved");
                tracker.addProperty("decision", decision);
                tracker.addProperty("feedback", feedback);

                String from = tracker.get("from").getAsString();
                bus.send("lead", from,
                        "Plan " + decision + ". " + feedback,
                        "plan_approval_response", reqId);
                return "Plan " + decision + " sent to " + from;
            }
        });

        // Lead 工具定义 (12 tools) / Lead tool definitions
        JsonArray leadToolDefs = BaseTools.allToolDefs();
        leadToolDefs.add(AnthropicClient.toolDef("spawn_teammate",
                "Create a new teammate.",
                AnthropicClient.schema("name", "string", "true",
                        "role", "string", "true", "prompt", "string", "true")));
        leadToolDefs.add(AnthropicClient.toolDef("list_teammates",
                "List all teammates and their status.",
                AnthropicClient.schema()));
        leadToolDefs.add(AnthropicClient.toolDef("send_message",
                "Send a message to a teammate.",
                AnthropicClient.schema("to", "string", "true",
                        "content", "string", "true", "msg_type", "string", "false")));
        leadToolDefs.add(AnthropicClient.toolDef("read_inbox",
                "Read lead's inbox.",
                AnthropicClient.schema()));
        leadToolDefs.add(AnthropicClient.toolDef("broadcast",
                "Broadcast message to all teammates.",
                AnthropicClient.schema("content", "string", "true")));
        leadToolDefs.add(AnthropicClient.toolDef("shutdown_request",
                "Send a shutdown request to a teammate. Returns request_id for tracking.",
                AnthropicClient.schema("target", "string", "true")));
        leadToolDefs.add(AnthropicClient.toolDef("shutdown_status",
                "Check the status of a shutdown request by request_id.",
                AnthropicClient.schema("request_id", "string", "true")));
        leadToolDefs.add(AnthropicClient.toolDef("plan_review",
                "Approve or reject a teammate's submitted plan.",
                AnthropicClient.schema("request_id", "string", "true",
                        "decision", "string", "true", "feedback", "string", "false")));

        String systemPrompt = "You are the Lead agent coordinating a team.\n"
                + "You have two protocols:\n"
                + "1. SHUTDOWN: Use shutdown_request to ask a teammate to shut down.\n"
                + "   They'll respond via shutdown_response. Check with shutdown_status.\n"
                + "2. PLAN APPROVAL: Teammates submit plans. Use plan_review to approve/reject.\n"
                + "Both protocols use request_id correlation: send a request, get a response with same ID.\n"
                + "Check your inbox regularly.";

        // REPL loop
        JsonArray messages = new JsonArray();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\nYou (/team, /inbox, /quit): ");
            if (!scanner.hasNextLine()) break;
            String userInput = scanner.nextLine().trim();
            if (userInput.isEmpty()) continue;

            if ("/quit".equals(userInput)) {
                System.out.println("Shutting down...");
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

            // Lead agent loop
            while (true) {
                // Check lead inbox before LLM call
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
        System.out.println("Goodbye from S10 Team Protocols!");
    }
}
