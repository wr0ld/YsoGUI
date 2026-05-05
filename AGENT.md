# YsoGUI — AI Agent 项目索引

> 本文件供 AI 助手快速理解项目全貌，换 AI 时无需重新分析整个代码库。

## 一句话描述

基于 JavaFX 的 ysoserial GUI 封装工具，提供链浏览、调用图可视化、Payload 多格式生成、漏洞利用（RMI/JRMP/JNDI LDAP+RMI）、内存马注入与回显。

## 当前维护约定（2026-04-24）

- **RMI Registry / JRMP Listener**：底层统一走 **ysoserial**
- **JNDI Remote Reference（LDAP / RMI）**：底层统一走 **marshalsec**
- **JNDI 反序列化**：
  - LDAP：自定义 `LdapDeserialServer`，返回 `javaSerializedData`
  - RMI：不是"直接塞序列化字节"，而是 **RMI lookup → JRMPClient → JRMPListener** 二阶段方案
- `MainWindow.loadMarshalsecJar()` 已修正：**loader 的主 jar 语义仍然是 ysoserial.jar**，只是把 marshalsec 追加到同一个 `URLClassLoader`
- `ExploitPanel` 已增加：
  - 分模式帮助按钮 `?`
  - 启动摘要日志（监听地址 / lookup 链接 / codebase / 二阶段地址 / 原理）
- **内存马联动（已修正）**：
  - `PayloadPanel` / `ExploitPanel` 的命令框现支持：
    - 普通命令
    - `CLASS:ysoserial内置模板类名`
    - `CLASS:内存马配置名称`
    - `CLASS:自定义内存马完整类名/简单类名`
  - `PayloadGenerator.generateRawSmart(...)` 会优先解析 `CLASS:`，内置模板继续走 ysoserial 原生逻辑，自定义内存马则读取 GUI 管理的 classBytes 注入 `TemplatesImpl`
  - **JNDI 反序列化模式**已支持上述 `CLASS:` 联动
  - **JNDI 远程 Reference 模式**不直接支持 `CLASS:`，只支持：
    - 命令 → 自动编译 `Exploit.class`
    - 用户手动选择无包名 `.class`
- **自定义内存马 class 存储路径** 已统一为项目目录：
  - `memshells/classes/`
  - 不再使用旧文档里的 `~/.ysogui/shells/`
- **内存马 UI 增强（本轮已完成）**：
  - `MemshellPanel` 详情区已补充：
    - 当前实际注入类名
    - 回显型触发说明
    - 连接型连接说明
  - echo 模式已增加 "复制 curl" 快捷按钮
  - 使用说明区会给出：
    - 默认 Header / Path
    - 推荐测试步骤
    - curl 示例
    - 连接型的 tool / password / key 提示
  - 随着运行时自动发现真实模板类，原先"模板变体"下拉已取消
  - `PayloadPanel` / `ExploitPanel` 原先的 `Y4er 内置 CLASS` 按钮区已移除，只保留内存马下拉入口
  - `MemshellPanel` 顶层滚动区已修正：
    - 不再拦截 `ListView` / `ComboBox` / `TextArea` 的滚轮
    - 内存马列表现在可直接使用滚轮滚动
  - 自定义内存马添加对话框的滚轮速度已提升
- **画布交互增强（2026-04-24 已完成）**：
  - 快捷键：
    - `Ctrl+Z`：撤销
    - `Escape`：回到查看模式
    - `Delete`：删除选中的节点或边
  - 节点右键菜单：
    - ✏ 编辑标签（弹出 TextInputDialog 修改节点文本，自动调整节点高度）
    - ✕ 删除节点（同时删除关联边，自动 pushUndo）
  - 边右键菜单：
    - ✏ 编辑标签（弹出 TextInputDialog 修改边标签文本）
    - ✕ 删除边（自动 pushUndo）
  - 边单击选中：单击边高亮为橙色（曲线+箭头），配合 Delete 键删除
  - `NodePane` / `EdgeLine` 内部类已支持 `setLabel()` 方法，labelText/edgeLabel 字段可变
- **内置图准确性修正（2026-04-26 已完成）**：
  - `CommonsCollections3` 已补齐 `TrAXFilter` 构造触发路径
  - `CommonsCollections4` 已补齐 `siftDownUsingComparator / ChainedTransformer / TrAXFilter` 路径
  - `Jdk7u21` 已移除旧版混入的 `LazyMap` 风格节点，改为更接近当前 payload 实现的 `Proxy(Templates) -> AnnotationInvocationHandler -> TemplatesImpl` 路径
