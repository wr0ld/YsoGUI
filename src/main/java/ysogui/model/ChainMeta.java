package ysogui.model;

/**
 * 链的扩展元信息，用于覆盖/补充从 jar 反射获取的基础信息。
 *
 * 优先级：自定义信息（项目目录 chain-meta/custom.json） > 内置信息（builtin-chain-meta.json） > jar 反射
 */
public class ChainMeta {

    /**
     * 链的参数类型枚举，决定命令输入框的适配行为
     */
    public enum ParamType {
        COMMAND,    // 系统命令，如 calc, whoami
        DNSLOG,     // DNSLog URL，如 http://xxx.dnslog.cn
        JNDI,       // JNDI/RMI 地址，如 ldap://host:port/obj
        HOSTPORT,   // host:port 格式
        URL,        // 远程 URL
        CLASSNAME   // CLASS:xxx 格式（仅支持内存马的链）
    }

    private String name;                // 链名，作为 key
    private String description;         // 链的中文描述
    private boolean memshellSupported;  // 是否支持注入内存马（走 TemplatesImpl）
    private String memshellReason;      // 不支持内存马时的原因说明
    private String category;            // 分类：CC / CB / Spring / Fastjson / JDK / Other
    private String gadgetChain;         // 调用链路简述，如 "HashSet->HashMap->TiedMapEntry->..."
    private ParamType paramType;        // 参数类型
    private String paramHint;           // 参数提示文本

    public ChainMeta() {}

    // ──── Getter / Setter ──────────────────────────────────────────────────────

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isMemshellSupported() { return memshellSupported; }
    public void setMemshellSupported(boolean memshellSupported) { this.memshellSupported = memshellSupported; }

    public String getMemshellReason() { return memshellReason; }
    public void setMemshellReason(String memshellReason) { this.memshellReason = memshellReason; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getGadgetChain() { return gadgetChain; }
    public void setGadgetChain(String gadgetChain) { this.gadgetChain = gadgetChain; }

    public ParamType getParamType() { return paramType; }
    public void setParamType(ParamType paramType) { this.paramType = paramType; }

    public String getParamHint() { return paramHint; }
    public void setParamHint(String paramHint) { this.paramHint = paramHint; }
}
