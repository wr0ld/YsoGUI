package ysogui.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ysogui.model.MemshellConfig;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * 内存马配置管理器。
 *
 * 加载优先级：
 *   1. 用户自定义 → 项目目录 memshells/custom.json（可读写，优先）
 *   2. 内置默认  → resources/builtin-memshells.json（只读）
 */
public class MemshellManager {

    private static final Logger LOG = Logger.getLogger(MemshellManager.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BUILTIN_RESOURCE = "builtin-memshells.json";
    /** 内置内存马缓存 */
    private static final Map<String, MemshellConfig> BUILTIN = new LinkedHashMap<>();

    /** 用户自定义内存马缓存 */
    private static final Map<String, MemshellConfig> CUSTOM = new LinkedHashMap<>();
    /** 运行时从 ysoserial 模板目录自动发现的内存马缓存 */
    private static final Map<String, MemshellConfig> DISCOVERED = new LinkedHashMap<>();

    static {
        loadBuiltin();
        loadCustom();
    }

    // ──── 加载逻辑 ────────────────────────────────────────────────────────────

    private static void loadBuiltin() {
        InputStream is = MemshellManager.class.getClassLoader()
                                              .getResourceAsStream(BUILTIN_RESOURCE);
        if (is == null) {
            LOG.info("未找到内置内存马配置: " + BUILTIN_RESOURCE);
            return;
        }
        try (InputStream stream = is) {
            Map<String, MemshellConfig> loaded = MAPPER.readValue(
                    stream, new TypeReference<Map<String, MemshellConfig>>() {});
            normalizeConfigs(loaded, true);
            BUILTIN.putAll(loaded);
            LOG.info("内置内存马配置加载完成，共 " + BUILTIN.size() + " 条");
        } catch (Exception e) {
            LOG.warning("解析内置内存马配置失败: " + e.getMessage());
        }
    }

    private static void loadCustom() {
        Path path = AppPaths.memshellConfigFile();
        if (!Files.exists(path)) return;
        try (InputStream stream = Files.newInputStream(path)) {
            Map<String, MemshellConfig> loaded = MAPPER.readValue(
                    stream, new TypeReference<Map<String, MemshellConfig>>() {});
            normalizeConfigs(loaded, false);
            CUSTOM.putAll(loaded);
            LOG.info("自定义内存马配置加载完成，共 " + CUSTOM.size() + " 条");
        } catch (Exception e) {
            LOG.warning("解析自定义内存马配置失败: " + e.getMessage());
        }
    }

    // ──── Public API ─────────────────────────────────────────────────────────

    /** 获取所有内存马（自定义在前，内置在后） */
    public static List<MemshellConfig> getAll() {
        List<MemshellConfig> result = new ArrayList<>();
        result.addAll(CUSTOM.values());
        result.addAll(BUILTIN.values());
        for (Map.Entry<String, MemshellConfig> entry : DISCOVERED.entrySet()) {
            if (!CUSTOM.containsKey(entry.getKey()) && !BUILTIN.containsKey(entry.getKey())) {
                result.add(entry.getValue());
            }
        }
        return result;
    }

    /** 获取指定名称的内存马配置（自定义优先） */
    public static MemshellConfig get(String name) {
        MemshellConfig custom = CUSTOM.get(name);
        if (custom != null) return custom;
        MemshellConfig builtin = BUILTIN.get(name);
        if (builtin != null) return builtin;
        return DISCOVERED.get(name);
    }

    /** 添加或更新一条自定义内存马配置 */
    public static void putCustom(MemshellConfig config) {
        config.setBuiltin(false);
        normalizeConfig(config, false);
        CUSTOM.put(config.getName(), config);
        saveCustom();
    }

    /** 删除一条自定义内存马配置 */
    public static void removeCustom(String name) {
        CUSTOM.remove(name);
        saveCustom();
    }

    /** 保存自定义内存马配置到文件 */
    public static void saveCustom() {
        try {
            Files.createDirectories(AppPaths.memshellDir());
            Path path = AppPaths.memshellConfigFile();
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), CUSTOM);
            LOG.info("自定义内存马配置已保存，共 " + CUSTOM.size() + " 条");
        } catch (Exception e) {
            LOG.severe("保存自定义内存马配置失败: " + e.getMessage());
        }
    }

    /** 获取所有自定义内存马 */
    public static Map<String, MemshellConfig> getCustoms() {
        return Collections.unmodifiableMap(CUSTOM);
    }

    /** 获取所有内置内存马 */
    public static Map<String, MemshellConfig> getBuiltins() {
        return Collections.unmodifiableMap(BUILTIN);
    }

    /** 用运行时自动发现的模板刷新补充内置列表，不写入磁盘 */
    public static void replaceDiscoveredBuiltins(Collection<MemshellConfig> configs) {
        DISCOVERED.clear();
        if (configs == null) {
            return;
        }
        for (MemshellConfig config : configs) {
            if (config == null || config.getName() == null || config.getName().trim().isEmpty()) {
                continue;
            }
            config.setBuiltin(true);
            normalizeConfig(config, true);
            if (CUSTOM.containsKey(config.getName()) || BUILTIN.containsKey(config.getName())) {
                continue;
            }
            DISCOVERED.put(config.getName(), config);
        }
        LOG.info("运行时自动发现内存马模板，共 " + DISCOVERED.size() + " 条");
    }

    public static Map<String, MemshellConfig> getDiscoveredBuiltins() {
        return Collections.unmodifiableMap(DISCOVERED);
    }

    public static void exportCustom(Path path) throws Exception {
        if (path == null) {
            throw new IllegalArgumentException("导出路径不能为空");
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), CUSTOM);
    }

    public static int importCustom(Path path, boolean replaceExisting) throws Exception {
        if (path == null || !Files.exists(path)) {
            throw new FileNotFoundException("导入文件不存在");
        }
        Map<String, MemshellConfig> loaded;
        try (InputStream stream = Files.newInputStream(path)) {
            loaded = MAPPER.readValue(stream, new TypeReference<Map<String, MemshellConfig>>() {});
        }
        if (loaded == null || loaded.isEmpty()) {
            return 0;
        }
        normalizeConfigs(loaded, false);
        if (replaceExisting) {
            CUSTOM.clear();
        }
        int count = 0;
        for (Map.Entry<String, MemshellConfig> entry : loaded.entrySet()) {
            MemshellConfig config = entry.getValue();
            if (config == null || isBlank(config.getName())) {
                continue;
            }
            CUSTOM.put(config.getName(), config);
            count++;
        }
        saveCustom();
        return count;
    }

    private static void normalizeConfigs(Map<String, MemshellConfig> configs, boolean builtin) {
        if (configs == null) {
            return;
        }
        for (Map.Entry<String, MemshellConfig> entry : configs.entrySet()) {
            MemshellConfig config = entry.getValue();
            if (config == null) {
                continue;
            }
            if (isBlank(config.getName())) {
                config.setName(entry.getKey());
            }
            normalizeConfig(config, builtin);
        }
    }

    private static void normalizeConfig(MemshellConfig config, boolean builtin) {
        if (config == null) {
            return;
        }

        config.setBuiltin(builtin);

        if (config.isEchoMode()) {
            if (isBlank(config.getRequestMethod())) {
                config.setRequestMethod("GET");
            } else {
                config.setRequestMethod(config.getRequestMethod().trim().toUpperCase(Locale.ROOT));
            }
            if (isBlank(config.getTriggerSource())) {
                config.setTriggerSource("header");
            } else {
                config.setTriggerSource(config.getTriggerSource().trim().toLowerCase(Locale.ROOT));
            }
            if (isBlank(config.getTriggerName())) {
                if (!isBlank(config.getTriggerHeader())) {
                    config.setTriggerName(config.getTriggerHeader().trim());
                } else {
                    config.setTriggerName("X-Cmd");
                }
            }
            if (isBlank(config.getTriggerHeader())
                && "header".equalsIgnoreCase(config.getTriggerSource())) {
                config.setTriggerHeader(config.getTriggerName());
            }
            if (isBlank(config.getTriggerPath())) {
                config.setTriggerPath("/");
            }
            if (isBlank(config.getResponseMode())) {
                config.setResponseMode("BODY_RAW");
            } else {
                config.setResponseMode(config.getResponseMode().trim().toUpperCase(Locale.ROOT));
            }
        } else if (config.isConnectMode()) {
            if (isBlank(config.getTool())) {
                config.setTool("godzilla");
            }
        }
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