- **内置模板自动发现（本轮已完成）**：
  - `PayloadGenerator.discoverBuiltinMemshellConfigs()` 会扫描当前加载的 ysoserial jar：
    - `ysoserial/payloads/templates/*.class`
  - 自动识别包含以下关键字的模板：
    - `MemShell`
    - `CmdEcho`
    - `NeoReg`
  - 运行时自动补充进 `MemshellManager` 的 `DISCOVERED` 区，不写回磁盘
  - `PayloadPanel` / `ExploitPanel` / `MemshellPanel` 会在 `setGenerator(...)` 后刷新列表
  - 因此 GUI 不再只依赖手写的 `builtin-memshells.json` 那 4 条
  - 自动发现模板已补一层语义化：
    - `SpringInterceptorMemShell` 单独按 **Spring** 类展示
    - `TomcatListenerNeoRegFromThread` 单独按 **NeoReg** 类型展示
  - `MemshellPanel` 已增加双维筛选：
    - 模式：全部 / 只看 echo / 只看 connect
    - 容器：全部 / 只看 Tomcat / 只看 Spring
  - 自动发现模板的说明文案已更精确区分：
    - Header 回显
    - Spring MVC 请求链
    - NeoReg HTTP 隧道/代理

> 如果后续 AI 会话中断，以上行为应视为当前真实实现，不要再按旧版注释推断。

## 技术栈

- **语言**: Java 8（source/target = 8，不使用 Java 9+ API）
- **GUI**: JavaFX 8（JDK 自带，无需额外依赖）
- **构建**: Maven + shade-plugin（fat jar，主类 `ysogui.Main`）
- **唯一外部依赖**: `jackson-databind:2.13.5`（JSON 序列化）
- **核心引擎**: ysoserial（通过 `URLClassLoader` 动态加载，反射调用）

## 目录结构

```
src/main/java/ysogui/
├── Main.java                    # 入口（委派到 App，避免 JavaFX ClassLoader 问题）
├── App.java                     # JavaFX Application
├── core/                        # 核心业务（纯 Java，零 JavaFX 依赖）
│   ├── YsoLoader.java           #   加载 ysoserial.jar，反射扫描 ObjectPayload 链
│   ├── PayloadGenerator.java    #   核心：生成 payload（多种格式 + 内存马 + 提取 class）
│   ├── ExploitRunner.java       #   漏洞利用：RMI Registry / JRMP Listener
│   ├── HttpClassServer.java     #   内置 HTTP 服务（JNDI 场景提供 .class 下载）
│   ├── LdapDeserialServer.java  #   自定义 LDAP 反序列化服务
│   ├── RmiDeserialServer.java   #   自定义 RMI 反序列化服务（二阶段：JRMPClient → JRMPListener）
│   ├── FileCleanup.java         #   临时编译/解压目录递归清理
│   ├── JavaCompileUtil.java     #   类名推导 / 内部类复制 / help 读取
│   ├── ChainGraphs.java         #   内置调用图加载（builtin-graphs.json）
│   ├── ChainMetaManager.java    #   链元信息管理（分类/参数类型/内存马支持）
│   └── MemshellManager.java     #   内存马配置管理
├── model/                       # 数据模型（POJO，Jackson 兼容）
│   ├── ChainInfo.java           #   链基础信息（name/author/dependencies/custom）
│   ├── ChainMeta.java           #   链扩展元信息（含 ParamType 枚举）
│   ├── GraphData.java           #   调用图数据（NodeData + EdgeData）
│   └── MemshellConfig.java      #   内存马配置（connect/echo 两种模式）
└── ui/                          # JavaFX 界面（VBox 子类）
    ├── MainWindow.java          #   主窗口（MenuBar + 三栏 SplitPane + StatusBar）
    ├── ChainListPanel.java      #   左栏：链列表 + 分类标签搜索 + 右键编辑/删除
    ├── GraphEditorPanel.java    #   中栏：调用图可视化编辑器（快捷键/右键菜单/文本编辑）
    ├── PayloadPanel.java        #   右栏 Tab1：Payload 生成 + 动态信息展示
    ├── ExploitPanel.java        #   右栏 Tab2：漏洞利用（3模式 × 4子模式）
    └── MemshellPanel.java       #   右栏 Tab3：内存马注入 + 回显

src/main/resources/
├── builtin-chain-meta.json      # 45 条链元信息（分类/描述/调用链路/内存马支持）
├── builtin-graphs.json          # 45 条链调用图
├── builtin-memshells.json       # 4 条内置内存马配置
└── help/
    ├── custom-chain-help.txt    # 自定义链编写指南（原硬编码在 ChainListPanel）
    └── custom-memshell-help.txt # 自定义内存马编写指南（原硬编码在 MemshellPanel）
```

