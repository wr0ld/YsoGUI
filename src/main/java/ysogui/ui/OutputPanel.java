package ysogui.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.EnumMap;

public class OutputPanel extends VBox {
    private static final double LOG_SCROLL_SPEED = 1.20;

    public enum Source {
        ALL("全部"),
        EXPLOIT("Exploit"),
        PAYLOAD("Payload"),
        MEMSHELL("内存马"),
        SYSTEM("系统");

        private final String label;
        Source(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final MainWindow mainWindow;
    private final EnumMap<Source, StringBuilder> sourceBuffers = new EnumMap<Source, StringBuilder>(Source.class);
    private final TextArea outputArea = new TextArea();
    private Source activeSource = Source.EXPLOIT;

    public OutputPanel(MainWindow mainWindow) {
        this.mainWindow = mainWindow;
        for (Source source : Source.values()) {
            if (source != Source.ALL) {
                sourceBuffers.put(source, new StringBuilder());
            }
        }
        setStyle("-fx-background-color: #f5f5f5;");
        setMinWidth(0);
        setPrefWidth(320);
        setMinHeight(180);
        setPrefHeight(240);
        setSpacing(0);

        getChildren().add(buildBody());
    }

    private VBox buildBody() {
        outputArea.setEditable(false);
        outputArea.setWrapText(true);
        outputArea.setStyle(
            "-fx-background-color: #fff; -fx-text-fill: #333; " +
            "-fx-font-family: Consolas; -fx-font-size: 11; -fx-border-color: #ccc;"
        );
        outputArea.addEventFilter(ScrollEvent.SCROLL, event -> {
            double delta = event.getDeltaY() * LOG_SCROLL_SPEED;
            double next = outputArea.getScrollTop() - delta;
            outputArea.setScrollTop(Math.max(0.0, next));
            event.consume();
        });
        VBox.setVgrow(outputArea, Priority.ALWAYS);

        Button copyBtn = new Button("复制当前");
        copyBtn.setStyle("-fx-background-color: #e0e0e0; -fx-text-fill: #555; " +
            "-fx-border-color: #bbb; -fx-font-size: 11; -fx-padding: 4 10;");
        copyBtn.setOnAction(e -> copyCurrentView());

        Button clearBtn = new Button("清空");
        clearBtn.setStyle("-fx-background-color: #e0e0e0; -fx-text-fill: #555; " +
            "-fx-border-color: #bbb; -fx-font-size: 11; -fx-padding: 4 10;");
        clearBtn.setOnAction(e -> clearActiveSource());

        HBox btnRow = new HBox(6, copyBtn, clearBtn);

        VBox box = new VBox(6, outputArea, btnRow);
        box.setPadding(new Insets(8, 10, 8, 10));
        box.setStyle("-fx-background-color: #f5f5f5;");
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    public void appendLog(Source source, String line) {
        if (source == null || source == Source.ALL) {
            return;
        }
        Platform.runLater(() -> {
            StringBuilder sb = sourceBuffers.get(source);
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
            if (activeSource == source || activeSource == Source.ALL) {
                refreshView();
            }
        });
    }

    public void setSourceContent(Source source, String text) {
        if (source == null || source == Source.ALL) {
            return;
        }
        Platform.runLater(() -> {
            StringBuilder sb = sourceBuffers.get(source);
            sb.setLength(0);
            if (text != null && !text.isEmpty()) {
                sb.append(text);
            }
            if (activeSource == source || activeSource == Source.ALL) {
                refreshView();
            }
        });
    }

    public void clearSource(Source source) {
        if (source == null || source == Source.ALL) {
            return;
        }
        Platform.runLater(() -> {
            sourceBuffers.get(source).setLength(0);
            if (activeSource == source || activeSource == Source.ALL) {
                refreshView();
            }
        });
    }

    public void setActiveSource(Source source) {
        Platform.runLater(() -> {
            activeSource = source != null ? source : Source.ALL;
            refreshView();
        });
    }

    public void setLatestLookup(String lookup) {
    }

    private void refreshView() {
        outputArea.setText(readSource(activeSource));
        outputArea.setScrollTop(Double.MAX_VALUE);
    }

    private String readSource(Source source) {
        if (source == null || source == Source.ALL) {
            StringBuilder merged = new StringBuilder();
            for (Source item : Source.values()) {
                if (item == Source.ALL) {
                    continue;
                }
                String text = sourceBuffers.get(item).toString();
                if (text.isEmpty()) {
                    continue;
                }
                if (merged.length() > 0) {
                    merged.append('\n').append('\n');
                }
                merged.append(text);
            }
            return merged.toString();
        }
        return sourceBuffers.get(source).toString();
    }

    private void copyCurrentView() {
        String text = outputArea.getText();
        if (text == null || text.isEmpty()) {
            mainWindow.showError("当前没有可复制的输出");
            return;
        }
        ClipboardContent cc = new ClipboardContent();
        cc.putString(text);
        Clipboard.getSystemClipboard().setContent(cc);
        mainWindow.setStatus("✓ 已复制当前输出");
    }

    public void clearActiveSource() {
        if (activeSource == null || activeSource == Source.ALL) {
            for (Source source : Source.values()) {
                if (source != Source.ALL) {
                    sourceBuffers.get(source).setLength(0);
                }
            }
        } else {
            sourceBuffers.get(activeSource).setLength(0);
        }
        refreshView();
    }

}
