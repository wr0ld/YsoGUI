package ysogui.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;

/**
 * 表示一条反序列化利用链的元信息
 * 数据来源：通过反射从 ysoserial.jar 中读取，或由用户自定义
 */
public class ChainInfo {

    private String name;         // 链名，如 CommonsCollections6
    private String author;       // 作者，从 @Authors 注解读取
    private List<String> dependencies = new ArrayList<>(); // 依赖版本列表
    private String className;    // 完整类名，如 ysoserial.payloads.CommonsCollections6

    // 自定义链特有字段
    private boolean custom;          // 是否为用户自定义链
    private String customJarPath;    // 自定义链的外部 jar 路径
    private String customClassName;  // 自定义链的完整类名（实现 ObjectPayload 的类）

    public ChainInfo() {}

    public ChainInfo(String name, String author, List<String> dependencies, String className) {
        this.name = name;
        this.author = author;
        this.dependencies = dependencies;
        this.className = className;
    }

    /** 格式化依赖信息，用于在 UI 中展示 */
    @JsonIgnore
    public String getDependenciesFormatted() {
        if (dependencies == null || dependencies.isEmpty()) {
            return "无依赖信息";
        }
        return String.join("\n", dependencies);
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public List<String> getDependencies() { return dependencies; }
    public void setDependencies(List<String> dependencies) { this.dependencies = dependencies; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public boolean isCustom() { return custom; }
    public void setCustom(boolean custom) { this.custom = custom; }

    public String getCustomJarPath() { return customJarPath; }
    public void setCustomJarPath(String customJarPath) { this.customJarPath = customJarPath; }

    public String getCustomClassName() { return customClassName; }
    public void setCustomClassName(String customClassName) { this.customClassName = customClassName; }

    @Override
    public String toString() { return name; }
}
