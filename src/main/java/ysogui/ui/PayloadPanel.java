package ysogui.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import ysogui.core.MemshellManager;
import ysogui.core.PayloadGenerator;
import ysogui.core.PayloadGenerator.OutputFormat;
import ysogui.core.ChainMetaManager;
import ysogui.model.ChainInfo;
import ysogui.model.ChainMeta.ParamType;
import ysogui.model.MemshellConfig;

import java.io.File;
import javafx.util.Duration;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/**
 * 右侧 Payload 生成面板。
 *
 * 展示当前链的：名称、作者、依赖版本、输入框（命令/CLASS:xxx）、
 * 格式选择、生成按钮、输出区域、一键复制/保存。
 */
public class PayloadPanel extends VBox {
    private static final String MEMSHELL_PLACEHOLDER_NAME = "（不选择内存马）";

    private final MainWindow mainWindow;
    private PayloadGenerator generator;
    private ChainInfo currentChain;

    // ──── UI 组件 ─────────────────────────────────────────────────────────────
    private Label chainNameLabel;
    private Label authorLabel;
    private TextArea depsArea;
    private TextField cmdField;
    private ComboBox<OutputFormat> formatCombo;
    private Button generateBtn;
    private Label statusLabel;
    private OutputPanel outputPanel;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "payload-gen");
        t.setDaemon(true);
        return t;
    });

    // 生成任务的超时时间（秒）
    private static final int GENERATE_TIMEOUT_SECONDS = 60;

    public PayloadPanel(MainWindow mainWindow) {
        this.mainWindow = mainWindow;
        setStyle("-fx-background-color: #f5f5f5;");
        setMinWidth(260);
        setPrefWidth(300);
        setSpacing(0);
        setPadding(Insets.EMPTY);

        getChildren().addAll(
            buildHeader(),
            buildInfoSection(),
            buildGenerateSection()
        );
    }

    // ──── Header ──────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        Label title = new Label("Payload 生成");
        title.setStyle("-fx-text-fill: #2563a0; -fx-font-size: 13; -fx-font-weight: bold;");
        HBox box = new HBox(title);
        box.setStyle("-fx-background-color: #e8e8e8; -fx-padding: 8 10;");
        return box;
    }

    // ──── 链信息区 ────────────────────────────────────────────────────────────

    private VBox buildInfoSection() {
        chainNameLabel = new Label("未选择");
        chainNameLabel.setStyle("-fx-text-fill: #c07020; -fx-font-size: 14; -fx-font-weight: bold; -fx-font-family: Consolas;");
        chainNameLabel.setWrapText(true);

        authorLabel = new Label("作者：-");
        authorLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 11;");

        Label depsLbl = new Label("依赖版本：");
        depsLbl.setStyle("-fx-text-fill: #555; -fx-font-size: 11;");

        depsArea = new TextArea("请先选择链");
        depsArea.setEditable(false);
        depsArea.setPrefRowCount(3);
        depsArea.setStyle(
            "-fx-background-color: #fff; -fx-text-fill: #2e8b57; " +
            "-fx-font-family: Consolas; -fx-font-size: 11; -fx-border-color: #ccc;"
        );

        VBox box = new VBox(6, chainNameLabel, authorLabel, depsLbl, depsArea);
        box.setPadding(new Insets(10));
        box.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: transparent transparent #ddd transparent;");
        return box;
    }

    // ──── 生成区 ──────────────────────────────────────────────────────────────

    private Label memshellHint;
    private ComboBox<MemshellConfig> memshellCombo;
    private Label dnslogHint;
    private FlowPane dnslogButtons;

    private VBox buildGenerateSection() {
        // 命令输入
        Label cmdLbl = new Label("命令 / CLASS:模板名");
        cmdLbl.setStyle("-fx-text-fill: #555; -fx-font-size: 11;");

        cmdField = new TextField("calc");
        cmdField.setStyle(
            "-fx-background-color: #fff; -fx-text-fill: #333; " +
            "-fx-border-color: #ccc; -fx-font-family: Consolas; -fx-font-size: 12; -fx-padding: 4 8;"
        );
        cmdField.setPromptText("如：calc 或 CLASS:TomcatFilterMemShell");

        memshellHint = new Label("CLASS: 模板：");
        memshellHint.setStyle("-fx-text-fill: #999; -fx-font-size: 10;");

        memshellCombo = new ComboBox<>();
        memshellCombo.setMaxWidth(Double.MAX_VALUE);
        memshellCombo.setPromptText("选择 yso 模板后自动填入 CLASS:名称");
        memshellCombo.setStyle("-fx-background-color: #fff; -fx-text-fill: #333; -fx-font-size: 11;");
        memshellCombo.setCellFactory(lv -> new ListCell<MemshellConfig>() {
            @Override
            protected void updateItem(MemshellConfig item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : formatMemshellOption(item));
            }
        });
        memshellCombo.setButtonCell(new ListCell<MemshellConfig>() {
            @Override
            protected void updateItem(MemshellConfig item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : formatMemshellOption(item));
            }
        });
        memshellCombo.getItems().add(createMemshellPlaceholder());
        for (MemshellConfig config : MemshellManager.getBuiltins().values()) {
            memshellCombo.getItems().add(config);
        }
        memshellCombo.setOnAction(e -> {
            MemshellConfig config = memshellCombo.getValue();
            if (config != null && isPlaceholderMemshell(config)) {
                cmdField.setText("calc");
            } else if (config != null && config.getName() != null && !config.getName().trim().isEmpty()) {
                cmdField.setText("CLASS:" + config.getName().trim());
            }
        });

        // DNSLog 快捷按钮区域
        dnslogHint = new Label("DNSLog 平台：");
        dnslogHint.setStyle("-fx-text-fill: #999; -fx-font-size: 10;");

        dnslogButtons = new FlowPane(4, 4);
        String[][] dnslogPlatforms = {
            {"dnslog.cn", "http://xxx.dnslog.cn"},
            {"ceye.io", "http://xxx.ceye.io"},
            {"burpcollaborator", "http://xxx.burpcollaborator.net"},
            {"interactsh", "http://xxx.oast.fun"}
        };
        for (String[] platform : dnslogPlatforms) {
            Button b = new Button(platform[0]);
            b.setStyle("-fx-background-color: #fff0e0; -fx-text-fill: #b06000; " +
                       "-fx-border-color: #d0a060; -fx-font-size: 10; -fx-padding: 2 6; -fx-cursor: hand;");
            b.setOnAction(e -> cmdField.setText(platform[1]));
            dnslogButtons.getChildren().add(b);
        }

        // 输出格式
        Label fmtLbl = new Label("输出格式");
        fmtLbl.setStyle("-fx-text-fill: #555; -fx-font-size: 11;");

        formatCombo = new ComboBox<>();
        formatCombo.getItems().addAll(OutputFormat.values());
        formatCombo.setValue(OutputFormat.BASE64);
        formatCombo.setMaxWidth(Double.MAX_VALUE);
        formatCombo.setStyle("-fx-background-color: #fff; -fx-text-fill: #333;");

        // 生成按钮
        generateBtn = new Button("▶  生 成  P a y l o a d");
        generateBtn.setMaxWidth(Double.MAX_VALUE);
        generateBtn.setStyle(
            "-fx-background-color: #2e8b57; -fx-text-fill: #fff; " +
            "-fx-border-color: #3aa868; -fx-font-size: 13; -fx-font-weight: bold; -fx-padding: 8;"
        );
        generateBtn.setOnAction(e -> doGenerate());
        generateBtn.setOnMouseEntered(e -> generateBtn.setStyle(generateBtn.getStyle().replace("#2e8b57", "#3aa868")));
        generateBtn.setOnMouseExited(e  -> generateBtn.setStyle(generateBtn.getStyle().replace("#3aa868", "#2e8b57")));

        Button saveBtn = new Button("保存文件");
        saveBtn.setMaxWidth(Double.MAX_VALUE);
        saveBtn.setStyle("-fx-background-color: #e0e0e0; -fx-text-fill: #333; " +
                         "-fx-border-color: #bbb; -fx-font-size: 11; -fx-padding: 4 10;");
        saveBtn.setOnAction(e -> saveToFile());

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 11;");
        statusLabel.setWrapText(true);

        VBox box = new VBox(6, cmdLbl, cmdField, memshellHint, memshellCombo,
                            dnslogHint, dnslogButtons,
                            new Separator(), fmtLbl, formatCombo, generateBtn, saveBtn, statusLabel);
        box.setPadding(new Insets(10));
        box.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: transparent transparent #ddd transparent;");
        return box;
    }

    // ──── 生成逻辑 ────────────────────────────────────────────────────────────

    // 保存最近一次生成的原始字节，供 Raw / CLASS_FILE 保存文件用
    private byte[] lastRawBytes = null;
    private String lastDisplayText = "";

    private void doGenerate() {
        if (generator == null) {
            mainWindow.showError("请先加载 ysoserial.jar");
            return;
        }
        if (currentChain == null) {
            mainWindow.showError("请先选择一条链");
            return;
        }

        String command = cmdField.getText().trim();
        if (command.isEmpty()) {
            mainWindow.showError("命令不能为空");
            return;
        }

        OutputFormat fmt = formatCombo.getValue();

        // CLASS_FILE 格式：直接提取模板 .class，不需要生成序列化 payload
        if (fmt == OutputFormat.CLASS_FILE) {
            doExportClassFile(command);
            return;
        }

        setStatus("生成中...");
        generateBtn.setDisable(true);
        lastDisplayText = "";
        if (outputPanel != null) outputPanel.clearSource(OutputPanel.Source.PAYLOAD);

        Future<?> future = executor.submit(() -> {
            try {
                byte[] rawBytes = generator.generateRawSmart(currentChain, command);
                lastRawBytes = rawBytes;

                if (fmt == OutputFormat.RAW) {
                    // Raw 格式只写文件，文本框仅做提示，不展示字节内容
                    Platform.runLater(() -> {
                        lastDisplayText = "[Raw 字节已就绪，点击「保存文件」写出 .bin]";
                        if (outputPanel != null) outputPanel.setSourceContent(OutputPanel.Source.PAYLOAD, lastDisplayText);
                        setStatus("✓ Raw 生成成功  " + rawBytes.length + " bytes，请保存文件");
                        generateBtn.setDisable(false);
                    });
                } else {
                    // 用 convertRaw() 复用已生成的字节，避免再次调用 ysoserial
                    String result = generator.convertRaw(rawBytes, fmt);
                    Platform.runLater(() -> {
                        lastDisplayText = result;
                        if (outputPanel != null) outputPanel.setSourceContent(OutputPanel.Source.PAYLOAD, result);
                        setStatus("✓ 生成成功  " + rawBytes.length + " bytes → "
                                + result.length() + " chars (" + fmt + ")");
                        generateBtn.setDisable(false);
                    });
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setStatus("✗ 失败: " + e.getMessage());
                    generateBtn.setDisable(false);
                });
            }
        });

        // 超时保护：在 FX 线程用 Timeline 检查任务是否完成
        javafx.animation.Timeline timeout = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.seconds(GENERATE_TIMEOUT_SECONDS), ev -> {
                if (generateBtn.isDisabled()) {
                    future.cancel(true); // 中断生成线程
                    setStatus("✗ 生成超时（" + GENERATE_TIMEOUT_SECONDS + "s），请检查参数或换条链");
                    generateBtn.setDisable(false);
                }
            })
        );
        timeout.play();
    }

    /**
     * 导出当前链的原始 .class 文件。
     * 内置链：从 ysoserial jar 的 payloads 包中提取。
     * 自定义链：从本地 payloads/ 目录读取。
     */
    private void doExportClassFile(String command) {
        if (currentChain == null) {
            mainWindow.showError("请先选择一条链");
            return;
        }

        String chainName = currentChain.getName();
        generateBtn.setDisable(true);
        lastDisplayText = "";
        if (outputPanel != null) outputPanel.clearSource(OutputPanel.Source.PAYLOAD);
        setStatus("提取链 .class ...");

        Future<?> future = executor.submit(() -> {
            try {
                byte[] classBytes;
                String classPath;
                if (currentChain.isCustom()) {
                    // 自定义链：从本地文件读取
                    String jarPath = currentChain.getCustomJarPath();
                    java.io.File f = new java.io.File(jarPath);
                    if (!f.exists()) throw new Exception("自定义链文件不存在: " + jarPath);
                    classBytes = java.nio.file.Files.readAllBytes(f.toPath());
                    classPath = jarPath;
                } else {
                    // 内置链：从 ysoserial jar 提取
                    classBytes = generator.extractChainClass(chainName);
                    classPath = "ysoserial/payloads/" + chainName + ".class";
                }
                lastRawBytes = classBytes;

                String finalClassPath = classPath;
                Platform.runLater(() -> {
                    lastDisplayText = "[链 .class 已就绪，点击「保存文件」写出 .class]\n"
                                    + "链名: " + chainName + "\n"
                                    + "类路径: " + finalClassPath + "\n"
                                    + "大小: " + classBytes.length + " bytes";
                    if (outputPanel != null) outputPanel.setSourceContent(OutputPanel.Source.PAYLOAD, lastDisplayText);
                    setStatus("✓ 链 .class 提取成功  " + classBytes.length + " bytes，请保存文件");
                    generateBtn.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setStatus("✗ 提取失败: " + e.getMessage());
                    generateBtn.setDisable(false);
                });
            }
        });

        // 超时保护
        javafx.animation.Timeline timeout = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.seconds(GENERATE_TIMEOUT_SECONDS), ev -> {
                if (generateBtn.isDisabled()) {
                    future.cancel(true);
                    setStatus("✗ 提取超时（" + GENERATE_TIMEOUT_SECONDS + "s）");
                    generateBtn.setDisable(false);
                }
            })
        );
        timeout.play();
    }

    private void saveToFile() {
        if (lastRawBytes == null && (lastDisplayText == null || lastDisplayText.isEmpty())) {
            mainWindow.showError("还没有生成任何 Payload");
            return;
        }

        OutputFormat fmt = formatCombo.getValue();
        String chainName = currentChain != null ? currentChain.getName() : "payload";

        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存 Payload");

        // 根据格式设置默认文件名
        if (fmt == OutputFormat.CLASS_FILE) {
            // 导出链的原始 .class 文件，用链名命名
            chooser.setInitialFileName(chainName + ".class");
            chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Class 文件", "*.class"));
        } else if (fmt == OutputFormat.RAW) {
            chooser.setInitialFileName(chainName + ".bin");
            chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Binary", "*.bin", "*.ser"));
        } else {
            chooser.setInitialFileName(chainName + ".txt");
            chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Text", "*.txt"));
        }

        File file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) return;

        try {
            if ((fmt == OutputFormat.RAW || fmt == OutputFormat.CLASS_FILE) && lastRawBytes != null) {
                // Raw / CLASS_FILE：直接写字节
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(lastRawBytes);
                }
                setStatus("✓ 已保存: " + file.getName()
                        + "  (" + lastRawBytes.length + " bytes)");
            } else {
                // 其他格式：UTF-8 文本写出
                String text = lastDisplayText != null ? lastDisplayText : "";
                try (OutputStreamWriter ow = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                    ow.write(text);
                }
                setStatus("✓ 已保存: " + file.getName()
                        + "  (" + text.length() + " chars)");
            }
        } catch (Exception e) {
            mainWindow.showError("保存失败: " + e.getMessage());
        }
    }

    // ──── Public API ─────────────────────────────────────────────────────────

    public void setChain(ChainInfo chain) {
        this.currentChain = chain;
        Platform.runLater(() -> {
            if (chain == null) {
                chainNameLabel.setText("未选择链");
                authorLabel.setText("");
                depsArea.setText("");
                lastDisplayText = "";
                if (outputPanel != null) outputPanel.clearSource(OutputPanel.Source.PAYLOAD);
                statusLabel.setText("");
                return;
            }
            chainNameLabel.setText(chain.getName());
            if (chain.isCustom()) {
                authorLabel.setText("作者：自定义");
                depsArea.setText(chain.getDependenciesFormatted());
            } else {
                authorLabel.setText("作者：" + chain.getAuthor());
                depsArea.setText(chain.getDependenciesFormatted());
            }
            lastDisplayText = "";
            if (outputPanel != null) outputPanel.clearSource(OutputPanel.Source.PAYLOAD);
            statusLabel.setText("");

            // URLDNS 链适配：命令参数是 DNSLog URL
            adaptCmdFieldForChain(chain);
        });
    }

    /**
     * 根据链类型适配命令输入框。
     * 使用 ChainMetaManager 的参数类型推断，自动调整提示文本、默认值和快捷按钮。
     */
    private void adaptCmdFieldForChain(ChainInfo chain) {
        String name = chain.getName();
        ParamType paramType = ChainMetaManager.getParamType(name);
        String hint = ChainMetaManager.getParamHint(name);

        cmdField.setPromptText(hint);

        // 动态显隐快捷按钮
        boolean showDnslogButtons = (paramType == ParamType.DNSLOG);
        boolean showMemshellCombo = ChainMetaManager.supportsBuiltinTemplate(name);

        memshellHint.setVisible(showMemshellCombo);
        memshellHint.setManaged(showMemshellCombo);
        memshellCombo.setVisible(showMemshellCombo);
        memshellCombo.setManaged(showMemshellCombo);

        dnslogHint.setVisible(showDnslogButtons);
        dnslogHint.setManaged(showDnslogButtons);
        dnslogButtons.setVisible(showDnslogButtons);
        dnslogButtons.setManaged(showDnslogButtons);

        // 根据参数类型调整当前值
        String currentText = cmdField.getText().trim();
        boolean isDefaultCmd = "calc".equals(currentText);
        boolean isDnslog = currentText.contains("dnslog") || currentText.contains("ceye")
                        || currentText.contains("burpcollaborator") || currentText.contains("oast.fun");
        boolean isHostPort = currentText.contains(":") && !currentText.contains("/") && !currentText.contains(" ");
        boolean isJndi = currentText.startsWith("ldap://") || currentText.startsWith("rmi://");

        switch (paramType) {
            case DNSLOG:
                if (isDefaultCmd) cmdField.setText("http://xxx.dnslog.cn");
                break;
            case HOSTPORT:
                if (isDefaultCmd || isDnslog) cmdField.setText("127.0.0.1:1099");
                break;
            case JNDI:
                if (isDefaultCmd || isDnslog || isHostPort) cmdField.setText("ldap://127.0.0.1:1389/obj");
                break;
            case URL:
                if (isDefaultCmd || isDnslog || isJndi) cmdField.setText("http://127.0.0.1:8080/payload");
                break;
            case CLASSNAME:
                // TemplatesImpl 路线的链：保持当前命令，因为 CLASS: 和命令都可以
                if (isDnslog || isHostPort || isJndi) cmdField.setText("calc");
                break;
            default: // COMMAND
                if (isDnslog || isHostPort || isJndi) cmdField.setText("calc");
                break;
        }
    }


    public void setOutputPanel(OutputPanel outputPanel) {
        this.outputPanel = outputPanel;
        if (this.outputPanel != null) {
            this.outputPanel.setSourceContent(OutputPanel.Source.PAYLOAD, lastDisplayText != null ? lastDisplayText : "");
        }
    }
    public void setGenerator(PayloadGenerator gen) {
        this.generator = gen;
        refreshMemshellOptions();
    }

    private void refreshMemshellOptions() {
        if (memshellCombo == null) {
            return;
        }
        MemshellConfig current = memshellCombo.getValue();
        memshellCombo.getItems().clear();
        memshellCombo.getItems().add(createMemshellPlaceholder());
        for (MemshellConfig config : MemshellManager.getBuiltins().values()) {
            memshellCombo.getItems().add(config);
        }
        if (current != null && !isPlaceholderMemshell(current)) {
            for (MemshellConfig config : memshellCombo.getItems()) {
                if (config != null && !isPlaceholderMemshell(config) && current.getName().equals(config.getName())) {
                    memshellCombo.setValue(config);
                    return;
                }
            }
            memshellCombo.setValue(memshellCombo.getItems().get(0));
        } else {
            memshellCombo.setValue(memshellCombo.getItems().get(0));
        }
    }

    private String formatMemshellOption(MemshellConfig config) {
        if (isPlaceholderMemshell(config)) {
            return MEMSHELL_PLACEHOLDER_NAME;
        }
        String mode = config.isEchoMode() ? "echo" : "connect";
        String type = config.getType() != null && !config.getType().trim().isEmpty()
            ? config.getType().trim() : "-";
        String server = config.getServerType() != null && !config.getServerType().trim().isEmpty()
            ? config.getServerType().trim() : "-";
        return config.getName() + " [" + mode + " | " + type + " | " + server + "]";
    }

    private MemshellConfig createMemshellPlaceholder() {
        MemshellConfig placeholder = new MemshellConfig();
        placeholder.setName(MEMSHELL_PLACEHOLDER_NAME);
        placeholder.setMode("placeholder");
        placeholder.setType("placeholder");
        placeholder.setServerType("placeholder");
        return placeholder;
    }

    private boolean isPlaceholderMemshell(MemshellConfig config) {
        return config != null && MEMSHELL_PLACEHOLDER_NAME.equals(config.getName());
    }

    /**
     * 更新状态栏文字，并根据前缀播放对应颜色动画：
     *  ✓ 开头 → 绿色渐变（成功）
     *  ✗ 开头 → 红色渐变（失败）
     *  其他   → 灰色（中性提示）
     */
    private void setStatus(String msg) {
        Platform.runLater(() -> {
            statusLabel.setText(msg);

            // 根据消息类型选择颜色
            String targetColor;
            if (msg.startsWith("✓")) {
                targetColor = "#2e8b57"; // 成功：绿
            } else if (msg.startsWith("✗")) {
                targetColor = "#cc3333"; // 失败：红
            } else {
                targetColor = "#888888"; // 中性：灰
                statusLabel.setStyle(statusLabel.getStyle()
                    .replaceAll("-fx-text-fill:[^;]+;", "-fx-text-fill: #888;"));
                return;
            }

            // 先设置目标颜色，再用 Timeline 淡回灰色（给用户视觉反馈后自动复原）
            final String bright = targetColor;
            statusLabel.setStyle("-fx-text-fill: " + bright + "; -fx-font-size: 11;");

            Timeline fade = new Timeline(
                new KeyFrame(Duration.ZERO
                    // 起始帧：当前已是 bright 颜色，不需要再设
                ),
                new KeyFrame(Duration.millis(1800),
                    e2 -> statusLabel.setStyle("-fx-text-fill: " + bright + "; -fx-font-size: 11;")
                ),
                new KeyFrame(Duration.millis(3000),
                    e2 -> statusLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 11;")
                )
            );
            fade.play();
        });
    }
}

