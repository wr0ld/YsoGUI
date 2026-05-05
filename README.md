# YsoGUI

一个面向 Java 反序列化测试的桌面 GUI 工具。  
当前项目主要围绕：

- `ysoserial`
- `marshalsec`
- Y4er 扩展的 `CLASS:` 模板

做统一的可视化封装。

---

## 当前功能

### 1. Payload 生成

支持从 `ysoserial.jar` 动态扫描链并生成 payload。

支持输出格式：

- Base64
- Gzip+Base64
- URL 编码
- Base64+URL 编码
- Hex
- Hex Dump
- Raw

支持：

- 普通命令参数
- `CLASS:模板名`
- 自定义 `ObjectPayload` 链

---

### 2. Exploit 模块

当前保留并可用的利用方式：

- `RMI Registry`
- `JRMP Listener`
- `JNDI / LDAP Remote Reference`
- `JNDI / LDAP 反序列化`
- `JNDI / RMI Remote Reference`

当前 **不提供**：

- `JNDI / RMI 反序列化`

原因很简单：这条路径实现复杂、稳定性差，当前版本不作为正式功能提供。

---

### 3. JNDI 模式说明

#### LDAP

支持两种模式：

- `远程 Reference（6u211 / 7u201 / 8u191 / 11.0.1）`
- `反序列化`

#### RMI

只保留一种模式：

- `远程 Reference（6u132 / 7u122 / 8u113）`

---

### 4. 调用图编辑

支持：

- 查看内置链调用图
- 编辑节点/连边
- 保存用户自定义图
- 重置为内置图

---

### 5. 自定义链

支持导入你自己写的 `ObjectPayload` 实现类。

要求：

- 必须实现 `ysoserial.payloads.ObjectPayload`
- 导入的是已编译好的 `.class`

---

### 6. 内存马相关

项目里保留了内置模板 / 内存马相关管理代码，但当前版本 **不建议作为主功能使用**。

原因：

- 这部分兼容性强依赖具体链、容器和目标环境
- 不同链对 `TemplatesImpl` / class 字节码加载路径并不一致
- 工程上很难承诺“任意模板、任意容器、任意版本”都稳定

所以当前更推荐的用法是：

- 把它当成辅助能力
- 优先使用已经验证过的 payload / JNDI 路径
- 不把内存马部分当成当前版本的稳定能力

---

## 当前项目状态

按当前版本的真实情况，可以这样理解：

- `Payload 生成`：可用
- `RMI Registry / JRMP Listener`：可用
- `LDAP 反序列化`：可用
- `LDAP Remote Reference`：可用
- `RMI Remote Reference`：可用
- `RMI 反序列化`：不提供
- `内存马相关能力`：保留，但不建议使用

其中：

- `LDAP 反序列化` 更适合不依赖远程 class 下载的场景
- `Remote Reference` 是否成功，和目标 JVM 是否允许远程类加载有关

---

## 依赖

本工具本身是 GUI 外壳，不内置完整利用生态。使用时通常需要你自己准备：

- `ysoserial.jar`
  - 项目地址：<https://github.com/frohoff/ysoserial>
- `marshalsec.jar`
  - 项目地址：<https://github.com/mbechler/marshalsec>

首次启动后，从界面中加载对应 jar 即可。

---

## 构建

```bash
mvn package -DskipTests
```

输出：

```text
target/YsoGUI-1.0.0.jar
```

---

## 启动

```bash
java -jar target/YsoGUI-1.0.0.jar
```

首次启动后：

1. 加载 `ysoserial.jar`
2. 如需 JNDI Reference / LDAP 反序列化，再加载 `marshalsec.jar`

---

## 目录结构

```text
YsoGUI/
├── pom.xml                         # Maven 构建配置
├── README.md                       # 项目说明
├── src/main/java/ysogui/
│   ├── Main.java                   # 程序入口
│   ├── App.java                    # JavaFX Application 启动类
│   ├── core/
│   │   ├── YsoLoader.java          # 反射加载 ysoserial.jar 并扫描 payload 链
│   │   ├── PayloadGenerator.java   # 生成 payload，负责多种编码/格式输出
│   │   ├── ExploitRunner.java      # 调用 ysoserial exploit 相关入口
│   │   ├── HttpClassServer.java    # JNDI Reference 模式下的本地 .class HTTP 服务
│   │   ├── LdapDeserialServer.java # LDAP 反序列化服务
│   │   ├── RmiDeserialServer.java  # RMI 反序列化相关实现（当前不作为正式模式提供）
│   │   ├── ChainMetaManager.java   # 链的扩展元信息管理
│   │   └── MemshellManager.java    # 内置模板 / 内存马配置管理
│   ├── model/                      # 数据模型定义
│   └── ui/
│       ├── MainWindow.java         # 主窗口与整体布局
│       ├── ChainListPanel.java     # 左侧链列表
│       ├── GraphEditorPanel.java   # 调用图展示与编辑面板
│       ├── PayloadPanel.java       # Payload 生成功能面板
│       ├── ExploitPanel.java       # Exploit / JNDI 服务功能面板
│       └── OutputPanel.java        # 日志输出面板
└── src/test/java/ysogui/           # 测试与调试用代码
```

---

## 说明

这个项目的定位不是“重写一套 ysoserial / marshalsec”，而是：

> 把常用 payload 生成、JNDI 服务、利用入口、调用图和一些模板能力收进同一个桌面工具里。

所以它更适合：

- 本地测试
- 链路验证
- 日常利用调试
- 自定义链开发联调

而不是：

- 覆盖所有 Java 利用场景
- 承诺所有版本 / 所有容器 / 所有链都兼容

---

## 免责声明

仅用于授权测试与安全研究。
