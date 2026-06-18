package com.yunyoucloud.tool.service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 内置 PowerShell 脚本注册表。
 * 每个脚本定义可提取的参数，支持从项目信息自动填充默认值。
 */
public final class BuiltInScriptRegistry {
	
	public static final class ScriptParam {
		/**
		 * PowerShell 变量名（不含 $ 前缀），如 "ip"
		 */
		public final String varName;
		/**
		 * 参数标签，显示在对话框中
		 */
		public final String label;
		/**
		 * 脚本中的默认值（含引号，如 {@code "192.168.10.61"}）
		 */
		public final String defaultValue;
		/**
		 * 项目信息映射键，用于自动填充默认值。
		 * 可选值：artifactId, moduleNames, basePath, null（不自动填充）
		 */
		@Nullable
		public final String projectInfoKey;
		/**
		 * 是否为必填参数（值为空时阻止执行）
		 */
		public final boolean required;
		/**
		 * 是否为高级参数（在折叠的"高级"区域中显示）
		 */
		public final boolean advanced;
		
		/**
		 * 选项列表
		 */
		public final String options;

		public ScriptParam(@NotNull String varName, @NotNull String label,
		                   @Nullable String defaultValue, @Nullable String projectInfoKey) {
			this(varName, label, defaultValue, projectInfoKey, false, false, null);
		}

		public ScriptParam(@NotNull String varName, @NotNull String label,
		                   @Nullable String defaultValue, @Nullable String projectInfoKey,
		                   boolean required) {
			this(varName, label, defaultValue, projectInfoKey, required, false, null);
		}
		
		public ScriptParam(@NotNull String varName, @NotNull String label,
		                   @Nullable String defaultValue, @Nullable String projectInfoKey,
		                   boolean required, boolean advanced, @Nullable String options) {
			this.varName = varName;
			this.label = label;
			this.defaultValue = defaultValue;
			this.projectInfoKey = projectInfoKey;
			this.required = required;
			this.advanced = advanced;
			this.options = options;
		}
	}
	
	public static final class ScriptDef {
		/**
		 * 显示名称
		 */
		public final String name;
		/**
		 * 描述文本
		 */
		public final String description;
		/**
		 * 资源路径，如 "/script/deploy.ps1"
		 */
		public final String resourcePath;
		/**
		 * 可提取的参数列表（按脚本中出现的顺序）
		 */
		public final List<ScriptParam> params;
		
		public ScriptDef(@NotNull String name, @NotNull String description,
		                 @NotNull String resourcePath, @NotNull List<ScriptParam> params) {
			this.name = name;
			this.description = description;
			this.resourcePath = resourcePath;
			this.params = params;
		}
		
		public String getScriptName() {
			final int i = resourcePath.lastIndexOf("/");
			return i >=0 ? resourcePath.substring(i + 1) : "";
		}
		
