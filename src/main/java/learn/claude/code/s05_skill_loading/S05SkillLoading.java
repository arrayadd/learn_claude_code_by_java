package learn.claude.code.s05_skill_loading;

import com.google.gson.*;
import learn.claude.code.common.AnthropicClient;
import learn.claude.code.common.BaseTools;
import learn.claude.code.common.ToolHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * ============================================================
 * S05 - 技能加载 (Skill Loading)
 * ============================================================
 *
 * 【与前一课的关系 / 从 S04 的演进】
 * S04 通过子代理实现了上下文隔离，解决了"做什么"的问题。
 * S05 解决的是"怎么做"的问题——当模型遇到特定类型的任务时，
 * 如何让它获得正确的操作指南？
 *
 * 如果把所有技能的完整指南都塞进 system prompt，会导致：
 * 1. 每次 API 调用都消耗大量 token（即使本次任务不需要这些技能）
 * 2. 过长的 system prompt 让模型"注意力分散"，降低任务执行质量
 * 3. 技能数量受限于 system prompt 的 token 预算
 *
 * S05 用两层注入架构优雅地解决了这个问题。
 *
 * 【核心洞察 / Key Insight】
 *   "Don't put everything in the system prompt. Load on demand."
 *   "不要把所有东西都塞进系统提示词里，按需加载。"
 *
 * 【两层注入架构 / Two-layer injection architecture】
 *   Layer 1 (廉价层 / Catalog): 技能名称 + 简短描述放入 system prompt (~100 tokens/skill)
 *     -> 让模型知道"有哪些技能可用"，但不知道具体怎么做
 *     -> 类似于图书馆的目录卡片：告诉你有这本书，但不是书的全文
 *
 *   Layer 2 (按需层 / On-demand): 当模型调用 load_skill 工具时，返回完整技能正文
 *     -> 只有模型判断需要某个技能时，才通过工具调用加载完整内容
 *     -> 类似于从书架上取出一本书：只在需要时才获取全文
 *
 * 【与 Claude Code 的对应】
 * 这是 Claude Code 的真实设计——slash commands (如 /commit, /review-pr) 就是这样实现的。
 * Claude Code 中的 Skill tool 扫描 .claude/agents/ 目录下的技能定义文件，
 * 先把技能目录注入 system prompt，当用户触发或模型判断需要时才加载完整指令。
 *
 * 【Token 经济学 / Token Economics】
 * 假设有 50 个技能，每个完整正文 2000 tokens：
 * - 全部塞入 system prompt: 50 * 2000 = 100,000 tokens/请求
 * - 两层架构: 50 * 100 (目录) + 1 * 2000 (按需加载) = 7,000 tokens/请求
 * 节省了 93% 的 token！
 */
public class S05SkillLoading {

    // ===== 内部类：技能加载器 / Inner Class: SkillLoader =====

    /**
     * 技能加载器 - 负责扫描、解析和管理所有可用的技能。
     *
     * 【职责】
     * 1. 启动时扫描 skills/ 目录下的所有 SKILL.md 文件（一次性）
     * 2. 解析 YAML frontmatter 提取元数据（name/description/tags）
     * 3. 提供 Layer 1 接口：getDescriptions() 返回简短目录
     * 4. 提供 Layer 2 接口：getContent(name) 返回完整正文
     *
     * 【文件格式约定 / File Format Convention】
     * 每个技能是一个子目录，包含一个 SKILL.md 文件：
     * skills/
     *   commit/
     *     SKILL.md      <-- YAML frontmatter + markdown body
     *   review-pr/
     *     SKILL.md
     *
     * SKILL.md 文件格式：
     * ---
     * name: skill-name
     * description: what this skill does
     * tags: tag1, tag2
     * ---
     * (skill body in markdown - 这是 Layer 2 的内容)
     *
     * 【与 Claude Code 的对应】
     * Claude Code 中，技能定义存储在 .claude/agents/ 目录下，
     * 格式类似（也使用 markdown + frontmatter）。
     */
    static class SkillLoader {

