package com.yunyoucloud.tool.service;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.yunyoucloud.tool.service.CommandRunnerSettings.CommandType;
import com.yunyoucloud.tool.toolwindow.CommandRunnerToolWindow.GoalNode;
import com.yunyoucloud.tool.util.CommandUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.yunyoucloud.tool.toolwindow.CommandRunnerToolWindow.Factory.extractModuleName;

@Service(Service.Level.PROJECT)
public final class CommandGoalService {
	
	public static CommandGoalService getInstance(@NotNull Project project) {
		return project.getService(CommandGoalService.class);
	}
	
	public enum GoalSource {LIFECYCLE, COMMON, PLUGIN, SCRIPT, MODULE_PACKAGE, CUSTOM}
	
	public static class GoalCategory {
		public final String name;
		public final CommandType commandType;
		public final List<GoalNode> goals;
		
		public GoalCategory(String name, CommandType commandType, List<GoalNode> goals) {
			this.name = name;
			this.commandType = commandType;
			this.goals = goals;
		}
	}
	
	public boolean isMavenProject(@NotNull Project project) {
		String basePath = project.getBasePath();
		if (basePath == null) {
			return false;
		}
		return new File(basePath, "pom.xml").exists();
	}
	
	@NotNull
	public List<GoalCategory> getCategorizedGoals(@NotNull Project project) {
		List<GoalCategory> categories = new ArrayList<>();
		if (isMavenProject(project)) {
//			categories.add(createLifecycleCategory());
//			GoalCategory pc = createPluginCategory(project);
//			if (!pc.goals.isEmpty()) {
//				categories.add(pc);
//			}
			GoalCategory mc = createModulePackageCategory(project);
			if (!mc.goals.isEmpty()) {
				categories.add(mc);
			}
			
			// 脚本执行对所有项目类型可用
			GoalCategory codeGenCategory = createCodeGenCategory(project);
			if (!codeGenCategory.goals.isEmpty()) {
				categories.add(codeGenCategory);
			}
			
			categories.add(createCommonCategory(project));
		}
		
		categories.addAll(createCustomCategories(project));
		return categories;
	}
	
	// ── Lifecycle ──
	
	private GoalCategory createLifecycleCategory() {
		List<GoalNode> g = new ArrayList<>();
		g.add(new GoalNode("clean", "删除 target 目录", CommandType.MAVEN, GoalSource.LIFECYCLE));
		g.add(new GoalNode("validate", "校验项目信息", CommandType.MAVEN, GoalSource.LIFECYCLE));
		g.add(new GoalNode("compile", "编译主源码", CommandType.MAVEN, GoalSource.LIFECYCLE));
		g.add(new GoalNode("test", "运行单元测试", CommandType.MAVEN, GoalSource.LIFECYCLE));
		g.add(new GoalNode("package", "打包项目", CommandType.MAVEN, GoalSource.LIFECYCLE));
		g.add(new GoalNode("verify", "验证打包结果", CommandType.MAVEN, GoalSource.LIFECYCLE));
		g.add(new GoalNode("install", "安装到本地仓库", CommandType.MAVEN, GoalSource.LIFECYCLE));
		g.add(new GoalNode("deploy", "发布到远程仓库", CommandType.MAVEN, GoalSource.LIFECYCLE));
		g.add(new GoalNode("site", "生成项目站点", CommandType.MAVEN, GoalSource.LIFECYCLE));
		return new GoalCategory("Lifecycle", CommandType.MAVEN, g);
	}
	
	private GoalCategory createCommonCategory(@NotNull Project project) {
		List<GoalNode> g = new ArrayList<>();
		g.add(new GoalNode("clean test", "清理并测试", CommandType.MAVEN, GoalSource.COMMON));
		g.add(new GoalNode("clean compile", "清理并编译", CommandType.MAVEN, GoalSource.COMMON));
		g.add(new GoalNode("clean package", "清理并打包", CommandType.MAVEN, GoalSource.COMMON));
		g.add(new GoalNode("clean install", "清理并安装", CommandType.MAVEN, GoalSource.COMMON));
		g.add(new GoalNode("dependency:purge-local-repository", "清理本地yunyou依赖", CommandType.MAVEN, GoalSource.COMMON,
			" -DmanualInclude=com.yunyoucloud:yy-common,com.yunyoucloud:yy-common-jdk17,com.yunyoucloud:yy-mybatis-core,com.yunyoucloud:yy-mybatis-service"
		));
		
		// 添加收藏的自定义命令到常用
		CommandRunnerSettings settings = CommandRunnerSettings.getInstance(project);
		for (CommandRunnerSettings.CustomGoal cg : settings.getCustomGoals()) {
			if (settings.isFavorite(cg)) {
				CommandType type;
				try {
					type = CommandType.valueOf(cg.commandType);
				} catch (IllegalArgumentException e) {
					type = CommandType.MAVEN;
				}
				g.add(new GoalNode(cg.name, cg.description, type,
					GoalSource.COMMON, cg.defaultParams, cg.group));
			}
		}
		
		return new GoalCategory("常用命令", CommandType.MAVEN, g);
	}
	
