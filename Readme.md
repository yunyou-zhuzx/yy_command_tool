# YunYou Command Tool

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2023.2%2B-green)
![Java](https://img.shields.io/badge/Java-17-orange)

一个 IntelliJ IDEA 插件，在 IDE 右侧工具窗口中提供树形命令管理界面，支持 **Maven**、**Gradle**、**PowerShell**、**Shell**、**Cmd** 命令的快速执行，内置部署脚本和代码生成脚本。

---

## 功能特性

### 📂 分类命令树

| 分类 | 说明 |
|------|------|
| **常用命令** | 预置 `clean test`、`clean package`、`clean install` 等高频 Maven 命令，支持收藏自定义命令 |
| **模块打包** | 自动检测 Spring Boot Maven 模块，一键 `clean package` |
| **脚本执行** | 内置部署与代码生成 PowerShell 脚本，支持参数对话框 |
| **自定义命令** | 用户添加任意类型命令，支持分组管理、排序、收藏 |

### ⚡ 命令执行

- 双击命令 → 在 Run Content Executor 中实时输出日志
- 支持额外参数输入框
- 终止运行中进程
- 自动重试（Rerun）
- Java 堆栈跟踪文件链接（点击跳转源码）
- **ANSI 颜色输出** — 伪终端（ConPTY）渲染 PowerShell 彩色日志
- **UTF-8 编码** — 完美支持中文输出

### 🛠 自定义命令管理

- 添加/编辑/删除自定义命令
- 拖拽式排序（上移/下移）
- 分组管理（新建、重命名、排序）
- 收藏到常用命令

### 📜 内置脚本

提供三个内置 PowerShell 脚本，带参数对话框：

| 脚本 | 功能 |
|------|------|
| `deleteGenFile.ps1` | 清理指定模块的 gen 代码目录 |
| `updateGenFile.ps1` | 从远程脚本更新生成的代码文件 |
| `deploy.ps1` | 部署项目到远程服务器（支持 jar/docker） |

### 🔄 Maven 集成

- 同步模块打包目标为 Maven Run Configuration
- 使用 IDE 配置的 Maven 设置（settings.xml、本地仓库等）

---

## 快速开始

### 构建

```bash
# 在沙箱 IDE 中运行
./gradlew runIde

# 构建插件分发包
./gradlew buildPlugin

# 构建产物位于 build/distributions/*.zip
```

### 安装

1. 打开 IntelliJ IDEA → `Settings` → `Plugins` → `⚙️` → `Install Plugin from Disk...`
2. 选择 `build/distributions/YunYou-Command-Tool-*.zip`
3. 重启 IDE

### 使用

1. 打开 Maven 项目（包含 `pom.xml`）
2. 点击右侧工具窗口 **YunYou Command Tool**
3. 在命令树中双击任意命令执行

---

## 兼容性

- **IDE**: IntelliJ IDEA 2023.2 及以上（Community / Ultimate）
- **Build**: 232 — 261.*
- **Java**: 17
- **依赖**:
  - `com.intellij.modules.platform`（核心平台）
  - `org.jetbrains.idea.maven`（Maven 集成）

---

## 架构概览

### 组件

| 组件 | 文件 | 职责 |
|------|------|------|
| `CommandRunnerToolWindow` | `toolwindow/CommandRunnerToolWindow.java` | 工具窗口 UI：树形渲染、工具栏、右键菜单、增删改对话框、脚本参数对话框 |
| `CommandGoalService` | `service/CommandGoalService.java` | 构建分类树模型 — 通过 PSI 读取 pom.xml、DOM 扫描 Maven 模块、合并自定义命令 |
| `CommandRunnerSettings` | `service/CommandRunnerSettings.java` | 持久化项目级配置（`command-runner.xml`）：自定义命令、分组排序、收藏 |
| `CommandUtil` | `util/CommandUtil.java` | 命令行构造与执行 — 按 `CommandType` 分发到 mvn/gradle/powershell/cmd/bash；集成 `RunContentExecutor`；添加文件链接过滤器 |
| `BuiltInScriptRegistry` | `service/BuiltInScriptRegistry.java` | 内置 PowerShell 脚本定义与参数 Schema，支持从项目信息自动填充默认值 |

### 数据流

1. `CommandRunnerToolWindow.Factory.createToolWindowContent()` 构建 UI 并调用 `rebuildTree()`
2. `rebuildTree()` → `CommandGoalService.getCategorizedGoals()` → `List<GoalCategory>`
3. 分类分为 **内置**（常用命令、模块打包、脚本执行 — 挂载在根节点下）和 **自定义**（统一归入"自定义"分组）
4. 双击 → `executeSelected()` → `CommandUtil.executeCommand()` → `RunContentExecutor.run()`
5. 内置脚本：`ScriptParamDialog` 收集参数 → `ScriptDef.buildInjectedScript()` 将变量赋值注入脚本体

### 关键技术点

- **双 XML 解析策略并存**: IntelliJ PSI（`XmlTag`）用于实时 pom.xml 插件目标发现；W3C DOM 用于无需 PSI 的文件系统模块扫描
- **伪终端（ConPTY）**: `PtyCommandLine` 创建伪终端，使 Windows PowerShell 的 Console API 颜色调用转为 ANSI 转义序列，`ColoredProcessHandler` 渲染颜色
- **编码处理**: 脚本内容注入 `$OutputEncoding` / `[Console]::OutputEncoding = UTF8`，配合 UTF-8 BOM 文件头和 `commandLine.setCharset(UTF_8)`，确保中文不乱码
- **模块扫描**: `deepScanModules()` 递归遍历 Maven 模块树，仅收集含 `spring-boot-maven-plugin` 的叶子模块作为打包目标
- **持久化**: `CommandRunnerSettings` 基于 `PersistentStateComponent`，序列化到项目 `.idea/command-runner.xml`

---

## 许可证

本项目基于 Apache License 2.0 开源。详见 [LICENSE](LICENSE) 文件。

```
Copyright 2025 YunYou Cloud

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
