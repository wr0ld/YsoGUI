# YsoGUI

基于 [Y4er/ysoserial](https://github.com/Y4er/ysoserial)、[marshalsec](https://github.com/mbechler/marshalsec) 等的 Java 反序列化利用工具 GUI 版。

这个项目一开始其实没有想得特别复杂。  
说白了，就是想把平时用 ysoserial 时那一堆零散动作——找链、看依赖、生成 payload、起 exploit、记调用路径、试模板——尽量收进一个桌面界面里，别每次都在命令行和笔记之间来回切。

所以 YsoGUI 的目标一直不是“重写一个 ysoserial”，而是做一个更顺手、偏实战的 GUI 外壳。

---

## 这个项目到底是什么

如果只用一句话概括，我会把它定义成：

> **一个偏实战、偏工程整合的 ysoserial GUI 工具。**

你可以把它理解成：

- 一个 ysoserial 的 GUI 增强器
- 一个把 Payload 和 Exploit 放到一起的桌面工具
- 一个可以顺手管理自定义链、看调用图、试内置模板的面板

但不要把它想成：

- 任意自定义内存马的万能注入平台
- 全部 `TemplatesImpl` 链的自动适配器
- 任意容器 / 任意版本环境的全兼容利用器

---

## 这个版本能做什么，不能做什么

先说结论，我对当前版本的判断比较直接：

- **Payload 模块：可用**
- **Exploit 模块：可用**
- **自定义链：可用**
- **内置内存马模板：支持**
- **自定义内存马 `.class` 注入：不太兼容 暂时删除**


如果你的诉求是：

- 日常生成 ysoserial payload
- 统一管理常见 exploit 入口
- 自己写 `ObjectPayload` 做自定义链

那这个版本其实已经够用了。

但如果你的期待是：

- 任意自定义内存马
- 任意 `TemplatesImpl` 链
- 任意容器环境全自动兼容

那这不属于当前版本的承诺范围。

---

## 顺手说一下 AI 参与

这个项目很适合直接标成 **AI-assisted / AI-first** 开发。

如果要写得更真实一点，我觉得下面这句话就够了：

> 这个项目的代码实现几乎全程由 AI 辅助生成，人工主要负责需求提出、方向判断、功能测试、问题复现、边界取舍和最终验收。

我不觉得这是什么需要回避的事。  
反过来说，这个项目本来就挺像“用 AI 把一个偏工具化的想法快速落成工程”的例子，写出来反而更符合实际。

---

## 为什么我只说“内存马部分稳定支持”

这块是整个项目里最容易让人误解的部分，所以得单独说。

### 1. 内置模板

如果你用的是内置模板，那基本走的是 ysoserial 原生 `CLASS:` 机制，比如：

- `CLASS:TomcatCmdEcho`
- `CLASS:TomcatFilterMemShellFromThread`
- `CLASS:TomcatListenerMemShellFromThread`

这条路是当前版本里最稳的用法，没什么好绕的。

### 2. 自定义内存马

自定义内存马就不是同一回事了。  
它不是 ysoserial 自带模板，而是 GUI 额外做了一层：

`classBytes -> TemplatesImpl -> 某条支持链包装`

所以更准确的理解应该是：

- 这是一个增强能力
- 不是“任意模板全兼容能力”

也就是说：

- “某条链标记支持内存马”
- 不等于
- “这条链已经稳定支持 GUI 的自定义内存马 `.class` 注入”

当前更推荐拿来做自定义内存马 `.class` 注入的链是：

- `CommonsBeanutils1`
- `CommonsBeanutils183NOCC`
- `CommonsBeanutils192NOCC`
- `CommonsBeanutils192WithDualTreeBidiMap`
- `CommonsCollections2`
- `CommonsCollections4`

---

## 这项目里我踩过的一些坑

很多后来的取舍，其实不是“功能做不到”，而是“做出来也未必值得继续宣传”。  
下面这些坑基本就是边界最后怎么收的原因。

### 1. “理论上能走 TemplatesImpl” 不等于 “现在就能稳定支持”

很多链理论上都能触发 `TemplatesImpl`，但触发路径并不一样：

- 有的走 `newTransformer()`
- 有的走 `getOutputProperties()`
- 有的走 `InstantiateTransformer`
- 有的走 `TrAXFilter`

所以这事不能简单理解成：  
“只要是 `TemplatesImpl` 链，就能用一个通用封装一次性全适配。”

### 2. CC2 / CC4 这类链很容易在本地生成阶段提前触发

这个坑我觉得非常典型：

- 本来只是想构造 payload
- 结果 `PriorityQueue` / comparator 在本地构造时先比较了元素
- 提前把 `TemplatesImpl.newTransformer()` 调用了
- 自定义内存马 static 块在本机先执行了

这就说明，自定义 class 注入这件事，真不是“理论能打就等于工程上能稳打”。

### 3. `.java` 导入 + GUI 内部编译并不划算

一开始也想过支持 `.java` 直接导入、GUI 内编译，但这条路实际问题很多：

- 本机 `javac` 环境差异
- classpath 不稳定
- 编码问题
- UI 卡顿
- 用户误以为“源码能导入就等于能稳定打”

所以最后我把这条路直接砍掉了，只保留：

- **导入已编译好的 `.class`**

现在回头看，这是个正确的收边界动作。

### 4. 自定义链反而是最稳的扩展点

反而是自定义链这块，一直都很稳。  
原因也很简单，它的模型很清楚：

- 你自己写 `ObjectPayload`
- 你自己控制 gadget 构造
- GUI 只负责加载类、调用 `getObject()`、序列化输出

所以如果后面还要继续投入，我还是会优先做自定义链，而不是去追“自定义内存马全适配”。

---

## 这几个功能大概是怎么做的

这里不贴大段代码，只说思路。

### 1. 内置链不是手写列表，而是反射扫描 ysoserial.jar

`YsoLoader` 会：

- 遍历 ysoserial jar 中 `ysoserial/payloads/`
- 用 `URLClassLoader` 加载 class
- 检查是否实现 `ObjectPayload`
- 读取 `@Authors` / `@Dependencies`

所以链名、作者、依赖这些东西不是我手写死在 GUI 里的，而是运行时直接从 jar 扫出来的。

### 2. Payload 生成优先复用 ysoserial 原生逻辑

普通 payload 生成也不是自己重写一套序列化逻辑，而是：

- 通过反射调用 ysoserial 原有入口
- 捕获原始字节
- 再按 GUI 需要转成 Base64 / Gzip+Base64 / URL 编码 / Hex / Raw

这样做的好处很直接：生成结果尽量跟原版 ysoserial 保持一致。

### 3. 自定义链走的是 `ObjectPayload` 反射调用

自定义链这块逻辑也很直：

- 用户导入 `.class`
- 工具加载类
- 反射实例化
- 调 `getObject(String)`
- 再把返回对象序列化

这也是为什么我一直觉得“自定义链”才是这个项目最值得用、也最值得继续扩展的部分。

### 4. 调用图不是截图，而是可编辑的数据结构

调用图数据来自：

- 内置 `builtin-graphs.json`
- 用户本地 `custom-graphs.json`



UI 层用 JavaFX 的节点和边去做可视化，所以这些图不是死图，而是真的能改：

- 可以拖拽
- 可以编辑标签
- 可以保存
- 可以重置回内置图

### 5. Exploit 模块本质上是把几个常见利用入口整合到一起

这块本质上也不是“重新发明协议”，而是把常见入口都收进来：

- RMI Registry
- JRMP Listener
- LDAP / RMI Reference
- LDAP / RMI 反序列化

其中：

- ysoserial 负责一部分利用逻辑
- marshalsec 负责一部分 JNDI Reference 能力
- GUI 负责参数组织、生命周期控制和日志展示

### 6. JNDI 注入不是一个按钮，而是 4 种不同路径

这块如果不单独说清楚，特别容易被理解成“无非就是起个 LDAP 服务”。  
但实际上完全不是这么简单。

当前版本实际拆成了 4 种模式：

- **LDAP / 远程 Reference**
- **LDAP / 反序列化**
- **RMI / 远程 Reference**
- **RMI / 反序列化**

这四种路径差别挺大，不能混着看。

#### 1. LDAP / RMI 远程 Reference

这两种本质上都是：

- 返回一个远程引用
- 让目标再去拉取 `.class`
- 更依赖目标环境是否允许远程类加载

当前实现上：

- `marshalsec` 负责 `LDAPRefServer` / `RMIRefServer`
- GUI 内置 `HttpClassServer` 负责提供 `.class` 下载

所以这条路更适合：

- 明确知道目标能走 `trustURLCodebase`
- 或者就是想验证远程 Reference 路径

#### 2. LDAP / 反序列化

这条路不是返回远程类引用，而是：

- LDAP 直接返回 `javaSerializedData`
- 目标在 lookup 过程中直接进入反序列化

当前实现上：

- GUI 用自定义 `LdapDeserialServer`

这条路的特点比较明确：

- 不依赖远程类加载
- 更接近“直接把 gadget 打进去”
- 在很多场景下比 Reference 更稳

#### 3. RMI / 反序列化

RMI 反序列化这条是最容易被误解的。

它不是“RMI 里直接塞一坨序列化字节”。

当前实现实际是二阶段：

- 目标先做 RMI lookup
- lookup 返回 `JRMPClient`
- 目标再反连本地 `JRMPListener`
- 第二阶段再投递真正的 payload

也就是说它的真实链路更接近：

`RMI lookup -> JRMPClient -> JRMPListener`

所以我后来专门把它单独拎出来写。  
它跟 LDAP 反序列化真的不是同一套思路。

#### 4. `CLASS:` 联动在 JNDI 里的边界

这里也要区分：

- **JNDI 反序列化模式**
  - 支持 `CLASS:`
  - 可以走内置模板，也可以走 GUI 管理的自定义 classBytes 注入

- **JNDI Reference 模式**
  - 不适合作为 `CLASS:` 入口
  - 它本质是远程类加载场景，不是 `TemplatesImpl` 序列化注入场景

所以如果你的目标是：

- 打 `TemplatesImpl`
- 联动内置模板
- 联动 `CLASS:内存马名称`

那应该优先看：

- **LDAP / 反序列化**
- **RMI / 反序列化**

而不是优先去试 Reference。

---





---



## 构建

```bash
mvn package -DskipTests
# 输出: target/YsoGUI-1.0.0.jar
```

## 启动

```bash
java -jar target/YsoGUI-1.0.0.jar
```

首次启动后通过菜单「文件 → 加载 ysoserial.jar」选择本地 jar，路径自动记住，下次启动无需重复操作。

---

## 界面布局



---

## 功能说明

### 链列表

- 自动扫描 ysoserial.jar，读取所有可用链的名称、作者、依赖版本
- 搜索框实时过滤
- 彩色图标按类型区分：CC 蓝、CB 紫、Spring 绿、探测类黄
- 🐴 图标标识该链支持注入内存马（走 TemplatesImpl 加载字节码）
- 底部「+ 新建自定义链」可添加自定义 Payload 类

**链列表图标说明**

| 图标 | 含义 |
|------|------|
| ● | 有内置调用图 |
| ○ | 暂无内置调用图 |
| ★ | 自定义链 |
| 🐴 | 支持注入内存马 |

### 调用图编辑器

内置主流链的调用图，选中链后自动展示。所有图均可编辑。

**工具栏操作**

| 按钮 | 说明 |
|------|------|
| + 节点 | 切换到添加节点模式，双击画布空白处创建节点 |
| → 连边 | 依次点击两个节点，创建带箭头的贝塞尔曲线边 |
| ✕ 删除 | 切换到删除模式，双击节点或边将其删除 |
| ↩ 撤销 | 撤销上一步操作（最多 50 步） |
| 💾 保存 | 保存当前图到 `custom-graphs.json` |
| ↺ 重置为内置图 | 放弃本地修改，恢复内置默认图 |
| 📂 导入 JSON | 从外部 JSON 文件导入图数据 |
| 🗑 清空 | 清空当前画布 |

**图数据文件**

| 文件 | 路径 | 说明 |
|------|------|------|
| 内置图 | jar 内 `builtin-graphs.json` | 默认调用图，随 jar 打包 |
| 用户图 | `custom-graphs.json` | 用户编辑/保存的图，优先于内置图加载 |

加载优先级：用户本地版 → 内置默认版 → 空白画布。

**节点交互**
- 任意模式下均可拖拽节点
- hover 时蓝色外发光，选中时金色外发光

### Payload 生成

1. 左侧选中链，右侧自动显示链名、作者、依赖版本
2. 输入命令，支持 Y4er 扩展的 `CLASS:xxx` 语法
3. 选择输出格式，点击生成

**输出格式**

| 格式 | 说明 | 适用场景 |
|------|------|---------|
| Base64 | 标准 Base64 编码 | 通用 |
| Gzip+Base64 | 先 Gzip 压缩再 Base64 | Shiro rememberMe |
| URL编码 | 每字节 `%XX` 全字符编码 | URL 参数注入 |
| Base64+URL编码 | Base64 后再 URL 编码 | 二次编码场景 |
| Hex | 连续十六进制字符串 | 调试 |
| Hex Dump | 带空格的十六进制 | 阅读分析 |
| Raw | 原始字节，仅可保存文件 | 直接发包 |

**Y4er 内置 CLASS 快捷按钮**

| CLASS | 说明 |
|-------|------|
| `CLASS:TomcatCmdEcho` | Tomcat 命令回显 |
| `CLASS:TomcatFilterMemShellFromThread` | Tomcat Filter 内存马 |
| `CLASS:TomcatServletMemShellFromThread` | Tomcat Servlet 内存马 |
| `CLASS:TomcatListenerMemShellFromThread` | Tomcat Listener 内存马 |

### Exploit 模块

**RMI Registry（主动打目标）**

向目标 RMI 注册中心直接发送反序列化 payload，对应 `ysoserial.exploit.RMIRegistryExploit`。

```
目标 Host + Port → 选链 → 填命令 → 发起攻击
```

**JRMP Listener（等目标回连）**

在本地启动 JRMP 服务端，等待目标反连后自动投递 payload，对应 `ysoserial.exploit.JRMPListener`。

```
填本地监听端口 → 选链 → 填命令 → 启动监听 → 等目标回连
```

- 启动后后台线程常驻，状态栏实时显示监听中
- 点「停止」安全中断监听线程

两种模式的执行日志实时输出到面板底部日志区。

### 内存马模块

内存马模块提供内存马管理、Payload 注入生成、命令执行回显三大功能。

**内存马类型**

| 类型 | 说明 | 工具内回显 |
|------|------|-----------|
| 🔄 回显型 | 通过 HTTP Header 触发命令执行，结果回显在响应中 | ✓ 支持 |
| 🔗 连接型 | 注入后作为 WebShell，用哥斯拉/冰蝎连接 | ✗ 需外部工具 |

**内置内存马**

| 名称 | 类型 | 模式 | 触发方式 |
|------|------|------|---------|
| TomcatFilter-Echo | Filter | 回显 | Header `X-Cmd` |
| TomcatFilter-Godzilla | Filter | 连接 | 哥斯拉连接，密码 pass，密钥 key |
| TomcatServlet-Echo | Servlet | 回显 | 路径 `/shellecho` + Header `X-Cmd` |
| TomcatListener-Echo | Listener | 回显 | Header `X-Cmd` |

**注入流程**

1. 选择一个内存马
2. 选择利用链（仅支持走 TemplatesImpl 的链；其中自定义内存马 class 注入只对部分链稳定）
3. 选择输出格式
4. 点击「生成注入 Payload」
5. 将生成的 payload 发送到目标的反序列化入口

**链的内存马支持说明**

只有最终触发 `TemplatesImpl` 加载字节码的链才适合注入内存马；但“理论支持 TemplatesImpl”不等于“当前 GUI 已稳定支持自定义 classBytes 注入”。

当前更推荐用于**自定义内存马 `.class` 注入**的稳定链：

- `CommonsBeanutils1`
- `CommonsBeanutils183NOCC`
- `CommonsBeanutils192NOCC`
- `CommonsBeanutils192WithDualTreeBidiMap`
- `CommonsCollections2`
- `CommonsCollections4`

其余链即使元信息里标记“支持内存马”，也更适合理解为：

- 支持内置模板或理论上属于 `TemplatesImpl` 路线
- 不代表已经完成自定义 classBytes 注入的稳定适配

选了不支持的链注入时会弹窗提示原因并阻止生成。

**命令执行回显（仅回显型内存马）**

注入成功后，在「命令执行回显」区域：

1. 输入目标 URL（如 `http://target:8080/app`）
2. 输入要执行的命令（如 `whoami`）
3. 触发 Header 默认 `X-Cmd`，可自定义
4. 触发路径默认 `/`，可自定义
5. 点击「执行命令」

工具发送 HTTP 请求，从响应中提取命令执行结果。回显提取兼容两种方式：
- **自定义回显型**：用随机 boundary 标记精确提取 `<!--boundary-->结果<!--/boundary-->`
- **Y4er 内置 TomcatCmdEcho**：直接读取响应体内容

---

## 自定义功能

### 自定义链

点击链列表底部的「+ 新建自定义链」，配置：

| 字段 | 说明 |
|------|------|
| 链名 | 自定义名称，如 `TomcatMemShell` |
| 完整类名 | 实现 `ObjectPayload` 接口的全限定类名 |
| 类文件 | 选择 `.class` 文件 |

**注意事项**
- 类必须实现 `ysoserial.payloads.ObjectPayload` 接口
- 仅支持导入已编译好的 `.class` 文件
- 文件会复制到项目目录 `payloads/`，不依赖原始文件路径
- 如果 `.class` 文件有内部类（如 `Foo$1.class`），会一并复制
- 自定义链在列表中显示 ★ 标识

### 自定义内存马

点击内存马面板的「+ 添加」，配置：

| 字段 | 说明 |
|------|------|
| 名称 | 内存马名称 |
| 类型 | Filter / Servlet / Listener / Valve / Agent |
| 模式 | echo（回显型）或 connect（连接型） |
| 类文件 | 选择 `.class` 文件 |

**回显型额外配置**

| 字段 | 说明 |
|------|------|
| 触发 Header | 触发命令执行的 HTTP Header 名，默认 `X-Cmd` |
| 触发路径 | 触发路径，默认 `/` |

**连接型额外配置**

| 字段 | 说明 |
|------|------|
| 连接工具 | godzilla / behinder / custom |
| 密码 | 连接密码 |
| 密钥 | 加密密钥 |

**注意事项**
- 内存马的 class 必须继承 `com.sun.org.apache.xalan.internal.xsltc.runtime.AbstractTranslet`
- 注入逻辑写在 `static {}` 静态代码块中，JSP 中的 StandardContext 注入代码可直接搬入
- 必须实现两个 `transform` 方法（可留空）
- 仅支持导入已编译好的 `.class` 文件
- 自定义内存马文件保存在项目目录 `memshells/classes/`

### 自定义链元信息

链的扩展信息（描述、是否支持内存马、分类等）存储在 JSON 配置文件中，可在工具内编辑。

| 文件 | 路径 | 说明 |
|------|------|------|
| 内置 | jar 内 `builtin-chain-meta.json` | 45 条链的默认元信息 |
| 自定义 | `chain-meta/custom.json` | 用户覆盖的元信息，优先加载 |

加载优先级：自定义 → 内置 → jar 反射获取。

---

## 运行时数据文件

当前版本的运行时数据默认存储在项目目录：

```
./
├── payloads/                    # 自定义链与 custom-chains.json
├── memshells/
│   ├── classes/                 # 自定义内存马 .class 文件
│   └── custom.json              # 自定义内存马配置
├── custom-graphs.json           # 用户编辑保存的调用图
└── chain-meta/
    └── custom.json              # 用户自定义的链元信息
```

删除对应文件即可恢复默认状态。

---

## 项目结构

```
YsoGUI/
├── pom.xml
├── README.md
├── src/main/
│   ├── java/ysogui/
│   │   ├── Main.java                  # 启动入口
│   │   ├── App.java                   # JavaFX Application
│   │   ├── core/
│   │   │   ├── YsoLoader.java         # 反射扫描 jar，读取链元信息
│   │   │   ├── PayloadGenerator.java  # 生成 payload，多格式转换
│   │   │   ├── ExploitRunner.java     # RMI / JRMP 利用模块
│   │   │   ├── ChainGraphs.java       # 加载内置调用图
│   │   │   ├── ChainMetaManager.java  # 链元信息管理（内置+自定义）
│   │   │   └── MemshellManager.java   # 内存马配置管理（内置+自定义）
│   │   ├── model/
│   │   │   ├── ChainInfo.java         # 链元信息模型
│   │   │   ├── ChainMeta.java         # 链扩展元信息模型
│   │   │   ├── GraphData.java         # 调用图数据模型
│   │   │   └── MemshellConfig.java    # 内存马配置模型
│   │   └── ui/
│   │       ├── MainWindow.java        # 主窗口
│   │       ├── ChainListPanel.java    # 左栏：链列表
│   │       ├── GraphEditorPanel.java  # 中栏：调用图编辑器
│   │       ├── PayloadPanel.java      # 右栏：Payload 生成
│   │       ├── ExploitPanel.java      # 右栏：Exploit 模块
│   │       └── MemshellPanel.java     # 右栏：内存马模块
│   └── resources/
│       ├── builtin-graphs.json        # 内置链调用图数据
│       ├── builtin-chain-meta.json    # 内置链元信息
│       └── builtin-memshells.json     # 内置内存马配置
│
# 运行时自动生成
payloads/                              # 自定义链与 custom-chains.json
memshells/classes/                     # 自定义内存马 .class 文件
memshells/custom.json                  # 用户内存马配置
custom-graphs.json                     # 用户调用图
chain-meta/custom.json                 # 用户链元信息
```

---

## 免责声明

本工具仅供安全研究与授权渗透测试使用，禁止用于非法用途。
