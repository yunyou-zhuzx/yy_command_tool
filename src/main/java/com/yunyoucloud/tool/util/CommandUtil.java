package com.yunyoucloud.tool.util;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.RunContentExecutor;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.PtyCommandLine;
import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.OpenFileHyperlinkInfo;
import com.intellij.execution.process.ColoredProcessHandler;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.xml.XmlTag;
import com.yunyoucloud.tool.service.CommandRunnerSettings.CommandType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.project.MavenGeneralSettings;
import org.jetbrains.idea.maven.project.MavenProjectsManager;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommandUtil {
	
	private static final Logger LOG = Logger.getInstance(CommandUtil.class);
	
	private static final Pattern FILE_PATTERN = Pattern.compile("(\\S+\\.java):(\\d+)(?:,(\\d+))?");
	
	/**
	 * 执行命令，返回 OSProcessHandler 供调用方终止。
	 * 返回 null 表示启动失败。
	 */
	@Nullable
	public static OSProcessHandler executeCommand(
		@NotNull Project project,
		@NotNull String command,
		@NotNull CommandType commandType,
		@Nullable String extraParams,
		@Nullable Runnable onComplete
	) {
		String projectPath = project.getBasePath();
		if (projectPath == null) {
			notifyError(project, command, "Project base path is null");
			if (onComplete != null) {
				onComplete.run();
			}
			return null;
		}
		
		boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
		List<String> args = buildCommandLine(project, command, commandType, extraParams, isWin);
		
		String title = commandType.getDisplayName() + ": " + command;
		Runnable rerun = () -> executeCommand(project, command, commandType, extraParams, onComplete);
		return doExecute(project, projectPath, args, title, rerun, onComplete);
	}
	
	// 强制 PowerShell 使用 UTF-8 输出，避免中文乱码
	private static final String PS_ENCODING_PREFIX =
		"$OutputEncoding = [Text.Encoding]::UTF8; [Console]::OutputEncoding = [Text.Encoding]::UTF8; ";
	private static final String PS_ANSI_PREFIX =
		"$PSStyle.OutputRendering='Ansi'; ";
	
	/**
	 * 将 PowerShell 脚本写入临时文件并通过 -File 执行（不在控制台输出脚本内容）。
	 *
	 * @param scriptContent 完整的 PowerShell 脚本内容
	 * @param scriptName    脚本显示名称（用于生成临时文件名和标题）
	 */
	@Nullable
	public static OSProcessHandler executePowerShellScript(@NotNull Project project,
	                                                       @NotNull String scriptContent,
	                                                       @NotNull String scriptName,
	                                                       @Nullable Runnable onComplete) {
		String projectPath = project.getBasePath();
		if (projectPath == null) {
			notifyError(project, scriptName, "Project base path is null");
			if (onComplete != null) {
				onComplete.run();
			}
			return null;
		}
		
		boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
		String psExe = resolvePowerShellPath(isWin);
		
		// 将编码设置注入到脚本内容中，保持 -File 执行方式
		String injectedScript;
		if (psExe.contains("pwsh")) {
			// PowerShell 7+: 注入 ANSI 颜色 + UTF-8 编码设置
			injectedScript = PS_ENCODING_PREFIX + PS_ANSI_PREFIX + scriptContent;
		} else {
			// Windows PowerShell 5.1: 注入 UTF-8 编码设置
			injectedScript = PS_ENCODING_PREFIX + scriptContent;
		}
		
		// 写入临时 .ps1 文件（UTF-8 BOM + 编码设置 + 脚本内容）
		Path tempFile;
		try {
			tempFile = Files.createTempFile("powershell_", "_" + scriptName);
			tempFile.toFile().deleteOnExit();
			byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
			byte[] content = injectedScript.getBytes(StandardCharsets.UTF_8);
			byte[] data = new byte[bom.length + content.length];
			System.arraycopy(bom, 0, data, 0, bom.length);
			System.arraycopy(content, 0, data, bom.length, content.length);
			Files.write(tempFile, data);
		} catch (IOException e) {
			LOG.warn("Failed to write temp script file", e);
			notifyError(project, scriptName, "无法创建临时脚本文件: " + e.getMessage());
			if (onComplete != null) {
				onComplete.run();
			}
			return null;
		}
		
		String scriptPath = tempFile.toAbsolutePath().toString();
		
		List<String> args = new ArrayList<>();
		args.add(psExe);
		args.add("-NoProfile");
		args.add("-ExecutionPolicy");
		args.add("Bypass");
		// 统一使用 -File 执行，维持与手动执行一致的行为
		args.add("-File");
		args.add(scriptPath);
		
		String title = "PowerShell: " + scriptName;
		Runnable rerun = () -> executePowerShellScript(project, scriptContent, scriptName, onComplete);
		return doExecute(project, projectPath, args, title, rerun, onComplete);
	}
	
	// ──────────────────── 内部执行逻辑 ────────────────────
	
	@Nullable
	private static OSProcessHandler doExecute(@NotNull Project project,
	                                          @NotNull String projectPath,
	                                          @NotNull List<String> args,
	                                          @NotNull String title,
	                                          @NotNull Runnable rerun,
	                                          @Nullable Runnable onComplete) {
		GeneralCommandLine commandLine;
		try {
			// PtyCommandLine 创建伪终端（ConPTY），使 Windows PowerShell 的 Console API
			// 调用转换为 ANSI 转义序列，ColoredProcessHandler 可渲染颜色
			commandLine = new PtyCommandLine(new GeneralCommandLine(args));
		} catch (Throwable ignored) {
			commandLine = new GeneralCommandLine(args);
		}
		commandLine.setWorkDirectory(projectPath);
		commandLine.setCharset(StandardCharsets.UTF_8);
		// 通知 PowerShell 等程序输出终端支持颜色
		commandLine.withEnvironment("TERM", "xterm-256color");
		
		try {
			ColoredProcessHandler handler = new ColoredProcessHandler(commandLine);
			Filter fileFilter = createFileLinkFilter(project, projectPath);
			
			handler.addProcessListener(new ProcessAdapter() {
				@Override
				public void processTerminated(@NotNull ProcessEvent event) {
					if (event.getExitCode() != 0) {
						ApplicationManager.getApplication().invokeLater(() ->
							notifyWarning(project, title, event.getExitCode()));
					}
					if (onComplete != null) {
						onComplete.run();
					}
				}
			});
			
			ApplicationManager.getApplication().invokeLater(() -> new RunContentExecutor(project, handler)
				.withFilter(fileFilter)
				.withTitle(title)
				.withRerun(rerun)
				.withStop(handler::destroyProcess, () -> !handler.isProcessTerminated())
				.run());
			
			return handler;
			
		} catch (ExecutionException e) {
			LOG.warn("Failed to execute: " + title, e);
			notifyError(project, title, e.getMessage());
			if (onComplete != null) {
				onComplete.run();
			}
			return null;
		}
	}
	
	// ──────────────────── 命令构造 ────────────────────
	
	@NotNull
	private static List<String> buildCommandLine(@Nullable Project project,
	                                             @NotNull String command,
	                                             @NotNull CommandType commandType,
	                                             @Nullable String extraParams,
	                                             boolean isWin) {
		List<String> args = new ArrayList<>();
		
		switch (commandType) {
			case MAVEN -> {
				String mvnPath = resolveMavenPath(project, isWin);
				args.add(mvnPath);
				// 添加 IntelliJ Maven 标准参数（来自 IDE Maven 配置）
				args.addAll(buildMavenIntelliJArgs(project));
				args.addAll(Arrays.asList(command.split("\\s+")));
				appendExtra(args, extraParams);
			}
			case GRADLE -> {
				args.add(isWin ? "gradle.bat" : "gradle");
				args.addAll(Arrays.asList(command.split("\\s+")));
				appendExtra(args, extraParams);
			}
			case POWERSHELL -> {
				String psExe = resolvePowerShellPath(isWin);
				args.add(psExe);
				args.add("-NoProfile");
				args.add("-Command");
				String prefix = psExe.contains("pwsh") ? "$PSStyle.OutputRendering='Ansi'; " : "";
				args.add(prefix + command);
			}
			case SHELL -> {
				args.add(isWin ? "cmd" : "bash");
				args.add(isWin ? "/c" : "-c");
				args.add(command);
			}
			case CMD -> {
				args.add("cmd");
				args.add("/c");
				args.add(command);
			}
			case GENERIC -> {
				String[] tokens = parseGenericCommand(command);
				args.addAll(Arrays.asList(tokens));
			}
		}
		return args;
	}
	
	/**
	 * 从 IntelliJ Maven 配置中解析 Maven 可执行文件路径。
	 * 优先使用 IDE 设置的自定义 Maven home，找不到时回退到系统 PATH 中的 mvn。
	 * 使用 bundled Maven（getMavenHome() 为空）时也回退到 PATH。
	 */
	@NotNull
	private static String resolveMavenPath(@Nullable Project project, boolean isWin) {
		if (project != null) {
			try {
				MavenProjectsManager manager = MavenProjectsManager.getInstance(project);
				if (manager != null) {
					String mavenHome = manager.getGeneralSettings().getMavenHome();
					if (mavenHome != null && !mavenHome.isEmpty()) {
						String mvn = mavenHome + File.separator + "bin" + File.separator
							+ (isWin ? "mvn.cmd" : "mvn");
						if (new File(mvn).exists()) {
							return new File(mvn).getAbsolutePath();
						}
					}
				}
			} catch (Exception ignored) {
				// MavenProjectsManager 不可用时，回退到系统 PATH
			}
		}
		return isWin ? "mvn.cmd" : "mvn";
	}

	/**
	 * 构建 IntelliJ Maven 标准参数列表，使执行的 Maven 命令与 IDE 配置一致。
	 * 包括：-Didea.version, -Dmaven.ext.class.path, -Djansi.passthrough,
	 * -Dstyle.color=always, -s (settings.xml), -Dmaven.repo.local
	 */
	@NotNull
	private static List<String> buildMavenIntelliJArgs(@Nullable Project project) {
		List<String> args = new ArrayList<>();
		if (project == null) return args;

		try {
			MavenProjectsManager manager = MavenProjectsManager.getInstance(project);
			if (manager == null) return args;

			MavenGeneralSettings settings = manager.getGeneralSettings();
			String mavenHome = settings.getMavenHome();
			if (mavenHome == null || mavenHome.isEmpty()) return args;

			// -Didea.version (如 "2026.1")
			String fullVersion = ApplicationInfo.getInstance().getFullVersion();
			if (fullVersion != null && !fullVersion.isEmpty()) {
				args.add("-Didea.version=" + fullVersion);
			}

			// -Dmaven.ext.class.path (事件监听器 JAR，位于 Maven home 上一级的 intellij.maven.rt 目录)
			File mavenHomeDir = new File(mavenHome);
			File extJar = new File(mavenHomeDir.getParentFile(), "intellij.maven.rt/maven-event-listener.jar");
			if (extJar.exists()) {
				args.add("-Dmaven.ext.class.path=" + extJar.getAbsolutePath());
			}

			// -Djansi.passthrough=true (ANSI 颜色直通)
			args.add("-Djansi.passthrough=true");
			// -Dstyle.color=always (强制 Maven 输出带颜色)
			args.add("-Dstyle.color=always");

			// -s (用户 settings.xml)
			String userSettingsFile = settings.getUserSettingsFile();
			if (userSettingsFile != null && !userSettingsFile.isEmpty()) {
				args.add("-s");
				args.add(userSettingsFile);
			}

			// -Dmaven.repo.local (本地仓库路径)
			// 注意: MavenGeneralSettings.getLocalRepository() 是 internal API,此处跳过
			// Maven 会使用配置的默认本地仓库 ~/.m2/repository

		} catch (Exception ignored) {
			// MavenProjectsManager 不可用时静默跳过
		}

		return args;
	}

	/**
	 * 解析 PowerShell 可执行文件路径，优先使用 pwsh（PowerShell 7+，支持 ANSI）。
	 */
	@NotNull
	private static String resolvePowerShellPath(boolean isWin) {
		if (isWin) {
			// Windows: 优先尝试 pwsh.exe（PowerShell 7+）
			try {
				String[] cmd = {"where", "pwsh"};
				Process p = Runtime.getRuntime().exec(cmd);
				boolean found = p.waitFor() == 0;
				p.destroy();
				if (found) return "pwsh";
			} catch (Exception ignored) {
			}
			return "powershell";
		}
		return "pwsh";
	}

	private static void appendExtra(List<String> args, @Nullable String extraParams) {
		if (extraParams != null && !extraParams.isBlank()) {
			args.addAll(parseExtraParams(extraParams.trim()));
		}
	}
	
	private static String[] parseGenericCommand(String command) {
		return splitBySpacesPreservingQuotes(command).toArray(new String[0]);
	}
	
	private static List<String> parseExtraParams(String extraParams) {
		return splitBySpacesPreservingQuotes(extraParams);
	}
	
	/**
	 * 按空格切分字符串，支持双引号包裹的带空格片段
	 */
	private static List<String> splitBySpacesPreservingQuotes(String input) {
		List<String> result = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean inQuotes = false;
		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (c == '"') {
				inQuotes = !inQuotes;
			} else if (c == ' ' && !inQuotes) {
				if (!current.isEmpty()) {
					result.add(current.toString());
					current.setLength(0);
				}
			} else {
				current.append(c);
			}
		}
		if (!current.isEmpty()) {
			result.add(current.toString());
		}
		return result;
	}
	
	// ──────────────────── 文件链接 ────────────────────
	
	@NotNull
	private static Filter createFileLinkFilter(@NotNull Project project, @NotNull String projectPath) {
		return (line, entireLength) -> {
			Matcher m = FILE_PATTERN.matcher(line);
			if (!m.find()) {
				return null;
			}
			String filePath = m.group(1);
			int lineNum = Integer.parseInt(m.group(2));
			int col = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
			VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(projectPath + "/" + filePath);
			if (vf == null) {
				return null;
			}
			return new Filter.Result(
				entireLength - line.length() + m.start(),
				entireLength - line.length() + m.end(),
				new OpenFileHyperlinkInfo(project, vf, lineNum - 1, col));
		};
	}
	
	// ──────────────────── 通知 ────────────────────
	
	public static void notifyError(@NotNull Project project, @NotNull String command, @Nullable String detail) {
		String content = "命令 '" + command + "' 无法启动。"
			+ (detail != null ? "\n错误: " + detail : "");
		Notification notification = new Notification(
			"YunYou Command Tool",
			"命令启动失败",
			content,
			NotificationType.ERROR);
		Notifications.Bus.notify(notification, project);
	}
	
	private static void notifyWarning(@NotNull Project project, @NotNull String command, int exitCode) {
		String content = "命令 '" + command + "' 退出码: " + exitCode + "。";
		Notification notification = new Notification(
			"YunYou Command Tool",
			"命令异常退出",
			content,
			NotificationType.WARNING);
		Notifications.Bus.notify(notification, project);
	}
	
	public static void fileSaveNotify(@NotNull Project project, @NotNull String command) {
		String content = "文件脚本 '" + command + "' 保存成功。";
		Notification notification = new Notification(
			"YunYou Command Tool",
			"文件保存成功",
			content,
			NotificationType.INFORMATION);
		Notifications.Bus.notify(notification, project);
	}
	
	public static List<String> getModuleNames(@NotNull Project project) {
		List<String> modules = new ArrayList<>();
		deepScanModules(project.getBasePath(), "", modules);
		return modules;
	}
	
	/**
	 * 递归深度扫描 Maven 模块，只收集含有 spring-boot-maven-plugin 的叶子模块。
	 *
	 * @param rootPath 项目根路径
	 * @param relative 当前模块相对于 rootPath 的路径
	 * @param result   收集到的模块路径列表
	 */
	private static void deepScanModules(String rootPath, String relative, List<String> result) {
		File dir = new File(rootPath, relative);
		File pomFile = new File(dir, "pom.xml");
		if (!pomFile.exists()) {
			return;
		}
		
		// 读取 pom.xml
		List<String> subModules = new ArrayList<>();
		boolean hasSpringBootPlugin = false;
		boolean isPomPackaging = false;
		
		try {
			VirtualFile vf = LocalFileSystem.getInstance().findFileByIoFile(pomFile);
			if (vf == null) {
				return;
			}
			// PSI 需要 project，这里只能通过文件名推断
			// 用简单 XML 解析避免 PSI 依赖问题
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document doc = builder.parse(pomFile);
			Element docElem = doc.getDocumentElement();
			
			// 检查 packaging
			String packaging = getChildText(docElem, "packaging");
			isPomPackaging = "pom".equals(packaging);
			
			// 收集子模块
			Element modulesEl = getChild(docElem, "modules");
			if (modulesEl != null) {
				var moduleNodes = modulesEl.getElementsByTagName("module");
				for (int i = 0; i < moduleNodes.getLength(); i++) {
					String name = moduleNodes.item(i).getTextContent().trim();
					if (!name.isEmpty()) {
						subModules.add(name);
					}
				}
			}
			
			// 检查 spring-boot-maven-plugin
			Element buildEl = getChild(docElem, "build");
			if (buildEl != null) {
				hasSpringBootPlugin = checkSpringBootPlugin(buildEl);
			}
			if (!hasSpringBootPlugin) {
				// 也检查 pluginManagement
				Element pmEl = getChild(docElem, "build");
				if (pmEl != null) {
					Element pmPlugins = getChild(pmEl, "pluginManagement");
					if (pmPlugins != null) {
						hasSpringBootPlugin = checkSpringBootPlugin(pmPlugins);
					}
				}
			}
			
		} catch (Exception e) {
			// XML 解析失败时静默返回，不阻塞模块扫描
			return;
		}
		
		if (!subModules.isEmpty()) {
			// 有子模块 → 聚合 POM，递归扫描
			for (String sub : subModules) {
				String subRel = relative.isEmpty() ? sub : relative + "/" + sub;
				deepScanModules(rootPath, subRel, result);
			}
		} else if (hasSpringBootPlugin && !isPomPackaging) {
			// 叶子模块 + 有 spring-boot 插件 → 可打包
			result.add(relative);
		}
	}
	
	@Nullable
	public static Element getChild(Element parent, String name) {
		var nodes = parent.getElementsByTagNameNS("*", name);
		if (nodes.getLength() == 0) {
			// 尝试不带命名空间
			nodes = parent.getElementsByTagName(name);
		}
		if (nodes.getLength() > 0 && nodes.item(0) instanceof Element e) {
			return e;
		}
		return null;
	}
	
	@Nullable
	public static String getChildText(Element parent, String name) {
		Element child = getChild(parent, name);
		if (child == null) {
			return null;
		}
		String t = child.getTextContent();
		return (t != null && !t.isBlank()) ? t.trim() : null;
	}
	
	@Nullable
	public static XmlTag findChild(XmlTag p, String name) {
		for (XmlTag c : p.getSubTags()) {
			if (name.equals(c.getLocalName())) {
				return c;
			}
		}
		return null;
	}
	
	@Nullable
	public static String getChildText(XmlTag p, String name) {
		XmlTag c = findChild(p, name);
		if (c == null) {
			return null;
		}
		String t = c.getValue().getTrimmedText();
		return t.isEmpty() ? null : t;
	}
	
	private static boolean checkSpringBootPlugin(Element parent) {
		Element pluginsEl = getChild(parent, "plugins");
		if (pluginsEl == null) {
			return false;
		}
		var pluginNodes = pluginsEl.getElementsByTagName("plugin");
		for (int i = 0; i < pluginNodes.getLength(); i++) {
			if (pluginNodes.item(i) instanceof Element pluginEl) {
				String aid = getChildText(pluginEl, "artifactId");
				if ("spring-boot-maven-plugin".equals(aid)) {
					return true;
				}
			}
		}
		return false;
	}
}
