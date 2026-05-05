package ysogui.core;

import ysogui.model.ChainInfo;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 通过反射动态加载 ysoserial.jar，提取所有可用链的元信息。
 *
 * 读取流程：
 *  1. 遍历 jar 内 ysoserial/payloads/ 下的所有顶层 .class
 *  2. 用 URLClassLoader 加载
 *  3. 判断是否实现 ObjectPayload 接口
 *  4. 反射读取 @Authors / @Author / @Dependencies 注解
 *  5. 构建 ChainInfo 列表
 */
public class YsoLoader {

    private final File jarFile;
    private URLClassLoader classLoader;
    private final List<ChainInfo> chains = new ArrayList<>();

    public YsoLoader(File jarFile) throws Exception {
        this.jarFile = jarFile;
        this.classLoader = new URLClassLoader(
                new URL[]{jarFile.toURI().toURL()},
                null
        );
        scanChains();
    }

    /**
     * 使用外部传入的 ClassLoader 构造（用于加载 marshalsec 等扩展 jar）。
     * 此时 jarFile 可以是扩展 jar（不一定是 ysoserial），不扫描链。
     */
    public YsoLoader(File jarFile, URLClassLoader classLoader) throws Exception {
        this.jarFile = jarFile;
        this.classLoader = classLoader;
        // 不重新扫描链，保持已有的链列表
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void scanChains() throws Exception {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                // 只处理 ysoserial/payloads/ 直接子类（不含子包、不含匿名类）
                if (!name.startsWith("ysoserial/payloads/")) continue;
                if (!name.endsWith(".class")) continue;
                if (name.contains("$")) continue;
                // 确保是直接子类，不是 util/annotation 子包
                long slashCount = name.chars().filter(c -> c == '/').count();
                if (slashCount != 2) continue;

                String className = name.replace('/', '.').replace(".class", "");
                try {
                    Class<?> clazz = classLoader.loadClass(className);
                    ChainInfo info = extractInfo(clazz);
                    if (info != null) {
                        chains.add(info);
                    }
                } catch (Throwable t) {
                    java.util.logging.Logger.getLogger(YsoLoader.class.getName())
                          .warning("加载类失败，跳过: " + className + " (" + t.getMessage() + ")");
                }
            }
        }
        chains.sort(Comparator.comparing(ChainInfo::getName));
    }

    /**
     * 从 Class 对象提取链信息，不是 ObjectPayload 的实现类则返回 null
     */
    private ChainInfo extractInfo(Class<?> clazz) {
        // 必须实现 ObjectPayload 接口
        if (!implementsObjectPayload(clazz)) return null;
        // 排除接口和抽象类（如 ReleaseableObjectPayload、ObjectPayload 等）
        if (clazz.isInterface() || java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) return null;

        String name = clazz.getSimpleName();
        String author = "";
        List<String> deps = new ArrayList<>();

        for (Annotation ann : clazz.getAnnotations()) {
            String annType = ann.annotationType().getSimpleName();
            switch (annType) {
                case "Authors":
                case "Author": {
                    try {
                        Method value = ann.annotationType().getMethod("value");
                        Object result = value.invoke(ann);
                        if (result instanceof String[]) {
                            author = String.join(", ", (String[]) result);
                        } else {
                            author = result.toString();
                        }
                    } catch (Exception ignored) {}
                    break;
                }
                case "Dependencies": {
                    try {
                        Method value = ann.annotationType().getMethod("value");
                        Object result = value.invoke(ann);
                        if (result instanceof String[]) {
                            deps = Arrays.asList((String[]) result);
                        }
                    } catch (Exception ignored) {}
                    break;
                }
            }
        }

        ChainInfo info = new ChainInfo();
        info.setName(name);
        info.setAuthor(author.isEmpty() ? "unknown" : author);
        info.setDependencies(deps);
        info.setClassName(clazz.getName());
        return info;
    }

    private boolean implementsObjectPayload(Class<?> clazz) {
        // 直接接口
        for (Class<?> iface : clazz.getInterfaces()) {
            if (iface.getName().equals("ysoserial.payloads.ObjectPayload")) return true;
        }
        // 父类的接口（部分链继承了抽象基类）
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null && !superClass.equals(Object.class)) {
            return implementsObjectPayload(superClass);
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────

    public List<ChainInfo> getChains() { return chains; }

    public URLClassLoader getClassLoader() { return classLoader; }

    public File getJarFile() { return jarFile; }
}