## 核心类关系

```
Main → App → MainWindow
                │
                ├─ YsoLoader ──(URLClassLoader+反射)──→ ysoserial.jar
                │      │
                │      ├─ PayloadGenerator ←── ChainInfo
                │      ├─ ExploitRunner    ←── ChainInfo
                │      │
                │      ├─ ChainListPanel   ←── ChainInfo, ChainMetaManager, ChainGraphs
                │      ├─ GraphEditorPanel ←── GraphData, ChainGraphs
                │      ├─ PayloadPanel     ←── PayloadGenerator, ChainInfo, ChainMetaManager
                │      ├─ ExploitPanel     ←── ExploitRunner, PayloadGenerator, HttpClassServer,
                │      │                     LdapDeserialServer, RmiDeserialServer
                │      └─ MemshellPanel    ←── PayloadGenerator, MemshellManager
                │
                └─ 配置管理
                       ChainMetaManager ←── builtin-chain-meta.json + chain-meta/custom.json
                       ChainGraphs      ←── builtin-graphs.json
                       MemshellManager  ←── builtin-memshells.json + memshells/custom.json
```

## UI 布局

```
┌─────────────────────────────────────────────────────────────┐
│ MenuBar: [加载 ysoserial.jar] [加载 marshalsec.jar] [帮助]          │
├──────────┬─────────────────┬─────────────────────────────┤
│ 链列表    │ 调用图编辑器     │ Tab: Payload / Exploit / 内存马    │
│ 18%       │ 50%             │                                 │
│ 搜索框    │ 可视化节点/连线   │                                 │
│ 分类标签  │                  │                                 │
├──────────┴─────────────────┴─────────────────────────────┤
│ StatusBar: 状态 | yso ✓ | mar ✗ | 45条链              │
└─────────────────────────────────────────────────────────────┘
```

### 链列表区域（ChainListPanel）

- **顶部**：标题 "Gadget Chains" + 链数量
- **搜索框**：实时过滤链名
- **分类标签行**（Chip 风格）：
  | 全部 | CC | CB | Spring | Fastjson | JDK | 🐴内存马 |
- **ListView**：每项显示图标 + 链名
  - ●/★ 有内置图（实心=内置，实心★=自定义有图），○/☆ 无内置图
  - 🐴 支持内存马的链
  - ★ 绿色 = 自定义链
  - 不同分类用不同颜色区分
  - 右键自定义链可弹出编辑/删除菜单
- **底部**："+ 新建自定义链" 按钮

### 调用图编辑器（GraphEditorPanel）

画布支持 4 种编辑模式（工具栏按钮切换）：

| 模式 | 操作 |
|------|------|
| 查看 | 拖拽节点、单击选中节点/边 |
| 添加节点 | 双击画布空白处弹出对话框添加 |
| 连边 | 依次点击两个节点创建有向边 |
| 删除 | 双击节点/边删除 |

交互方式：

| 方式 | 节点 | 边 |
|------|------|----|
| 左键单击 | 选中（橙色高亮） | 选中（橙色高亮曲线+箭头） |
| 左键双击 | 删除模式下删除 | 删除模式下删除 |
| 右键 | 菜单：编辑标签 / 删除 | 菜单：编辑标签 / 删除 |
| 拖拽 | 移动节点位置（边自动跟随） | — |

快捷键：

| 快捷键 | 功能 |
|--------|------|
| `Ctrl+Z` | 撤销（最多 50 步） |
| `Escape` | 切回查看模式 |
| `Delete` | 删除当前选中的节点或边 |

其他：

- 缩放：`Ctrl+滚轮` / 工具栏 `+` `−` `1:1`
- 撤销栈：拖拽节点、增删节点/边、编辑标签均自动 pushUndo
- 保存：写入项目目录 `custom-graphs.json`（Map 结构，键=链名）
- 重置：`↺ 重置为内置图` 按钮删除自定义数据并重新加载内置图

### Payload 生成面板（PayloadPanel）

