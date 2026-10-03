# FileSummer - 基于 Gradle 的文件过滤收集打包工具（JavaFX）

按条件（后缀包含/排除、子目录排除、日期范围、任务级递归开关）从多个来源目录收集文件，
输出到项目统一输出位置的各任务组子目录（复制或 ZIP，保留原始修改日期），
并可一键生成**可独立在命令行运行的 Gradle 工程**（`gradlew.bat collectAll`）。

界面术语说明：界面上的「任务组」在代码与配置 JSON 中对应 `RuleGroup` / `rules` 字段（历史兼容，勿改名）。

## 环境要求

| 项目 | 要求 |
|---|---|
| JDK | **17 及以上**（Gradle 9 与 JavaFX 21 的最低要求；实测 17.0.8 与 Liberica 25 均可） |
| Gradle | 无需预装，仓库自带 **Gradle Wrapper 9.8.0**（首次运行自动下载发行包） | 
| 操作系统 | Windows x64（JavaFX 依赖使用 `win` classifier；其他平台改 `build.gradle` 中 `jfxPlatform`） |


## 构建与测试

```bat
cd /d D:\workspace\file-summer
:: 机器默认 java 若不是 JDK 17+，先指过去（Gradle 9 本身必须跑在 17 及以上）
set JAVA_HOME=D:\path\to\jdk-17
.\gradlew.bat build        :: 编译 + 全部单元测试
```

- 仓库里**不写死** `org.gradle.java.home`（那是本机路径，会破坏别人的克隆和 CI）。
  想把 JDK 路径固定下来的，写进用户级 `%USERPROFILE%\.gradle\gradle.properties`，
  它对机器上所有工程生效，且不进版本库。
- 未指定时会看到 `Gradle requires JVM 17 or later to run`，按上面设置 `JAVA_HOME` 即可。

## 运行（开发模式）

```bat
.\gradlew.bat runUi
```

`runUi` 任务实际执行的 JavaFX 运行参数（等价的手工命令）：

```bat
java --module-path "<gradle缓存中的 javafx-base/graphics/controls 的 *-win.jar 路径，用 ; 分隔>" ^
     --add-modules javafx.controls ^
     -cp "build\classes\java\main;build\resources\main;<jackson/atlantafx jar>" ^
     com.filesummer.Launcher
```

要点：
- 应用类**不带** `module-info.java`，走 classpath；JavaFX 走 `--module-path` + `--add-modules javafx.controls`。
- 入口是 `com.filesummer.Launcher`（不继承 `Application`），由它调用 `MainApp`，
  这样 classpath 启动方式下 JavaFX 的模块检查才能通过。

## 打包 Windows exe

```bat
:: 若旧的 FileSummer.exe 正在运行，先关闭（否则 jpackage 目录被占用会失败）
taskkill /IM FileSummer.exe /F
rmdir /S /Q build\jpackage 2>nul

.\gradlew.bat jpackage
```

- 产物：`build\jpackage\FileSummer\FileSummer.exe`（自包含 app-image，双击即可运行，**目标机器无需安装 JDK**）。
- 原理：`jlink` 用 JavaFX 的模块化 jar + JDK jmods 生成裁剪运行时（`build/runtime-image`），
  `jpackage --type app-image` 把应用 jar、AtlantaFX/Jackson/MigLayout/Groovy 与运行时合并为独立目录。
- 如需安装包（msi/exe installer），把 `--type app-image` 改为 `--type exe` 并安装 WiX Toolset v3。


## 配置文件与容错

- 位置：`%USERPROFILE%\.file-summer\projects.json`（示例见 `docs/projects-example.json`）。
- 保存：结构性操作即时持久化；文本输入在失焦时持久化，也可点「保存配置」。
- 容错：文件缺失→自动创建默认项目；格式错误→优先从 `projects.json.bak` 恢复，
  无备份则重置为默认并把损坏文件保留为 `projects.json.corrupt`；字段不完整→按默认值补齐。
  以上情况启动时都会弹窗提示，不影响应用运行。）。

## 过滤语义