	// ── Plugins ──
	
	@NotNull
	private GoalCategory createPluginCategory(@NotNull Project project) {
		List<GoalNode> goals = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		String bp = project.getBasePath();
		if (bp == null) {
			return new GoalCategory("Plugins", CommandType.MAVEN, goals);
		}
		VirtualFile pomFile = LocalFileSystem.getInstance().findFileByPath(bp + "/pom.xml");
		if (pomFile == null) {
			return new GoalCategory("Plugins", CommandType.MAVEN, goals);
		}
		PsiFile psi = PsiManager.getInstance(project).findFile(pomFile);
		if (!(psi instanceof XmlFile xf)) {
			return new GoalCategory("Plugins", CommandType.MAVEN, goals);
		}
		XmlTag root = xf.getRootTag();
		if (root == null) {
			return new GoalCategory("Plugins", CommandType.MAVEN, goals);
		}
		
		List<XmlTag> pluginTags = new ArrayList<>();
		collectPluginTags(root, pluginTags);
		Set<String> std = Set.of("maven-compiler-plugin", "maven-resources-plugin", "maven-jar-plugin",
			"maven-install-plugin", "maven-deploy-plugin", "maven-clean-plugin",
			"maven-site-plugin", "maven-surefire-plugin");
		
		for (XmlTag pt : pluginTags) {
			String aid = CommandUtil.getChildText(pt, "artifactId");
			if (aid == null || std.contains(aid)) {
				continue;
			}
			String prefix = deriveGoalPrefix(aid);
			String gid = CommandUtil.getChildText(pt, "groupId");
			List<String> gns = collectPluginGoals(pt);
			if (!gns.isEmpty()) {
				for (String gn : gns) {
					String fn = prefix + ":" + gn;
					if (seen.add(fn)) {
						String desc = gid != null ? gid + ":" + aid + " - " + gn : aid + " - " + gn;
						goals.add(new GoalNode(fn, desc, CommandType.MAVEN, GoalSource.PLUGIN));
					}
				}
			} else {
				String d = prefix + ":";
				if (seen.add(d)) {
					goals.add(new GoalNode(d, aid + "（目标见插件文档）", CommandType.MAVEN, GoalSource.PLUGIN));
				}
			}
		}
		return new GoalCategory("Plugins", CommandType.MAVEN, goals);
	}
	
	// ── 深度扫描 Spring Boot 模块 ──
	
	@NotNull
	private GoalCategory createModulePackageCategory(@NotNull Project project) {
		List<GoalNode> goals = new ArrayList<>();
		String basePath = project.getBasePath();
		if (basePath == null) {
			return new GoalCategory("模块打包", CommandType.MAVEN, goals);
		}
		
		List<String> modules = CommandUtil.getModuleNames(project);
		
		for (String modulePath : modules) {
			String goal = modulePath.isEmpty() ? "clean package" : "clean package -U -pl " + modulePath + " -am";
			String displayName = modulePath.isEmpty() ? "(root)" : extractModuleName(goal);
			goals.add(new GoalNode(goal, "打包 Spring Boot 模块: " + modulePath,
				CommandType.MAVEN, GoalSource.MODULE_PACKAGE, null, null, null, displayName));
		}
		return new GoalCategory("模块打包", CommandType.MAVEN, goals);
	}
	
	@NotNull
	private GoalCategory createCodeGenCategory(@NotNull Project project) {
		List<GoalNode> goals = new ArrayList<>();
		for (BuiltInScriptRegistry.ScriptDef def : BuiltInScriptRegistry.ALL_SCRIPTS) {
			goals.add(new GoalNode(def.name, def.description,
				CommandType.POWERSHELL, GoalSource.SCRIPT, null, null, def));
		}
		
		// 按持久化的 scriptOrder 排序
		CommandRunnerSettings settings = CommandRunnerSettings.getInstance(project);
		List<String> order = settings.getScriptOrder();
		if (order != null && !order.isEmpty()) {
			Map<String, Integer> rank = new LinkedHashMap<>();
			for (int i = 0; i < order.size(); i++) {
				rank.put(order.get(i), i);
			}
			goals.sort((a, b) -> {
				int ra = rank.getOrDefault(a.name, Integer.MAX_VALUE);
				int rb = rank.getOrDefault(b.name, Integer.MAX_VALUE);
				return Integer.compare(ra, rb);
			});
		}
		
		return new GoalCategory("脚本执行", CommandType.POWERSHELL, goals);
	}
	
