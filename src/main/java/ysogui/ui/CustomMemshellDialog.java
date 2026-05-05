package ysogui.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import ysogui.model.MemshellConfig;

import java.io.File;

final class CustomMemshellDialog {

    interface HelpAction {
        void show();
    }

    interface ClassNameDeriver {
        String derive(File file);
    }

    private CustomMemshellDialog() {
    }

    static MemshellConfig show(MemshellConfig existing,
                               HelpAction helpAction,
                               ClassNameDeriver classNameDeriver) {
        boolean editing = existing != null;
        Dialog<MemshellConfig> dlg = new Dialog<>();
        dlg.setTitle(editing ? "编辑自定义内存马" : "添加自定义内存马");
        dlg.setHeaderText(editing ? "修改自定义内存马配置" : "配置自定义内存马（需继承 AbstractTranslet）");

        Form form = buildForm(dlg, helpAction, classNameDeriver);
        if (editing) {
            populateForm(form, existing);
        }
        updateModeState(form);
        updateResponseModeState(form);

        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        javafx.scene.Node okBtn = dlg.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.setDisable(true);
        form.nameField.textProperty().addListener((obs, o, n) -> validate(okBtn, form));
        form.classNameField.textProperty().addListener((obs, o, n) -> validate(okBtn, form));
        form.classField.textProperty().addListener((obs, o, n) -> validate(okBtn, form));
        validate(okBtn, form);

        dlg.setResultConverter(btn -> btn == ButtonType.OK
            ? buildConfig(form, existing)
            : null);

        return dlg.showAndWait().orElse(null);
    }

