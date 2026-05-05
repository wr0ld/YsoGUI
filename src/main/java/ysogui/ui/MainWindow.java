package ysogui.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import ysogui.core.ChainGraphs;
import ysogui.core.ExploitRunner;
import ysogui.core.PayloadGenerator;
import ysogui.core.YsoLoader;
import ysogui.core.AppPaths;
import ysogui.model.ChainInfo;
import ysogui.model.GraphData;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Properties;

/**
 * 主窗口类 - 管理整个应用界面
 *  左侧链列表 (ChainListPanel)
 *  中部调用图编辑器 (GraphEditorPanel)
 *  右侧 Payload 生成面板 (PayloadPanel)
 */
public class MainWindow {

    private final Stage stage;
    private YsoLoader loader;
    private SplitPane rootSplit;
    private SplitPane contentSplit;
    private boolean logCollapsed;
    private double logDividerPosition = 0.78;

    private ChainListPanel   chainListPanel;
    private GraphEditorPanel graphEditor;
    private PayloadPanel     payloadPanel;
    
    private ExploitPanel     exploitPanel;
    private MemshellPanel    memshellPanel;   // 内存马面板
    private OutputPanel      outputPanel;
    private Label            statusLabel;
    private Button           logToggleButton;
    private Label            ysoStatusLabel;  // ysoserial 加载状态
    private Label            marStatusLabel;  // marshalsec 加载状态
    private Label            chainCountLabel;   // 链数量显示
    private final Properties appState = new Properties();