        /**
         * 技能元数据 - 同时存储 Layer 1 信息（name/description/tags）和 Layer 2 内容（body）。
         * 在内存中保持完整数据，按需向外部暴露不同层级的信息。
         */
        static class SkillMeta {
            /** 技能名称，用于 load_skill 时的查找键 */
            String name;
            /** Layer 1 信息：简短描述，注入 system prompt 供模型判断是否需要此技能 */
            String description;
            /** 标签，帮助模型按类别筛选相关技能 */
            String tags;
            /** Layer 2 信息：完整的技能正文（markdown 格式），只在按需加载时返回 */
            String body;

            SkillMeta(String name, String description, String tags, String body) {
                this.name = name;
                this.description = description;
                this.tags = tags;
                this.body = body;
            }
        }

        /**
         * 技能注册表：name -> SkillMeta。
         * 使用 LinkedHashMap 保持插入顺序，使输出的技能目录顺序稳定。
         */
        private final Map<String, SkillMeta> skills = new LinkedHashMap<String, SkillMeta>();

        /** 技能文件的根目录路径 */
        private final String skillsDir;

        /**
         * 构造器：在实例化时立即扫描目录，构建技能注册表。
         * 这意味着技能在 agent 启动时一次性加载，运行期间不会动态刷新。
         * 如果需要热加载，可以在每轮循环前重新调用 scan()（本示例不做此优化）。
         *
         * @param skillsDir skills/ 目录的绝对路径
         */
        SkillLoader(String skillsDir) {
            this.skillsDir = skillsDir;
            scan();  // 启动时立即扫描，填充 skills Map
        }

        /**
         * 扫描技能目录，递归查找 SKILL.md 文件。
         * Scan skills directory, recursively find SKILL.md files.
         */
        private void scan() {
            File dir = new File(skillsDir);
            if (!dir.exists() || !dir.isDirectory()) {
                System.err.println("[SkillLoader] Skills directory not found: " + skillsDir);
                return;
            }
            scanRecursive(dir);
        }

        /**
         * 递归扫描目录查找 SKILL.md 文件。
         * Recursively scan directories to find SKILL.md files.
         */
        private void scanRecursive(File dir) {
            File[] children = dir.listFiles();
            if (children == null) return;

            for (File child : children) {
                if (!child.isDirectory()) continue;
                File skillFile = new File(child, "SKILL.md");
                if (skillFile.exists()) {
                    try {
                        String content = new String(
                                Files.readAllBytes(skillFile.toPath()), StandardCharsets.UTF_8);
                        SkillMeta meta = parseFrontmatter(content, child.getName());
                        if (meta != null) {
                            skills.put(meta.name, meta);
                            System.out.println("[SkillLoader] Loaded skill: " + meta.name);
                        }
                    } catch (IOException e) {
                        System.err.println("[SkillLoader] Failed to read: " + skillFile + " - " + e.getMessage());
                    }
                }
                // 递归扫描子目录 / Recursively scan subdirectories
                scanRecursive(child);
            }
        }