	/**
	 * 读取项目信息，用于内置脚本参数默认值填充。
	 * 返回的 Map 包含：artifactId, moduleNames, basePath
	 */
	@NotNull
	public Map<String, String> getProjectInfo(@NotNull Project project) {
		Map<String, String> info = new LinkedHashMap<>();
		String basePath = project.getBasePath();
		if (basePath != null) {
			info.put("basePath", basePath);
		}
		
		// 从 pom.xml 读取 artifactId 和模块列表
		String pomPath = basePath != null ? basePath + "/pom.xml" : null;
		if (pomPath != null) {
			try {
				File pomFile = new File(pomPath);
				if (pomFile.exists()) {
					DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
					factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
					DocumentBuilder builder = factory.newDocumentBuilder();
					Document doc = builder.parse(pomFile);
					Element docElem = doc.getDocumentElement();
					
					String artifactId = CommandUtil.getChildText(docElem, "artifactId");
					if (artifactId != null && !artifactId.isEmpty()) {
						info.put("artifactId", artifactId);
					}
					
					// 收集所有子模块名
					List<String> moduleNames = new ArrayList<>();
					org.w3c.dom.Element modulesEl = CommandUtil.getChild(docElem, "modules");
					if (modulesEl != null) {
						var moduleNodes = modulesEl.getElementsByTagName("module");
						for (int i = 0; i < moduleNodes.getLength(); i++) {
							String name = moduleNodes.item(i).getTextContent().trim();
							if (!name.isEmpty()) {
								moduleNames.add(name);
							}
						}
					}
					if (!moduleNames.isEmpty()) {
						info.put("moduleNames", String.join(",", moduleNames));
					}
				}
			} catch (Exception ignored) {
				// 解析失败时使用空值
			}
		}
		
		return info;
	}
	
	// ── 自定义命令（按分组） ──
	
	@NotNull
	private List<GoalCategory> createCustomCategories(@NotNull Project project) {
		CommandRunnerSettings settings = CommandRunnerSettings.getInstance(project);
		List<CommandRunnerSettings.CustomGoal> customGoals = settings.getCustomGoals();
		if (customGoals == null || customGoals.isEmpty()) {
			return List.of();
		}
		
		// 按 group（或 commandType）分组
		Map<String, List<GoalNode>> grouped = new LinkedHashMap<>();
		for (CommandRunnerSettings.CustomGoal cg : customGoals) {
			CommandType type;
			try {
				type = CommandType.valueOf(cg.commandType);
			} catch (IllegalArgumentException e) {
				type = CommandType.MAVEN;
			}
			
			String groupKey = (cg.group != null && !cg.group.isEmpty())
				? cg.group : type.getDisplayName();
			
			grouped.computeIfAbsent(groupKey, k -> new ArrayList<>())
				.add(new GoalNode(cg.name, cg.description, type, GoalSource.CUSTOM, cg.defaultParams, cg.group));
		}
		
		// 按 groupOrder 排序
		Map<String, Integer> order = settings.getGroupOrder();
		List<Map.Entry<String, List<GoalNode>>> sorted = new ArrayList<>(grouped.entrySet());
		sorted.sort((a, b) -> {
			int oa = order.getOrDefault(a.getKey(), Integer.MAX_VALUE);
			int ob = order.getOrDefault(b.getKey(), Integer.MAX_VALUE);
			return Integer.compare(oa, ob);
		});
		
		List<GoalCategory> categories = new ArrayList<>();
		for (var entry : sorted) {
			CommandType firstType = entry.getValue().isEmpty() ? CommandType.MAVEN
				: entry.getValue().get(0).commandType;
			categories.add(new GoalCategory(entry.getKey(), firstType, entry.getValue()));
		}
		return categories;
	}
	
	// ── PSI / XML 工具 ──
	
	private static void collectPluginTags(XmlTag parent, List<XmlTag> result) {
		for (XmlTag child : parent.getSubTags()) {
			String n = child.getLocalName();
			if ("plugins".equals(n)) {
				for (XmlTag pt : child.getSubTags()) {
					if ("plugin".equals(pt.getLocalName())) {
						result.add(pt);
					}
				}
			} else if ("build".equals(n) || "pluginManagement".equals(n)
				|| "project".equals(n) || "profiles".equals(n) || "profile".equals(n)) {
				collectPluginTags(child, result);
			}
		}
	}
	
	private static List<String> collectPluginGoals(XmlTag pluginTag) {
		List<String> goals = new ArrayList<>();
		XmlTag et = CommandUtil.findChild(pluginTag, "executions");
		if (et != null) {
			for (XmlTag ext : et.getSubTags()) {
				if (!"execution".equals(ext.getLocalName())) {
					continue;
				}
				XmlTag gt = CommandUtil.findChild(ext, "goals");
				if (gt != null) {
					for (XmlTag g : gt.getSubTags()) {
						if ("goal".equals(g.getLocalName())) {
							String t = g.getValue().getTrimmedText();
							if (!t.isEmpty()) {
								goals.add(t);
							}
						}
					}
				}
			}
		}
		return goals;
	}
	
	
	@NotNull
	private static String deriveGoalPrefix(@NotNull String artifactId) {
		String p = artifactId;
		if (p.startsWith("maven-")) {
			p = p.substring("maven-".length());
		}
		if (p.endsWith("-maven-plugin")) {
			p = p.substring(0, p.length() - "-maven-plugin".length());
		} else if (p.endsWith("-plugin")) {
			p = p.substring(0, p.length() - "-plugin".length());
		}
		return p.isEmpty() ? artifactId : p;
	}
}
