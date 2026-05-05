package ysogui.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 内存马配置数据模型
 *
 * 两种模式：
 *   connect — 连接型（哥斯拉/冰蝎等外部工具连接，工具内无回显）
 *   echo    — 回显型（工具内直接执行命令，通过 HTTP Header 触发，随机 boundary 提取结果）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemshellConfig {

    private String name;            // 内存马名称，如 "TomcatFilter-Echo"
    private String type;            // 注入类型：Filter / Servlet / Listener / Valve / Agent
    private String mode;            // 模式：connect / echo
    private String tool;            // 连接型专用：godzilla / behinder / custom
    private String password;        // 连接型专用：连接密码
    private String secretKey;       // 连接型专用：加密密钥
    private String triggerHeader;   // 回显型专用：触发 Header 名，默认 X-Cmd
    private String requestMethod;   // 回显型专用：GET / POST
    private String triggerSource;   // 回显型专用：header / query / body / cookie
    private String triggerName;     // 回显型专用：命令触发名，如 X-Cmd / cmd
    private String description;     // 描述信息
    private String classFile;       // class 文件路径（自定义内存马使用）
    private String className;       // 自定义内存马的完整类名（可选，但建议保存）
    private String templateClass;   // ysoserial payloads templates 包中的类名（内置内存马使用，如 TomcatCmdEcho）
    private boolean builtin;        // 是否为内置内存马
    private String serverType;      // 适用服务器类型：Tomcat / Spring / WebLogic / Universal
    private String triggerPath;     // 回显型触发路径，如 /shellcmd（可选）
    private String responseMode;    // 回显提取模式：BODY_RAW / BODY_BOUNDARY / BODY_SUBSTRING / BODY_REGEX / HEADER_VALUE / STATUS_ONLY / NO_ECHO
    private String responseHeader;  // 响应头提取模式时使用
    private String responseStart;   // 字符串截取开始标记
    private String responseEnd;     // 字符串截取结束标记
    private String responseRegex;   // 正则提取模式
    private String verifyHint;      // 无回显/弱回显时的验证提示

    public MemshellConfig() {}

    // ──── 快捷判断方法 ─────────────────────────────────────────────────────────

    /** 是否为回显型内存马 */
    @JsonIgnore
    public boolean isEchoMode() {
        return "echo".equals(mode);
    }

    /** 是否为连接型内存马 */
    @JsonIgnore
    public boolean isConnectMode() {
        return "connect".equals(mode);
    }

    // ──── Getter / Setter ──────────────────────────────────────────────────────

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getTool() { return tool; }
    public void setTool(String tool) { this.tool = tool; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getTriggerHeader() { return triggerHeader; }
    public void setTriggerHeader(String triggerHeader) { this.triggerHeader = triggerHeader; }

    public String getRequestMethod() { return requestMethod; }
    public void setRequestMethod(String requestMethod) { this.requestMethod = requestMethod; }

    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource; }

    public String getTriggerName() { return triggerName; }
    public void setTriggerName(String triggerName) { this.triggerName = triggerName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getClassFile() { return classFile; }
    public void setClassFile(String classFile) { this.classFile = classFile; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getTemplateClass() { return templateClass; }
    public void setTemplateClass(String templateClass) { this.templateClass = templateClass; }

    public boolean isBuiltin() { return builtin; }
    public void setBuiltin(boolean builtin) { this.builtin = builtin; }

    public String getServerType() { return serverType; }
    public void setServerType(String serverType) { this.serverType = serverType; }

    public String getTriggerPath() { return triggerPath; }
    public void setTriggerPath(String triggerPath) { this.triggerPath = triggerPath; }

    public String getResponseMode() { return responseMode; }
    public void setResponseMode(String responseMode) { this.responseMode = responseMode; }

    public String getResponseHeader() { return responseHeader; }
    public void setResponseHeader(String responseHeader) { this.responseHeader = responseHeader; }

    public String getResponseStart() { return responseStart; }
    public void setResponseStart(String responseStart) { this.responseStart = responseStart; }

    public String getResponseEnd() { return responseEnd; }
    public void setResponseEnd(String responseEnd) { this.responseEnd = responseEnd; }

    public String getResponseRegex() { return responseRegex; }
    public void setResponseRegex(String responseRegex) { this.responseRegex = responseRegex; }

    public String getVerifyHint() { return verifyHint; }
    public void setVerifyHint(String verifyHint) { this.verifyHint = verifyHint; }

    @Override
    public String toString() { return name; }
}