    public MainWindow(Stage stage) {
        this.stage = stage;
        stage.setTitle("YsoGUI  - Java 反序列化利用工具 |  by wr0ld");
        // 设置应用图标
        try {
            Image appIcon = new Image(getClass().getResourceAsStream("/icon.jpg"));
            if (appIcon.isError()) {
                appIcon = new Image("file:icon.jpg");
            }
            if (!appIcon.isError() && appIcon.getWidth() > 0) {
                stage.getIcons().add(appIcon);
            }
        } catch (Exception ignored) {}
        stage.setWidth(1280);
        stage.setHeight(860);
        stage.setMinWidth(900);
        stage.setMinHeight(600);

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #ffffff;");
        root.setTop(buildMenuBar());
        root.setCenter(buildSplitPane());
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root);
        applyLightTheme(scene);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> shutdown());

        loadAppState();
        loadLastJar();
    }

    // ──── MenuBar 菜单栏 ──────────────────────────────────────────────────────

    private MenuBar buildMenuBar() {
        // 文件菜单
        Menu fileMenu = new Menu("文件");
        MenuItem miOpen = new MenuItem("加载 ysoserial.jar...");
        MenuItem miOpenMarshalsec = new MenuItem("加载 marshalsec.jar...");
        MenuItem miExit = new MenuItem("退出");
        miOpen.setOnAction(e -> openJar());
        miOpenMarshalsec.setOnAction(e -> openMarshalsecJar());
        miExit.setOnAction(e -> Platform.exit());
        fileMenu.getItems().addAll(miOpen, miOpenMarshalsec, new SeparatorMenuItem(), miExit);

        // 帮助菜单
        Menu helpMenu = new Menu("帮助");
        MenuItem miGitHubProject = new MenuItem("打开 GitHub 项目页面");
        MenuItem miAbout = new MenuItem("关于 YsoGUI");
        miGitHubProject.setOnAction(e -> showGitHubProject());
        miAbout.setOnAction(e -> showAbout());
        helpMenu.getItems().addAll(miGitHubProject, miAbout);

        MenuBar bar = new MenuBar(fileMenu, helpMenu);
        bar.setStyle("-fx-background-color: #e8e8e8;");
        return bar;
    }

    // ──── 主界面 SplitPane 布局 ──────────────────────────────────────────────────

    private SplitPane buildSplitPane() {
        chainListPanel = new ChainListPanel(this);
        graphEditor    = new GraphEditorPanel();
        payloadPanel   = new PayloadPanel(this);
        exploitPanel   = new ExploitPanel(this);
        memshellPanel  = new MemshellPanel(this);
        outputPanel    = new OutputPanel(this);
        payloadPanel.setOutputPanel(outputPanel);
        exploitPanel.setOutputPanel(outputPanel);

        Tab tabPayload = new Tab("Payload 生成", wrapScrollable(payloadPanel));
        tabPayload.setClosable(false);
        tabPayload.setStyle("-fx-text-fill: #2e8b57;");

        Tab tabExploit = new Tab("Exploit", wrapScrollable(exploitPanel));
        tabExploit.setClosable(false);
        tabExploit.setStyle("-fx-text-fill: #e07020;");

        Tab tabMemshell = new Tab("内存马", wrapScrollable(memshellPanel));
        tabMemshell.setClosable(false);
        tabMemshell.setStyle("-fx-text-fill: #7c3aed;");

        TabPane rightPane = new TabPane(tabPayload, tabExploit, tabMemshell);
        rightPane.setStyle(
            "-fx-background-color: #f5f5f5;" +
            "-fx-tab-min-width: 100;" +
            "-fx-tab-max-width: 140;"
        );
        rightPane.setMinWidth(280);
        outputPanel.setActiveSource(OutputPanel.Source.PAYLOAD);
        rightPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == tabPayload) {
                outputPanel.setActiveSource(OutputPanel.Source.PAYLOAD);
            } else if (newTab == tabExploit) {
                outputPanel.setActiveSource(OutputPanel.Source.EXPLOIT);
            } else if (newTab == tabMemshell) {
                outputPanel.setActiveSource(OutputPanel.Source.MEMSHELL);
            } else {
                outputPanel.setActiveSource(OutputPanel.Source.ALL);
            }
        });

        contentSplit = new SplitPane(chainListPanel, graphEditor, rightPane);
        contentSplit.setDividerPositions(0.18, 0.68);
        contentSplit.setStyle("-fx-background-color: #ffffff;");

        rootSplit = new SplitPane(contentSplit, outputPanel);
        rootSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        rootSplit.setDividerPositions(0.78);
        rootSplit.setStyle("-fx-background-color: #ffffff;");
        return rootSplit;
    }

    private ScrollPane wrapScrollable(Region content) {
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(false);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setPannable(true);
        scrollPane.setStyle("-fx-background: #f5f5f5; -fx-background-color: #f5f5f5;");
        scrollPane.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            double delta = event.getDeltaY() * 0.0060;
            double next = scrollPane.getVvalue() - delta;
            scrollPane.setVvalue(Math.max(0.0, Math.min(1.0, next)));
            event.consume();
        });
        content.setMaxWidth(Double.MAX_VALUE);
        return scrollPane;
    }

    // ──── Status Bar 状态栏 ─────────────────────────────────────────────────────

    private HBox buildStatusBar() {
        statusLabel = new Label("就绪 |  请加载 ysoserial.jar");
        statusLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 11;");

        // jar 加载状态标签 - 初始状态为未加载
        ysoStatusLabel = new Label(" ysoserial 未加载");
        ysoStatusLabel.setStyle("-fx-background-color: #f0e0e0; -fx-text-fill: #cc3333; " +
            "-fx-font-size: 10; -fx-padding: 1 6; -fx-background-radius: 3;");

        marStatusLabel = new Label(" marshalsec 未加载");
        marStatusLabel.setStyle("-fx-background-color: #f0e0e0; -fx-text-fill: #cc3333; " +
            "-fx-font-size: 10; -fx-padding: 1 6; -fx-background-radius: 3;");

        chainCountLabel = new Label("");
        chainCountLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 10; -fx-padding: 1 4;");

        logToggleButton = new Button("收起日志");
        logToggleButton.setFocusTraversable(false);
        logToggleButton.setStyle(
            "-fx-background-color: #e0e0e0; -fx-text-fill: #555; " +
            "-fx-border-color: #bbb; -fx-font-size: 11; -fx-padding: 1 8;"
        );
        logToggleButton.setOnAction(e -> toggleLogPanel());

        Label hint = new Label("");

        HBox bar = new HBox(6, statusLabel, ysoStatusLabel, marStatusLabel, chainCountLabel, logToggleButton, hint);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        bar.setStyle(
            "-fx-background-color: #e8e8e8; " +
            "-fx-border-color: #ccc transparent transparent transparent; " +
            "-fx-padding: 4 10;"
        );
        return bar;
    }

    // ──── 加载 Jar 文件相关方法 ──────────────────────────────────────────────────

    private void openJar() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 Y4er/ysoserial jar 文件");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("JAR 文件", "*.jar")
        );
        String lastDir = appState.getProperty("lastDir");
        if (lastDir != null) chooser.setInitialDirectory(new File(lastDir));

        File file = chooser.showOpenDialog(stage);
        if (file != null) {
            loadJar(file);
        }
    }

    public void loadJar(File file) {
        setStatus("正在加载 " + file.getName() + " ...");
        // 后台线程加载避免阻塞 UI
        new Thread(() -> {
            try {
                YsoLoader newLoader = new YsoLoader(file);
                PayloadGenerator gen = new PayloadGenerator(newLoader.getClassLoader());
                List<ChainInfo> chains = newLoader.getChains();

                // ExploitRunner 需要 PayloadGenerator 的 ClassLoader
                ExploitRunner exploitRunner = new ExploitRunner(newLoader.getClassLoader());

                Platform.runLater(() -> {
                    loader = newLoader;
                    payloadPanel.setGenerator(gen);
                    exploitPanel.setExploitRunner(exploitRunner);
                    exploitPanel.setGenerator(gen);
                    memshellPanel.setGenerator(gen);
                    memshellPanel.setAvailableChains(chains);
                    chainListPanel.setChains(chains);
                    updateChainCount(chains.size(), chains.size());
                    setStatus("加载 " + file.getName()
                        + "  |  " + chains.size() + " 个链"
                        + "  |  调用图数量: " + ChainGraphs.getKnownChains().size() + " 个链");
                    stage.setTitle("YsoGUI  - " + file.getName());
                    // 设置应用图标
                    ysoStatusLabel.setText(" ysoserial 已加载");
                    ysoStatusLabel.setStyle("-fx-background-color: #e0f0e0; -fx-text-fill: #2e8b57; " +
                        "-fx-font-size: 10; -fx-padding: 1 6; -fx-background-radius: 3;");
                    // 保存当前加载的 jar 文件路径
                    appState.setProperty("lastJar", file.getAbsolutePath());
                    if (file.getParent() != null) {
                        appState.setProperty("lastDir", file.getParent());
                    }
                    saveAppState();

                    // ysoserial 加载后自动检测 lib/ 目录下的 marshalsec jar
                    autoDetectMarshalsec();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showError("加载失败: " + e.getMessage());
                    setStatus("加载失败");
                });
            }
        }, "jar-loader").start();
    }

    private void loadLastJar() {
        String path = appState.getProperty("lastJar");
        if (path != null) {
            File f = new File(path);
            if (f.exists()) {
                loadJar(f);
                return; // loadJar 中会调用 autoDetectMarshalsec
            }
        }

        // lib/ 目录下没有 ysoserial jar
        File libDir = new File("lib");
        if (!libDir.exists() || !libDir.isDirectory()) return;

        File[] jars = libDir.listFiles((dir, name) ->
            name.toLowerCase().contains("ysoserial") && name.endsWith(".jar"));
        if (jars != null && jars.length > 0) {
            loadJar(jars[0]);
        }
    }

    /**
     * 自动检测 lib/ 目录下的 marshalsec jar
     * 在 ysoserial 加载后自动调用
     */
    private void autoDetectMarshalsec() {
        if (loader == null) return; // 没有加载 ysoserial

        File libDir = new File("lib");
        if (!libDir.exists() || !libDir.isDirectory()) return;

        File[] jars = libDir.listFiles((dir, name) ->
            name.toLowerCase().contains("marshalsec") && name.endsWith(".jar"));
        if (jars != null && jars.length > 0) {
            loadMarshalsecJar(jars[0]);
        }
    }

    /**
     * 手动选择 marshalsec jar 文件
     */
    private void openMarshalsecJar() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 marshalsec jar 文件");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("JAR 文件", "*.jar")
        );
        // 优先选择 lib/ 目录
        File libDir = new File("lib");
        if (libDir.exists()) chooser.setInitialDirectory(libDir);
        else {
            String lastDir = appState.getProperty("lastDir");
            if (lastDir != null) chooser.setInitialDirectory(new File(lastDir));
        }

        File file = chooser.showOpenDialog(stage);
        if (file != null) {
            loadMarshalsecJar(file);
        }
    }

    /**
     * 加载 marshalsec jar 文件
     */
    private void loadMarshalsecJar(File marshalsecFile) {
        if (loader == null) {
            showError("请先加载 ysoserial.jar");
            return;
        }

        setStatus("正在加载 marshalsec: " + marshalsecFile.getName() + " ...");
        new Thread(() -> {
            try {
                // 获取 marshalsec jar 的 URL
                java.net.URL marshalsecUrl = marshalsecFile.toURI().toURL();
                java.net.URLClassLoader existingLoader = loader.getClassLoader();

                // 合并两个 jar 的 ClassLoader
                java.net.URL[] allUrls = new java.net.URL[]{
                    existingLoader.getURLs()[0],  // ysoserial jar
                    marshalsecUrl                  // marshalsec jar
                };
                java.net.URLClassLoader combinedLoader = new java.net.URLClassLoader(allUrls,
                    null);

                // 使用新的 ClassLoader 重新创建 YsoLoader
                YsoLoader newLoader = new YsoLoader(loader.getJarFile(), combinedLoader);
                PayloadGenerator gen = new PayloadGenerator(combinedLoader);
                ExploitRunner exploitRunner = new ExploitRunner(combinedLoader);

                Platform.runLater(() -> {
                    loader = newLoader;
                    payloadPanel.setGenerator(gen);
                    exploitPanel.setExploitRunner(exploitRunner);
                    exploitPanel.setGenerator(gen);
                    memshellPanel.setGenerator(gen);
                    setStatus("已加载 marshalsec: " + marshalsecFile.getName()
                        + "  |  JNDI 可用");
                    // 设置 marshalsec 加载状态
                    marStatusLabel.setText(" marshalsec 已加载");
                    marStatusLabel.setStyle("-fx-background-color: #e0f0e0; -fx-text-fill: #2e8b57; " +
                        "-fx-font-size: 10; -fx-padding: 1 6; -fx-background-radius: 3;");
                    // 更新窗口标题
                    String currentTitle = stage.getTitle();
                    if (!currentTitle.contains("marshalsec")) {
                        stage.setTitle(currentTitle + "  +  marshalsec");
                    }
                    // 保存配置
                    appState.setProperty("lastMarshalsec", marshalsecFile.getAbsolutePath());
                    saveAppState();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showError("加载 marshalsec 失败: " + e.getMessage());
                    setStatus("加载 marshalsec 失败");
                });
            }
        }, "marshalsec-loader").start();
    }


    /**
     * 閻ChainListPanel 更新链计数标签
     */
    public void updateChainCount(int total, int visible) {
        Platform.runLater(() -> {
            if (visible > 0 && visible < total) {
                chainCountLabel.setText(" " + visible + "/" + total + " 条链");
            } else {
                chainCountLabel.setText(total + " 条链");
            }
        });
    }

    public void onChainSelected(ChainInfo chain) {
        // 链选择器中选择链后，更新所有面板的链
        payloadPanel.setChain(chain);
        exploitPanel.setChain(chain);
        memshellPanel.setChain(chain);

        if (chain == null) {
            graphEditor.newEmptyGraph("");
            return;
        }

        graphEditor.setCurrentChainName(chain.getName());

        // 链选择器中选择链后，加载自定义或内置的链
        GraphData custom = graphEditor.loadCustomGraph(chain.getName());
        if (custom != null) {
            graphEditor.loadGraph(custom, true);
            graphEditor.setSourceCustom();
        } else {
            GraphData builtIn = ChainGraphs.getGraph(chain.getName());
            if (builtIn != null) {
                graphEditor.loadGraph(builtIn, true);
                graphEditor.setSourceBuiltIn();
            } else {
                graphEditor.newEmptyGraph(chain.getName());
            }
        }
    }

    // ──── About 关于对话框 ──────────────────────────────────────────────────────

    private void showAbout() {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("YsoGUI");
        a.setHeaderText("YsoGUI  v1.0.0");
        a.setContentText(
            "基于 Y4er/ysoserial 的 Java 反序列化利用工具 GUI 界面\n\n" +
            "ysoserial GUI 封装工具 / Payload & Exploit 一体化界面\n\n" +
            "作者: wr0ld\n" +
            "网站: wr0ld.github.io\n\n" +
            "功能特性:\n" +
            "  - 基于 Y4er/ysoserial 的 Java 反序列化/ 作者: wr0ld/ 内存马注入\n" +
            "  - 链浏览与调用图可视化 / 自定义链编辑\n" +
            "  - Payload 生成支持 Base64 / Gzip+Base64 / Hex 等格式\n" +
            "  - 支持 Y4er 内置 CLASS:xxx 模板\n" +
            "    - 链分类筛选\n" +
            "    - 自定义链无限制\n" +
            "    - 内存马注入\n" +
            "基于 AI 辅助开发的 Java 反序列化利用工具，支持多种攻击场景\n" +
            "GitHub: github.com/wr0ld/YsoGUI"
        );
        a.showAndWait();
    }

    // 浏览器打开 GitHub 项目页面
    private void showGitHubProject() {
        try {
            java.awt.Desktop.getDesktop().browse(new java.net.URI("https://github.com/wr0ld/YsoGUI"));
        } catch (Exception e) {
            showError("无法打开 GitHub 项目页面: " + e.getMessage());
        }
    }

    public void showError(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle("Error");
               a.setContentText(msg);
        a.showAndWait();
    }

    public void setStatus(String msg) {
        Platform.runLater(() -> statusLabel.setText(msg));
    }

    public Stage getStage() { return stage; }

    public java.net.URLClassLoader getYsoClassLoader() {
        return loader != null ? loader.getClassLoader() : null;
    }

    public void toggleLogPanel() {
        if (rootSplit == null) {
            return;
        }

        if (logCollapsed) {
            if (!rootSplit.getItems().contains(outputPanel)) {
                rootSplit.getItems().add(outputPanel);
            }
            rootSplit.setDividerPositions(logDividerPosition);
            logCollapsed = false;
            logToggleButton.setText("收起日志");
            return;
        }

        double[] positions = rootSplit.getDividerPositions();
        if (positions.length > 0) {
            logDividerPosition = positions[0];
        }
        rootSplit.getItems().remove(outputPanel);
        logCollapsed = true;
        logToggleButton.setText("展开日志");
    }

    private void loadAppState() {
        try {
            java.nio.file.Files.createDirectories(AppPaths.configDir());
            java.nio.file.Path path = AppPaths.appStateFile();
            if (java.nio.file.Files.exists(path)) {
                try (InputStream in = java.nio.file.Files.newInputStream(path)) {
                    appState.load(in);
                }
            }
        } catch (Exception ignored) {}
    }

    private void saveAppState() {
        try {
            java.nio.file.Files.createDirectories(AppPaths.configDir());
            try (OutputStream out = java.nio.file.Files.newOutputStream(AppPaths.appStateFile())) {
                appState.store(out, "YsoGUI local app state");
            }
        } catch (Exception ignored) {}
    }

    private void applyLightTheme(Scene scene) {
        // 应用浅主题
        scene.getRoot().setStyle("-fx-base: #f0f0f0; -fx-background: #ffffff;");
    }

    public void shutdown() {
        if (exploitPanel != null) {
            exploitPanel.shutdown();
        }
    }
}