        /**
         * 解析 SKILL.md 文件中的 YAML frontmatter（--- 分隔符之间的内容）。
         *
         * 【为什么用 YAML frontmatter？】
         * 这是一种广泛使用的约定（Jekyll、Hugo、Obsidian 等都用这种格式），
         * 它允许在 Markdown 文件开头嵌入结构化的元数据。
         * Claude Code 也采用类似的格式来定义自定义 agent 的元数据。
         *
         * 【简化实现的权衡】
         * 这里只做了最简单的 key: value 解析，不支持嵌套、数组等复杂 YAML 特性。
         * 对于技能定义来说，简单的 key-value 已经够用了。
         *
         * @param content SKILL.md 文件的完整内容
         * @param dirName 父目录名，作为 name 字段的后备值
         * @return 解析后的 SkillMeta，解析失败返回 null
         */
        private SkillMeta parseFrontmatter(String content, String dirName) {
            // 查找两个 --- 分隔符 / Find the two --- delimiters
            if (!content.startsWith("---")) return null;

            int secondDelim = content.indexOf("---", 3);
            if (secondDelim < 0) return null;

            String frontmatter = content.substring(3, secondDelim).trim();
            String body = content.substring(secondDelim + 3).trim();

            // 解析简单的 key: value 对 / Parse simple key: value pairs
            Map<String, String> meta = new HashMap<String, String>();
            for (String line : frontmatter.split("\n")) {
                line = line.trim();
                int colonIdx = line.indexOf(':');
                if (colonIdx > 0) {
                    String key = line.substring(0, colonIdx).trim();
                    String value = line.substring(colonIdx + 1).trim();
                    meta.put(key, value);
                }
            }

            // 如果 name 缺失或为空，使用父目录名作为后备
            // If name is missing or empty, fall back to the parent directory name
            String name = meta.get("name");
            if (name == null || name.isEmpty()) {
                name = dirName;
            }

            return new SkillMeta(
                    name,
                    meta.containsKey("description") ? meta.get("description") : "(no description)",
                    meta.containsKey("tags") ? meta.get("tags") : "",
                    body
            );
        }

        /**
         * 获取所有技能的简短描述列表（用于注入 system prompt）。
         * Get short description list of all skills (for system prompt injection).
         *
         * 每个技能大约 ~100 tokens，即使有 100 个技能也只占 ~10K tokens。
         * ~100 tokens per skill; even 100 skills only cost ~10K tokens.
         */
        String getDescriptions() {
            if (skills.isEmpty()) return "(No skills available)";

            StringBuilder sb = new StringBuilder();
            sb.append("Available skills (use load_skill to get full instructions):\n");
            for (SkillMeta meta : skills.values()) {
                // 格式: - skill-name: description [tags: tag1, tag2]
                sb.append("  - ").append(meta.name).append(": ").append(meta.description);
                if (meta.tags != null && !meta.tags.isEmpty()) {
                    sb.append("  [tags: ").append(meta.tags).append("]");
                }
                sb.append("\n");
            }
            return sb.toString();
        }

        /**
         * 获取完整技能内容（当模型调用 load_skill 时返回）。
         * Get full skill content (returned when model calls load_skill).
         *
         * 用 <skill> 标签包裹，方便模型识别技能边界。
         * Wrapped in <skill> tags so the model can identify skill boundaries.
         */
        String getContent(String name) {
            SkillMeta meta = skills.get(name);
            if (meta == null) {
                return "Error: Unknown skill '" + name + "'. Available: " + skills.keySet();
            }
            // Layer 2: 完整技能正文，用 <skill> 标签包裹
            // Layer 2: Full skill body wrapped in <skill> tags
            return "<skill name=\"" + name + "\">\n" + meta.body + "\n</skill>";
        }

        /** 获取所有已知技能名称 / Get all known skill names */
        Set<String> getSkillNames() {
            return skills.keySet();
        }
    }

    // ===== 工具定义 / Tool Definitions =====

    /**
     * load_skill 工具的 JSON Schema 定义。
     *
     * 【设计决策】
     * 这个工具只有一个参数 "name"，设计非常简洁。
     * 模型从 system prompt 中的技能目录（Layer 1）得知可用技能名称，
     * 然后通过 load_skill(name) 加载完整内容（Layer 2）。
     *
     * 【工具描述的引导作用】
     * "Use this when a user's request matches one of the available skills"
     * 引导模型在识别到匹配的技能时主动加载，而非猜测技能内容。
     * 这对应 Claude Code 中 Skill tool 的行为。
     */
    private static JsonObject loadSkillToolDef() {
        return AnthropicClient.toolDef(
                "load_skill",
                "Load a skill by name to get its full instructions. "
                        + "Use this when a user's request matches one of the available skills.",
                AnthropicClient.schema("name", "string", "true")
        );
    }

