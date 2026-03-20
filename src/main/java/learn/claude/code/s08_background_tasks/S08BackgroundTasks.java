package learn.claude.code.s08_background_tasks;

import com.google.gson.*;
import learn.claude.code.common.AnthropicClient;
import learn.claude.code.common.BaseTools;
import learn.claude.code.common.ToolHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * S08 - 后台任务 (Background Tasks)
 *
 * 核心洞察 / Key Insight:
 *   "Fire and forget — the agent doesn't block while the command runs."
 *   "发射后忘记——智能体不会因为命令执行而阻塞。"
 *
 * 为什么需要这个 / Why this matters:
 *   有些命令需要很长时间（编译、测试、部署）。如果 agent 同步等待，
 *   就浪费了可以做其他事情的时间。后台执行让 agent 可以并行工作。
 *
 *   Some commands take a long time (build, test, deploy). If the agent
 *   waits synchronously, it wastes time that could be used for other work.
 *   Background execution lets the agent work in parallel.
 *
 * 实现要点 / Implementation details:
 *   - 使用 java.util.concurrent 管理后台线程
 *   - ConcurrentHashMap 存储任务状态（线程安全）
 *   - CopyOnWriteArrayList 作为通知队列
 *   - 每次 LLM 调用前注入已完成的通知
 *
 *   - Uses java.util.concurrent for background thread management
 *   - ConcurrentHashMap for task state (thread-safe)
 *   - CopyOnWriteArrayList as notification queue
 *   - Completed notifications injected before each LLM call
 */
public class S08BackgroundTasks {

    /** JSON 序列化工具，启用格式化输出 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ===== 内部类：后台任务管理器 =====
    //
    // 后台执行的核心设计思想：
    // Agent 在执行耗时命令（如编译、测试、部署）时，如果同步等待结果，
    // 就浪费了可以用来做其他工作的时间。后台执行让 Agent 提交命令后立即返回，
    // 在等待结果期间继续处理其他任务。
    //
    // 线程安全模型：
    // 1. ConcurrentHashMap 存储任务状态 — 多个后台线程并发更新，主线程并发读取
    // 2. CopyOnWriteArrayList 作为通知队列 — 后台线程写入完成通知，主线程消费
    // 3. volatile 修饰可变字段 — 确保后台线程的写入对主线程可见
    // 4. synchronized 方法保护 drain 操作的原子性
    //
    // 通知注入机制：
    // 每次调用 LLM 之前，主线程会排空通知队列，将已完成的后台任务结果
    // 注入为用户消息，让 LLM 知道后台任务的最新状态。

    /**
     * 后台命令执行管理器。
     *
     * 职责：
     * 1. 接收命令提交，在线程池中异步执行
     * 2. 跟踪每个后台任务的状态和输出
     * 3. 维护完成通知队列，供主线程在 LLM 调用前消费
     */
    static class BackgroundManager {

        /**
         * 后台任务的状态对象。
         *
         * 线程安全说明：status, output, endTime 字段使用 volatile 修饰，
         * 因为它们由后台线程写入、主线程读取，volatile 保证了可见性（happens-before 语义）。
         * id, command, startTime 是 final 的，天然线程安全。
         */
        static class BgTask {
            /** 任务唯一标识符，格式为 "bg_001", "bg_002" 等 */
            final String id;
            /** 要执行的 shell 命令 */
            final String command;
            /** 任务状态：running（运行中）| completed（成功）| failed（失败）| timeout（超时） */
            volatile String status;
            /** 命令的标准输出（含标准错误，因为 redirectErrorStream） */
            volatile String output;
            /** 任务启动时间戳（毫秒） */
            final long startTime;
            /** 任务结束时间戳（毫秒），运行中时为 0 */
            volatile long endTime;

            BgTask(String id, String command) {
                this.id = id;
                this.command = command;
                this.status = "running";
                this.output = "";
                this.startTime = System.currentTimeMillis();
                this.endTime = 0;
            }
        }

        /**
         * 线程安全的任务存储：taskId -> BgTask 映射。
         *
         * 选择 ConcurrentHashMap 的理由：
         * - 多个后台线程并发执行命令，各自更新自己任务的状态（并发写）
         * - 主线程通过 check() 方法随时查询任务状态（并发读）
         * - ConcurrentHashMap 提供分段锁，读写互不阻塞，性能优于 synchronized HashMap
         */
        private final ConcurrentHashMap<String, BgTask> tasks =
                new ConcurrentHashMap<String, BgTask>();