		/**
		 * 从 classpath 资源加载脚本原始内容
		 */
		@NotNull
		public String loadScriptContent() {
			try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
				if (is == null) {
					return "# Error: script resource not found: " + resourcePath;
				}
				try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(is, StandardCharsets.UTF_8))) {
					return reader.lines().collect(Collectors.joining("\n"));
				}
			} catch (IOException e) {
				return "# Error loading script: " + e.getMessage();
			}
		}
		
		/**
		 * 根据用户参数值构建注入后的完整脚本内容。
		 * 替换脚本中硬编码的变量赋值行，同时将 param() 声明转换为内联赋值。
		 */
		@NotNull
		public String buildInjectedScript(@NotNull Map<String, String> paramValues) {
			String content = loadScriptContent();
			
			for (ScriptParam p : params) {
				String value = paramValues.get(p.varName);
				if (value == null || value.isEmpty()) {
					continue;
				}
				
				if ("true".equals(value) || "false".equals(value)) {
					value = "true".equals(value) ? "1" : "0";
				} else if (value.contains(",")) {
					value = Arrays.stream(value.split(","))
						.map(String::trim)
						.map(item -> "\"" + item + "\"")
						.collect(Collectors.joining(","));
				}
				else {
					value = "\"" + value + "\"";
				}
				
				// 替换内联赋值: $varName = oldValue
				content = content.replaceAll(
					"(?m)^\\$" + Pattern.quote(p.varName) + "\\s*=\\s*.+",
					Matcher.quoteReplacement(
						"$" + p.varName + " = " + value));
			}
			return content;
		}
		
		/**
		 * 获取带默认值的初始参数值映射
		 */
		@NotNull
		public Map<String, String> getDefaultParamValues() {
			Map<String, String> values = new LinkedHashMap<>();
			for (ScriptParam p : params) {
				values.put(p.varName, p.defaultValue != null ? p.defaultValue : "");
			}
			return values;
		}
	}
	
	// ── 内置脚本定义 ──
	public static final ScriptDef DELETE_GEN_FILE = new ScriptDef(
		"删除生成文件 (deleteGenFile.ps1)",
		"清理指定模块的 gen 代码目录",
		"/script/deleteGenFile.ps1",
		List.of(
			new ScriptParam("RepositoryRoot", "仓库根目录", ".\\yy-service", null, true),
			new ScriptParam("PROJECT", "项目标识", "", "artifactId", true),
			new ScriptParam("MODULES", "模块列表", "bm", null, true)
		)
	);
	
	public static final ScriptDef UPDATE_GEN_FILE = new ScriptDef(
		"更新生成文件 (updateGenFile.ps1)",
		"从远程脚本更新生成的代码文件",
		"/script/updateGenFile.ps1",
		List.of(
			new ScriptParam("project_name", "项目名称", "-", "artifactId", true),
			new ScriptParam("type", "类型 (server/ui)", "server", "options", true, false,"server,ui"),
			new ScriptParam("version", "生成器版本", "2", null, true),
			
			new ScriptParam("pluginScriptDir", "项目根目录", "", "basePath", true, true, null),
			new ScriptParam("no_update", "跳过更新schema", "false", "options", false, true, "false,true"),
			new ScriptParam("download_way", "下载方式(down|scp)", "scp", "options", false, true, "scp,down"),
			new ScriptParam("gen_way", "生成方式(schema|bbx)", "schema","options", false, true, "schema,bbx")
		)
	);
	
	public static final ScriptDef DEPLOY = new ScriptDef(
		"部署 (deploy.ps1)",
		"部署项目到远程服务器",
		"/script/deploy.ps1",
		List.of(
			new ScriptParam("projectName", "项目名称", "-", "artifactId", true),
			new ScriptParam("type", "类型 (server/ui)", "server", "options", true, false,"server,ui"),
			new ScriptParam("service", "服务名称", "-", "moduleNames", true),
			new ScriptParam("ip", "服务器 IP", "", null, true),
			
			new ScriptParam("pluginScriptDir", "项目根目录", "", "basePath", true, true, null),
			new ScriptParam("user", "服务器登陆用户", "root", null, false, true, ""),
			new ScriptParam("isPackage", "是否编译打包", "false", "options", false, true, "false,true"),
			new ScriptParam("needReload", "是否重启服务(只对server生效)", "true", "options", false, true, "true,false"),
			new ScriptParam("deployWay", "部署方式(jar|docker)", "docker", "options", false, true, "docker,jar")
		)
	);
	
	/**
	 * 所有内置脚本
	 */
	public static final List<ScriptDef> ALL_SCRIPTS = List.of(DELETE_GEN_FILE, UPDATE_GEN_FILE, DEPLOY);
	
	private BuiltInScriptRegistry() { /* 工具类 */ }
	
	/**
	 * 根据项目信息填充参数默认值。
	 *
	 * @param def         脚本定义
	 * @param projectInfo 项目信息映射（artifactId, moduleNames, basePath 等）
	 * @return 填充后的参数值（varName → value）
	 */
	@NotNull
	public static Map<String, String> fillDefaults(@NotNull ScriptDef def,
	                                               @NotNull Map<String, String> projectInfo) {
		Map<String, String> values = def.getDefaultParamValues();
		for (ScriptParam p : def.params) {
			if (p.projectInfoKey != null && projectInfo.containsKey(p.projectInfoKey)) {
				String infoValue = projectInfo.get(p.projectInfoKey);
				if (Objects.equals(p.projectInfoKey, "artifactId")) {
					infoValue = infoValue.replace("-server", "");
				}
				System.out.println(" projectInfoKey:  " + p.projectInfoKey + ", infoValue: " + infoValue);
				if (infoValue != null && !infoValue.isEmpty()) {
					String dv = p.defaultValue != null ? p.defaultValue : "";
					if (dv.startsWith("\"") && dv.endsWith("\"")) {
						// 双引号字符串 → 保持双引号格式
						values.put(p.varName, "\"" + infoValue + "\"");
					} else if (dv.startsWith("'") && dv.endsWith("'")) {
						// 单引号字符串 → 保持单引号格式
						values.put(p.varName, "'" + infoValue + "'");
					} else if (dv.startsWith("@(") && dv.endsWith(")")) {
						// 数组类型 → 将逗号分隔的值转为 PowerShell 数组
						String[] parts = infoValue.split(",");
						StringBuilder arr = new StringBuilder("@(");
						for (int i = 0; i < parts.length; i++) {
							if (i > 0) {
								arr.append(", ");
							}
							arr.append("'").append(parts[i].trim()).append("'");
						}
						arr.append(")");
						values.put(p.varName, arr.toString());
					} else {
						// 数值或其他类型 → 直接使用
						values.put(p.varName, infoValue);
					}
				}
			}
		}
		return values;
	}
}
