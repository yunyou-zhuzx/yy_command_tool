package com.yunyoucloud.tool.service;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 持久化存储用户自定义命令与分组。
 */
@State(
	name = "CommandRunnerSettings",
	storages = {@Storage("command-runner.xml")}
)
@Service(Service.Level.PROJECT)
public final class CommandRunnerSettings implements PersistentStateComponent<CommandRunnerSettings.State> {
	
	public enum CommandType {
		MAVEN,
		GRADLE,
		POWERSHELL,
		SHELL,
		CMD,
		GENERIC;
		
		public String getDisplayName() {
			return switch (this) {
				case MAVEN -> "Maven";
				case GRADLE -> "Gradle";
				case POWERSHELL -> "PowerShell";
				case SHELL -> "Shell";
				case CMD -> "Cmd";
				case GENERIC -> "通用";
			};
		}
	}
	
	public static CommandRunnerSettings getInstance(@NotNull Project project) {
		return project.getService(CommandRunnerSettings.class);
	}
	
	public static class State {
		public List<CustomGoal> customGoals = new ArrayList<>();
		/**
		 * 用户自定义分组排序：分组名 → 排序权重（越小越靠前）
		 */
		public Map<String, Integer> groupOrder = new LinkedHashMap<>();
		/**
		 * 收藏到常用的自定义命令标识（格式: name|description|commandType|group）
		 */
		public Set<String> favoriteGoalKeys = new LinkedHashSet<>();
		/**
		 * 内置脚本排序：脚本名称列表（按显示顺序排列）
		 */
		public List<String> scriptOrder = new ArrayList<>();
		/**
		 * 脚本参数持久化：脚本名称 → (变量名 → 值)
		 */
		public Map<String, Map<String, String>> scriptParamValues = new LinkedHashMap<>();
	}
	
	public static class CustomGoal {
		public String name;
		public String description;
		public String defaultParams;
		public String commandType = CommandType.MAVEN.name();
		/**
		 * 自定义分组名，为空则按 commandType 自动分组
		 */
		public String group = "";
		
		@SuppressWarnings("unused")
		public CustomGoal() {
		}
		
		public CustomGoal(String name, String description, String defaultParams,
		                  CommandType commandType, String group) {
			this.name = name;
			this.description = description;
			this.defaultParams = defaultParams;
			this.commandType = commandType.name();
			this.group = group != null ? group : "";
		}
	}
	
	private State myState = new State();
	
	@Override
	public @Nullable State getState() {
		return myState;
	}
	
	@Override
	public void loadState(@NotNull State state) {
		this.myState = state;
		if (myState.customGoals == null) {
			myState.customGoals = new ArrayList<>();
		}
		if (myState.groupOrder == null) {
			myState.groupOrder = new LinkedHashMap<>();
		}
		if (myState.favoriteGoalKeys == null) {
			myState.favoriteGoalKeys = new LinkedHashSet<>();
		}
		if (myState.scriptOrder == null) {
			myState.scriptOrder = new ArrayList<>();
		}
		if (myState.scriptParamValues == null) {
			myState.scriptParamValues = new LinkedHashMap<>();
		}
	}
	
	public List<CustomGoal> getCustomGoals() {
		return myState.customGoals;
	}
	
	/**
	 * 返回用户自定义的分组排序（可编辑）
	 */
	public Map<String, Integer> getGroupOrder() {
		return myState.groupOrder;
	}
	
	/**
	 * 返回内置脚本排序（可编辑）
	 */
	public List<String> getScriptOrder() {
		return myState.scriptOrder;
	}

	/**
	 * 返回脚本参数持久化映射（脚本名称 → (变量名 → 值)）
	 */
	public Map<String, Map<String, String>> getScriptParamValues() {
		return myState.scriptParamValues;
	}

	/**
	 * 保存指定脚本的参数值
	 */
	public void saveScriptParamValues(String scriptName, Map<String, String> values) {
		myState.scriptParamValues.put(scriptName, new LinkedHashMap<>(values));
	}

	/**
	 * 获取指定脚本已保存的参数值，不存在时返回空 Map
	 */
	public Map<String, String> getScriptParamValues(String scriptName) {
		return myState.scriptParamValues.getOrDefault(scriptName, new LinkedHashMap<>());
	}
	
	public void addGoal(CustomGoal goal) {
		myState.customGoals.add(goal);
	}
	
	public void removeGoal(CustomGoal goal) {
		myState.customGoals.remove(goal);
	}
	
	public void updateGoal(int index, CustomGoal goal) {
		if (index >= 0 && index < myState.customGoals.size()) {
			myState.customGoals.set(index, goal);
		}
	}
	
	/**
	 * 对一批命令统一修改分组名（重命名分组时使用）
	 */
	public void renameGroup(String oldName, String newName) {
		for (CustomGoal cg : myState.customGoals) {
			if (oldName.equals(cg.group)) {
				cg.group = newName;
			}
		}
		Integer order = myState.groupOrder.remove(oldName);
		if (order != null) {
			myState.groupOrder.put(newName, order);
		}
		// 更新收藏 key 中的分组名
		Set<String> updated = new LinkedHashSet<>();
		for (String key : myState.favoriteGoalKeys) {
			String[] parts = key.split("\\|", 4);
			if (parts.length == 4 && oldName.equals(parts[3])) {
				updated.add(parts[0] + "|" + parts[1] + "|" + parts[2] + "|" + newName);
			} else {
				updated.add(key);
			}
		}
		myState.favoriteGoalKeys = updated;
	}
	
	// ── 收藏管理 ──
	
	@NotNull
	private static String makeKey(@NotNull CustomGoal cg) {
		return cg.name + "|" + (cg.description != null ? cg.description : "")
			+ "|" + cg.commandType + "|" + (cg.group != null ? cg.group : "");
	}
	
	public boolean isFavorite(@NotNull CustomGoal cg) {
		return myState.favoriteGoalKeys.contains(makeKey(cg));
	}
	
	public void addFavorite(@NotNull CustomGoal cg) {
		myState.favoriteGoalKeys.add(makeKey(cg));
	}
	
	public void removeFavorite(@NotNull CustomGoal cg) {
		myState.favoriteGoalKeys.remove(makeKey(cg));
	}
	
	/**
	 * 清理失效的收藏（自定义命令已删除时调用）
	 */
	public void cleanupFavorites() {
		Set<String> valid = new LinkedHashSet<>();
		for (CustomGoal cg : myState.customGoals) {
			valid.add(makeKey(cg));
		}
		myState.favoriteGoalKeys.retainAll(valid);
	}
}