    // ===== Agent 主循环 / Agent Loop =====

    /**
     * 构建包含技能目录的系统提示词 - Layer 1 注入的具体实现。
     *
     * 【System Prompt 结构】
     * 1. 角色定义
     * 2. Skills 章节（Layer 1）：列出所有可用技能的名称和描述
     * 3. 行为指导：要求模型先 load_skill 再执行，不要猜测
     * 4. Tools 章节：列出可用的工具
     *
     * 【关键指令解析】
     * - "ALWAYS call load_skill first" —— 强制模型先加载再行动
     * - "Do NOT guess how a skill works" —— 防止模型凭"记忆"猜测技能内容
     *   （技能可能随时更新，模型的训练数据可能已过时）
     *
     * 这两条指令确保了 Layer 2 的按需加载机制不会被模型"绕过"。
     */
    private static String buildSystemPrompt(SkillLoader loader) {
        return "You are a helpful coding assistant.\n\n"
                + "## Skills\n"
                + loader.getDescriptions() + "\n"
                + "When a user's request matches an available skill, ALWAYS call load_skill first "
                + "to get the full instructions before proceeding.\n"
                + "Do NOT guess how a skill works — load it first.\n\n"
                + "## Tools\n"
                + "You have bash, read_file, write_file, edit_file, and load_skill available.";
    }

