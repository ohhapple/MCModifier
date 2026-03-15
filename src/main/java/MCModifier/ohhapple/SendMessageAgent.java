package MCModifier.ohhapple;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class SendMessageAgent {

    private static Path LOG_FILE;
    private static Path RULES_JSON;

    public static void agentmain(String agentArgs, Instrumentation inst) {
        String outputDir = System.getProperty("user.dir");
        String action = null;
        String commandsStr = null;

        if (agentArgs != null && !agentArgs.trim().isEmpty()) {
            String trimmed = agentArgs.trim();
            if (trimmed.startsWith("{")) {
                // 简易 JSON 解析
                int actionIdx = trimmed.indexOf("\"action\"");
                if (actionIdx >= 0) {
                    int colon = trimmed.indexOf(':', actionIdx);
                    if (colon >= 0) {
                        int startQuote = trimmed.indexOf('"', colon);
                        if (startQuote >= 0) {
                            int endQuote = trimmed.indexOf('"', startQuote + 1);
                            if (endQuote >= 0) {
                                action = trimmed.substring(startQuote + 1, endQuote);
                            }
                        }
                    }
                }
                int dirIdx = trimmed.indexOf("\"outputDir\"");
                if (dirIdx >= 0) {
                    int colon = trimmed.indexOf(':', dirIdx);
                    if (colon >= 0) {
                        int startQuote = trimmed.indexOf('"', colon);
                        if (startQuote >= 0) {
                            int endQuote = trimmed.indexOf('"', startQuote + 1);
                            if (endQuote >= 0) {
                                outputDir = trimmed.substring(startQuote + 1, endQuote);
                                outputDir = outputDir.replace("\\\\", "\\");
                            }
                        }
                    }
                }
                int cmdIdx = trimmed.indexOf("\"commands\"");
                if (cmdIdx >= 0) {
                    int colon = trimmed.indexOf(':', cmdIdx);
                    if (colon >= 0) {
                        int startQuote = trimmed.indexOf('"', colon);
                        if (startQuote >= 0) {
                            int endQuote = trimmed.indexOf('"', startQuote + 1);
                            if (endQuote >= 0) {
                                commandsStr = trimmed.substring(startQuote + 1, endQuote);
                            }
                        }
                    }
                }
            } else {
                action = "commands";
                commandsStr = trimmed;
            }
        }

        Path dir = Paths.get(outputDir);
        LOG_FILE = dir.resolve("carpet_agent.log");
        RULES_JSON = dir.resolve("carpet_rules.json");

        try {
            Files.deleteIfExists(LOG_FILE);
        } catch (Exception ignored) {}

        logToFile("=== Agent 启动，参数: " + agentArgs + " ===");
        logToFile("输出目录: " + outputDir);
        logToFile("解析到的 action: " + action);
        if (commandsStr != null) {
            logToFile("解析到的 commands: " + commandsStr);
        }

        if (action == null) {
            logToFile("未识别到有效操作，退出。");
            return;
        }

        String finalAction = action;
        String finalCommands = commandsStr;
        new Thread(() -> {
            try {
                if ("list".equals(finalAction)) {
                    exportRulesToJson(inst);
                } else if ("commands".equals(finalAction)) {
                    String cmdToExecute = finalCommands != null ? finalCommands : agentArgs;
                    executeCommands(cmdToExecute, inst);
                } else {
                    logToFile("未知 action: " + finalAction);
                }
                logToFile("=== 所有操作完成 ===\n");
            } catch (Exception e) {
                logToFile("线程异常: " + e);
                e.printStackTrace();
            }
        }).start();
    }

    private static void executeCommands(String commandsStr, Instrumentation inst) {
        logToFile("executeCommands 被调用，原始命令字符串: " + commandsStr);
        String[] commands = commandsStr.split(";");
        logToFile("分割后得到 " + commands.length + " 条命令");
        for (String cmd : commands) {
            cmd = cmd.trim();
            if (cmd.isEmpty()) continue;
            logToFile("准备执行命令: " + cmd);

            boolean success = false;
            try {
                success = executeViaServer(cmd, inst);
            } catch (Exception e) {
                logToFile("通过服务器执行异常: " + e);
            }

            if (!success) {
                try {
                    success = executeViaPlayer(cmd, inst);
                } catch (Exception e) {
                    logToFile("通过玩家执行异常: " + e);
                }
            }

            if (success) {
                logToFile("命令执行成功: " + cmd);
            } else {
                logToFile("命令执行失败（所有方法均无效）: " + cmd);
            }
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }
    }

    // ==================== 服务端命令执行 ====================
    private static boolean executeViaServer(String command, Instrumentation inst) {
        logToFile("executeViaServer 被调用，命令: " + command);
        logToFile("尝试通过服务器实例执行命令...");
        try {
            Object server = getServerInstance(inst);
            if (server == null) {
                logToFile("无法获取服务器实例");
                return false;
            }
            return executeCommandOnServer(server, command);
        } catch (Exception e) {
            logToFile("executeViaServer 异常: " + e);
            return false;
        }
    }

    /**
     * 获取 MinecraftServer 实例
     * 优先通过 CarpetServer.minecraft_server 静态字段获取
     */
    private static Object getServerInstance(Instrumentation inst) {
        logToFile("开始获取服务器实例...");

        // 优先尝试从 CarpetServer 的静态字段 minecraft_server 获取
        try {
            Class<?> carpetServerClass = findLoadedClass(inst, "carpet.CarpetServer");
            if (carpetServerClass != null) {
                Field mcField = carpetServerClass.getDeclaredField("minecraft_server");
                mcField.setAccessible(true);
                Object server = mcField.get(null);
                if (server != null) {
                    logToFile("通过 CarpetServer.minecraft_server 获取服务器实例成功: " + server.getClass().getName());
                    return server;
                } else {
                    logToFile("CarpetServer.minecraft_server 字段值为 null");
                }
            } else {
                logToFile("未找到 carpet.CarpetServer 类");
            }
        } catch (Exception e) {
            logToFile("通过 CarpetServer 获取服务器实例异常: " + e);
        }

        // 回退到原有的搜索逻辑（遍历 MinecraftServer 类及其子类）
        try {
            Class<?> serverClass = findLoadedClass(inst, "net.minecraft.server.MinecraftServer");
            if (serverClass != null) {
                logToFile("找到 MinecraftServer 类: " + serverClass.getName() + "，类加载器: " + serverClass.getClassLoader());

                // 尝试所有静态方法（无参，返回类型为 MinecraftServer 或其子类）
                for (Method method : serverClass.getDeclaredMethods()) {
                    if (java.lang.reflect.Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0 &&
                            serverClass.isAssignableFrom(method.getReturnType())) {
                        method.setAccessible(true);
                        try {
                            Object server = method.invoke(null);
                            if (server != null) {
                                logToFile("通过静态方法 " + method.getName() + "() 获取服务器实例成功");
                                return server;
                            }
                        } catch (Exception e) {
                            logToFile("调用静态方法 " + method.getName() + "() 异常: " + e);
                        }
                    }
                }

                // 尝试常见静态字段
                String[] staticFieldNames = {"SERVER", "INSTANCE", "server", "instance"};
                for (String fieldName : staticFieldNames) {
                    try {
                        Field field = serverClass.getDeclaredField(fieldName);
                        field.setAccessible(true);
                        logToFile("找到静态字段 " + fieldName + "，尝试获取...");
                        Object server = field.get(null);
                        if (server != null && serverClass.isInstance(server)) {
                            logToFile("通过静态字段 " + serverClass.getSimpleName() + "." + fieldName + " 获取服务器实例成功");
                            return server;
                        } else {
                            logToFile("静态字段 " + fieldName + " 的值为 null 或类型不匹配");
                        }
                    } catch (NoSuchFieldException ignored) {
                        logToFile("静态字段 " + fieldName + " 不存在");
                    } catch (Exception e) {
                        logToFile("访问静态字段 " + fieldName + " 异常: " + e);
                    }
                }

                // 遍历子类
                List<Class<?>> subClasses = new ArrayList<>();
                for (Class<?> cls : inst.getAllLoadedClasses()) {
                    if (cls != null && serverClass.isAssignableFrom(cls) && cls != serverClass) {
                        subClasses.add(cls);
                        logToFile("找到子类: " + cls.getName());
                    }
                }
                logToFile("共找到 " + subClasses.size() + " 个子类");

                for (Class<?> cls : subClasses) {
                    logToFile("检查子类: " + cls.getName());
                    // 尝试常见字段
                    for (String fieldName : staticFieldNames) {
                        try {
                            Field field = cls.getDeclaredField(fieldName);
                            field.setAccessible(true);
                            logToFile("  尝试常见字段 " + fieldName + "...");
                            Object server = field.get(null);
                            if (server != null && cls.isInstance(server)) {
                                logToFile("通过静态字段 " + cls.getSimpleName() + "." + fieldName + " 获取服务器实例成功");
                                return server;
                            } else {
                                logToFile("  字段 " + fieldName + " 值为 null 或类型不匹配");
                            }
                        } catch (NoSuchFieldException ignored) {
                            logToFile("  字段 " + fieldName + " 不存在");
                        } catch (Exception e) {
                            logToFile("  访问字段 " + fieldName + " 异常: " + e);
                        }
                    }

                    // 搜索所有静态字段
                    for (Field field : cls.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                            Class<?> type = field.getType();
                            if (serverClass.isAssignableFrom(type)) {
                                field.setAccessible(true);
                                try {
                                    Object server = field.get(null);
                                    if (server != null && serverClass.isInstance(server)) {
                                        logToFile("通过静态字段 " + cls.getSimpleName() + "." + field.getName() + " 获取服务器实例成功");
                                        return server;
                                    }
                                } catch (Exception e) {
                                    logToFile("  访问字段 " + field.getName() + " 异常: " + e);
                                }
                            }
                        }
                    }

                    // 搜索所有静态方法
                    for (Method method : cls.getDeclaredMethods()) {
                        if (java.lang.reflect.Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0 &&
                                serverClass.isAssignableFrom(method.getReturnType())) {
                            method.setAccessible(true);
                            try {
                                Object server = method.invoke(null);
                                if (server != null) {
                                    logToFile("通过静态方法 " + cls.getSimpleName() + "." + method.getName() + "() 获取服务器实例成功");
                                    return server;
                                }
                            } catch (Exception e) {
                                logToFile("  调用静态方法 " + method.getName() + "() 异常: " + e);
                            }
                        }
                    }
                }
            } else {
                logToFile("未找到 MinecraftServer 类");
            }
        } catch (Exception e) {
            logToFile("从 MinecraftServer 类获取实例异常: " + e);
        }

        // 尝试从客户端 Minecraft 获取集成服务器（备用）
        try {
            Object mc = getMinecraftInstance(inst);
            if (mc != null) {
                logToFile("找到 Minecraft 客户端实例，尝试获取集成服务器...");
                try {
                    Method m = mc.getClass().getMethod("getSingleplayerServer");
                    Object server = m.invoke(mc);
                    if (server != null) {
                        logToFile("通过 Minecraft.getSingleplayerServer() 获取服务器实例成功");
                        return server;
                    }
                } catch (NoSuchMethodException ignored) {}
                try {
                    Method m = mc.getClass().getMethod("getIntegratedServer");
                    Object server = m.invoke(mc);
                    if (server != null) {
                        logToFile("通过 Minecraft.getIntegratedServer() 获取服务器实例成功");
                        return server;
                    }
                } catch (NoSuchMethodException ignored) {}
                try {
                    Method m = mc.getClass().getMethod("getServer");
                    Object server = m.invoke(mc);
                    if (server != null) {
                        logToFile("通过 Minecraft.getServer() 获取服务器实例成功");
                        return server;
                    }
                } catch (NoSuchMethodException ignored) {}
            } else {
                logToFile("未找到 Minecraft 客户端实例");
            }
        } catch (Exception e) {
            logToFile("从 Minecraft 获取服务器实例异常: " + e);
        }

        logToFile("所有获取服务器实例的方法均失败");
        return null;
    }

    /**
     * 在服务器实例上执行命令
     */
    private static boolean executeCommandOnServer(Object server, String command) {
        try {
            logToFile("服务器实例类: " + server.getClass().getName());

            Method getCommandsMethod = server.getClass().getMethod("getCommands");
            Object commands = getCommandsMethod.invoke(server);
            logToFile("Commands 对象类: " + commands.getClass().getName());

            Method createSourceMethod = server.getClass().getMethod("createCommandSourceStack");
            Object commandSource = createSourceMethod.invoke(server);
            Class<?> sourceClass = commandSource.getClass();
            logToFile("命令源类: " + sourceClass.getName());

            // 尝试 performPrefixedCommand
            try {
                Method performPrefixed = commands.getClass().getMethod("performPrefixedCommand", sourceClass, String.class);
                logToFile("找到方法 performPrefixedCommand，尝试执行...");
                performPrefixed.invoke(commands, commandSource, command);
                logToFile("通过 performPrefixedCommand 执行成功: " + command);
                return true;
            } catch (NoSuchMethodException e) {
                logToFile("performPrefixedCommand 方法不存在");
            } catch (Exception e) {
                logToFile("performPrefixedCommand 调用异常: " + e);
            }

            // 尝试 performCommand
            try {
                Method performCommand = commands.getClass().getMethod("performCommand", sourceClass, String.class);
                logToFile("找到方法 performCommand，尝试执行...");
                String cmdToUse = command.startsWith("/") ? command.substring(1) : command;
                performCommand.invoke(commands, commandSource, cmdToUse);
                logToFile("通过 performCommand 执行成功: " + cmdToUse);
                return true;
            } catch (NoSuchMethodException e) {
                logToFile("performCommand 方法不存在");
            } catch (Exception e) {
                logToFile("performCommand 调用异常: " + e);
            }

            // 尝试 CommandDispatcher.execute
            try {
                Method getDispatcher = commands.getClass().getMethod("getDispatcher");
                Object dispatcher = getDispatcher.invoke(commands);
                logToFile("获取到 CommandDispatcher: " + dispatcher.getClass().getName());
                Method execute = dispatcher.getClass().getMethod("execute", String.class, sourceClass);
                logToFile("找到方法 execute，尝试执行...");
                execute.invoke(dispatcher, command, commandSource);
                logToFile("通过 CommandDispatcher.execute 执行成功: " + command);
                return true;
            } catch (NoSuchMethodException e) {
                logToFile("CommandDispatcher.execute 方法不存在");
            } catch (Exception e) {
                logToFile("CommandDispatcher.execute 调用异常: " + e);
            }

            logToFile("所有命令执行方式均失败");
            return false;
        } catch (Exception e) {
            logToFile("executeCommandOnServer 异常: " + e);
            return false;
        }
    }

    // ==================== 客户端命令发送 ====================
    private static boolean executeViaPlayer(String command, Instrumentation inst) {
        logToFile("executeViaPlayer 被调用，命令: " + command);
        logToFile("尝试通过玩家发送命令...");
        try {
            Object player = getPlayer(inst);
            if (player == null) {
                logToFile("无法获取玩家对象");
                return false;
            }

            Object connection = getPlayerConnection(player);
            if (connection == null) {
                logToFile("无法获取玩家连接对象");
                return false;
            }
            logToFile("玩家连接对象类型: " + connection.getClass().getName());

            if (tryInvokeMethod(connection, "sendCommand", command)) {
                logToFile("通过 connection.sendCommand 发送成功: " + command);
                return true;
            }

            String fullCommand = command.startsWith("/") ? command : "/" + command;
            if (tryInvokeMethod(connection, "sendChat", fullCommand)) {
                logToFile("通过 connection.sendChat 发送成功: " + fullCommand);
                return true;
            }

            if (tryInvokeMethod(player, "sendCommand", command)) {
                logToFile("通过 player.sendCommand 发送成功: " + command);
                return true;
            }

            if (tryInvokeMethod(player, "chat", fullCommand)) {
                logToFile("通过 player.chat 发送成功: " + fullCommand);
                return true;
            }

            logToFile("所有玩家发送方式均失败");
            return false;
        } catch (Exception e) {
            logToFile("executeViaPlayer 异常: " + e);
            return false;
        }
    }

    private static boolean tryInvokeMethod(Object obj, String methodName, String arg) {
        try {
            Method method = obj.getClass().getMethod(methodName, String.class);
            method.setAccessible(true);
            method.invoke(obj, arg);
            return true;
        } catch (NoSuchMethodException e) {
            for (Method m : obj.getClass().getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == 1 &&
                        m.getParameterTypes()[0].isAssignableFrom(String.class)) {
                    try {
                        m.setAccessible(true);
                        m.invoke(obj, arg);
                        return true;
                    } catch (Exception ex) {
                        logToFile("模糊方法 " + methodName + " 调用异常: " + ex);
                    }
                }
            }
        } catch (Exception e) {
            logToFile("方法 " + methodName + " 调用异常: " + e);
        }
        return false;
    }

    private static Object getPlayerConnection(Object player) {
        String[] fieldNames = {"connection", "field_7114_a", "f_8945_", "netHandler"};
        for (String name : fieldNames) {
            try {
                Field f = player.getClass().getDeclaredField(name);
                f.setAccessible(true);
                return f.get(player);
            } catch (NoSuchFieldException ignored) {
            } catch (Exception e) {
                logToFile("获取 connection 字段 " + name + " 异常: " + e);
            }
        }
        return null;
    }

    private static Object getPlayer(Instrumentation inst) {
        try {
            Object mc = getMinecraftInstance(inst);
            if (mc == null) return null;

            Class<?> mcClass = mc.getClass();
            String[] fieldNames = {"player", "thePlayer", "field_71449_j", "f_91074_", "p_91106_"};
            for (String name : fieldNames) {
                try {
                    Field playerField = mcClass.getDeclaredField(name);
                    playerField.setAccessible(true);
                    return playerField.get(mc);
                } catch (NoSuchFieldException ignored) {}
            }
            logToFile("未找到玩家字段");
            return null;
        } catch (Exception e) {
            logToFile("getPlayer 异常: " + e);
            return null;
        }
    }

    private static Object getMinecraftInstance(Instrumentation inst) throws Exception {
        Class<?> mcClass = findLoadedClass(inst, "net.minecraft.client.Minecraft");
        if (mcClass == null) return null;

        try {
            Method getInstance = mcClass.getMethod("getInstance");
            return getInstance.invoke(null);
        } catch (NoSuchMethodException e1) {
            try {
                Method getMinecraft = mcClass.getMethod("getMinecraft");
                return getMinecraft.invoke(null);
            } catch (NoSuchMethodException e2) {
                Field instanceField = mcClass.getDeclaredField("instance");
                instanceField.setAccessible(true);
                return instanceField.get(null);
            }
        }
    }

    private static Class<?> findLoadedClass(Instrumentation inst, String className) {
        for (Class<?> cls : inst.getAllLoadedClasses()) {
            if (cls.getName().equals(className)) {
                return cls;
            }
        }
        return null;
    }

    // ==================== 规则导出 ====================
    private static void exportRulesToJson(Instrumentation inst) {
        logToFile("开始导出规则...");
        List<Map<String, Object>> rulesList = new ArrayList<>();
        try {
            logToFile("正在查找 CarpetServer 类...");
            Class<?> carpetServerClass = findLoadedClass(inst, "carpet.CarpetServer");
            if (carpetServerClass == null) {
                logToFile("Carpet 未加载，无法导出规则");
                return;
            }
            logToFile("找到 CarpetServer 类: " + carpetServerClass.getName());

            Field settingsManagerField = carpetServerClass.getField("settingsManager");
            logToFile("获取 settingsManager 字段: " + settingsManagerField.getName());
            Object settingsManager = settingsManagerField.get(null);
            logToFile("settingsManager 对象: " + settingsManager.getClass().getName());

            Method getRules = settingsManager.getClass().getMethod("getRules");
            logToFile("调用 getRules 方法");
            List<?> rules = (List<?>) getRules.invoke(settingsManager);
            logToFile("获取到规则列表，数量: " + rules.size());

            for (Object rule : rules) {
                Map<String, Object> map = new HashMap<>();

                String name = getFieldValue(rule, "name", "ruleName", "id");
                map.put("name", name != null ? name : "");

                String desc = getFieldValue(rule, "description", "desc", "info");
                map.put("description", desc != null ? desc : "");

                String value = getFieldValue(rule, "value", "_value", "currentValue", "defaultValue", "storedValue");
                map.put("value", value != null ? value : "");

                String def = getFieldValue(rule, "default", "defaultValue", "def");
                map.put("default", def != null ? def : "");

                // categories
                List<String> categories = new ArrayList<>();
                Object catObj = null;
                try {
                    Field catField = rule.getClass().getDeclaredField("categories");
                    catField.setAccessible(true);
                    catObj = catField.get(rule);
                } catch (NoSuchFieldException ignored) {}
                if (catObj == null) {
                    try {
                        Method getCat = rule.getClass().getMethod("getCategories");
                        catObj = getCat.invoke(rule);
                    } catch (NoSuchMethodException ignored) {}
                }
                if (catObj != null) {
                    if (catObj instanceof String[]) {
                        categories.addAll(Arrays.asList((String[]) catObj));
                    } else if (catObj instanceof Collection) {
                        for (Object o : (Collection<?>) catObj) {
                            categories.add(o.toString());
                        }
                    } else if (catObj instanceof String) {
                        String s = (String) catObj;
                        if (s.contains(",")) {
                            categories.addAll(Arrays.asList(s.split(",")));
                        } else {
                            categories.add(s);
                        }
                    }
                }
                if (!categories.isEmpty()) {
                    map.put("categories", categories);
                }

                // options
                List<String> options = new ArrayList<>();
                Object optObj = null;
                try {
                    Field optField = rule.getClass().getDeclaredField("options");
                    optField.setAccessible(true);
                    optObj = optField.get(rule);
                } catch (NoSuchFieldException ignored) {}
                if (optObj == null) {
                    try {
                        Method getOpts = rule.getClass().getMethod("getOptions");
                        optObj = getOpts.invoke(rule);
                    } catch (NoSuchMethodException ignored) {}
                }
                if (optObj != null) {
                    if (optObj instanceof String[]) {
                        for (String s : (String[]) optObj) {
                            options.add(s.trim());
                        }
                    } else if (optObj instanceof Collection) {
                        for (Object o : (Collection<?>) optObj) {
                            options.add(o.toString().trim());
                        }
                    } else if (optObj instanceof String) {
                        String s = (String) optObj;
                        s = s.trim();
                        if (s.startsWith("[") && s.endsWith("]")) {
                            String inner = s.substring(1, s.length() - 1);
                            String[] parts = inner.split(",");
                            for (String part : parts) {
                                part = part.trim();
                                if (part.startsWith("\"") && part.endsWith("\"")) {
                                    part = part.substring(1, part.length() - 1);
                                }
                                options.add(part);
                            }
                        } else {
                            options.add(s);
                        }
                    }
                }

                if (!options.isEmpty()) {
                    map.put("options", options);
                }

                rulesList.add(map);
            }

            logToFile("构建 JSON 字符串，规则数量: " + rulesList.size());
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < rulesList.size(); i++) {
                Map<String, Object> rule = rulesList.get(i);
                json.append("{");
                json.append("\"name\":\"").append(escapeJson(String.valueOf(rule.get("name")))).append("\",");
                json.append("\"description\":\"").append(escapeJson(String.valueOf(rule.get("description")))).append("\",");
                json.append("\"value\":\"").append(escapeJson(String.valueOf(rule.get("value")))).append("\",");
                json.append("\"default\":\"").append(escapeJson(String.valueOf(rule.get("default")))).append("\"");

                if (rule.containsKey("categories")) {
                    json.append(",\"categories\":[");
                    List<String> cats = (List<String>) rule.get("categories");
                    for (int j = 0; j < cats.size(); j++) {
                        json.append("\"").append(escapeJson(cats.get(j))).append("\"");
                        if (j < cats.size() - 1) json.append(",");
                    }
                    json.append("]");
                }

                if (rule.containsKey("options")) {
                    json.append(",\"options\":[");
                    List<String> opts = (List<String>) rule.get("options");
                    for (int j = 0; j < opts.size(); j++) {
                        json.append("\"").append(escapeJson(opts.get(j))).append("\"");
                        if (j < opts.size() - 1) json.append(",");
                    }
                    json.append("]");
                }

                json.append("}");
                if (i < rulesList.size() - 1) json.append(",");
            }
            json.append("]");

            logToFile("写入 JSON 到文件: " + RULES_JSON);
            Files.write(RULES_JSON, json.toString().getBytes());
            logToFile("规则已成功导出到: " + RULES_JSON);
        } catch (Exception e) {
            logToFile("导出规则失败: " + e);
            // 记录堆栈
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            logToFile("堆栈: " + sw.toString());
        }
    }

    private static String getFieldValue(Object obj, String... possibleNames) {
        for (String name : possibleNames) {
            try {
                Field f = obj.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object val = f.get(obj);
                return val != null ? val.toString() : null;
            } catch (NoSuchFieldException ignored) {
            } catch (Exception e) {
                logToFile("获取字段 " + name + " 异常: " + e);
            }
        }
        return null;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static void logToFile(String msg) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String line = timestamp + " - " + msg + System.lineSeparator();
            Files.write(LOG_FILE, line.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("[CarpetAgent] " + msg);
            e.printStackTrace();
        }
    }
}