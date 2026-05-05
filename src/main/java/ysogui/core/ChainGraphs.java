package ysogui.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ysogui.model.GraphData;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;

/**
 * 内置链调用图的加载器。
 *
 * 数据文件说明（两种，互不干扰）：
 *
 *   内置图  → resources/builtin-graphs.json（随 jar 打包，只读）
 *             格式：{ "链名": { "nodes": [...], "edges": [...] }, ... }
 *             所有内置链合并在一个文件里，新增链只需编辑该文件。
 *
 *   自定义图 → 项目目录 custom-graphs.json（用户本地，可读写）
 *             由 GraphEditorPanel 负责读写，本类只在 hasGraph() 中做快速探测。
 *
 * 加载流程：
 *  1. 静态初始化块通过 ClassLoader 读取 builtin-graphs.json
 *  2. Jackson 反序列化为 Map<String, GraphData>，整体缓存到 GRAPHS
 *  3. 读取失败时打日志，GRAPHS 保持空 Map，不崩溃
 */
public class ChainGraphs {

    private static final Logger LOG    = Logger.getLogger(ChainGraphs.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 内置图资源路径（classpath 根目录下） */
    private static final String BUILTIN_RESOURCE = "builtin-graphs.json";

    /** 链名 → 图数据缓存 */
    private static final Map<String, GraphData> GRAPHS = new LinkedHashMap<>();

    static {
        loadBuiltin();
    }

    // ──── 加载逻辑 ────────────────────────────────────────────────────────────

    /**
     * 从 classpath 读取 builtin-graphs.json，
     * 将其中所有链的图数据一次性反序列化并缓存。
     */
    private static void loadBuiltin() {
        InputStream is = ChainGraphs.class.getClassLoader()
                                          .getResourceAsStream(BUILTIN_RESOURCE);
        if (is == null) {
            LOG.severe("找不到内置图文件: " + BUILTIN_RESOURCE
                    + "（请确认打包时 resources 目录已包含该文件）");
            return;
        }
        try (InputStream stream = is) {
            // 反序列化为 Map<String, GraphData>
            Map<String, GraphData> loaded = MAPPER.readValue(
                    stream,
                    new TypeReference<Map<String, GraphData>>() {}
            );
            GRAPHS.putAll(loaded);
            LOG.info("内置链图加载完成，共 " + GRAPHS.size() + " 条");
        } catch (Exception e) {
            LOG.severe("解析内置图文件失败: " + e.getMessage());
        }
    }

    // ──── Public API ─────────────────────────────────────────────────────────

    /**
     * 获取指定链的内置调用图，不存在时返回 null。
     * 调用方根据返回值决定显示内置图（只读）还是空白可编辑画布。
     */
    public static GraphData getGraph(String chainName) {
        return GRAPHS.get(chainName);
    }

    /** 判断某条链是否有内置调用图或自定义调用图（有节点才算有图） */
    public static boolean hasGraph(String chainName) {
        // 内置图
        GraphData builtin = GRAPHS.get(chainName);
        if (builtin != null && !builtin.nodes.isEmpty()) return true;
        // 自定义图
        try {
            Path customPath = AppPaths.customGraphsFile();
            if (Files.exists(customPath)) {
                Map<String, GraphData> all = MAPPER.readValue(customPath.toFile(),
                    new TypeReference<Map<String, GraphData>>() {});
                if (all != null) {
                    GraphData gd = all.get(chainName);
                    return gd != null && !gd.nodes.isEmpty();
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** 返回所有已加载的内置链名集合（不可修改） */
    public static Set<String> getKnownChains() {
        return Collections.unmodifiableSet(GRAPHS.keySet());
    }

    /** 已加载的内置链数量，供状态栏显示 */
    public static int size() {
        return GRAPHS.size();
    }
}
