package ysogui.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.stage.FileChooser;
import ysogui.model.GraphData;
import ysogui.model.GraphData.EdgeData;
import ysogui.model.GraphData.NodeData;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 调用图可视化编辑器面板
 *
 * 功能：
 *   - 可视化展示和编辑 gadget chain 调用图（节点拖拽/增删/连边/标签编辑）
 *   - 支持撤销（最多 50 步）、缩放（Ctrl+滚轮 或 工具栏按钮）、保存/导入/重置
 *   - 自定义图保存到 custom-graphs.json，优先于内置图加载
 *   - 快捷键：Ctrl+Z 撤销、Escape 回到查看模式、Delete 删除选中节点或边
 */
public class GraphEditorPanel extends VBox {

    // ─── 常量 ─────────────────────────────────────────────────────
    private static final double NODE_W  = 220;
    private static final double NODE_H  = 50;
    private static final Color  BG       = Color.web("#ffffff");
    private static final Color  NODE_FILL   = Color.web("#dce8f5");
    private static final Color  NODE_STROKE = Color.web("#2563a0");
    private static final Color  NODE_SEL    = Color.web("#e07020");
    private static final Color  EDGE_COLOR  = Color.web("#2563a0");
    private static final Color  TEXT_COLOR  = Color.web("#1a1a1a");
    private static final double CANVAS_W = 800;
    private static final double CANVAS_H = 1200;

    // ─── 编辑模式 ─────────────────────────────────────────────────
    private enum EditMode { VIEW, ADD_NODE, ADD_EDGE, DELETE }

    private Pane canvas;
    private ScrollPane scroll;
    private Label modeLabel;
    private Label sourceLabel;  // 连边时显示已选源节点
    private Label zoomLabel;    // 缩放百分比
    private String currentChainName = "";
    private boolean isEditable = true;  // 当前图是否可编辑（自定义图和内置图均可编辑）
    private boolean isShowingCustom = false;  // 当前显示的是否为自定义图

    // ─── 缩放 ────────────────────────────────────────────────────
    private double zoomLevel = 1.0;
    private static final double ZOOM_MIN = 0.2;
    private static final double ZOOM_MAX = 3.0;
    private static final double ZOOM_STEP = 0.15;
    private javafx.scene.transform.Scale canvasScale;

    private EditMode mode = EditMode.VIEW;
    private NodePane selectedNode = null;
    private NodePane edgeSource   = null; // 连边模式下已选的第一个节点
    private EdgeLine selectedEdge = null;

    private final List<NodePane>  nodePanes  = new ArrayList<>();
    private final List<EdgeLine>  edgeLines  = new ArrayList<>();
    private final ObjectMapper    mapper     = new ObjectMapper();

    // ─── 撤销栈 ──────────────────────────────────────────────────
    private final java.util.Deque<GraphData> undoStack = new java.util.ArrayDeque<>();
    private static final int MAX_UNDO = 50;

    /** 保存当前图快照到撤销栈 */
    private void pushUndo() {
        undoStack.push(exportCurrentGraph());
        if (undoStack.size() > MAX_UNDO) undoStack.removeLast();
    }

    /** 从撤销栈弹出上一状态并恢复（不压栈） */
    private void undo() {
        if (undoStack.isEmpty()) return;
        GraphData prev = undoStack.pop();
        loadGraph(prev, true);
    }

    // ─── 缩放方法 ────────────────────────────────────────────────

    private void zoomIn() {
        setZoom(zoomLevel + ZOOM_STEP);
    }

    private void zoomOut() {
        setZoom(zoomLevel - ZOOM_STEP);
    }

