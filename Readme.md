# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build/Run

```bash
# Run the plugin in a sandbox IDE
./gradlew runIde

# Build the plugin distribution
./gradlew buildPlugin
```

Gradle daemon is disabled (`org.gradle.daemon=false`). JVM args: `-Xmx2048m -Dfile.encoding=UTF-8`.

The pre-configured run configuration is `Run IDE with Plugin` (runs `gradle runIde`).

## Architecture

This is an **IntelliJ IDEA plugin** that adds a "YunYou Command Tool" tool window (anchored right). It presents a tree of executable commands — Maven lifecycle phases, plugin goals, Spring Boot module packages, built-in PowerShell scripts, and user-defined custom commands. Double-click executes the command in a Run Content executor with real-time log output.

**Package:** `com.yunyoucloud.tool`

### Component Map

| Component | File | Role |
|---|---|---|
| `MavenRunnerToolWindow` | `toolwindow/MavenRunnerToolWindow.java` | Tool window UI: tree rendering, toolbar, context menus, add/edit/delete dialogs, script parameter dialog |
| `MavenGoalService` | `service/MavenGoalService.java` | Builds the categorized tree model — reads pom.xml via PSI, scans Maven modules via DOM, merges custom goals from settings |
| `MavenRunnerSettings` | `service/MavenRunnerSettings.java` | Persistent per-project state (`maven-runner.xml`): custom goals, group ordering, favorites |
| `MavenCommandUtil` | `util/MavenCommandUtil.java` | Command-line construction and execution — dispatches to `mvn`, `gradle`, `powershell`, `cmd`, `bash` based on `CommandType`; wires into IntelliJ's `RunContentExecutor`; adds file-link filters for Java stack traces |
| `BuiltInScriptRegistry` | `service/BuiltInScriptRegistry.java` | Hardcoded definitions for 3 built-in PowerShell scripts with parameter schemas — fills defaults from project info (artifactId, moduleNames, basePath) |

### Data Flow

1. `MavenRunnerToolWindow.Factory.createToolWindowContent()` builds the UI and calls `rebuildTree()`
2. `rebuildTree()` calls `MavenGoalService.getCategorizedGoals()` which returns `List<GoalCategory>` — each category has a name, command type, and list of `GoalNode`s
3. Categories split into **built-in** (Lifecycle, 常用命令, Plugins, 模块打包, 脚本执行 — mounted directly under root) and **custom** (all grouped under a "自定义" parent node)
4. Double-click → `doExecute()` → `MavenCommandUtil.executeCommand()` → `RunContentExecutor.run()`
5. For built-in scripts, a `ScriptParamDialog` collects user input first, then `ScriptDef.buildInjectedScript()` prepends PowerShell variable assignments before the script body

### Key Implementation Details

- **Two XML parsing strategies coexist:** IntelliJ PSI (`XmlTag`) for live-plugin-goal discovery from the open project's pom.xml; W3C DOM for filesystem-based module scanning that doesn't require PSI access
- **`MavenGoalService.deepScanModules()`** recursively walks the Maven module tree, looking for leaf modules with `spring-boot-maven-plugin` — only those are added as "模块打包" targets
- **`CommandType`** enum supports MAVEN, GRADLE, POWERSHELL, SHELL, CMD, and GENERIC — each has platform-aware command construction (e.g., `mvn.cmd` vs `mvn` on Windows)
- **Tool window is a `projectService`**, not application-level — state is per-project
- **Persistence:** `MavenRunnerSettings` implements `PersistentStateComponent`, serializing to `maven-runner.xml` in the project's `.idea` directory

### Plugin Dependencies

- `com.intellij.modules.platform` — base IntelliJ platform
- `org.jetbrains.idea.maven` — IntelliJ's built-in Maven plugin (required for pom.xml PSI access)
- Targets IntelliJ 2023.2 Community Edition, compatible with builds 232–251.*
- Java 17 source/target
