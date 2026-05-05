package ysogui.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import ysogui.core.ChainGraphs;
import ysogui.core.ChainMetaManager;
import ysogui.core.JavaCompileUtil;
import ysogui.model.ChainInfo;
import ysogui.model.ChainMeta;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * 左侧链列表面板：搜索过滤 + 链名列表 + 自定义链入口
 */
public class ChainListPanel extends VBox {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CUSTOM_CHAINS_FILE = java.nio.file.Paths.get(
        "payloads", "custom-chains.json").toString();

    private final MainWindow mainWindow;
    private final ObservableList<ChainInfo> allChains = FXCollections.observableArrayList();
    private FilteredList<ChainInfo> filteredChains;
    private ListView<ChainInfo> listView;
    private Label countLabel;
    private TextField searchField;            // 搜索框引用

    // 分类筛选标签
    private static final String[][] CATEGORY_TAGS = {
        {"全部",   "#e8e8e8", "#555"},
        {"CC",     "#e0ecf5", "#2563a0"},
        {"CB",     "#ece0f5", "#7c3aed"},
        {"Spring", "#e0f0e0", "#2e8b57"},
        {"Fastjson","#fff5e0", "#cc6600"},
        {"JDK",    "#f0f0f0", "#555"},
        {"🐴CLASS", "#e8f5e8", "#2e8b57"},
    };
    private String activeCategory = "全部";  // 当前激活的分类
    private Map<String, ToggleButton> categoryButtons = new LinkedHashMap<>();
    private ContextMenu activeContextMenu = null;  // 当前显示的右键菜单;

    public ChainListPanel(MainWindow mainWindow) {
        this.mainWindow = mainWindow;
        setStyle("-fx-background-color: #f5f5f5;");
        setMinWidth(180);
        setPrefWidth(200);

        getChildren().addAll(
            buildHeader(),
            buildSearchBox(),
            buildListView(),
            buildFooter()
        );
    }

    // ──── Header ──────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        Label title = new Label("Gadget Chains");
        title.setStyle("-fx-text-fill: #2563a0; -fx-font-size: 13; -fx-font-weight: bold;");
        countLabel = new Label("0 条");
        countLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 11;");