        /**
         * 通知队列：后台线程完成后写入通知字符串，主线程在 LLM 调用前排空并消费。
         *
         * 选择 CopyOnWriteArrayList 的理由：
         * - 写操作很少（只在后台任务完成时写一次）
         * - 读操作很多（每次 LLM 调用前都要检查）
         * - CopyOnWriteArrayList 的写操作复制整个数组（写慢），但读操作无需加锁（读快）
         * - 适合这种"写少读多"的场景
         *
         * 注意：drainNotifications() 用 synchronized 保证排空操作的原子性，
         * 防止在复制和清空之间有新通知插入导致丢失。
         */
        private final CopyOnWriteArrayList<String> notifications =
                new CopyOnWriteArrayList<String>();

        /**
         * 缓存线程池：按需创建线程，空闲 60 秒后回收。
         * 适合后台命令执行场景——任务数量不确定，但通常不会太多。
         */
        private final ExecutorService executor = Executors.newCachedThreadPool();

        /** 任务 ID 计数器（自增），注意：仅在主线程中调用 run()，所以不需要 AtomicInteger */
        private int nextId = 1;

        /** 命令执行的工作目录 */
        private final String workDir;

        BackgroundManager(String workDir) {
            this.workDir = workDir;
        }

        /**
         * 在后台启动一个 shell 命令，立即返回任务 ID。
         *
         * 这就是"发射后忘记"（fire and forget）模式的核心实现：
         * 1. 创建 BgTask 对象并注册到 tasks 映射中
         * 2. 提交 Runnable 到线程池
         * 3. 立即返回任务 ID，不等待命令完成
         *
         * 后台线程执行完毕后会：
         * - 更新 BgTask 的 status 和 output 字段（volatile 保证主线程可见）
         * - 向 notifications 队列添加完成通知
         *
         * @param command 要在 shell 中执行的命令
         * @return 包含任务 ID 的提示信息
         */
        String run(String command) {
            // 生成格式化的任务 ID（如 bg_001, bg_002）
            final String taskId = "bg_" + String.format("%03d", nextId++);
            final BgTask bgTask = new BgTask(taskId, command);
            // 将任务注册到并发映射中，主线程可通过 check() 随时查询状态
            tasks.put(taskId, bgTask);

            System.out.println("[Background] Started " + taskId + ": " + command);

            // 提交到线程池异步执行，submit() 立即返回
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 构建子进程：根据操作系统选择 shell
                        ProcessBuilder pb = new ProcessBuilder();
                        String os = System.getProperty("os.name").toLowerCase();
                        if (os.contains("win")) {
                            pb.command("cmd", "/c", command);    // Windows 使用 cmd
                        } else {
                            pb.command("sh", "-c", command);     // Unix/Mac 使用 sh
                        }
                        pb.directory(new File(workDir));          // 在工作目录下执行
                        pb.redirectErrorStream(true);             // 合并标准错误到标准输出

                        Process proc = pb.start();
                        // 读取子进程的输出（阻塞直到进程结束或流关闭）
                        StringBuilder output = new StringBuilder();
                        try {
                            BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
                            String line;
                            while ((line = reader.readLine()) != null) {
                                output.append(line).append("\n");
                            }
                            reader.close();
                        } catch (IOException e) {
                            output.append("Error reading output: ").append(e.getMessage());
                        }

                        // 等待进程完成，最多 300 秒（5 分钟）超时保护
                        boolean finished = proc.waitFor(300, TimeUnit.SECONDS);
                        bgTask.endTime = System.currentTimeMillis();

                        if (!finished) {
                            // 超时：强制杀死子进程
                            proc.destroyForcibly();
                            bgTask.status = "timeout";
                            bgTask.output = "Timeout after 300 seconds";
                        } else {
                            // 正常结束：根据退出码判断成功或失败
                            int exitCode = proc.exitValue();
                            bgTask.status = (exitCode == 0) ? "completed" : "failed";
                            String out = output.toString().trim();
                            // 截断过长的输出（防止占用过多内存和上下文空间）
                            bgTask.output = out.length() > 50000 ? out.substring(0, 50000) : out;
                        }

                        // 向通知队列添加完成通知（主线程会在下次 LLM 调用前消费）
                        long elapsed = (bgTask.endTime - bgTask.startTime) / 1000;
                        String notification = "[Background " + taskId + " " + bgTask.status + "] "
                                + "Command: " + command + " | "
                                + "Duration: " + elapsed + "s | "
                                + "Output: " + bgTask.output.substring(0,
                                Math.min(500, bgTask.output.length()));
                        notifications.add(notification);
                        System.out.println("[Background] " + taskId + " " + bgTask.status
                                + " (" + elapsed + "s)");

                    } catch (Exception e) {
                        bgTask.status = "failed";
                        bgTask.output = "Exception: " + e.getMessage();
                        bgTask.endTime = System.currentTimeMillis();
                        notifications.add("[Background " + taskId + " failed] " + e.getMessage());
                    }
                }
            });

            return "Started background task " + taskId + ": " + command
                    + "\nUse check_background to see the result later.";
        }

        /**
         * 检查后台任务的状态和输出。
         *
         * 两种模式：
         * - taskId 为 null/空：列出所有后台任务的状态摘要
         * - taskId 非空：返回指定任务的详细信息（状态、输出、耗时）
         *
         * @param taskId 要查询的任务 ID（可为 null 表示列出所有）
         * @return 任务状态的 JSON 字符串
         */
        String check(String taskId) {
            // 如果未提供 taskId，列出所有后台任务的概览
            if (taskId == null || taskId.isEmpty()) {
                if (tasks.isEmpty()) {
                    return "No background tasks.";
                }
                JsonArray allTasks = new JsonArray();
                for (Map.Entry<String, BgTask> entry : tasks.entrySet()) {
                    BgTask bg = entry.getValue();
                    JsonObject info = new JsonObject();
                    info.addProperty("id", bg.id);
                    info.addProperty("command", bg.command);
                    info.addProperty("status", bg.status);
                    if (!"running".equals(bg.status)) {
                        long elapsed = (bg.endTime - bg.startTime) / 1000;
                        info.addProperty("duration_seconds", elapsed);
                    } else {
                        long elapsed = (System.currentTimeMillis() - bg.startTime) / 1000;
                        info.addProperty("running_for_seconds", elapsed);
                    }
                    allTasks.add(info);
                }
                return GSON.toJson(allTasks);
            }

            BgTask bgTask = tasks.get(taskId);
            if (bgTask == null) {
                return "Error: Unknown background task: " + taskId
                        + ". Active tasks: " + tasks.keySet();
            }

            JsonObject result = new JsonObject();
            result.addProperty("id", bgTask.id);
            result.addProperty("command", bgTask.command);
            result.addProperty("status", bgTask.status);

            if (!"running".equals(bgTask.status)) {
                result.addProperty("output", bgTask.output);
                long elapsed = (bgTask.endTime - bgTask.startTime) / 1000;
                result.addProperty("duration_seconds", elapsed);
            } else {
                long elapsed = (System.currentTimeMillis() - bgTask.startTime) / 1000;
                result.addProperty("running_for_seconds", elapsed);
            }

            return GSON.toJson(result);
        }

        /**
         * 排空通知队列并返回所有累积的通知。
         *
         * 线程安全说明：使用 synchronized 方法级别锁（锁对象为 this），
         * 确保排空操作的原子性——在复制和清空之间不会有新的通知被插入。
         *
         * 注意：之前此方法内部有一个冗余的 synchronized(this) 块，
         * 由于方法本身已经是 synchronized 的（锁对象同为 this），内层同步块毫无意义，
         * 属于重复加锁（reentrant lock），已被移除。
         *
         * @return 自上次调用以来累积的所有通知字符串列表（可能为空列表）
         */
        synchronized List<String> drainNotifications() {
            List<String> drained = new ArrayList<String>(notifications);
            notifications.clear();
            return drained;
        }

        /**
         * 优雅关闭线程池。
         *
         * 两阶段关闭策略：
         * 1. shutdown()：不再接受新任务，等待已提交的任务完成
         * 2. 如果 5 秒内未完成，shutdownNow()：尝试中断正在执行的任务
         *
         * 这在程序退出时调用（finally 块），确保不会有后台线程泄漏。
         */
        void shutdown() {
            executor.shutdown();
            try {
                // 等待最多 5 秒让已提交的任务完成
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    // 超时：强制中断所有任务
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                // 等待被中断：直接强制关闭
                executor.shutdownNow();
            }
        }
    }

    // ===== 工具定义 =====

    /**
     * background_run 工具定义：在后台启动一个 shell 命令。
     * 必需参数：command（要执行的命令）。
     * 返回任务 ID，可通过 check_background 查询结果。
     */
    private static JsonObject backgroundRunToolDef() {
        return AnthropicClient.toolDef("background_run",
                "Run a shell command in the background. Returns immediately with a task ID. "
                        + "Use this for long-running commands like builds, tests, or deployments. "
                        + "Check results later with check_background.",
                AnthropicClient.schema("command", "string", "true"));
    }

    /**
     * check_background 工具定义：查询后台任务的状态和输出。
     * 可选参数：task_id（指定任务 ID，省略则列出所有后台任务）。
     */
    private static JsonObject checkBackgroundToolDef() {
        return AnthropicClient.toolDef("check_background",
                "Check the status and output of a background task by its ID. "
                        + "If task_id is omitted, lists all background tasks with their status.",
                AnthropicClient.schema("task_id", "string", "false"));
    }

    // ===== Agent 主循环 =====

    /**
     * Agent 主循环：REPL 模式，支持 6 个工具（4 基础 + 2 后台）。
     *
     * 与之前 S05/S06 的 Agent 循环相比，关键区别在于"通知注入"机制：
     * 每次调用 LLM 之前，检查是否有后台任务完成的通知，如果有，
     * 将通知内容作为用户消息注入对话，让 LLM 知道后台任务的结果。
     *
     * 这样 LLM 不需要主动轮询后台任务状态——系统会自动告诉它。
     *
     * @param workDir 工作目录路径
     */
    private static void agentLoop(String workDir) {
        AnthropicClient client = new AnthropicClient();
        BaseTools baseTools = new BaseTools(workDir);
        final BackgroundManager bgMgr = new BackgroundManager(workDir);

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
        // 后台任务工具处理器
        handlers.put("background_run", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return bgMgr.run(input.get("command").getAsString());
            }
        });
        handlers.put("check_background", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                String taskId = input.has("task_id") ? input.get("task_id").getAsString() : null;
                return bgMgr.check(taskId);
            }
        });

        // 组装工具列表：4 个基础工具 + 2 个后台任务工具 = 共 6 个
        JsonArray tools = BaseTools.allToolDefs();
        tools.add(backgroundRunToolDef());
        tools.add(checkBackgroundToolDef());

        String systemPrompt = "You are a helpful coding assistant with background execution capability.\n\n"
                + "## Background Tasks\n"
                + "You can run long-running commands in the background:\n"
                + "- Use background_run for commands that take >10 seconds (builds, tests, deploys)\n"
                + "- Use check_background to check on a task's status and output\n"
                + "- You'll be notified automatically when background tasks complete\n\n"
                + "### Best Practices\n"
                + "- Start a build in background, then continue with other work\n"
                + "- Run tests in background while writing more code\n"
                + "- Use regular bash for quick commands (<10 seconds)\n\n"
                + "## Tools\n"
                + "You have bash, read_file, write_file, edit_file, "
                + "background_run, check_background available.";

        JsonArray messages = new JsonArray();

        Scanner scanner = new Scanner(System.in);
        System.out.print("You: ");

        try {
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

                // Agent 循环 / Agent loop
                int maxIterations = 25;
                for (int i = 0; i < maxIterations; i++) {

                    // === 通知注入机制 ===
                    // 在每次 LLM 调用前，排空通知队列，检查是否有后台任务完成
                    // 如果有，将通知内容注入为对话消息，让 LLM 自动获知后台任务的结果
                    // 这样 LLM 不需要主动调用 check_background 来轮询，减少不必要的工具调用
                    List<String> newNotifications = bgMgr.drainNotifications();
                    if (!newNotifications.isEmpty()) {
                        StringBuilder notifText = new StringBuilder();
                        notifText.append("[System: Background task notifications]\n");
                        for (String notif : newNotifications) {
                            notifText.append(notif).append("\n");
                        }
                        System.out.println("[Notify] Injecting " + newNotifications.size()
                                + " background notification(s)");

                        // 注入两条消息：
                        // 1. user 消息：承载通知内容（LLM 从 user 消息获取信息）
                        // 2. assistant 消息：简短确认（保持消息交替的 user-assistant 格式要求）
                        messages.add(AnthropicClient.userMessage(notifText.toString()));
                        messages.add(AnthropicClient.assistantMessage("Noted background results."));
                    }

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
        } finally {
            // 确保线程池关闭，防止后台线程泄漏（即使发生异常也会执行）
            bgMgr.shutdown();
        }

        scanner.close();
    }

    // ===== 程序入口 =====

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  S08 - Background Tasks (后台任务)                      ║");
        System.out.println("║                                                          ║");
        System.out.println("║  Key Insight:                                            ║");
        System.out.println("║  Fire and forget — the agent doesn't block               ║");
        System.out.println("║  while the command runs.                                 ║");
        System.out.println("║  发射后忘记——智能体不会因为命令执行而阻塞。              ║");
        System.out.println("║                                                          ║");
        System.out.println("║  - background_run: start commands asynchronously         ║");
        System.out.println("║  - check_background: poll for results                    ║");
        System.out.println("║  - Notifications auto-injected before each LLM call      ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝\n");

        String workDir = System.getProperty("user.dir");
        if (args.length > 0 && !args[0].startsWith("--")) {
            workDir = args[0];
        }

        agentLoop(workDir);
    }
}