    /**
     * Agent 主循环 - 标准的 agentic loop，与 S02/S03/S04 结构一致。
     *
     * 【S05 的特殊之处】
     * 循环结构不变，但工具集中新增了 load_skill 工具。
     * 当模型调用 load_skill 时，返回的是技能的完整正文（Layer 2 内容），
     * 这些内容会作为 tool_result 进入 messages，供模型在后续轮次中使用。
     *
     * 【信息流】
     * 1. system prompt 中包含技能目录（Layer 1）
     * 2. 模型判断需要某个技能，调用 load_skill("skill-name")
     * 3. load_skill 返回完整正文（Layer 2），进入 messages
     * 4. 模型根据完整正文执行任务
     *
     * 注意：加载后的技能正文会一直留在 messages 中，后续轮次都能看到。
     * 这意味着同一个技能只需加载一次（在同一次对话中）。
     */
    private static String agentLoop(JsonArray messages, String workDir) {
        AnthropicClient client = new AnthropicClient();
        BaseTools baseTools = new BaseTools(workDir);
        // 在 agent 启动时一次性扫描 skills/ 目录，构建技能注册表
        SkillLoader skillLoader = new SkillLoader(workDir + "/skills");

        // 注册工具处理器（与 S02/S03/S04 的模式完全一致）
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
        // Layer 2 入口：load_skill 处理器（S05 的核心新增工具）
        // 当模型调用此工具时，返回完整技能正文，实现"按需加载"
        // 这就是两层架构中 Layer 2 的触发点
        handlers.put("load_skill", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                String name = input.get("name").getAsString();
                System.out.println("[Agent] Loading skill: " + name);
                // getContent() 返回 <skill> 标签包裹的完整正文
                // 如果技能不存在，返回错误信息和可用技能列表
                return skillLoader.getContent(name);
            }
        });

        // 构建工具列表：4 个基础工具 + load_skill（S05 新增）
        JsonArray tools = BaseTools.allToolDefs();
        tools.add(loadSkillToolDef());

        // Layer 1 注入：技能目录嵌入 system prompt
        // 此时模型知道有哪些技能可用，但还不知道具体操作步骤
        String systemPrompt = buildSystemPrompt(skillLoader);
        // 打印 system prompt 帮助调试，可以看到 Layer 1 的内容
        System.out.println("=== System Prompt (Layer 1 — skill catalog) ===");
        System.out.println(systemPrompt);
        System.out.println("================================================\n");

        /** 记录最后一次模型的文本输出，用于最终返回给调用方 */
        String lastTextOutput = "";

        // Agent 循环：与 S02/S03/S04 的 agentic loop 结构一致
        // 限制最多 20 轮，防止无限循环
        int maxIterations = 20;
        for (int i = 0; i < maxIterations; i++) {
            System.out.println("[Agent] Calling LLM (iteration " + (i + 1) + ")...");
            JsonObject response = client.createMessage(systemPrompt, messages, tools, 8000);

            String stopReason = AnthropicClient.getStopReason(response);
            JsonArray content = AnthropicClient.getContent(response);

            // 打印模型的文本输出 / Print model's text output
            String textOutput = AnthropicClient.extractText(content);
            if (!textOutput.isEmpty()) {
                lastTextOutput = textOutput;
                System.out.println("\n[Assistant] " + textOutput);
            }

            // 如果模型结束对话，退出循环
            // If model ends the conversation, exit the loop
            if (!"tool_use".equals(stopReason)) {
                System.out.println("\n[Agent] Done. (stop_reason=" + stopReason + ")");
                break;
            }

            // 处理工具调用 / Process tool calls
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

                // 截断输出以节省 tokens
                // 10000 字符是一个实用的阈值：既能包含大部分有用内容，
                // 又不会让单次工具结果占用过多上下文窗口
                if (result.length() > 10000) {
                    result = result.substring(0, 10000) + "\n... (truncated)";
                }
                System.out.println("[Tool] " + toolName + " -> " + result.substring(0, Math.min(200, result.length())));

                toolResults.add(AnthropicClient.toolResult(toolId, result));
            }

            messages.add(AnthropicClient.userMessage(toolResults));
        }

        return lastTextOutput;
    }

    // ===== 入口 / Main Entry =====

    /**
     * 程序入口。
     *
     * 【运行前准备】
     * 需要在工作目录下创建 skills/ 子目录，并放入 SKILL.md 文件。
     * 如果 skills/ 目录不存在，程序会自动创建空目录。
     * 没有技能时程序仍可运行，只是 load_skill 工具不可用。
     *
     * 【交互流程】
     * 1. 启动时扫描 skills/ 目录，构建技能注册表
     * 2. 将技能目录注入 system prompt（Layer 1）
     * 3. 进入用户交互循环
     * 4. 用户输入触发 agent loop -> 模型可能调用 load_skill（Layer 2）
     */
    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║  S05 - Skill Loading (技能加载)                 ║");
        System.out.println("║                                                  ║");
        System.out.println("║  Key Insight:                                    ║");
        System.out.println("║  Don't put everything in the system prompt.      ║");
        System.out.println("║  Load on demand.                                 ║");
        System.out.println("║  不要把所有东西都塞进系统提示词里，按需加载。    ║");
        System.out.println("╚══════════════════════════════════════════════════╝\n");

        // 工作目录：默认使用当前目录，也可通过参数指定
        // Working directory: defaults to current dir, or pass as argument
        String workDir = System.getProperty("user.dir");
        if (args.length > 0 && !args[0].startsWith("--")) {
            workDir = args[0];
        }

        // 确保 skills/ 目录存在 / Ensure skills/ directory exists
        File skillsDir = new File(workDir, "skills");
        if (!skillsDir.exists()) {
            System.out.println("[Setup] Creating skills/ directory at: " + skillsDir.getPath());
            skillsDir.mkdirs();
        }

        // 持久消息列表（跨多轮用户输入保持对话上下文）
        // 注意：一旦某个技能被 load_skill 加载，它的正文会一直留在 messages 中，
        // 后续轮次无需重复加载（这是按需加载的另一个优势）
        JsonArray messages = new JsonArray();

        // 交互式对话循环
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
            String response = agentLoop(messages, workDir);
            System.out.println("\n" + response);
            System.out.print("\nYou: ");
        }
        scanner.close();
    }
}