- 命令输入框（根据 ParamType 自适应提示：DNSLog / JNDI / HOSTPORT / URL / CLASSNAME / COMMAND）
- 输出格式选择（BASE64 / HEX / RAW 等 8 种格式）
- **动态信息区**（选中链后实时更新）：
  - 链名、作者、分类
  - 中文描述
  - 调用链路（紫色 Consolas 字体）
  - 参数格式说明（参数类型 + 格式提示 + 示例值）
  - 内存马支持状态（✓绿色 / ✗橙色 + 原因）
  - 依赖版本列表
- CLASS 快捷按钮（TomcatCmdEcho 等）、DNSLog 平台快捷按钮

### Exploit 利用面板（ExploitPanel）

三种顶层模式：

| 模式 | 方向 | 说明 |
|------|------|------|
| **RMI Registry** | 主动打目标 | 向目标 RMI Registry 发送反序列化 payload |
| **JRMP Listener** | 等目标回连 | 本地起 JRMP 服务端等目标连过来 |
| **JNDI 注入服务** | 等目标 JNDI lookup | 支持 LDAP 和 RMI 两种协议 |

JNDI 注入服务的 4 种子模式：

| 协议 | 模式 | 底层实现 | 目标要求 |
|-----|------|---------|---------|
| LDAP | 远程 Reference | marshalsec LDAPRefServer + HttpClassServer | 需 trustURLCodebase |
| LDAP | 反序列化 | LdapDeserialServer (unboundid) | 无需远程类加载 |
| RMI | 远程 Reference | marshalsec RMIRefServer + HttpClassServer | 需 trustURLCodebase |
| RMI | 反序列化 | RmiDeserialServer（lookup 返回 JRMPClient，再回连 JRMPListener） | 无需远程类加载，无需marshalsec |

### 内存马面板（MemshellPanel）

- connect 模式：配合冰蝎/哥斯拉连接回显
- echo 模式：HTTP Header 触发命令执行
- 支持的内存马模板
- 回显测试逻辑：
  - Header 默认 `X-Cmd`
  - 路径默认 `/`
  - 工具会额外发送 `X-Boundary` 方便自定义 echo 模板精确包裹输出
- 详情区 / 说明区：
  - 显示当前实际注入模板类
  - 显示 `FromThread` / `FromJMX` 建议场景
  - 显示 echo/connect 两类模板的触发或连接说明
- 注入区：
  - 直接选择实际模板，不再保留旧的"模板变体"下拉
  - 自动发现的模板会按 echo/connect、Tomcat/Spring 做筛选查看

## ExploitPanel 三种利用模式详细

| 模式 | 方向 | 投递内容 | 实用性 |
|------|------|---------|--------|
| RMI Registry | 主动打目标 | 序列化 gadget chain | 低 |
| JRMP Listener | 等目标回连 | 序列化 gadget chain | 低 |
| **JNDI / LDAP / 远程 Reference** | 等目标 JNDI 查询 | LDAP Reference + HTTP .class | 中 |
| **JNDI / LDAP / 反序列化** | 等目标 JNDI 查询 | LDAP javaSerializedData | **高（默认）** |
| **JNDI / RMI / 远程 Reference** | 等目标 JNDI 查询 | RMI Reference + HTTP .class | 中 |
| **JNDI / RMI / 反序列化** | 等目标 JNDI 查询 | RMI lookup → JRMPClient → JRMPListener | 高（无需marshalsec）|

## ExploitPanel 当前拆分情况

虽然 `ExploitPanel.java` 仍偏大，但 JNDI 启动主流程已拆成两层：

- `startJndiReferenceMode(...)`
- `startJndiDeserMode(...)`

日志模板已统一抽成：

- `logRmiRegistrySummary(...)`
- `logJrmpListenerSummary(...)`
- `logJndiReferenceSummary(...)`
- `logJndiLdapDeserSummary(...)`
- `logJndiRmiDeserSummary(...)`

后续继续重构时，优先把"帮助文案"和"模式配置"再抽离，不要再把所有逻辑塞回 `doStartJndi()`

## 内存马模块当前实现状态（2026-04-23，已更新）

当前主路径已是**双路径**：

1. **内置模板路径**
   - 继续优先走 ysoserial 原生 `CLASS:模板类名`
   - 例如：
     - `TomcatCmdEcho`
     - `TomcatFilterMemShellFromThread`
     - `TomcatFilterMemShellFromJMX`

2. **自定义模板路径**
   - GUI 从 `memshells/classes/` 读取 classBytes
   - 自动补带内部类字节码
   - 手工注入 `TemplatesImpl`
   - 再用支持的 gadget 链包装