    private static Form buildForm(Dialog<MemshellConfig> dlg, HelpAction helpAction, ClassNameDeriver classNameDeriver) {
        Form form = new Form();

        Hyperlink helpLink = new Hyperlink("如何编写自定义内存马？");
        helpLink.setStyle("-fx-font-size: 11;");
        helpLink.setOnAction(e -> helpAction.show());

        form.nameField = new TextField();
        form.nameField.setPromptText("如 MyFilterEcho");

        form.typeCombo = new ComboBox<>();
        form.typeCombo.getItems().addAll("Filter", "Servlet", "Listener", "Valve", "Agent");
        form.typeCombo.setValue("Filter");

        form.modeCombo = new ComboBox<>();
        form.modeCombo.getItems().addAll("echo", "connect");
        form.modeCombo.setValue("echo");
        form.modeCombo.setOnAction(e -> updateModeState(form));

        form.serverField = new TextField("Tomcat");
        form.serverField.setPromptText("适用服务器类型");

        form.requestMethodField = new ComboBox<>();
        form.requestMethodField.getItems().addAll("GET", "POST");
        form.requestMethodField.setValue("GET");

        form.triggerSourceField = new ComboBox<>();
        form.triggerSourceField.getItems().addAll("header", "query", "body", "cookie");
        form.triggerSourceField.setValue("header");

        form.triggerNameEchoField = new TextField("X-Cmd");
        form.triggerNameEchoField.setPromptText("触发名，如 X-Cmd / cmd");

        form.headerField = new TextField("X-Cmd");
        form.headerField.setPromptText("兼容 Header 名（可留空）");

        form.pathField = new TextField("/");
        form.pathField.setPromptText("触发路径");

        form.responseModeField = new ComboBox<>();
        form.responseModeField.getItems().addAll(
            "BODY_RAW", "BODY_BOUNDARY", "BODY_SUBSTRING",
            "BODY_REGEX", "HEADER_VALUE", "STATUS_ONLY", "NO_ECHO"
        );
        form.responseModeField.setValue("BODY_RAW");
        form.responseModeField.setOnAction(e -> updateResponseModeState(form));

        form.responseHeaderField = new TextField();
        form.responseHeaderField.setPromptText("HEADER_VALUE 模式使用");

        form.responseStartField = new TextField();
        form.responseStartField.setPromptText("BODY_SUBSTRING 起始标记");

        form.responseEndField = new TextField();
        form.responseEndField.setPromptText("BODY_SUBSTRING 结束标记");

        form.responseRegexField = new TextField();
        form.responseRegexField.setPromptText("BODY_REGEX 提取正则");

        form.verifyHintField = new TextArea();
        form.verifyHintField.setPromptText("无回显/弱回显时的验证提示");
        form.verifyHintField.setPrefRowCount(2);

        form.toolField = new TextField("godzilla");
        form.toolField.setPromptText("godzilla / behinder / custom");

        form.passField = new TextField("pass");
        form.passField.setPromptText("连接密码");

        form.keyField = new TextField("key");
        form.keyField.setPromptText("加密密钥");

        form.classField = new TextField();
        form.classField.setPromptText("选择 .class 文件");
        form.classField.setEditable(false);
        form.classField.setPrefWidth(300);

        form.classNameField = new TextField();
        form.classNameField.setPromptText("完整类名，如 com.example.MyShell");

        Button browseButton = new Button("浏览...");
        browseButton.setOnAction(ev -> browseClassFile(dlg, form, classNameDeriver));

        Label fileHint = new Label("仅支持导入 .class 文件");
        fileHint.setStyle("-fx-text-fill: #888; -fx-font-size: 10;");

        form.descArea = new TextArea();
        form.descArea.setPromptText("描述信息（可选）");
        form.descArea.setPrefRowCount(2);

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
        grid.add(new Label("帮助："), 0, row); grid.add(helpLink, 1, row); row++;
        grid.add(new Label("名称："), 0, row); grid.add(form.nameField, 1, row); row++;
        grid.add(new Label("类型："), 0, row); grid.add(form.typeCombo, 1, row); row++;
        grid.add(new Label("模式："), 0, row); grid.add(form.modeCombo, 1, row); row++;
        grid.add(new Label("适用："), 0, row); grid.add(form.serverField, 1, row); row++;

        grid.add(new Separator(), 0, row); grid.add(new Label("回显型配置"), 1, row); row++;
        grid.add(new Label("请求方法："), 0, row); grid.add(form.requestMethodField, 1, row); row++;
        grid.add(new Label("触发位置："), 0, row); grid.add(form.triggerSourceField, 1, row); row++;
        grid.add(new Label("触发名："), 0, row); grid.add(form.triggerNameEchoField, 1, row); row++;
        grid.add(new Label("触发Header："), 0, row); grid.add(form.headerField, 1, row); row++;
        grid.add(new Label("触发路径："), 0, row); grid.add(form.pathField, 1, row); row++;
        grid.add(new Label("提取模式："), 0, row); grid.add(form.responseModeField, 1, row); row++;
        grid.add(new Label("响应头名："), 0, row); grid.add(form.responseHeaderField, 1, row); row++;
        grid.add(new Label("开始标记："), 0, row); grid.add(form.responseStartField, 1, row); row++;
        grid.add(new Label("结束标记："), 0, row); grid.add(form.responseEndField, 1, row); row++;
        grid.add(new Label("提取正则："), 0, row); grid.add(form.responseRegexField, 1, row); row++;
        grid.add(new Label("验证提示："), 0, row); grid.add(form.verifyHintField, 1, row); row++;

        grid.add(new Separator(), 0, row); grid.add(new Label("连接型配置"), 1, row); row++;
        grid.add(new Label("连接工具："), 0, row); grid.add(form.toolField, 1, row); row++;
        grid.add(new Label("密码："), 0, row); grid.add(form.passField, 1, row); row++;
        grid.add(new Label("密钥："), 0, row); grid.add(form.keyField, 1, row); row++;

        grid.add(new Separator(), 0, row); grid.add(new Label("类文件配置"), 1, row); row++;
        grid.add(new Label("类文件："), 0, row);
        HBox fileRow = new HBox(6, form.classField, browseButton);
        HBox.setHgrow(form.classField, Priority.ALWAYS);
        grid.add(fileRow, 1, row); row++;
        grid.add(new Label(""), 0, row); grid.add(fileHint, 1, row); row++;
        grid.add(new Label("完整类名："), 0, row); grid.add(form.classNameField, 1, row); row++;
        grid.add(new Label("描述："), 0, row); grid.add(form.descArea, 1, row); row++;

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(450);
        scroll.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            double delta = event.getDeltaY();
            if (delta != 0) {
                scroll.setVvalue(scroll.getVvalue() - delta * 9.0 / Math.max(scroll.getHeight(), 1.0));
                event.consume();
            }
        });

        dlg.getDialogPane().setContent(scroll);
        return form;
    }

    private static void browseClassFile(Dialog<MemshellConfig> dlg, Form form, ClassNameDeriver classNameDeriver) {
        FileChooser fc = new FileChooser();
        fc.setTitle("选择内存马类文件");
        fc.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Class 文件", "*.class")
        );
        File file = fc.showOpenDialog(dlg.getDialogPane().getScene().getWindow());
        if (file == null) {
            return;
        }
        form.classField.setText(file.getAbsolutePath());
        if (form.classNameField.getText().trim().isEmpty()) {
            String derived = classNameDeriver.derive(file);
            if (derived != null) {
                form.classNameField.setText(derived);
            }
        }
        if (form.nameField.getText().trim().isEmpty()) {
            form.nameField.setText(file.getName().replaceAll("\\.class$", ""));
        }
    }

    private static void populateForm(Form form, MemshellConfig config) {
        form.nameField.setText(config.getName());
        form.typeCombo.setValue(config.getType() != null ? config.getType() : "Filter");
        form.modeCombo.setValue(config.getMode() != null ? config.getMode() : "echo");
        form.serverField.setText(config.getServerType() != null ? config.getServerType() : "");
        form.requestMethodField.setValue(valueOrDefault(config.getRequestMethod(), "GET"));
        form.triggerSourceField.setValue(valueOrDefault(config.getTriggerSource(), "header"));
        form.triggerNameEchoField.setText(firstNonBlank(config.getTriggerName(), config.getTriggerHeader(), "X-Cmd"));
        form.headerField.setText(valueOrDefault(config.getTriggerHeader(), ""));
        form.pathField.setText(valueOrDefault(config.getTriggerPath(), "/"));
        form.responseModeField.setValue(valueOrDefault(config.getResponseMode(), "BODY_RAW"));
        form.responseHeaderField.setText(valueOrDefault(config.getResponseHeader(), ""));
        form.responseStartField.setText(valueOrDefault(config.getResponseStart(), ""));
        form.responseEndField.setText(valueOrDefault(config.getResponseEnd(), ""));
        form.responseRegexField.setText(valueOrDefault(config.getResponseRegex(), ""));
        form.verifyHintField.setText(valueOrDefault(config.getVerifyHint(), ""));
        form.toolField.setText(valueOrDefault(config.getTool(), ""));
        form.passField.setText(valueOrDefault(config.getPassword(), ""));
        form.keyField.setText(valueOrDefault(config.getSecretKey(), ""));
        form.classField.setText(valueOrDefault(config.getClassFile(), ""));
        form.classNameField.setText(valueOrDefault(config.getClassName(), ""));
        form.descArea.setText(valueOrDefault(config.getDescription(), ""));
    }

    private static void updateModeState(Form form) {
        boolean echoMode = "echo".equals(form.modeCombo.getValue());
        setEchoFieldsDisabled(form, !echoMode);
        setConnectFieldsDisabled(form, echoMode);
        updateResponseModeState(form);
    }

    private static void setEchoFieldsDisabled(Form form, boolean disabled) {
        form.requestMethodField.setDisable(disabled);
        form.triggerSourceField.setDisable(disabled);
        form.triggerNameEchoField.setDisable(disabled);
        form.headerField.setDisable(disabled);
        form.pathField.setDisable(disabled);
        form.responseModeField.setDisable(disabled);
        form.verifyHintField.setDisable(disabled);
        if (disabled) {
            form.responseHeaderField.setDisable(true);
            form.responseStartField.setDisable(true);
            form.responseEndField.setDisable(true);
            form.responseRegexField.setDisable(true);
        }
    }

    private static void setConnectFieldsDisabled(Form form, boolean disabled) {
        form.toolField.setDisable(disabled);
        form.passField.setDisable(disabled);
        form.keyField.setDisable(disabled);
    }

    private static void updateResponseModeState(Form form) {
        boolean echoMode = "echo".equals(form.modeCombo.getValue());
        String responseMode = form.responseModeField.getValue();
        form.responseHeaderField.setDisable(!(echoMode && "HEADER_VALUE".equals(responseMode)));
        boolean substring = echoMode && "BODY_SUBSTRING".equals(responseMode);
        form.responseStartField.setDisable(!substring);
        form.responseEndField.setDisable(!substring);
        form.responseRegexField.setDisable(!(echoMode && "BODY_REGEX".equals(responseMode)));
    }

    private static void validate(javafx.scene.Node okBtn, Form form) {
        boolean valid = !trim(form.nameField.getText()).isEmpty()
            && !trim(form.classNameField.getText()).isEmpty()
            && !trim(form.classField.getText()).isEmpty();
        okBtn.setDisable(!valid);
    }

    private static MemshellConfig buildConfig(Form form, MemshellConfig existing) {
        String name = trim(form.nameField.getText());
        String className = trim(form.classNameField.getText());
        String srcPath = trim(form.classField.getText());
        if (name.isEmpty() || className.isEmpty() || srcPath.isEmpty()) {
            return null;
        }
        MemshellConfig config = new MemshellConfig();
        config.setName(name);
        config.setType(form.typeCombo.getValue());
        config.setMode(form.modeCombo.getValue());
        config.setServerType(trim(form.serverField.getText()));
        config.setBuiltin(false);
        config.setClassFile(srcPath);
        config.setClassName(className);
        config.setDescription(trim(form.descArea.getText()));

        if ("echo".equals(form.modeCombo.getValue())) {
            config.setRequestMethod(form.requestMethodField.getValue());
            config.setTriggerSource(form.triggerSourceField.getValue());
            config.setTriggerName(trim(form.triggerNameEchoField.getText()));
            config.setTriggerHeader(trim(form.headerField.getText()));
            config.setTriggerPath(trim(form.pathField.getText()));
            config.setResponseMode(form.responseModeField.getValue());
            config.setResponseHeader(trim(form.responseHeaderField.getText()));
            config.setResponseStart(trim(form.responseStartField.getText()));
            config.setResponseEnd(trim(form.responseEndField.getText()));
            config.setResponseRegex(trim(form.responseRegexField.getText()));
            config.setVerifyHint(trim(form.verifyHintField.getText()));
        } else {
            config.setTool(trim(form.toolField.getText()));
            config.setPassword(trim(form.passField.getText()));
            config.setSecretKey(trim(form.keyField.getText()));
        }
        return config;
    }

    private static String trim(String text) {
        return text == null ? "" : text.trim();
    }

    private static String valueOrDefault(String text, String def) {
        return trim(text).isEmpty() ? def : trim(text);
    }

    private static String firstNonBlank(String a, String b, String def) {
        if (!trim(a).isEmpty()) return trim(a);
        if (!trim(b).isEmpty()) return trim(b);
        return def;
    }

    private static class Form {
        TextField nameField;
        ComboBox<String> typeCombo;
        ComboBox<String> modeCombo;
        TextField serverField;
        ComboBox<String> requestMethodField;
        ComboBox<String> triggerSourceField;
        TextField triggerNameEchoField;
        TextField headerField;
        TextField pathField;
        ComboBox<String> responseModeField;
        TextField responseHeaderField;
        TextField responseStartField;
        TextField responseEndField;
        TextField responseRegexField;
        TextArea verifyHintField;
        TextField toolField;
        TextField passField;
        TextField keyField;
        TextField classField;
        TextField classNameField;
        TextArea descArea;
    }
}