1. 后缀包含/排除、子目录均**不区分大小写**；后缀可不带点。
2. 子目录以**文件相对扫描根的相对路径**做包含匹配（绝不用绝对路径）。
3. **排除（后缀/子目录）优先于包含**。
4. 递归为**任务级**开关（「扫描方式」勾选框在任务组设置里）；关闭时该任务只扫描所选目录的直接子层。
5. 复制与 ZIP 均保留原始文件修改时间。
6. 每个任务组可命名、启停、排序，输出到项目唯一输出位置下的各自子目录（如 `01_DD`、`02_source`）。
7. 日期过滤：界面上勾选「启用日期过滤」后，按所填日期自动判定模式——只填起始=**不早于**
   （mtime ≥ 当天 00:00）、只填结束=**早于**（mtime < 当天 00:00）、两个都填=**范围**
   （两端按天含边界）；不勾选或两个日期都空视为不限制。配置中仍显式记录 `dateMode`+`dateFrom`/`dateTo`。
   生成的 Gradle 脚本用
   `exclude { d -> !d.file.isDirectory() && (d.file.lastModified() < 起毫秒L || d.file.lastModified() >= 止毫秒L) }`
   实现（不排除目录，避免误剪子树）。
8. **Groovy 脚本过滤**（任务字段 `filterScript`，空=停用）：任务表单「脚本过滤」行点「编辑脚本…」弹窗编写，
   脚本体作为闭包/方法体逐文件求值，参数 `f`（java.io.File）、`relPath`（相对扫描根路径，`/` 分隔）、`name`（文件名），
   返回**真值=保留**（Groovy truth）。界面执行用内嵌 Groovy 引擎（`org.apache.groovy:groovy`，classpath 依赖）；
   生成的 Gradle 工程把它写成 `def <task>_keep(File f, String relPath, String name) { … }` +
   `exclude { d -> d.file.isFile() && !<task>_keep(...) }`，两边语义一致。脚本编译/执行错误会中止本次收集并弹窗提示。
   注意脚本可执行任意 Groovy 代码，仅填写自己编写或可信来源的脚本。

## 界面与多语言

- 界面布局使用 **MigLayout**（`com.miglayout:miglayout-javafx`），左右分栏各占 50% + 底部日志：
  左侧上方为**项目设置**无边框标题的边框盒（输出位置/同名冲突/输出方式 + 执行行：「开始执行」与「Gradle」（生成 Gradle 工程）），
  左侧下方为**任务组**边框盒（工具栏「新增/删除/↑/↓」+ 任务名称列表，停用行置灰，占满左栏剩余高度）；
  右侧为当前选中任务的表单（启用/名称/输出子目录/来源目录/扫描方式/过滤条件/脚本过滤/日期）；
  底部为**日志**区（收集报告、错误信息）。
- 日期过滤：「修改日期」行 = 勾选框（无文字）+ 起始/结束 DatePicker 同行排列（中间 `~`），勾选后日期框才可编辑；不勾或全空=不过滤。
- 支持**简体中文 / 日本語 / English**切换：顶栏「文A」纯文字按钮，点击弹出语言菜单（当前语言打勾），选择后即时重建界面；
  选择保存在 `projects.json` 的 `language` 字段（`zh_CN`/`ja_JP`/`en_US`），下次启动生效。
- 文案资源：`src/main/resources/messages.properties`（中文基准）、`messages_ja_JP.properties` 与 `messages_en_US.properties`（UTF-8，JEP 226）。
  新增文案请同时补三个文件；带参文案使用 `MessageFormat`（`{0}`…），英文文案中避免裸单引号 `'`（MessageFormat 转义符）。

## 生成可独立运行的 Gradle 工程

界面点「生成 Gradle 脚本」，选择一个目录后会在其下生成 `<项目名>-collector/`：

```
build.gradle / collect.gradle   每个启用任务组一个 Copy 任务（collect_N_xxx）
settings.gradle
gradlew.bat + gradle/wrapper    自带 Wrapper，开箱即用
README.txt
```

命令行运行（目标机器需 JDK 17+；Gradle 由 Wrapper 提供）：

```bat
cd <项目名>-collector
gradlew.bat collectAll
```

实现细节：子目录排除使用 `exclude { closure }` 按相对路径匹配（Ant 模式会误伤文件名）；
日期保留用 `eachFile` 记录源文件时间戳 + `doLast` 回写，兼容 Gradle 7.6+。


## 常见问题