### 自定义 class 导入（2026-04-26 已调整）

- **当前策略**：不再支持导入 `.java` 并在 GUI 内编译
- **自定义链导入**：`ChainListPanel.copyPayloadFile(...)`
  - 仅支持 `.class`
  - 直接复制到 `payloads/<包路径>/Foo.class`
  - 同目录内部类通过 `JavaCompileUtil.copyInnerClasses(...)` 一并复制
- **自定义内存马导入**：`MemshellPanel.copyShellFile(...)`
  - 仅支持 `.class`
  - 直接复制到 `memshells/classes/`
  - 同目录内部类一并复制
- **工具类**：类名推导 / 内部类复制 / help 读取 3 个方法统一在 [JavaCompileUtil.java](file:///e:/tools/YsoGUI/src/main/java/ysogui/core/JavaCompileUtil.java)

### 当前已确认可用的联动

- `MemshellPanel` 可生成内存马 payload
- `PayloadPanel` 可直接输入 `CLASS:内存马名称`
- `ExploitPanel` 的 **JNDI LDAP/RMI 反序列化模式** 可直接输入 `CLASS:内存马名称`
- 自定义内存马带包名时，优先使用完整类名，不再粗暴裁成 simple name
- `MemshellPanel` 可直接为 echo 模板生成 curl 测试请求
- 真实模板（如 `FromThread` / `FromJMX`）现直接展示在列表中，无需再额外切换变体

### 当前仍需注意的限制

- **自定义 classBytes 注入**目前只稳定适配：
  - CB1 / CB183 / CB192 等 CB 变体
  - CC2-like / CC4-like
- 对外描述时应区分：
  - **内置模板**：优先走 ysoserial 原生 `CLASS:`，强支持
  - **自定义内存马**：GUI 手工 `classBytes -> TemplatesImpl`，仅部分稳定链推荐
- `ChainMetaManager` 里标记"支持内存马"的链，并不等于"每条都已验证自定义模板稳定注入"
- **JNDI Reference 模式**不适合作为"CLASS:内存马"入口，它本质是远程类加载场景，不是 `TemplatesImpl` 序列化注入场景

## 内置模板命名差异：FromJMX vs FromThread

ysoserial/Y4er 模板里两类名字都存在，例如：

- `TomcatFilterMemShellFromThread`
- `TomcatFilterMemShellFromJMX`
- `TomcatServletMemShellFromThread`
- `TomcatServletMemShellFromJMX`
- `TomcatListenerMemShellFromThread`
- `TomcatListenerMemShellFromJMX`

语义上可理解为：

- **FromThread**
  - 通过当前线程上下文、请求线程、容器线程栈等路径回溯 Tomcat 对象
  - 更适合普通反序列化 / 请求线程触发场景

- **FromJMX**
  - 通过 JMX / MBeanServer / Tomcat 暴露对象等路径拿 `StandardContext`
  - 更适合目标环境能走到 JMX 相关对象图的场景

建议默认优先：

- **反序列化 / JNDI 反序列化场景**：先试 `FromThread`
- 若目标环境 Thread 路径拿不到容器对象，再试 `FromJMX`

当前 GUI 内置配置里：

- `TomcatCmdEcho`：回显型
- `TomcatFilterMemShell`：默认映射 `TomcatFilterMemShellFromJMX`
- `TomcatServletMemShell`：默认映射 `TomcatServletMemShellFromJMX`
- `TomcatListenerMemShell`：默认映射 `TomcatListenerMemShellFromJMX`

## 当前模板发现现状（2026-04-23）

除 `builtin-memshells.json` 手写的 4 条外，运行时还会自动扫描 ysoserial jar 中的模板目录。

当前已确认可被自动发现的相关模板至少包括：

- `TomcatFilterMemShellFromThread`
- `TomcatServletMemShellFromThread`
- `TomcatListenerMemShellFromThread`
- `TomcatFilterMemShellFromJMX`
- `TomcatServletMemShellFromJMX`
- `TomcatListenerMemShellFromJMX`
- `TomcatCmdEcho`
- `ZohoPMPTomcatEcho`
- `SpringInterceptorMemShell`
- `TomcatListenerNeoRegFromThread`

说明：

- 经典 Tomcat 主流模板仍可按 `3 + 3 + 1` 理解
- 但当前这份 ysoserial jar 实际不止这 7 条，还包括额外的 echo / Spring / NeoReg 模板
- GUI 展示时会把：
  - `SpringInterceptorMemShell` 归到 Spring 语义
  - `TomcatListenerNeoRegFromThread` 归到 NeoReg 语义
- GUI 现在会把额外模板也展示出来，避免只能手工写死

## 模板命名差异：CmdEcho vs MemShell

这里的 `MemShell` 不是第三种东西，本质上仍然是"内存马 / memory shell"的缩写命名。

可按用途理解为两类：

- `CmdEcho`
  - 偏**回显型**
  - 注入后直接通过某个 Header / 请求方式执行命令并回显
  - 更适合工具内直接测试
  - 当前内置代表：`TomcatCmdEcho`

- `*MemShell*`
  - 偏**驻留型 / 挂载型**
  - 注入后向容器注册一个 Filter / Servlet / Listener 等驻留入口
  - 后续再通过外部管理端或约定请求去触发/连接
  - 当前内置代表：
    - `TomcatFilterMemShellFromThread`
    - `TomcatFilterMemShellFromJMX`
    - `TomcatServletMemShellFromThread`
    - `TomcatServletMemShellFromJMX`
    - `TomcatListenerMemShellFromThread`
    - `TomcatListenerMemShellFromJMX`

因此：

- 名字里有 `MemShell`，只是说明它是"驻留型内存马模板"
- 名字里没有 `MemShell`（如 `TomcatCmdEcho`），通常说明它更偏"命令回显模板"

不要把：

- `TomcatFilterMemShell`
- `TomcatFilterMemShellFromThread`
- `TomcatFilterMemShellFromJMX`

理解成三种完全不同的马。

更准确地说：

- `TomcatFilterMemShell`：GUI 里的配置名 / 逻辑别名
- `TomcatFilterMemShellFromThread` / `FromJMX`：底层真正注入用的 ysoserial 模板类名

## PayloadGenerator 输出格式

`BASE64` | `GZIP_BASE64` | `URL_ENCODED` | `BASE64_URL` | `HEX` | `RAW_HEX_DUMP` | `RAW` | `CLASS_FILE`

## 外部 Jar 加载

- **ysoserial.jar**: 菜单"加载 ysoserial.jar"，必须先加载
- **marshalsec.jar**: 菜单"加载 marshalsec.jar"或放 `lib/` 目录自动检测
  - 远程 Reference 模式直接使用 `LDAPRefServer` / `RMIRefServer`
  - 反序列化模式复用其中的 unboundid LDAP SDK
- 两者合并到同一个 `URLClassLoader`，类可互相访问

## 状态栏指示器

状态栏右侧固定显示 3 个状态标签，不会被其他操作覆盖：

| 标签 | 未加载 | 已加载 |
|------|--------|--------|
| ysoserial | 红底红字 `✗` | 绿底绿字 `✓` |
| marshalsec | 红底红字 `✗` | 绿底绿字 `✓` |
| 链数量 | 灰色文字 "N 条链" 或 "M/N 条链" | 加载后显示总链数和筛选后数量 |

## 用户配置目录

项目目录下可放自定义配置（优先级高于内置）：
- `chain-meta/custom.json` — 自定义链元信息（含 category/description/gadgetChain/memshellSupported）
- `memshells/custom.json` — 自定义内存马配置
- `memshells/classes/` — 自定义内存马 `.class`
- `payloads/` — 自定义链 `.class`
- `custom-graphs.json` — 自定义调用图

## 分类筛选标签

链列表搜索框下方一行分类标签按钮（Chip 风格）：

| 标签 | 颜色 | 匹配逻辑 |
|------|------|---------|
| 全部 | 灰色 | 显示所有 |
| CC | 蓝色 | CommonsCollections* 或 meta.category="CC" 或 deps 含 commons-collections |
| CB | 紫色 | CommonsBeanutils* 或 meta.category="CB" 或 deps 含 commons-beanutils |
| Spring | 绿色 | Spring* 或 meta.category="Spring" 或 deps 含 spring |
| Fastjson | 金色 | Fastjson* 或 meta.category="Fastjson" |
| JDK | 灰色 | 无外部依赖（deps 为空） |
| 🐴内存马 | 绿色 | ChainMetaManager.isMemshellSupported()=true |

点击选中 → 单选逻辑；再次点击已选中的非"全部"标签 → 取消选中回到全部。

## 编译运行

```bash
mvn clean package -DskipTests
java -jar target/YsoGUI-1.0.0-shaded.jar
```

需要 JDK 8（自带 JavaFX），不能用纯 JRE 启动。
