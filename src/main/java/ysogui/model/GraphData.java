package ysogui.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条链的调用图数据（节点 + 边）
 * 可序列化为 JSON 保存到本地
 */
public class GraphData {

    public List<NodeData> nodes = new ArrayList<>();
    public List<EdgeData> edges = new ArrayList<>();

    // ───────────────────────────────── 节点 ──────────────────────────────────

    public static class NodeData {
        public String id;
        public String label;   // 显示文本，如 "LazyMap\n::get()"
        public double x;
        public double y;

        public NodeData() {}

        public NodeData(String id, String label, double x, double y) {
            this.id = id;
            this.label = label;
            this.x = x;
            this.y = y;
        }
    }

    // ───────────────────────────────── 边 ────────────────────────────────────

    public static class EdgeData {
        public String fromId;
        public String toId;
        public String label;   // 可选标注

        public EdgeData() {}

        public EdgeData(String fromId, String toId) {
            this.fromId = fromId;
            this.toId = toId;
            this.label = "";
        }

        public EdgeData(String fromId, String toId, String label) {
            this.fromId = fromId;
            this.toId = toId;
            this.label = label;
        }
    }
}
