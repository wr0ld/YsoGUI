package ysogui.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ysogui.model.ChainMeta;
import ysogui.model.ChainMeta.ParamType;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * 链元信息管理器。
 *
 * 加载优先级：
 *   1. 用户自定义 → 项目目录 chain-meta/custom.json（可读写，优先）
 *   2. 内置默认  → resources/builtin-chain-meta.json（只读）
 *   3. 都没有    → 不显示额外信息
 */
public class ChainMetaManager {

    private static final Logger LOG = Logger.getLogger(ChainMetaManager.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BUILTIN_RESOURCE = "builtin-chain-meta.json";
    private static final Set<String> CUSTOM_CLASS_INJECTION_SUPPORTED = Collections.unmodifiableSet(
        new LinkedHashSet<String>(Arrays.asList(
            "CommonsCollections2",
            "CommonsCollections4",
            "CommonsBeanutils1",
            "CommonsBeanutils183NOCC",
            "CommonsBeanutils192NOCC",
            "CommonsBeanutils192WithDualTreeBidiMap"
        )));
    /** 内置元信息缓存 */
    private static final Map<String, ChainMeta> BUILTIN = new LinkedHashMap<>();

    /** 用户自定义元信息缓存 */
    private static final Map<String, ChainMeta> CUSTOM = new LinkedHashMap<>();

    static {
        loadBuiltin();
        loadCustom();
    }

    // ──── 加载逻辑 ────────────────────────────────────────────────────────────

    private static void loadBuiltin() {
        InputStream is = ChainMetaManager.class.getClassLoader()
                                               .getResourceAsStream(BUILTIN_RESOURCE);
        if (is == null) {
            LOG.info("未找到内置链元信息文件: " + BUILTIN_RESOURCE);
            return;
        }
        try (InputStream stream = is) {
            Map<String, ChainMeta> loaded = MAPPER.readValue(
                    stream, new TypeReference<Map<String, ChainMeta>>() {});
            BUILTIN.putAll(loaded);
            LOG.info("内置链元信息加载完成，共 " + BUILTIN.size() + " 条");
        } catch (Exception e) {
            LOG.warning("解析内置链元信息失败: " + e.getMessage());
        }
    }

    private static void loadCustom() {
        Path path = AppPaths.chainMetaFile();
        if (!Files.exists(path)) return;
        try (InputStream stream = Files.newInputStream(path)) {
            Map<String, ChainMeta> loaded = MAPPER.readValue(
                    stream, new TypeReference<Map<String, ChainMeta>>() {});
            CUSTOM.putAll(loaded);
            LOG.info("自定义链元信息加载完成，共 " + CUSTOM.size() + " 条");
        } catch (Exception e) {
            LOG.warning("解析自定义链元信息失败: " + e.getMessage());
        }
    }

    // ──── Public API ─────────────────────────────────────────────────────────

    /**
     * 获取指定链的元信息（自定义优先于内置）
     * @return ChainMeta 或 null（都没有时）
     */
    public static ChainMeta getMeta(String chainName) {
        ChainMeta custom = CUSTOM.get(chainName);
        if (custom != null) return custom;
        return BUILTIN.get(chainName);
    }

    /** 是否支持 Y4er/ysoserial 的 CLASS: 内置模板路线 */
    public static boolean supportsBuiltinTemplate(String chainName) {
        ChainMeta meta = getMeta(chainName);
        return meta != null && meta.isMemshellSupported();
    }

    /** 获取不支持 CLASS: 内置模板路线的原因 */
    public static String getBuiltinTemplateReason(String chainName) {
        ChainMeta meta = getMeta(chainName);
        if (meta == null) return "未知链，无法确定是否支持 CLASS: 内置模板";
        if (meta.isMemshellSupported()) return null;
        return meta.getMemshellReason() != null ? meta.getMemshellReason() : "该链不支持 TemplatesImpl 字节码路线，无法使用 CLASS: 内置模板";
    }

    /** 是否稳定支持 GUI 自定义 classBytes 注入 */
    public static boolean supportsCustomClassInjection(String chainName) {
        return CUSTOM_CLASS_INJECTION_SUPPORTED.contains(chainName);
    }

    /** 获取不支持 GUI 自定义 classBytes 注入的原因 */
    public static String getCustomClassInjectionReason(String chainName) {
        if (chainName == null || chainName.trim().isEmpty()) {
            return "未选择利用链";
        }
        if (supportsCustomClassInjection(chainName)) {
            return null;
        }
        return "当前“自定义 classBytes 注入”仅稳定适配 CB / CC2-like 包装链；"
            + chainName + " 暂未做可靠封装验证。"
            + " 内置模板可继续尝试 ysoserial 的 CLASS: 写法，自定义模板建议先使用 CB1 / CB183 / CB192 / CC2 / CC4。";
    }

    /** 兼容旧调用：等同于 supportsBuiltinTemplate() */
    public static boolean isMemshellSupported(String chainName) {
        return supportsBuiltinTemplate(chainName);
    }

    /** 兼容旧调用：等同于 getBuiltinTemplateReason() */
    public static String getMemshellReason(String chainName) {
        return getBuiltinTemplateReason(chainName);
    }

    /** 获取所有内置元信息 */
    public static Map<String, ChainMeta> getBuiltinMetas() {
        return Collections.unmodifiableMap(BUILTIN);
    }

    /** 获取所有自定义元信息 */
    public static Map<String, ChainMeta> getCustomMetas() {
        return Collections.unmodifiableMap(CUSTOM);
    }

    /** 保存用户自定义元信息到文件 */
    public static void saveCustom(Map<String, ChainMeta> customMetas) {
        try {
            Files.createDirectories(AppPaths.chainMetaDir());
            Path path = AppPaths.chainMetaFile();
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), customMetas);
            CUSTOM.clear();
            CUSTOM.putAll(customMetas);
            LOG.info("自定义链元信息已保存，共 " + CUSTOM.size() + " 条");
        } catch (Exception e) {
            LOG.severe("保存自定义链元信息失败: " + e.getMessage());
        }
    }

    /** 添加或更新一条自定义元信息 */
    public static void putCustom(ChainMeta meta) {
        CUSTOM.put(meta.getName(), meta);
        // 立即持久化
        saveCustom(CUSTOM);
    }

    /** 删除一条自定义元信息 */
    public static void removeCustom(String chainName) {
        CUSTOM.remove(chainName);
        saveCustom(CUSTOM);
    }

    // ──── 参数类型推断 ────────────────────────────────────────────────────────

    /**
     * 获取链的参数类型（优先从元信息读取，否则根据链名推断）
     */
    public static ParamType getParamType(String chainName) {
        ChainMeta meta = getMeta(chainName);
        if (meta != null && meta.getParamType() != null) {
            return meta.getParamType();
        }
        return inferParamType(chainName);
    }

    /**
     * 获取链的参数提示文本
     */
    public static String getParamHint(String chainName) {
        ChainMeta meta = getMeta(chainName);
        if (meta != null && meta.getParamHint() != null) {
            return meta.getParamHint();
        }
        ParamType type = getParamType(chainName);
        switch (type) {
            case DNSLOG:    return "输入 DNSLog 地址，如 http://xxx.dnslog.cn";
            case JNDI:      return "输入 JNDI/LDAP 地址，如 ldap://host:port/obj";
            case HOSTPORT:  return "输入 host:port，如 127.0.0.1:1099";
            case URL:       return "输入远程 URL，如 http://host/payload";
            case CLASSNAME: return "输入 CLASS:模板类名 或 系统命令";
            default:        return "输入命令，如 calc 或 whoami";
        }
    }

    /**
     * 根据链名推断参数类型
     */
    private static ParamType inferParamType(String name) {
        if (name == null) return ParamType.COMMAND;

        // URLDNS: 需要 DNSLog 地址
        if ("URLDNS".equals(name)) return ParamType.DNSLOG;

        // JRMPClient: 需要 host:port
        if ("JRMPClient".equals(name)) return ParamType.HOSTPORT;

        // C3P0: 支持 JNDI 引用或 hex 序列化 URL
        if ("C3P0".equals(name)) return ParamType.JNDI;

        // Shiro 链：通常配合 CB1 使用命令
        if (name.startsWith("Shiro")) return ParamType.COMMAND;

        // 支持 CLASS: 内置模板的链默认提示 CLASS
        if (supportsBuiltinTemplate(name)) return ParamType.CLASSNAME;

        return ParamType.COMMAND;
    }

    /**
     * 获取参数类型的默认值
     */
    public static String getParamDefault(String chainName) {
        ParamType type = getParamType(chainName);
        switch (type) {
            case DNSLOG:    return "http://xxx.dnslog.cn";
            case JNDI:      return "ldap://127.0.0.1:1389/obj";
            case HOSTPORT:  return "127.0.0.1:1099";
            case URL:       return "http://127.0.0.1:8080/payload";
            case CLASSNAME: return "calc";
            default:        return "calc";
        }
    }
}