    private void setZoom(double level) {
        zoomLevel = Math.round(Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, level)) * 100.0) / 100.0;
        canvasScale.setX(zoomLevel);
        canvasScale.setY(zoomLevel);
        // 同步调整画布最小/首选尺寸，使 ScrollPane 滚动范围匹配
        canvas.setMinSize(CANVAS_W * zoomLevel, CANVAS_H * zoomLevel);
        canvas.setPrefSize(CANVAS_W * zoomLevel, CANVAS_H * zoomLevel);
        if (zoomLabel != null) {
            zoomLabel.setText(Math.round(zoomLevel * 100) + "%");
        }
    }

    private void resetZoom() {
        setZoom(1.0);
    }

    // ─── 构造器 ──────────────────────────────────────────────────

    public GraphEditorPanel() {
        setStyle("-fx-background-color: #ffffff;");
        getChildren().addAll(buildToolbar(), buildCanvas());
        setFocusTraversable(true);
        addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);
    }

    // ─── 工具栏 ──────────────────────────────────────────────────

    private VBox buildToolbar() {
        Button btnAddNode = styledBtn("+ 节点");
        Button btnAddEdge = styledBtn("连边");
        Button btnDelete  = styledBtn("删除");
        Button btnUndo    = styledBtn("撤销");
        Button btnSave    = styledBtn("保存图");
        Button btnLoad    = styledBtn("导入 JSON");
        Button btnClear   = styledBtn("清空画布");
        Button btnReset   = styledBtn("恢复内置图");
        btnReset.setStyle("-fx-background-color: #fff0e0; -fx-text-fill: #b06000; -fx-border-color: #d0a060; " +
                          "-fx-border-radius: 3; -fx-background-radius: 3; -fx-font-size: 12;");
        btnReset.setOnMouseEntered(e -> btnReset.setStyle(btnReset.getStyle().replace("#fff0e0", "#ffe8cc")));
        btnReset.setOnMouseExited(e  -> btnReset.setStyle(btnReset.getStyle().replace("#ffe8cc", "#fff0e0")));
        btnReset.setOnAction(e -> resetToBuiltIn());

        modeLabel = new Label("当前模式：查看");
        modeLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12;");
        sourceLabel = new Label("");
        sourceLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 11;");

        Button btnZoomOut = new Button("-");
        btnZoomOut.setStyle("-fx-background-color: #e0e0e0; -fx-text-fill: #333; -fx-border-color: #bbb; " +
                            "-fx-border-radius: 3; -fx-background-radius: 3; -fx-font-size: 12; -fx-padding: 2 6;");
        btnZoomOut.setOnAction(e -> zoomOut());

        zoomLabel = new Label("100%");
        zoomLabel.setStyle("-fx-text-fill: #555; -fx-font-size: 11; -fx-pref-width: 40; -fx-alignment: CENTER;");

        Button btnZoomIn = new Button("+");
        btnZoomIn.setStyle("-fx-background-color: #e0e0e0; -fx-text-fill: #333; -fx-border-color: #bbb; " +
                           "-fx-border-radius: 3; -fx-background-radius: 3; -fx-font-size: 12; -fx-padding: 2 6;");
        btnZoomIn.setOnAction(e -> zoomIn());

        Button btnZoomReset = new Button("1:1");
        btnZoomReset.setStyle("-fx-background-color: #e0e0e0; -fx-text-fill: #333; -fx-border-color: #bbb; " +
                              "-fx-border-radius: 3; -fx-background-radius: 3; -fx-font-size: 11; -fx-padding: 2 5;");
        btnZoomReset.setOnAction(e -> resetZoom());

        btnAddNode.setOnAction(e -> setMode(EditMode.ADD_NODE));
        btnAddEdge.setOnAction(e -> setMode(EditMode.ADD_EDGE));
        btnDelete.setOnAction(e -> setMode(EditMode.DELETE));
        btnUndo.setOnAction(e -> undo());
        btnSave.setOnAction(e -> saveGraph());
        btnLoad.setOnAction(e -> loadGraphFromFile());
        btnClear.setOnAction(e -> {
            if (confirmClear()) { pushUndo(); clearCanvas(); }
        });

        // 第一行：编辑按钮 + 模式标签 + 缩放控件
        HBox row1 = new HBox(4, btnAddNode, btnAddEdge, btnDelete, btnUndo,
                              new Separator(), modeLabel, sourceLabel,
                              new Separator(), btnZoomOut, zoomLabel, btnZoomIn, btnZoomReset);
        row1.setStyle("-fx-background-color: #e8e8e8; -fx-padding: 4 8 2 8; -fx-alignment: CENTER_LEFT;");

        // 第二行：保存/导入/清空/恢复按钮
        HBox row2 = new HBox(4, btnSave, btnLoad, btnClear, btnReset);
        row2.setStyle("-fx-background-color: #e8e8e8; -fx-padding: 2 8 4 8; -fx-border-color: transparent transparent #ccc transparent;");

        VBox toolbar = new VBox(row1, row2);
        toolbar.setStyle("-fx-background-color: #e8e8e8;");
        return toolbar;
    }

    private Button styledBtn(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: #e0e0e0; -fx-text-fill: #333; -fx-border-color: #bbb; " +
                   "-fx-border-radius: 3; -fx-background-radius: 3; -fx-font-size: 12;");
        b.setOnMouseEntered(e -> b.setStyle(b.getStyle().replace("#e0e0e0", "#d0d0d0")));
        b.setOnMouseExited(e  -> b.setStyle(b.getStyle().replace("#d0d0d0", "#e0e0e0")));
        return b;
    }

    // ─── 画布 ────────────────────────────────────────────────────

    private ScrollPane buildCanvas() {
        canvas = new Pane();
        canvas.setStyle("-fx-background-color: #ffffff;");
        canvas.setMinSize(CANVAS_W, CANVAS_H);
        canvas.setPrefSize(CANVAS_W, CANVAS_H);

        // 缩放变换
        canvasScale = new javafx.scene.transform.Scale(zoomLevel, zoomLevel, 0, 0);
        canvas.getTransforms().add(canvasScale);

        // 白色背景
        canvas.setBackground(new Background(new BackgroundFill(BG, CornerRadii.EMPTY, Insets.EMPTY)));

        // 画布空白处点击事件
        // 忽略节点/边内部的点击事件，只在画布空白处触发
        canvas.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            // 排除点击到节点/边内部的情况——向上遍历 parent 看是否属于 NodePane 或 EdgeLine
            if (e.getTarget() instanceof javafx.scene.Node || e.getTarget() instanceof javafx.scene.shape.Shape
                || e.getTarget() instanceof javafx.scene.text.Text) {
                javafx.scene.Node target = (javafx.scene.Node) e.getTarget();
                while (target != null && target != canvas) {
                    if (target instanceof NodePane || target instanceof EdgeLine) return;
                    target = target.getParent();
                }
            }
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                if (mode == EditMode.ADD_NODE) {
                    Point2D point = canvas.sceneToLocal(e.getSceneX(), e.getSceneY());
                    double x = point.getX();
                    double y = point.getY();
                    promptAndAddNode(x, y);
                }
            } else if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 1) {
                deselectAll();
            }
        });

        scroll = new ScrollPane(canvas);
        scroll.setStyle("-fx-background: #ffffff; -fx-background-color: #ffffff;");
        scroll.setFitToWidth(false);
        scroll.setPannable(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        scroll.setFocusTraversable(true);
        scroll.setOnMouseClicked(e -> requestFocus());

        // Ctrl + 鼠标滚轮缩放；普通滚轮加速滚动画布
        scroll.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            if (event.isControlDown()) {
                double delta = event.getDeltaY();
                if (delta > 0) {
                    zoomIn();
                } else if (delta < 0) {
                    zoomOut();
                }
                event.consume();
                return;
            }

            if (event.isShiftDown()) {
                double delta = event.getDeltaY() * 0.0060;
                double next = scroll.getHvalue() - delta;
                scroll.setHvalue(Math.max(0.0, Math.min(1.0, next)));
            } else {
                double delta = event.getDeltaY() * 0.0060;
                double next = scroll.getVvalue() - delta;
                scroll.setVvalue(Math.max(0.0, Math.min(1.0, next)));
            }
            event.consume();
        });

        return scroll;
    }

    // ─── 图加载/源标记 ────────────────────────────────────────────

    /** 加载图数据到画布，editable 控制是否可编辑；true 为可编辑（自定义图或内置图均可编辑） */
    public void loadGraph(GraphData data, boolean editable) {
        clearCanvas();
        undoStack.clear();
        this.isEditable = editable;

        // 先创建所有节点，建立 id → NodePane 映射
        Map<String, NodePane> idMap = new HashMap<>();
        for (NodeData nd : data.nodes) {
            NodePane np = createNodePane(nd.label, nd.x, nd.y);
            np.nodeId = nd.id;
            idMap.put(nd.id, np);
        }

        // 再创建所有边
        for (EdgeData ed : data.edges) {
            NodePane from = idMap.get(ed.fromId);
            NodePane to   = idMap.get(ed.toId);
            if (from != null && to != null) {
                createEdge(from, to, ed.label);
            }
        }

        // 滚动到左上角
        scroll.setVvalue(0);
        scroll.setHvalue(0);

        // 根据所有节点位置扩展画布
        expandCanvasToFitAll();
    }

    /** 标记当前图为自定义图 */
    public void setSourceCustom() {
        isShowingCustom = true;
        sourceLabel.setText("[自定义图]");
        sourceLabel.setStyle("-fx-text-fill: #e07020; -fx-font-size: 11;");
    }

    /** 标记当前图为内置图 */
    public void setSourceBuiltIn() {
        isShowingCustom = false;
        sourceLabel.setText("[内置图]");
        sourceLabel.setStyle("-fx-text-fill: #2563a0; -fx-font-size: 11;");
    }

    /** 标记当前图为空白 */
    public void setSourceNone() {
        isShowingCustom = false;
        sourceLabel.setText("[空白]");
        sourceLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 11;");
    }

    /** 创建空白图并显示提示 */
    public void newEmptyGraph(String chainName) {
        clearCanvas();
        undoStack.clear();
        this.currentChainName = chainName;
        this.isEditable = true;
        setSourceNone();

        Label hint = new Label("当前链暂无内置图。\\n可在编辑模式中添加节点、连边并保存到本地。");
        hint.setStyle("-fx-text-fill: #999; -fx-font-size: 13; -fx-text-alignment: center;");
        hint.setLayoutX(80);
        hint.setLayoutY(60);
        canvas.getChildren().add(hint);
    }

    /**
     * 尝试从 custom-graphs.json 加载指定链的自定义图。
     * 如果文件不存在或链名无对应数据则返回 null。
     */
    public GraphData loadCustomGraph(String chainName) {
        Path path = getCustomGraphsPath();
        if (!Files.exists(path)) return null;
        try {
            Map<String, GraphData> all = mapper.readValue(
                path.toFile(),
                new TypeReference<Map<String, GraphData>>() {}
            );
            GraphData data = all.get(chainName);
            // 自定义图如果节点为空则视为无效，返回 null 让调用方回退到内置图
            if (data != null && (data.nodes == null || data.nodes.isEmpty())) {
                return null;
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    // ─── 节点操作 ────────────────────────────────────────────────

    private NodePane createNodePane(String label, double x, double y) {
        NodePane np = new NodePane(label, x, y);
        np.setLayoutX(x);
        np.setLayoutY(y);

        // 拖拽偏移量
        final double[] drag = {0, 0};
        final double[] startLayout = {0, 0};  // 拖拽起始布局位置
        np.setOnMousePressed(e -> {
            Point2D point = canvas.sceneToLocal(e.getSceneX(), e.getSceneY());
            drag[0] = point.getX() - np.getLayoutX();
            drag[1] = point.getY() - np.getLayoutY();
            startLayout[0] = np.getLayoutX();
            startLayout[1] = np.getLayoutY();
            np.setCursor(Cursor.CLOSED_HAND);
            if (mode == EditMode.ADD_EDGE) {
                handleEdgeNodeClick(np);
            } else {
                selectNode(np);
            }
            e.consume();
        });
        np.setOnMouseDragged(e -> {
            Point2D point = canvas.sceneToLocal(e.getSceneX(), e.getSceneY());
            double newX = point.getX() - drag[0];
            double newY = point.getY() - drag[1];
            // 不允许拖出画布左/上边界
            newX = Math.max(0, newX);
            newY = Math.max(0, newY);
            np.setLayoutX(newX);
            np.setLayoutY(newY);
            refreshEdges();
            expandCanvasToFit(np);
            e.consume();
        });
        np.setOnMouseReleased(e -> {
            np.setCursor(Cursor.HAND);
            expandCanvasToFitAll();
            // 拖拽结束后，如果位置发生了变化，补一次撤销快照
            if (Math.abs(np.getLayoutX() - startLayout[0]) > 1
                    || Math.abs(np.getLayoutY() - startLayout[1]) > 1) {
                // 先还原再 pushUndo，然后再设回新位置
                double curX = np.getLayoutX(), curY = np.getLayoutY();
                np.setLayoutX(startLayout[0]);
                np.setLayoutY(startLayout[1]);
                pushUndo();
                // 恢复到最终位置
                np.setLayoutX(curX);
                np.setLayoutY(curY);
                refreshEdges();
            }
        });

        // 双击删除节点
        np.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && mode == EditMode.DELETE) {
                pushUndo();
                removeNode(np);
                e.consume();
            }
        });

        // 右键菜单：编辑标签 / 删除节点
        ContextMenu nodeMenu = new ContextMenu();
        MenuItem miEditNode = new MenuItem("编辑节点");
        miEditNode.setOnAction(ev -> editNodeLabel(np));
        MenuItem miDeleteNode = new MenuItem("删除节点");
        miDeleteNode.setOnAction(ev -> { pushUndo(); removeNode(np); });
        nodeMenu.getItems().addAll(miEditNode, miDeleteNode);
        np.setOnContextMenuRequested(ev -> {
            nodeMenu.show(np, ev.getScreenX(), ev.getScreenY());
            ev.consume();
        });

        np.setCursor(Cursor.HAND);

        canvas.getChildren().add(np);
        nodePanes.add(np);
        return np;
    }

    private void handleEdgeNodeClick(NodePane np) {
        if (edgeSource == null) {
            edgeSource = np;
            np.setSelected(true);
            modeLabel.setText("当前模式：连边（请选择第二个节点）");
        } else if (edgeSource != np) {
            if (!edgeExists(edgeSource, np)) {
                pushUndo();
                createEdge(edgeSource, np, "");
            }
            edgeSource.setSelected(false);
            edgeSource = null;
            modeLabel.setText("当前模式：连边（选第二个节点）");
        }
    }

    private void selectNode(NodePane np) {
        deselectAll();
        np.setSelected(true);
        selectedNode = np;
    }

    private void deselectAll() {
        nodePanes.forEach(n -> n.setSelected(false));
        edgeLines.forEach(e -> e.setSelected(false));
        selectedNode = null;
        selectedEdge = null;
        edgeSource = null;
    }

    private void promptAndAddNode(double x, double y) {
        TextInputDialog dlg = new TextInputDialog("ClassName::method()");
        dlg.setTitle("新增节点");
        dlg.setHeaderText("请输入节点标签");
        dlg.setContentText("标签：");
        dlg.showAndWait().ifPresent(label -> {
            if (!label.trim().isEmpty()) {
                pushUndo();
                NodePane np = createNodePane(label.trim(), x, y);
                np.nodeId = "n" + System.currentTimeMillis();
            }
        });
    }



    private void removeNode(NodePane np) {
        List<EdgeLine> toRemove = new ArrayList<>();
        for (EdgeLine el : edgeLines) {
            if (el.from == np || el.to == np) toRemove.add(el);
        }
        toRemove.forEach(this::removeEdgeLine);
        canvas.getChildren().remove(np);
        nodePanes.remove(np);
    }

    private void editNodeLabel(NodePane np) {
        if (np == null) return;
        TextInputDialog dlg = new TextInputDialog(np.getLabel());
        dlg.setTitle("编辑节点");
        dlg.setHeaderText("修改节点标签");
        dlg.setContentText("标签：");
        dlg.showAndWait().ifPresent(label -> {
            String value = label != null ? label.trim() : "";
            if (!value.isEmpty() && !value.equals(np.getLabel())) {
                pushUndo();
                np.setLabel(value);
                expandCanvasToFitAll();
            }
        });
    }

    private void editEdgeLabel(EdgeLine el) {
        if (el == null) return;
        TextInputDialog dlg = new TextInputDialog(el.getLabel());
        dlg.setTitle("编辑边");
        dlg.setHeaderText("修改边标签");
        dlg.setContentText("标签：");
        dlg.showAndWait().ifPresent(label -> {
            String value = label != null ? label.trim() : "";
            if (!value.equals(el.getLabel())) {
                pushUndo();
                el.setLabel(value);
            }
        });
    }

    // ─── 边操作 ──────────────────────────────────────────────────

    private EdgeLine createEdge(NodePane from, NodePane to, String label) {
        EdgeLine el = new EdgeLine(from, to, label);
        canvas.getChildren().add(0, el);
        edgeLines.add(el);

        // 双击删除边
        el.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && mode == EditMode.DELETE) {
                pushUndo();
                removeEdgeLine(el);
                e.consume();
            }
        });

        // 单击选中边
        el.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 1) {
                deselectAll();
                selectedEdge = el;
                el.setSelected(true);
                e.consume();
            }
        });

        // 右键菜单：编辑标签 / 删除边
        ContextMenu edgeMenu = new ContextMenu();
        MenuItem miEditEdge = new MenuItem("编辑边");
        miEditEdge.setOnAction(ev -> editEdgeLabel(el));
        MenuItem miDeleteEdge = new MenuItem("删除边");
        miDeleteEdge.setOnAction(ev -> { pushUndo(); removeEdgeLine(el); });
        edgeMenu.getItems().addAll(miEditEdge, miDeleteEdge);
        el.setOnContextMenuRequested(ev -> {
            edgeMenu.show(el, ev.getScreenX(), ev.getScreenY());
            ev.consume();
        });

        refreshEdge(el);
        return el;
    }

    private void removeEdgeLine(EdgeLine el) {
        canvas.getChildren().remove(el);
        edgeLines.remove(el);
    }

    private void refreshEdges() {
        edgeLines.forEach(this::refreshEdge);
    }

    private void refreshEdge(EdgeLine el) {
        el.update();
    }

    private boolean edgeExists(NodePane from, NodePane to) {
        for (EdgeLine edge : edgeLines) {
            if (edge.from == from && edge.to == to) {
                return true;
            }
        }
        return false;
    }

    // ─── 保存/加载/重置 ──────────────────────────────────────────

    /**
     * 将当前画布内容导出为 GraphData 并保存到 custom-graphs.json。
     * 如果已有自定义图文件，会读取并合并后再写入。
     */
    private void saveGraph() {
        if (currentChainName.isEmpty()) {
            showInfo("请先选择一条链再保存");
            return;
        }
        GraphData data = exportCurrentGraph();
        Path path = getCustomGraphsPath();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Map<String, GraphData> all = new LinkedHashMap<>();
            // 读取并合并已有自定义图
            if (Files.exists(path)) {
                try {
                    all = mapper.readValue(
                        path.toFile(),
                        new TypeReference<Map<String, GraphData>>() {}
                    );
                } catch (Exception ignored) {
                    // 解析失败则覆盖写入
                }
            }

            // 将当前图放入 Map
            all.put(currentChainName, data);
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), all);
            showInfo("已保存（" + currentChainName + " -> custom-graphs.json）");
        } catch (Exception e) {
            showError("保存失败: " + e.getMessage());
        }
    }

    private void loadGraphFromFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择导入 JSON");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file != null) {
            try {
                GraphData data = tryReadGraphData(file);
                loadGraph(data, true);
                setSourceCustom();
            } catch (Exception e) {
                showError("导入失败: " + e.getMessage());
            }
        }
    }

    private GraphData tryReadGraphData(File file) throws Exception {
        try {
            GraphData direct = mapper.readValue(file, GraphData.class);
            if (direct != null && direct.nodes != null && direct.edges != null) {
                return direct;
            }
        } catch (Exception ignored) {
        }

        Map<String, GraphData> all = mapper.readValue(
            file,
            new TypeReference<Map<String, GraphData>>() {}
        );
        if (all == null || all.isEmpty()) {
            throw new Exception("JSON 中未找到可导入的图数据");
        }
        if (currentChainName != null && !currentChainName.isEmpty() && all.containsKey(currentChainName)) {
            return all.get(currentChainName);
        }
        return all.values().iterator().next();
    }

    /**
     * 重置为内置图：从 custom-graphs.json 中删除当前链的自定义图数据，
     * 然后重新加载内置图。
     */
    private void resetToBuiltIn() {
        if (currentChainName.isEmpty()) {
            showInfo("请先选择一条链再恢复");
            return;
        }
        Path path = getCustomGraphsPath();
        if (Files.exists(path)) {
            try {
                Map<String, GraphData> all = mapper.readValue(
                    path.toFile(),
                    new TypeReference<Map<String, GraphData>>() {}
                );
                if (all.containsKey(currentChainName)) {
                    all.remove(currentChainName);
                    mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), all);
                }
            } catch (Exception ignored) {}
        }
        // 尝试加载内置图
        GraphData builtIn = ysogui.core.ChainGraphs.getGraph(currentChainName);
        if (builtIn != null) {
            loadGraph(builtIn, true);
            setSourceBuiltIn();
        } else {
            newEmptyGraph(currentChainName);
        }
    }

    private GraphData exportCurrentGraph() {
        GraphData data = new GraphData();
        Map<NodePane, String> idMap = new HashMap<>();

        for (NodePane np : nodePanes) {
            String id = np.nodeId != null ? np.nodeId : "n" + nodePanes.indexOf(np);
            idMap.put(np, id);
            data.nodes.add(new NodeData(id, np.getLabel(), np.getLayoutX(), np.getLayoutY()));
        }
        for (EdgeLine el : edgeLines) {
            String fromId = idMap.get(el.from);
            String toId   = idMap.get(el.to);
            if (fromId != null && toId != null) {
                data.edges.add(new EdgeData(fromId, toId, el.edgeLabel));
            }
        }
        return data;
    }

    private void clearCanvas() {
        canvas.getChildren().clear();
        nodePanes.clear();
        edgeLines.clear();
        selectedNode = null;
        edgeSource   = null;
        // 重置画布尺寸
        canvas.setMinSize(CANVAS_W, CANVAS_H);
        canvas.setPrefSize(CANVAS_W, CANVAS_H);
    }

    /**
     * 根据单个节点位置扩展画布，确保节点不超出可视范围。
     */
    private void expandCanvasToFit(NodePane np) {
        double neededW = (np.getLayoutX() + NODE_W + 20) * zoomLevel;
        double neededH = (np.getLayoutY() + np.getPrefHeight() + 20) * zoomLevel;
        double curW = canvas.getWidth();
        double curH = canvas.getHeight();
        if (neededW > curW || neededH > curH) {
            double newW = Math.max(curW, neededW);
            double newH = Math.max(curH, neededH);
            canvas.setMinSize(newW, newH);
            canvas.setPrefSize(newW, newH);
        }
    }

    /**
     * 遍历所有节点，将画布扩展到足够容纳所有节点的尺寸。
     */
    private void expandCanvasToFitAll() {
        double maxW = CANVAS_W * zoomLevel;
        double maxH = CANVAS_H * zoomLevel;
        for (NodePane np : nodePanes) {
            double neededW = (np.getLayoutX() + NODE_W + 20) * zoomLevel;
            double neededH = (np.getLayoutY() + np.getPrefHeight() + 20) * zoomLevel;
            maxW = Math.max(maxW, neededW);
            maxH = Math.max(maxH, neededH);
        }
        canvas.setMinSize(maxW, maxH);
        canvas.setPrefSize(maxW, maxH);
    }

    // ─── 公共 API ────────────────────────────────────────────────
    public void setCurrentChainName(String name) {
        this.currentChainName = name;
    }

    private void setMode(EditMode m) {
        this.mode = m;
        edgeSource = null;
        deselectAll();
        String[] labels = {"查看", "新增节点", "连边", "删除"};
        modeLabel.setText("当前模式：" + labels[m.ordinal()]);
    }

    private void handleKeyPressed(KeyEvent event) {
        if (event.isControlDown() && event.getCode() == KeyCode.Z) {
            undo();
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.ESCAPE) {
            setMode(EditMode.VIEW);
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.DELETE) {
            if (selectedNode != null) {
                pushUndo();
                removeNode(selectedNode);
                selectedNode = null;
                event.consume();
            } else if (selectedEdge != null) {
                pushUndo();
                removeEdgeLine(selectedEdge);
                selectedEdge = null;
                event.consume();
            }
        }
    }

    /**
     * 获取自定义图文件路径，委托给 AppPaths 统一管理。
     */
    private Path getCustomGraphsPath() {
        return ysogui.core.AppPaths.customGraphsFile();
    }

    private boolean confirmClear() {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "确认清空当前画布？", ButtonType.YES, ButtonType.NO);
        a.setTitle("确认");
        return a.showAndWait().filter(b -> b == ButtonType.YES).isPresent();
    }

    private void showInfo(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setTitle("提示");
        a.showAndWait();
    }

    private void showError(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setTitle("错误");
        a.showAndWait();
    }


    // ═══════════════════════════════════════════════════════════════
    //  内部类：NodePane
    // ═══════════════════════════════════════════════════════════════
    class NodePane extends StackPane {

        String nodeId;
        private String labelText;
        private final Rectangle rect;
        private final Text text;

        NodePane(String label, double x, double y) {
            this.labelText = label;

            rect = new Rectangle(NODE_W, NODE_H);
            rect.setArcWidth(8);
            rect.setArcHeight(8);
            rect.setFill(NODE_FILL);
            rect.setStroke(NODE_STROKE);
            rect.setStrokeWidth(1.5);

            text = new Text(label);
            text.setFill(TEXT_COLOR);
            text.setFont(Font.font("Consolas", 11));
            text.setTextAlignment(TextAlignment.CENTER);
            text.setWrappingWidth(NODE_W - 12);

            // 文字可能换行，根据实际文字高度调整节点高度
            double textH = text.getBoundsInLocal().getHeight();
            double h = Math.max(NODE_H, textH + 16);
            rect.setHeight(h);
            setPrefSize(NODE_W, h);

            // layoutX/Y 在 createNodePane() 调用后由外部设置，此处不设置
            getChildren().addAll(rect, text);

            // hover 蓝色外发光 + 加粗边框
            setOnMouseEntered(e -> {
                DropShadow glow = new DropShadow();
                glow.setColor(Color.web("#2563a0"));
                glow.setRadius(14);
                glow.setSpread(0.2);
                rect.setEffect(glow);
                rect.setStrokeWidth(2.0);
            });
            // hover 退出时恢复
            setOnMouseExited(e -> {
                rect.setEffect(null);
                rect.setStrokeWidth(isSelected() ? 2.5 : 1.5);
            });
        }

        /** 判断是否选中（排除 hover 导致的 strokeWidth 变化） */
        private boolean isSelected() {
            return rect.getStrokeWidth() > 2.0 && rect.getStroke().equals(NODE_SEL);
        }

        void setSelected(boolean sel) {
            rect.setStroke(sel ? NODE_SEL : NODE_STROKE);
            rect.setStrokeWidth(sel ? 2.5 : 1.5);
            // 选中时加橙色外发光
            if (sel) {
                DropShadow selGlow = new DropShadow();
                selGlow.setColor(Color.web("#e07020"));
                selGlow.setRadius(12);
                selGlow.setSpread(0.15);
                rect.setEffect(selGlow);
            } else {
                rect.setEffect(null);
            }
        }

        String getLabel() { return labelText; }

        /** 节点中心 X（相对于 canvas） */
        double centerX() { return getLayoutX() + NODE_W / 2.0; }

        /** 节点中心 Y（相对于 canvas） */
        double centerY() { return getLayoutY() + getPrefHeight() / 2.0; }

        /** 节点底部 Y */
        double bottomY() { return getLayoutY() + getPrefHeight(); }

        /** 节点顶部 Y */
        double topY() { return getLayoutY(); }

        /** 设置新标签并自动调整节点高度 */
        void setLabel(String newLabel) {
            this.labelText = newLabel;
            text.setText(newLabel);
            // 重新计算高度
            double textH = text.getBoundsInLocal().getHeight();
            double h = Math.max(NODE_H, textH + 16);
            rect.setHeight(h);
            setPrefSize(NODE_W, h);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  内部类：EdgeLine
    // ═══════════════════════════════════════════════════════════════
    class EdgeLine extends javafx.scene.Group {

        final NodePane from;
        final NodePane to;
        String edgeLabel;

        // 使用 CubicCurve 绘制贝塞尔曲线连接两个节点，S 形曲线
        private final CubicCurve curve;
        private final Polygon arrow;
        private final Text labelText;

        EdgeLine(NodePane from, NodePane to, String label) {
            this.from      = from;
            this.to        = to;
            this.edgeLabel = label != null ? label : "";

            // 贝塞尔曲线：起点 from 底部，终点 to 顶部
            curve = new CubicCurve();
            curve.setStroke(EDGE_COLOR);
            curve.setStrokeWidth(1.5);
            curve.setFill(Color.TRANSPARENT); // 透明填充，仅描边
            // 箭头三角形
            arrow = new Polygon();
            arrow.setFill(EDGE_COLOR);

            // 边标签文字
            labelText = new Text(this.edgeLabel);
            labelText.setFill(Color.web("#666"));
            labelText.setFont(Font.font("Consolas", 10));

            getChildren().addAll(curve, arrow, labelText);
            update();
        }

        void update() {
            double sx = from.centerX();
            double sy = from.bottomY();   // 源节点底部
            double ex = to.centerX();
            double ey = to.topY();        // 目标节点顶部
            // 控制点纵向偏移 = 两节点纵向距离的 40%
            // 当两节点纵向距离较小时，控制点仍保持最小偏移，避免曲线过于平直
            double dy = Math.abs(ey - sy) * 0.4;
            double cx1 = sx;
            double cy1 = sy + dy;   // 第一控制点：从起点向下偏移
            double cx2 = ex;
            double cy2 = ey - dy;   // 第二控制点：从终点向上偏移

            curve.setStartX(sx);   curve.setStartY(sy);
            curve.setControlX1(cx1); curve.setControlY1(cy1);
            curve.setControlX2(cx2); curve.setControlY2(cy2);
            curve.setEndX(ex);     curve.setEndY(ey);

            // 计算箭头朝向（沿曲线末端切线方向）
            double angle = Math.atan2(ey - cy2, ex - cx2);
            double arrowLen = 10;
            double arrowWid = 5;
            // 箭头顶点退后 arrowLen 距离
            double ax = ex - arrowLen * Math.cos(angle);
            double ay = ey - arrowLen * Math.sin(angle);
            // 箭头两侧点
            double lx = ax - arrowWid * Math.sin(angle);
            double ly = ay + arrowWid * Math.cos(angle);
            double rx = ax + arrowWid * Math.sin(angle);
            double ry = ay - arrowWid * Math.cos(angle);
            arrow.getPoints().setAll(ex, ey, lx, ly, rx, ry);

            // 边标签定位到曲线中点（De Casteljau t=0.5 处）
            if (!edgeLabel.isEmpty()) {
                // De Casteljau 算法 t=0.5
                double mx = 0.125*sx + 0.375*cx1 + 0.375*cx2 + 0.125*ex;
                double my = 0.125*sy + 0.375*cy1 + 0.375*cy2 + 0.125*ey;
                labelText.setX(mx + 4);
                labelText.setY(my);
            }
        }

        void setSelected(boolean sel) {
            curve.setStroke(sel ? NODE_SEL : EDGE_COLOR);
            curve.setStrokeWidth(sel ? 2.5 : 1.5);
            arrow.setFill(sel ? NODE_SEL : EDGE_COLOR);
        }

        String getLabel() { return edgeLabel; }

        void setLabel(String newLabel) {
            String val = newLabel != null ? newLabel : "";
            this.edgeLabel = val;
            labelText.setText(val);
        }
    }
}