        HBox box = new HBox(title);
        box.setStyle("-fx-background-color: #e8e8e8; -fx-padding: 8 10;");
        HBox.setHgrow(title, Priority.ALWAYS);
        box.getChildren().add(countLabel);
        return box;
    }

    // ──── Search ──────────────────────────────────────────────────────────────

    private VBox buildSearchBox() {
        searchField = new TextField();
        searchField.setPromptText("搜索链名...");
        searchField.setStyle(
            "-fx-background-color: #fff; -fx-text-fill: #333; -fx-prompt-text-fill: #aaa;" +
            "-fx-border-color: #ccc; -fx-border-radius: 3; -fx-background-radius: 3; " +
            "-fx-font-size: 12; -fx-padding: 4 8;"
        );

        searchField.textProperty().addListener((obs, old, val) -> applyFilter());

        // 分类标签按钮行
        FlowPane tagRow = new FlowPane(4, 4);
        tagRow.setPadding(new Insets(2, 0, 0, 0));

        for (String[] tag : CATEGORY_TAGS) {
            String label = tag[0];
            String bgColor = tag[1];
            String textColor = tag[2];

            ToggleButton btn = new ToggleButton(label);
            btn.setSelected("全部".equals(label));
            btn.setStyle(
                "-fx-background-color: " + bgColor + "; -fx-text-fill: " + textColor + "; " +
                "-fx-border-color: transparent; -fx-border-radius: 10; -fx-background-radius: 10; " +
                "-fx-font-size: 10; -fx-padding: 3 8; -fx-cursor: hand;"
            );
            // 选中态样式
            btn.selectedProperty().addListener((obs, old, selected) -> {
                if (selected) {
                    btn.setStyle(
                        "-fx-background-color: " + textColor + "; -fx-text-fill: #fff; " +
                        "-fx-border-color: transparent; -fx-border-radius: 10; -fx-background-radius: 10; " +
                        "-fx-font-size: 10; -fx-padding: 3 8; -fx-font-weight: bold; -fx-cursor: hand;"
                    );
                } else {
                    btn.setStyle(
                        "-fx-background-color: " + bgColor + "; -fx-text-fill: " + textColor + "; " +
                        "-fx-border-color: transparent; -fx-border-radius: 10; -fx-background-radius: 10; " +
                        "-fx-font-size: 10; -fx-padding: 3 8; -fx-cursor: hand;"
                    );
                }
            });

            btn.setOnAction(e -> {
                // 单选逻辑：点击已选中的标签 → 取消选中回到"全部"
                if (activeCategory.equals(label) && !"全部".equals(label)) {
                    activeCategory = "全部";
                    categoryButtons.get("全部").setSelected(true);
                    btn.setSelected(false);
                } else {
                    activeCategory = label;
                    // 取消其他按钮选中
                    for (Map.Entry<String, ToggleButton> entry : categoryButtons.entrySet()) {
                        entry.getValue().setSelected(entry.getKey().equals(label));
                    }
                }
                applyFilter();
            });

            categoryButtons.put(label, btn);
            tagRow.getChildren().add(btn);
        }

        VBox box = new VBox(4, searchField, tagRow);
        box.setPadding(new Insets(6, 8, 6, 8));
        box.setStyle("-fx-background-color: #f5f5f5;");
        return box;
    }

    /**
     * 统一应用搜索框 + 分类标签的过滤逻辑
     */
    private void applyFilter() {
        if (filteredChains == null) return;
        String searchText = searchField.getText().toLowerCase().trim();
        final String category = activeCategory;

        filteredChains.setPredicate(chain -> {
            // 搜索框过滤
            if (!searchText.isEmpty() && !chain.getName().toLowerCase().contains(searchText)) {
                return false;
            }

            // 分类标签过滤
            if (!"全部".equals(category)) {
                String chainName = chain.getName();
                ChainMeta meta = ChainMetaManager.getMeta(chainName);
                String metaCategory = meta != null ? meta.getCategory() : null;
                List<String> deps = chain.getDependencies();

                switch (category) {
                    case "CC":
                        if (!matchCategory(chainName, metaCategory, "CC")
                            && !depsContains(deps, "commons-collections")) return false;
                        break;
                    case "CB":
                        if (!matchCategory(chainName, metaCategory, "CB")
                            && !depsContains(deps, "commons-beanutils")) return false;
                        break;
                    case "Spring":
                        if (!matchCategory(chainName, metaCategory, "Spring")
                            && !chainName.startsWith("Spring")
                            && !depsContains(deps, "spring")) return false;
                        break;
                    case "Fastjson":
                        if (!matchCategory(chainName, metaCategory, "Fastjson")
                            && !chainName.startsWith("Fastjson")) return false;
                        break;
                    case "JDK":
                        // 无外部依赖
                        if (deps != null && !deps.isEmpty()) return false;
                        break;
                    case "🐴CLASS":
                        if (!ChainMetaManager.supportsBuiltinTemplate(chainName)) return false;
                        break;
                    default:
                        break;
                }
            }
            return true;
        });
        int visible = filteredChains.size();
        int total = allChains.size();
        countLabel.setText(visible + " 条");
        mainWindow.updateChainCount(total, visible);
    }

    /** 分类匹配：先看 ChainMeta.category，再看链名前缀 */
    private boolean matchCategory(String chainName, String metaCategory, String target) {
        if (target.equals(metaCategory)) return true;
        // 链名前缀推断
        switch (target) {
            case "CC":  return chainName.startsWith("CommonsCollections");
            case "CB":  return chainName.startsWith("CommonsBeanutils");
            default:    return false;
        }
    }

    /** 依赖列表中是否包含指定关键词 */
    private boolean depsContains(List<String> deps, String keyword) {
        if (deps == null || deps.isEmpty()) return false;
        return deps.stream().anyMatch(d -> d.toLowerCase().contains(keyword.toLowerCase()));
    }

    // ──── List ────────────────────────────────────────────────────────────────

    private ListView<ChainInfo> buildListView() {
        listView = new ListView<>();
        listView.setStyle(
            "-fx-background-color: #f5f5f5; -fx-border-color: transparent;" +
            "-fx-padding: 0;"
        );
        VBox.setVgrow(listView, Priority.ALWAYS);

        // 自定义 Cell
        listView.setCellFactory(lv -> new ListCell<ChainInfo>() {
            @Override
            protected void updateItem(ChainInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("-fx-background-color: transparent;");
                    return;
                }

                // 是否有内置调用图
                boolean hasGraph = ChainGraphs.hasGraph(item.getName());
                // 单一语义：🐴 表示支持 yso 的 CLASS: 模板路线
                boolean builtinTemplateOk = ChainMetaManager.supportsBuiltinTemplate(item.getName());

                // 根据链名前缀分配图标，便于快速识别链的类型
                String chainName = item.getName();
                String icon;
                String color;
                String suffix = builtinTemplateOk ? " 🐴" : "";
                if (item.isCustom()) {
                    // 自定义链：有内置图用实心★，无内置图用空心☆
                    icon = hasGraph ? "★ " : "☆ "; color = hasGraph ? "#e07020" : "#cc9966";
                } else if (chainName.startsWith("CommonsCollections")) {
                    icon = hasGraph ? "● " : "○ "; color = hasGraph ? "#2563a0" : "#99b3d6";
                } else if (chainName.startsWith("CommonsBeanutils")) {
                    icon = hasGraph ? "● " : "○ "; color = hasGraph ? "#7c3aed" : "#b8a0e0";
                } else if (chainName.startsWith("Spring")) {
                    icon = hasGraph ? "● " : "○ "; color = hasGraph ? "#2e8b57" : "#90c0a0";
                } else if (chainName.startsWith("URLDNS") || chainName.startsWith("JRMPClient")) {
                    icon = hasGraph ? "● " : "○ "; color = hasGraph ? "#b8860b" : "#d0c078";
                } else {
                    icon = hasGraph ? "● " : "○ "; color = hasGraph ? "#444444" : "#aaa";
                }

                Label name = new Label(icon + chainName + suffix);
                name.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 12; -fx-font-family: Consolas;");
                name.setMaxWidth(Double.MAX_VALUE);

                setGraphic(name);
                setText(null);

                updateCellStyle();
            }

            @Override
            public void updateSelected(boolean selected) {
                super.updateSelected(selected);
                updateCellStyle();
            }

            private void updateCellStyle() {
                if (isSelected()) {
                    setStyle("-fx-background-color: #b8d4f0; -fx-border-color: #4a9fd5; -fx-border-width: 0 0 0 3;");
                } else if (isHover()) {
                    setStyle("-fx-background-color: #e8f0f8;");
                } else {
                    setStyle("-fx-background-color: transparent;");
                }
            }
        });

        listView.getSelectionModel().selectedItemProperty().addListener((obs, old, chain) -> {
            if (chain != null) {
                mainWindow.onChainSelected(chain);
            }
        });

        // 再次点击已选中项时取消选中；右键自定义链弹出删除菜单
        final ChainInfo[] lastSelected = {null};
        listView.setOnMouseClicked(event -> {
            if (event.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                // 右键：自定义链弹出删除菜单
                ChainInfo clicked = listView.getSelectionModel().getSelectedItem();
                if (clicked != null && clicked.isCustom()) {
                    showChainContextMenu(clicked, event.getScreenX(), event.getScreenY());
                }
                return;
            }
            // 左键：隐藏右键菜单
            hideContextMenu();
            // 左键：再次点击取消选中
            ChainInfo current = listView.getSelectionModel().getSelectedItem();
            if (current != null && current.equals(lastSelected[0])) {
                listView.getSelectionModel().clearSelection();
                lastSelected[0] = null;
                mainWindow.onChainSelected(null);
            } else {
                lastSelected[0] = current;
            }
        });

        // 提示
        Tooltip tip = new Tooltip("●/★ 有内置调用图   ○/☆ 暂无内置图   🐴 支持 yso CLASS: 模板");
        Tooltip.install(listView, tip);

        return listView;
    }

    // ──── Footer ─────────────────────────────────────────────────────────────

    private VBox buildFooter() {
        Button btnCustom = new Button("+ 新建自定义链");
        btnCustom.setMaxWidth(Double.MAX_VALUE);
        btnCustom.setStyle(
            "-fx-background-color: #e0f0e0; -fx-text-fill: #2e8b57; " +
            "-fx-border-color: #8abf8a; -fx-font-size: 12; -fx-padding: 6;"
        );
        btnCustom.setOnAction(e -> showCustomChainDialog());

        VBox box = new VBox(btnCustom);
        box.setPadding(new Insets(6, 8, 8, 8));
        box.setStyle("-fx-background-color: #e8e8e8; " +
                     "-fx-border-color: #ccc transparent transparent transparent;");
        return box;
    }

    // ──── Public API ─────────────────────────────────────────────────────────

    public void setChains(List<ChainInfo> chains) {
        Platform.runLater(() -> {
            // 追加持久化的自定义链
            List<ChainInfo> savedCustoms = loadCustomChains();
            List<ChainInfo> combined = new ArrayList<>(chains);
            combined.addAll(savedCustoms);

            allChains.setAll(combined);
            filteredChains = new FilteredList<>(allChains, p -> true);
            listView.setItems(filteredChains);
            countLabel.setText(combined.size() + " 条");
            mainWindow.updateChainCount(combined.size(), combined.size());
        });
    }

    // ──── 自定义链对话框 ─────────────────────────────────────────────────────

    private void showCustomChainDialog() {
        showCustomChainDialog(null);
    }

    private void showCustomChainDialog(ChainInfo existing) {
        boolean editing = existing != null;
        Dialog<ChainInfo> dlg = new Dialog<>();
        dlg.setTitle(editing ? "编辑自定义链" : "新建自定义链");
        dlg.setHeaderText(editing ? "修改自定义链配置" : "配置自定义利用链（需实现 ObjectPayload 接口）");

        // 帮助按钮
        Hyperlink helpLink = new Hyperlink("如何编写自定义链？");
        helpLink.setStyle("-fx-text-fill: #2563a0; -fx-font-size: 11; -fx-border-color: transparent; -fx-padding: 0;");
        helpLink.setOnAction(e -> showCustomChainHelp());

        // 链名
        TextField nameField = new TextField("MyCustomChain");
        nameField.setPromptText("链名，如 TomcatMemShell");

        // 完整类名（选择文件后自动填充）
        TextField classField = new TextField();
        classField.setPromptText("如 com.example.TomcatMemShell（选文件后自动填充）");
        classField.setPrefWidth(350);

        // 文件选择
        TextField fileField = new TextField();
        fileField.setPromptText("选择 .class 文件");
        fileField.setEditable(false);
        fileField.setPrefWidth(350);

        Label fileHint = new Label("仅支持导入 .class 文件");
        fileHint.setStyle("-fx-text-fill: #888; -fx-font-size: 10;");

        Button btnBrowse = new Button("浏览...");
        btnBrowse.setOnAction(ev -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("选择 Payload 类文件");
            fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Class 文件", "*.class")
            );
            File file = fc.showOpenDialog(dlg.getDialogPane().getScene().getWindow());
            if (file != null) {
                fileField.setText(file.getAbsolutePath());
                // 自动推导类名
                String derived = deriveClassName(file);
                if (derived != null && classField.getText().trim().isEmpty()) {
                    classField.setText(derived);
                }
                // 自动推导链名
                if (nameField.getText().trim().equals("MyCustomChain")) {
                    String simple = derived != null ? derived.substring(derived.lastIndexOf('.') + 1) : file.getName().replaceAll("\\.class$", "");
                    nameField.setText(simple);
                }
            }
        });

        // 作者（可选）
        TextField authorField = new TextField();
        authorField.setPromptText("如 wrold（可选，默认为\"自定义\"）");

        // 分类选择
        ComboBox<String> categoryCombo = new ComboBox<>();
        categoryCombo.getItems().addAll("CC", "CB", "Spring", "Fastjson", "JDK", "Other");
        categoryCombo.setPromptText("选择分类");
        categoryCombo.setValue("Other");

        // 是否支持内存马
        CheckBox memshellCheck = new CheckBox("支持 CLASS:内置模板（TemplatesImpl 路线）");
        memshellCheck.setSelected(false);

        // 依赖描述（可选）
        TextField depsField = new TextField();
        depsField.setPromptText("如 commons-collections:commons-collections:3.1（多个用逗号分隔，可选）");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 20, 10, 10));
        ColumnConstraints col0 = new ColumnConstraints();
        col0.setMinWidth(70);
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(col0, col1);
        grid.add(new Label("链名："), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("完整类名："), 0, 1);
        grid.add(classField, 1, 1);
        grid.add(new Label("类文件："), 0, 2);
        grid.add(fileField, 1, 2);
        grid.add(btnBrowse, 2, 2);
        grid.add(fileHint, 1, 3);
        grid.add(new Label("作者："), 0, 4);
        grid.add(authorField, 1, 4);
        grid.add(new Label("分类："), 0, 5);
        grid.add(categoryCombo, 1, 5);
        grid.add(memshellCheck, 1, 6);
        grid.add(new Label("依赖："), 0, 7);
        grid.add(depsField, 1, 7);
        grid.add(helpLink, 1, 8);

        if (editing) {
            nameField.setText(existing.getName());
            classField.setText(existing.getCustomClassName());
            fileField.setText(existing.getCustomJarPath());
            authorField.setText(existing.getAuthor());
            ChainMeta meta = ChainMetaManager.getMeta(existing.getName());
            if (meta != null) {
                if (meta.getCategory() != null) categoryCombo.setValue(meta.getCategory());
                memshellCheck.setSelected(meta.isMemshellSupported());
            }
            if (existing.getDependencies() != null && !existing.getDependencies().isEmpty()) {
                depsField.setText(String.join(", ", existing.getDependencies()));
            }
        }

        dlg.getDialogPane().setContent(grid);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // 验证输入
        javafx.scene.Node okBtn = dlg.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.setDisable(true);
        nameField.textProperty().addListener((obs, o, n) -> validateCustomChainInputs(okBtn, nameField, classField, fileField));
        classField.textProperty().addListener((obs, o, n) -> validateCustomChainInputs(okBtn, nameField, classField, fileField));
        fileField.textProperty().addListener((obs, o, n) -> validateCustomChainInputs(okBtn, nameField, classField, fileField));
        validateCustomChainInputs(okBtn, nameField, classField, fileField);

        dlg.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                String name = nameField.getText().trim();
                String className = classField.getText().trim();
                String srcPath = fileField.getText().trim();
                if (name.isEmpty() || className.isEmpty() || srcPath.isEmpty()) return null;

                if (!editing || !name.equals(existing.getName())) {
                    boolean duplicate = allChains.stream()
                        .anyMatch(c -> c.getName().equals(name));
                    if (duplicate) {
                        Platform.runLater(() ->
                            new Alert(Alert.AlertType.WARNING, "链名 \"" + name + "\" 已存在，请换一个名字", ButtonType.OK).showAndWait());
                        return null;
                    }
                }

                try {
                    String localPath;
                    if (editing && srcPath.equals(existing.getCustomJarPath())) {
                        localPath = srcPath;
                    } else {
                        localPath = copyPayloadFile(srcPath, name, className);
                    }
                    ChainInfo custom;
                    if (editing) {
                        custom = existing;
                        if (!name.equals(existing.getName())) {
                            ChainMetaManager.removeCustom(existing.getName());
                        }
                    } else {
                        custom = new ChainInfo();
                    }
                    custom.setName(name);
                    String author = authorField.getText().trim();
                    custom.setAuthor(author.isEmpty() ? "自定义" : author);
                    custom.setCustom(true);
                    custom.setCustomClassName(className);
                    custom.setCustomJarPath(localPath);

                    List<String> classDeps = readDependenciesFromClass(localPath, className);

                    String manualDeps = depsField.getText().trim();
                    if (!manualDeps.isEmpty()) {
                        classDeps = Arrays.asList(manualDeps.split("\\s*,\\s*"));
                    }
                    if (!classDeps.isEmpty()) {
                        custom.setDependencies(classDeps);
                    }

                    ChainMeta meta = new ChainMeta();
                    meta.setName(name);
                    meta.setCategory(categoryCombo.getValue());
                    meta.setMemshellSupported(memshellCheck.isSelected());
                    if (!memshellCheck.isSelected()) {
                        meta.setMemshellReason("该自定义链不支持 CLASS: 内置模板路线");
                    }
                    ChainMetaManager.putCustom(meta);

                    return custom;
                } catch (Exception e) {
                    new Alert(Alert.AlertType.ERROR, "文件处理失败: " + e.getMessage(), ButtonType.OK).showAndWait();
                    return null;
                }
            }
            return null;
        });

        dlg.showAndWait().ifPresent(custom -> {
            if (editing) {
                saveCustomChains();
            } else {
                allChains.add(custom);
                saveCustomChains();
            }
            if (filteredChains != null) {
                listView.getSelectionModel().select(custom);
            }
            int visible = filteredChains != null ? filteredChains.size() : allChains.size();
            countLabel.setText(visible + " 条");
            mainWindow.updateChainCount(allChains.size(), visible);
            mainWindow.onChainSelected(custom);
        });
    }

    private void validateCustomChainInputs(javafx.scene.Node okBtn, TextField nameField, TextField classField, TextField fileField) {
        boolean valid = !nameField.getText().trim().isEmpty()
                     && !classField.getText().trim().isEmpty()
                     && !fileField.getText().trim().isEmpty();
        okBtn.setDisable(!valid);
    }

    /** 右键自定义链弹出菜单 */
    private void showChainContextMenu(ChainInfo chain, double screenX, double screenY) {
        hideContextMenu();
        ContextMenu menu = new ContextMenu();

        MenuItem editItem = new MenuItem("编辑自定义链: " + chain.getName());
        editItem.setStyle("-fx-text-fill: #2563a0;");
        editItem.setOnAction(e -> showCustomChainDialog(chain));

        MenuItem deleteItem = new MenuItem("删除自定义链: " + chain.getName());
        deleteItem.setStyle("-fx-text-fill: #c0392b;");
        deleteItem.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "确定删除自定义链 \"" + chain.getName() + "\"？", ButtonType.YES, ButtonType.NO);
            confirm.setTitle("删除确认");
            Optional<ButtonType> result = confirm.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.YES) {
                allChains.remove(chain);
                saveCustomChains();
                // 同步删除 ChainMeta
                ChainMetaManager.removeCustom(chain.getName());
                // 删除本地 class 文件
                String jarPath = chain.getCustomJarPath();
                if (jarPath != null) {
                    try {
                        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(jarPath));
                    } catch (Exception ignored) {}
                }
                listView.getSelectionModel().clearSelection();
                mainWindow.onChainSelected(null);
                int visible = filteredChains != null ? filteredChains.size() : allChains.size();
                countLabel.setText(visible + " 条");
                mainWindow.updateChainCount(allChains.size(), visible);
            }
        });
        menu.getItems().addAll(editItem, new SeparatorMenuItem(), deleteItem);
        menu.setAutoHide(true);
        menu.setOnHidden(e -> activeContextMenu = null);
        menu.show(listView, screenX, screenY);
        activeContextMenu = menu;
    }

    /** 隐藏当前显示的右键菜单 */
    private void hideContextMenu() {
        if (activeContextMenu != null) {
            activeContextMenu.hide();
            activeContextMenu = null;
        }
    }

    /** 从 .class 文件读取 @Dependencies 注解，返回依赖列表 */
    private List<String> readDependenciesFromClass(String classFilePath, String className) {
        try {
            File classFile = new File(classFilePath);
            if (!classFile.exists()) return Collections.emptyList();

            // 构造 classpath：payloads 目录 + ysoserial jar
            List<URL> urls = new ArrayList<>();
            urls.add(classFile.getParentFile().toURI().toURL());
            String ysoJar = buildCustomPayloadClasspath();
            for (String cp : ysoJar.split(File.pathSeparator)) {
                File f = new File(cp);
                if (f.exists()) urls.add(f.toURI().toURL());
            }

            try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), null)) {
                Class<?> clazz = loader.loadClass(className);
                for (Annotation ann : clazz.getAnnotations()) {
                    if (ann.annotationType().getSimpleName().equals("Dependencies")) {
                        try {
                            Method value = ann.annotationType().getMethod("value");
                            Object result = value.invoke(ann);
                            if (result instanceof String[]) {
                                return Arrays.asList((String[]) result);
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
        return Collections.emptyList();
    }

    private String buildCustomPayloadClasspath() {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        URLClassLoader loader = mainWindow.getYsoClassLoader();
        if (loader != null) {
            for (URL url : loader.getURLs()) {
                try {
                    entries.add(new File(url.toURI()).getAbsolutePath());
                } catch (Exception ignored) {
                    entries.add(url.getPath());
                }
            }
        }
        String currentCp = System.getProperty("java.class.path");
        if (currentCp != null && !currentCp.trim().isEmpty()) {
            entries.add(currentCp);
        }
        return String.join(File.pathSeparator, entries);
    }

    // ──── 自定义链持久化 ──────────────────────────────────────────────────────────

    /** 保存所有自定义链到 payloads/custom-chains.json */
    private void saveCustomChains() {
        try {
            List<ChainInfo> customs = allChains.stream()
                .filter(ChainInfo::isCustom)
                .collect(Collectors.toList());
            java.nio.file.Path path = java.nio.file.Paths.get(CUSTOM_CHAINS_FILE);
            java.nio.file.Files.createDirectories(path.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), customs);
        } catch (Exception e) {
            System.err.println("保存自定义链失败: " + e.getMessage());
        }
    }

    /** 从 payloads/custom-chains.json 加载自定义链，返回列表（文件不存在时返回空列表） */
    private List<ChainInfo> loadCustomChains() {
        java.nio.file.Path path = java.nio.file.Paths.get(CUSTOM_CHAINS_FILE);
        if (!java.nio.file.Files.exists(path)) return Collections.emptyList();
        try {
            return MAPPER.readValue(path.toFile(),
                new TypeReference<List<ChainInfo>>() {});
        } catch (Exception e) {
            System.err.println("加载自定义链失败: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 显示自定义链编写说明和示例代码。
     */
    private void showCustomChainHelp() {
        Alert help = new Alert(Alert.AlertType.INFORMATION);
        help.setTitle("如何编写自定义链");
        help.setHeaderText("自定义链编写指南");

        TextArea content = new TextArea();
        content.setEditable(false);
        content.setWrapText(true);
        content.setStyle("-fx-font-family: Consolas; -fx-font-size: 11; -fx-background-color: #fafafa;");
        content.setPrefSize(620, 520);
        content.setText(JavaCompileUtil.readHelpResource("custom-chain-help.txt"));

        help.getDialogPane().setContent(content);
        help.getButtonTypes().setAll(ButtonType.OK);
        help.showAndWait();
    }

    /**
     * 从 .class 文件路径推导完整类名。
     * 从文件所在目录向上查找 classpath 根（包含 com/ 或 org/ 等包根的目录），
     * 然后将相对路径转为全限定类名。
     */
    private String deriveClassName(File file) {
        return JavaCompileUtil.deriveClassName(file);
    }

    /**
     * 将选中的 payload class 文件复制到 payloads/ 目录下。
     * 返回最终文件的路径。
     */
    private String copyPayloadFile(String srcPath, String chainName, String className) throws Exception {
        File srcFile = new File(srcPath);
        if (!srcFile.exists()) throw new Exception("文件不存在: " + srcPath);

        java.nio.file.Path payloadDir = java.nio.file.Paths.get("payloads");
        java.nio.file.Files.createDirectories(payloadDir);

        String fileName = srcFile.getName();
        String relativeClassPath = className.replace('.', java.io.File.separatorChar) + ".class";
        java.nio.file.Path relativeClass = java.nio.file.Paths.get(relativeClassPath);

        if (fileName.endsWith(".class")) {
            java.nio.file.Path dest = payloadDir.resolve(relativeClass);
            java.nio.file.Files.createDirectories(dest.getParent());
            java.nio.file.Files.copy(srcFile.toPath(), dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            JavaCompileUtil.copyInnerClasses(srcFile, dest.getParent());
            return dest.toString();
        } else {
            throw new Exception("不支持的文件类型: " + fileName + "（仅支持 .class）");
        }
    }
}

