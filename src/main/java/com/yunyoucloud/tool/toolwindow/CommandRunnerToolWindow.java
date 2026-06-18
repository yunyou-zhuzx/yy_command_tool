package com.yunyoucloud.tool.toolwindow;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.icons.AllIcons;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.treeStructure.Tree;
import com.yunyoucloud.tool.service.BuiltInScriptRegistry;
import com.yunyoucloud.tool.service.CommandGoalService;
import com.yunyoucloud.tool.service.CommandRunnerSettings;
import com.yunyoucloud.tool.service.CommandRunnerSettings.CommandType;
import com.yunyoucloud.tool.util.CommandUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public class CommandRunnerToolWindow {
	
	public static class GoalNode {
		
		public final String name;
		public final String description;
		public final CommandType commandType;
		public final CommandGoalService.GoalSource source;
		@Nullable
		public final String defaultParams;
		@Nullable
		public final String group;
		/**
		 * 内置脚本定义（source == SCRIPT 时可能非 null）
		 */
		@Nullable
		public final BuiltInScriptRegistry.ScriptDef builtInScript;
		/**
		 * 可选显示名，为 null 时使用 name 作为显示文本
		 */
		@Nullable
		public final String displayName;
		
		public GoalNode(String name, String description, CommandType commandType,
		                CommandGoalService.GoalSource source) {
			this(name, description, commandType, source, null, null);
		}
		
		public GoalNode(String name, String description, CommandType commandType,
		                CommandGoalService.GoalSource source, @Nullable String defaultParams) {
			this(name, description, commandType, source, defaultParams, null);
		}
		
		public GoalNode(String name, String description, CommandType commandType,
		                CommandGoalService.GoalSource source, @Nullable String defaultParams,
		                @Nullable String group) {
			this(name, description, commandType, source, defaultParams, group, null);
		}
		
		public GoalNode(String name, String description, CommandType commandType,
		                CommandGoalService.GoalSource source, @Nullable String defaultParams,
		                @Nullable String group, @Nullable BuiltInScriptRegistry.ScriptDef builtInScript) {
			this(name, description, commandType, source, defaultParams, group, builtInScript, null);
		}
		
		public GoalNode(String name, String description, CommandType commandType,
		                CommandGoalService.GoalSource source, @Nullable String defaultParams,
		                @Nullable String group, @Nullable BuiltInScriptRegistry.ScriptDef builtInScript,
		                @Nullable String displayName) {
			this.name = name;
			this.description = description;
			this.commandType = commandType;
			this.source = source;
			this.defaultParams = defaultParams;
			this.group = group;
			this.builtInScript = builtInScript;
			this.displayName = displayName;
		}
		
		@Override
		public String toString() {
			return displayName != null ? displayName : name;
		}
	}
	
	// ──────────────────── Factory ────────────────────
	
	public static class Factory implements ToolWindowFactory {
		
		// 保存 tree 引用以便 move 操作后重建
		private JTree currentTree;
		private Project currentProject;
		private JProgressBar progressBar;
		private JButton btnStop;
		private final List<OSProcessHandler> runningHandlers = new CopyOnWriteArrayList<>();
		
		@Override
		public boolean shouldBeAvailable(@NotNull Project project) {
			return true;
		}
		
		@Override
		public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
			currentProject = project;
			runningHandlers.clear();
			JPanel mainPanel = new JPanel(new BorderLayout());
			
			// === 工具栏 ===
			JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
			toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0,
				UIManager.getColor("Separator.foreground")));
			
			JButton btnRun = createToolbarButton("执行", AllIcons.Actions.Execute, "执行选中的命令");
			btnStop = createToolbarButton("终止", AllIcons.Actions.Suspend, "终止正在运行的命令");
			btnStop.setEnabled(false);
			
			JTextField paramField = new JTextField(12);
			paramField.setToolTipText("额外参数");
			
			progressBar = new JProgressBar();
			progressBar.setIndeterminate(true);
			progressBar.setVisible(false);
			progressBar.setPreferredSize(new Dimension(60, 20));
			
			JButton btnAdd = createToolbarButton("", AllIcons.General.Add, "添加自定义命令");
			JButton btnRefresh = createToolbarButton("", AllIcons.Actions.Refresh, "刷新");
			JButton btnSyncConfig = createToolbarButton("同步配置", AllIcons.General.Gear,"将模块打包目标同步为 Maven Run Configuration");
			
			toolbar.add(btnRun);
			toolbar.add(btnStop);
			toolbar.add(paramField);
			toolbar.add(progressBar);
			toolbar.add(Box.createHorizontalStrut(8));
			toolbar.add(btnAdd);
			toolbar.add(btnRefresh);
			toolbar.add(btnSyncConfig);
			
			// === 树 ===
			Tree tree = new Tree();
			currentTree = tree;
			tree.setRootVisible(false);
			tree.setShowsRootHandles(true);
			
			tree.setCellRenderer(new DefaultTreeCellRenderer() {
				@Override
				public Component getTreeCellRendererComponent(JTree tree, Object value,
				                                              boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
					super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
					// 强制从树取背景色，适配 islands Dark 等所有主题
					if (!sel) {
						setBackground(tree.getBackground());
						setBackgroundNonSelectionColor(tree.getBackground());
					}
					if (value instanceof DefaultMutableTreeNode node) {
						Object obj = node.getUserObject();
						if (obj instanceof GoalNode goal) {
							setText(goal.displayName != null ? goal.displayName : goal.name);
							setToolTipText(goal.description);
							setIcon(getIcon(goal));
						} else if (obj instanceof String s) {
							setIcon(AllIcons.Nodes.Folder);
							setText(s);
						}
					}
					return this;
				}
				
				private Icon getIcon(GoalNode g) {
					if (g.source == CommandGoalService.GoalSource.CUSTOM) {
						return switch (g.commandType) {
							case MAVEN -> AllIcons.Actions.Execute;
							case GRADLE -> AllIcons.Nodes.Plugin;
							case POWERSHELL -> AllIcons.Actions.Execute;
							case SHELL -> AllIcons.General.ExternalTools;
							case CMD -> AllIcons.Actions.MenuOpen;
							case GENERIC -> AllIcons.General.User;
						};
					}
					if (g.source == CommandGoalService.GoalSource.MODULE_PACKAGE) {
						return AllIcons.Nodes.Module;
					}
					if (g.source == CommandGoalService.GoalSource.PLUGIN) {
						return AllIcons.Nodes.Plugin;
					}
					return switch (g.commandType) {
						case MAVEN -> AllIcons.Actions.Execute;
						case GRADLE -> AllIcons.Nodes.Plugin;
						case POWERSHELL -> AllIcons.Actions.Execute;
						case SHELL -> AllIcons.General.ExternalTools;
						case CMD -> AllIcons.Actions.MenuOpen;
						case GENERIC -> AllIcons.Actions.Execute;
					};
				}
			});
			
			// === 执行 ===
			Runnable executeSelected = () -> {
				TreePath path = tree.getSelectionPath();
				if (path == null) {
					return;
				}
				Object node = path.getLastPathComponent();
				if (!(node instanceof DefaultMutableTreeNode tn)) {
					return;
				}
				if (!(tn.getUserObject() instanceof GoalNode goal)) {
					return;
				}
				
				// 内置脚本：使用默认参数值执行（双击行为）
				if (goal.builtInScript != null) {
					CommandGoalService svc = CommandGoalService.getInstance(currentProject);
					Map<String, String> projectInfo = svc.getProjectInfo(currentProject);
					Map<String, String> defaultValues = BuiltInScriptRegistry.fillDefaults(goal.builtInScript, projectInfo);
					
					Map<String, String> saved = CommandRunnerSettings.getInstance(currentProject)
						.getScriptParamValues(goal.builtInScript.name);
					for (Map.Entry<String, String> entry : saved.entrySet()) {
						if (entry.getValue() != null && !entry.getValue().isEmpty()) {
							defaultValues.put(entry.getKey(), entry.getValue());
						}
					}
					
					// 检查必填参数是否缺失 → 缺失时弹出配置对话框
					List<String> missing = getMissingRequiredParams(goal.builtInScript, defaultValues);
					if (!missing.isEmpty()) {
						ScriptParamDialog dlg = new ScriptParamDialog(
							currentProject, goal.builtInScript, defaultValues);
						if (dlg.showAndGet()) {
							Map<String, String> paramValues = dlg.getParamValues();
							// 持久化保存本次输入的参数值
							CommandRunnerSettings.getInstance(currentProject)
								.saveScriptParamValues(goal.builtInScript.name, paramValues);
							executeBuiltInScript(goal, paramValues);
						}
						return;
					}
					
					executeBuiltInScript(goal, defaultValues);
					return;
				}
				
				progressBar.setVisible(true);
				btnStop.setEnabled(true);
				
				String extra = paramField.getText().trim();
				if (extra.isEmpty() && goal.defaultParams != null && !goal.defaultParams.isEmpty()) {
					extra = goal.defaultParams;
				}
				
				OSProcessHandler handler = CommandUtil.executeCommand(
					currentProject, goal.name, goal.commandType, extra, () -> {
						runningHandlers.removeIf(h -> h.isProcessTerminated());
						ApplicationManager.getApplication().invokeLater(() -> {
							if (runningHandlers.isEmpty()) {
								progressBar.setVisible(false);
								btnStop.setEnabled(false);
							}
						});
					});
				
				if (handler != null) {
					runningHandlers.add(handler);
				} else if (runningHandlers.isEmpty()) {
					progressBar.setVisible(false);
					btnStop.setEnabled(false);
				}
			};
			
			btnStop.addActionListener(e -> {
				for (OSProcessHandler h : runningHandlers) {
					if (!h.isProcessTerminated()) {
						h.destroyProcess();
					}
				}
				runningHandlers.clear();
				progressBar.setVisible(false);
				btnStop.setEnabled(false);
			});
			
			tree.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent e) {
					if (e.getClickCount() == 2) {
						int row = tree.getRowForLocation(e.getX(), e.getY());
						if (row >= 0) {
							TreePath path = tree.getPathForRow(row);
							if (path != null && path.getLastPathComponent()
								instanceof DefaultMutableTreeNode tn
								&& tn.getUserObject() instanceof GoalNode) {
								tree.setSelectionPath(path);
								executeSelected.run();
							}
						}
					}
				}
			});
			
			btnRun.addActionListener(e -> executeSelected.run());
			
			// === 右键菜单 ===
			tree.addMouseListener(new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					if (e.isPopupTrigger()) {
						showPopup(e);
					}
				}
				
				@Override
				public void mouseReleased(MouseEvent e) {
					if (e.isPopupTrigger()) {
						showPopup(e);
					}
				}
				
				private void showPopup(MouseEvent e) {
					int x = e.getX();
					int y = e.getY();
					int row = tree.getClosestRowForLocation(x, y);
					TreePath path = (row >= 0) ? tree.getPathForRow(row) : tree.getPathForLocation(x, y);
					if (path == null) {
						return;
					}
					tree.setSelectionPath(path);
					
					Object node = path.getLastPathComponent();
					if (!(node instanceof DefaultMutableTreeNode tn)) {
						return;
					}
					Object obj = tn.getUserObject();
					
					JPopupMenu popup = new JPopupMenu();
					CommandRunnerSettings settings = CommandRunnerSettings.getInstance(project);
					
					System.out.println(obj + ":" + obj.getClass());
					
					// ── 自定义命令节点 ──
					if (obj instanceof GoalNode goal && goal.source == CommandGoalService.GoalSource.CUSTOM) {
						
						CommandRunnerSettings.CustomGoal cg = findCustomGoal(settings, goal);
						if (cg == null) {
							return;
						}
						
						JMenuItem editItem = new JMenuItem("编辑", AllIcons.Actions.Edit);
						editItem.addActionListener(ev -> {
							AddGoalDialog d = new AddGoalDialog(project, cg.name, cg.description,
								cg.defaultParams, parseType(cg.commandType), cg.group);
							if (d.showAndGet()) {
								int idx = settings.getCustomGoals().indexOf(cg);
								settings.updateGoal(idx, d.getResultGoal());
								rebuildTree();
							}
						});
						popup.add(editItem);
						
						// 上移
						JMenuItem upItem = new JMenuItem("上移", AllIcons.Actions.MoveUp);
						upItem.addActionListener(ev -> {
							moveCommand(settings, goal, -1);
							moveTreeNode(tn, -1);
						});
						popup.add(upItem);
						
						// 下移
						JMenuItem downItem = new JMenuItem("下移", AllIcons.Actions.MoveDown);
						downItem.addActionListener(ev -> {
							moveCommand(settings, goal, +1);
							moveTreeNode(tn, +1);
						});
						popup.add(downItem);
						
						// 收藏/取消收藏
						if (settings.isFavorite(cg)) {
							JMenuItem unfavItem = new JMenuItem("从常用命令移除", AllIcons.Actions.ToggleSoftWrap);
							unfavItem.addActionListener(ev -> {
								settings.removeFavorite(cg);
								rebuildTree();
							});
							popup.add(unfavItem);
						} else {
							JMenuItem favItem = new JMenuItem("添加到常用命令", AllIcons.General.Add);
							favItem.addActionListener(ev -> {
								settings.addFavorite(cg);
								rebuildTree();
							});
							popup.add(favItem);
						}
						
						popup.addSeparator();
						
						JMenuItem delItem = new JMenuItem("删除", AllIcons.General.Remove);
						delItem.addActionListener(ev -> {
							if (Messages.YES == Messages.showYesNoDialog(project,
								"确定要删除 \"" + goal.name + "\" 吗？", "确认删除",
								Messages.getQuestionIcon())) {
								settings.removeGoal(findCustomGoal(settings, goal));
								settings.cleanupFavorites();
								cleanupGroupOrder(settings);
								rebuildTree();
							}
						});
						popup.add(delItem);
					}
					
					// ── 常用命令中的收藏项 ──
					if (obj instanceof GoalNode goal
						&& goal.source == CommandGoalService.GoalSource.COMMON) {
						CommandRunnerSettings.CustomGoal matchedCg = findCustomGoal(settings, goal);
						if (matchedCg != null && settings.isFavorite(matchedCg)) {
							JMenuItem unfavItem = new JMenuItem("从常用命令移除", AllIcons.Actions.ToggleSoftWrap);
							unfavItem.addActionListener(ev -> {
								settings.removeFavorite(matchedCg);
								rebuildTree();
							});
							popup.add(unfavItem);
						}
					}
					
					// ── 自定义分组下的子分组节点 ──
					// 判断：节点是 String 类型，且其父节点是 "自定义" 节点或父节点的父节点是 "自定义"
					if (obj instanceof String groupName
						&& tn.getParent() instanceof DefaultMutableTreeNode pn) {
						boolean isCustomSubGroup = false;
						
						// 父节点就是 "自定义"
						if (pn.getUserObject() instanceof String ps && "自定义".equals(ps)) {
							isCustomSubGroup = true;
						}
						// 父节点在"自定义"下面且父节点也是分组节点
						if (pn.getParent() instanceof DefaultMutableTreeNode gpn
							&& gpn.getUserObject() instanceof String gps
							&& "自定义".equals(gps)) {
							isCustomSubGroup = true;
						}
						
						if (isCustomSubGroup && isCustomGroup(settings, groupName)) {
							JMenuItem renameItem = new JMenuItem("重命名分组", AllIcons.Actions.Edit);
							renameItem.addActionListener(ev -> {
								String newName = Messages.showInputDialog(project,
									"输入新分组名:", "重命名分组 - " + groupName,
									Messages.getQuestionIcon(), groupName, null);
								if (newName != null && !newName.isBlank()
									&& !newName.equals(groupName)) {
									settings.renameGroup(groupName, newName.trim());
									rebuildTree();
								}
							});
							popup.add(renameItem);
							
							JMenuItem gui = new JMenuItem("分组上移", AllIcons.Actions.MoveUp);
							gui.addActionListener(ev -> {
								moveGroupOrder(settings, groupName, -1);
								rebuildTree();
							});
							popup.add(gui);
							
							JMenuItem gdi = new JMenuItem("分组下移", AllIcons.Actions.MoveDown);
							gdi.addActionListener(ev -> {
								moveGroupOrder(settings, groupName, +1);
								rebuildTree();
							});
							popup.add(gdi);
						}
					}
					
					// ── 内置脚本节点 ──
					if (obj instanceof GoalNode goal
						&& goal.source == CommandGoalService.GoalSource.SCRIPT
						&& goal.builtInScript != null) {
						
						JMenuItem configItem = new JMenuItem("配置", AllIcons.Actions.Edit);
						configItem.addActionListener(ev -> {
							CommandGoalService svc = CommandGoalService.getInstance(currentProject);
							Map<String, String> projectInfo = svc.getProjectInfo(currentProject);
							Map<String, String> defaultValues = BuiltInScriptRegistry.fillDefaults(
								goal.builtInScript, projectInfo);
							ScriptParamDialog dlg = new ScriptParamDialog(
								currentProject, goal.builtInScript, defaultValues);
							if (dlg.showAndGet()) {
								Map<String, String> paramValues = dlg.getParamValues();
								// 持久化保存本次输入的参数值
								CommandRunnerSettings.getInstance(currentProject)
									.saveScriptParamValues(goal.builtInScript.name, paramValues);
//								executeBuiltInScript(goal, paramValues);
							}
						});
						popup.add(configItem);
						
						// 上移
						JMenuItem upItem = new JMenuItem("上移", AllIcons.Actions.MoveUp);
						upItem.addActionListener(ev -> {
							moveScriptInSettings(goal.name, -1);
							moveTreeNode(tn, -1);
						});
						popup.add(upItem);
						
						// 下移
						JMenuItem downItem = new JMenuItem("下移", AllIcons.Actions.MoveDown);
						downItem.addActionListener(ev -> {
							moveScriptInSettings(goal.name, +1);
							moveTreeNode(tn, +1);
						});
						popup.add(downItem);
						
						// 保存到项目
						JMenuItem saveItem = new JMenuItem("保存到项目", AllIcons.Actions.Download);
						saveItem.addActionListener(ev -> {
							saveScriptToProject(goal);
						});
						popup.add(saveItem);
					}
					
					if (obj instanceof GoalNode goal && goal.source == CommandGoalService.GoalSource.MODULE_PACKAGE) {
						// 同步到RunConfig
						JMenuItem syncToRunItem = new JMenuItem("同步到RunConfig", AllIcons.Actions.SynchronizeScrolling);
						syncToRunItem.addActionListener(ev -> {
							syncSingleMavenRunConfigurations(goal);
						});
						popup.add(syncToRunItem);
					}
					
					if (obj instanceof String && Objects.equals(obj, "脚本执行")) {
						// 保存到项目
						JMenuItem saveItem = new JMenuItem("保存到项目", AllIcons.Actions.Download);
						saveItem.addActionListener(ev -> {
							saveScriptToProject();
						});
						popup.add(saveItem);
					}
					
					if (obj instanceof String && Objects.equals(obj, "模块打包")) {
						// 同步到RunConfig
						JMenuItem syncToRunItem = new JMenuItem("同步到RunConfig", AllIcons.Actions.SynchronizeScrolling);
						syncToRunItem.addActionListener(ev -> {
							syncMavenRunConfigurations();
						});
						popup.add(syncToRunItem);
					}
					
					if (popup.getComponentCount() > 0) {
						popup.show(e.getComponent(), x, y);
					}
				}
			});
			
			btnAdd.addActionListener(e -> {
				AddGoalDialog d = new AddGoalDialog(project, "", "", "", CommandType.MAVEN, "");
				if (d.showAndGet()) {
					CommandRunnerSettings.getInstance(project).addGoal(d.getResultGoal());
					rebuildTree();
				}
			});
			
			btnRefresh.addActionListener(e -> rebuildTree());
			btnSyncConfig.addActionListener(e -> syncMavenRunConfigurations());
			
			mainPanel.add(toolbar, BorderLayout.NORTH);
			mainPanel.add(new JScrollPane(tree), BorderLayout.CENTER);
			toolWindow.getComponent().add(mainPanel);
			
			rebuildTree();
		}
		
		// ──────────────────── 树构建 ────────────────────
		
		private void rebuildTree() {
			if (currentTree == null || currentProject == null) {
				return;
			}
			CommandGoalService service = CommandGoalService.getInstance(currentProject);
			List<CommandGoalService.GoalCategory> categories = service.getCategorizedGoals(currentProject);
			
			DefaultMutableTreeNode root = new DefaultMutableTreeNode("Commands");
			
			// 分离内置分组和自定义分组
			List<CommandGoalService.GoalCategory> builtIn = new ArrayList<>();
			List<CommandGoalService.GoalCategory> custom = new ArrayList<>();
			
			for (CommandGoalService.GoalCategory cat : categories) {
				if (cat.goals.isEmpty()) {
					continue;
				}
				// 判断：所有 goal 的 source 都是 CUSTOM
				boolean allCustom = cat.goals.stream()
					.allMatch(g -> g.source == CommandGoalService.GoalSource.CUSTOM);
				if (allCustom) {
					custom.add(cat);
				} else {
					builtIn.add(cat);
				}
			}
			
			// 内置分组直接挂根下
			for (CommandGoalService.GoalCategory cat : builtIn) {
				DefaultMutableTreeNode group = new DefaultMutableTreeNode(cat.name);
				for (GoalNode goal : cat.goals) {
					group.add(new DefaultMutableTreeNode(goal));
				}
				root.add(group);
			}
			
			// 自定义分组统一挂在 "自定义" 下
			if (!custom.isEmpty()) {
				DefaultMutableTreeNode customRoot = new DefaultMutableTreeNode("自定义");
				for (CommandGoalService.GoalCategory cat : custom) {
					DefaultMutableTreeNode group = new DefaultMutableTreeNode(cat.name);
					for (GoalNode goal : cat.goals) {
						group.add(new DefaultMutableTreeNode(goal));
					}
					customRoot.add(group);
				}
				root.add(customRoot);
			}
			
			currentTree.setModel(new DefaultTreeModel(root));
			for (int i = 0; i < currentTree.getRowCount(); i++) {
				currentTree.expandRow(i);
			}
		}
		
		// ──────────────────── 分组操作 ────────────────────
		
		/**
		 * 判断分组名是否属于自定义分组
		 */
		private boolean isCustomGroup(CommandRunnerSettings s, String groupName) {
			List<CommandRunnerSettings.CustomGoal> goals = s.getCustomGoals();
			if (goals == null) {
				return false;
			}
			for (CommandRunnerSettings.CustomGoal cg : goals) {
				String g = (cg.group != null && !cg.group.isEmpty()) ? cg.group
					: parseType(cg.commandType).getDisplayName();
				if (groupName.equals(g)) {
					return true;
				}
			}
			return false;
		}
		
		private void moveGroupOrder(CommandRunnerSettings s, String name, int delta) {
			List<CommandRunnerSettings.CustomGoal> goals = s.getCustomGoals();
			if (goals == null) {
				return;
			}
			Map<String, Integer> order = s.getGroupOrder();
			// 构建完整分组列表（包含所有实际使用的分组）
			List<String> groups = new ArrayList<>();
			java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
			for (CommandRunnerSettings.CustomGoal cg : goals) {
				String g = (cg.group != null && !cg.group.isEmpty()) ? cg.group
					: parseType(cg.commandType).getDisplayName();
				all.add(g);
			}
			groups.addAll(all);
			
			// 按 order 排序
			groups.sort((a, b) -> Integer.compare(
				order.getOrDefault(a, Integer.MAX_VALUE),
				order.getOrDefault(b, Integer.MAX_VALUE)));
			
			int idx = groups.indexOf(name);
			if (idx < 0) {
				return;
			}
			int newIdx = idx + delta;
			if (newIdx < 0 || newIdx >= groups.size()) {
				return;
			}
			
			// 交换位置
			groups.remove(idx);
			groups.add(newIdx, name);
			
			// 重建 order
			for (int i = 0; i < groups.size(); i++) {
				order.put(groups.get(i), i);
			}
		}
		
		private void cleanupGroupOrder(CommandRunnerSettings s) {
			List<CommandRunnerSettings.CustomGoal> goals = s.getCustomGoals();
			if (goals == null) {
				return;
			}
			Map<String, Integer> order = s.getGroupOrder();
			Set<String> used = new java.util.HashSet<>();
			for (CommandRunnerSettings.CustomGoal cg : goals) {
				String g = (cg.group != null && !cg.group.isEmpty()) ? cg.group
					: parseType(cg.commandType).getDisplayName();
				used.add(g);
			}
			order.keySet().removeIf(k -> !used.contains(k));
		}
		
		// ──────────────────── 命令排序 ────────────────────
		
		/**
		 * 在同一分组内上下移动自定义命令。
		 * 移动 = 在与 goal 同分组的命令列表中交换相邻位置。
		 */
		private void moveCommand(CommandRunnerSettings s, GoalNode goal, int delta) {
			List<CommandRunnerSettings.CustomGoal> list = s.getCustomGoals();
			if (list == null) {
				return;
			}
			String targetGroup = (goal.group != null && !goal.group.isEmpty()) ? goal.group
				: parseType(goal.commandType.name()).getDisplayName();
			
			// 找到同分组的所有命令在 list 中的索引
			List<Integer> indices = new ArrayList<>();
			for (int i = 0; i < list.size(); i++) {
				CommandRunnerSettings.CustomGoal cg = list.get(i);
				String cgGroup = (cg.group != null && !cg.group.isEmpty()) ? cg.group
					: parseType(cg.commandType).getDisplayName();
				if (targetGroup.equals(cgGroup)) {
					indices.add(i);
				}
			}
			
			// 找到当前命令的索引（匹配 name + description + group）
			int curIdx = -1;
			for (int i = 0; i < list.size(); i++) {
				CommandRunnerSettings.CustomGoal cg = list.get(i);
				String cgGroup = (cg.group != null && !cg.group.isEmpty()) ? cg.group
					: parseType(cg.commandType).getDisplayName();
				if (cg.name.equals(goal.name)
					&& java.util.Objects.equals(cg.description, goal.description)
					&& targetGroup.equals(cgGroup)) {
					curIdx = i;
					break;
				}
			}
			if (curIdx < 0) {
				return;
			}
			
			// 在同分组内的位置
			int posInGroup = indices.indexOf(curIdx);
			if (posInGroup < 0) {
				return;
			}
			int newPos = posInGroup + delta;
			if (newPos < 0 || newPos >= indices.size()) {
				return;
			}
			
			// 交换
			int targetIdx = indices.get(newPos);
			CommandRunnerSettings.CustomGoal tmp = list.get(curIdx);
			list.set(curIdx, list.get(targetIdx));
			list.set(targetIdx, tmp);
		}
		
		// ──────────────────── 辅助 ────────────────────
		
		@Nullable
		private CommandRunnerSettings.CustomGoal findCustomGoal(CommandRunnerSettings s, GoalNode g) {
			List<CommandRunnerSettings.CustomGoal> goals = s.getCustomGoals();
			if (goals == null) {
				return null;
			}
			String goalGroup = (g.group != null && !g.group.isEmpty()) ? g.group
				: parseType(g.commandType.name()).getDisplayName();
			for (CommandRunnerSettings.CustomGoal cg : goals) {
				if (cg.name.equals(g.name)
					&& java.util.Objects.equals(cg.description, g.description)) {
					String cgGroup = (cg.group != null && !cg.group.isEmpty()) ? cg.group
						: parseType(cg.commandType).getDisplayName();
					if (goalGroup.equals(cgGroup)) {
						return cg;
					}
				}
			}
			// 回退：仅按名称和描述匹配（兼容旧数据）
			for (CommandRunnerSettings.CustomGoal cg : goals) {
				if (cg.name.equals(g.name)
					&& java.util.Objects.equals(cg.description, g.description)) {
					return cg;
				}
			}
			return null;
		}
		
		private static CommandType parseType(String name) {
			try {
				return CommandType.valueOf(name);
			} catch (IllegalArgumentException e) {
				return CommandType.MAVEN;
			}
		}
		
		private static JButton createToolbarButton(String text, Icon icon, String tooltip) {
			JButton btn = new JButton(text, icon);
			btn.setToolTipText(tooltip);
			btn.setMargin(new Insets(2, 6, 2, 6));
			btn.setFocusable(false);
			return btn;
		}
		
		// ──────────────────── 脚本执行 ────────────────────
		
		/**
		 * 检查必填参数，返回缺失的参数标签列表
		 */
		@NotNull
		private static List<String> getMissingRequiredParams(
			@NotNull BuiltInScriptRegistry.ScriptDef def,
			@NotNull Map<String, String> paramValues) {
			List<String> missing = new ArrayList<>();
			for (BuiltInScriptRegistry.ScriptParam p : def.params) {
				if (p.required) {
					String val = paramValues.get(p.varName);
					if (val == null || val.isEmpty()) {
						missing.add(p.label);
					}
				}
			}
			return missing;
		}
		
		/**
		 * 执行内置脚本（使用指定的参数值），管理进度条和停止按钮状态
		 */
		private void executeBuiltInScript(GoalNode goal, Map<String, String> paramValues) {
			String scriptContent = goal.builtInScript.buildInjectedScript(paramValues);
			System.out.println(scriptContent);
			// 从脚本名称中提取文件名（如 "部署 (deploy.ps1)" → "deploy.ps1"）
			String fileName = goal.name.replaceAll(".*\\(([^)]+)\\).*", "$1");
			if (fileName.equals(goal.name)) {
				fileName = goal.name + ".ps1";
			}
			
			progressBar.setVisible(true);
			btnStop.setEnabled(true);
			
			// 通过临时文件执行，避免在控制台输出脚本内容
			OSProcessHandler handler = CommandUtil.executePowerShellScript(
				currentProject, scriptContent, fileName, () -> {
					runningHandlers.removeIf(h -> h.isProcessTerminated());
					ApplicationManager.getApplication().invokeLater(() -> {
						if (runningHandlers.isEmpty()) {
							progressBar.setVisible(false);
							btnStop.setEnabled(false);
						}
					});
				});
			
			if (handler != null) {
				runningHandlers.add(handler);
			} else if (runningHandlers.isEmpty()) {
				progressBar.setVisible(false);
				btnStop.setEnabled(false);
			}
		}
		
		// ──────────────────── 局部树节点移动 ────────────────────
		
		/**
		 * 在树中将指定节点与相邻兄弟节点交换显示位置（交换 UserObject，保持树结构和折叠状态）。
		 *
		 * @param node  要移动的树节点
		 * @param delta -1 上移, +1 下移
		 */
		private void moveTreeNode(DefaultMutableTreeNode node, int delta) {
			DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
			if (parent == null) {
				return;
			}
			DefaultTreeModel model = (DefaultTreeModel) currentTree.getModel();
			
			int idx = parent.getIndex(node);
			int newIdx = idx + delta;
			if (newIdx < 0 || newIdx >= parent.getChildCount()) {
				return;
			}
			
			DefaultMutableTreeNode sibling = (DefaultMutableTreeNode) parent.getChildAt(newIdx);
			
			// 交换 UserObject（不改变树结构，避免父节点折叠）
			Object tmp = node.getUserObject();
			node.setUserObject(sibling.getUserObject());
			sibling.setUserObject(tmp);
			
			// 通知树刷新两个节点的显示
			model.nodeChanged(node);
			model.nodeChanged(sibling);
		}
		
		/**
		 * 在内置脚本排序列表中移动脚本
		 */
		private void moveScriptInSettings(String scriptName, int delta) {
			CommandRunnerSettings settings = CommandRunnerSettings.getInstance(currentProject);
			List<String> order = settings.getScriptOrder();
			
			// 确保所有当前脚本都在排序列表中
			for (BuiltInScriptRegistry.ScriptDef def : BuiltInScriptRegistry.ALL_SCRIPTS) {
				if (!order.contains(def.name)) {
					order.add(def.name);
				}
			}
			
			int idx = order.indexOf(scriptName);
			if (idx < 0) {
				order.add(scriptName);
				idx = order.size() - 1;
			}
			int newIdx = idx + delta;
			if (newIdx < 0 || newIdx >= order.size()) {
				return;
			}
			
			order.remove(idx);
			order.add(newIdx, scriptName);
		}
		
		private void saveScriptToProject() {
			java.util.List<CommandGoalService.GoalCategory> categories =
				CommandGoalService.getInstance(currentProject)
					.getCategorizedGoals(currentProject);
			
			for (CommandGoalService.GoalCategory cat : categories) {
				if ("脚本执行".equals(cat.name)) {
					cat.goals.forEach(this::saveScriptToProject);
				}
			}
		}
		
		private void saveScriptToProject(GoalNode goalNode) {
			System.out.println(goalNode);
			if (Objects.isNull(goalNode.builtInScript)) {
				return;
			}
			
			CommandGoalService svc = CommandGoalService.getInstance(currentProject);
			Map<String, String> projectInfo = svc.getProjectInfo(currentProject);
			Map<String, String> defaultValues = BuiltInScriptRegistry.fillDefaults(goalNode.builtInScript, projectInfo);
			
			Map<String, String> saved = CommandRunnerSettings.getInstance(currentProject)
				.getScriptParamValues(goalNode.builtInScript.name);
			for (Map.Entry<String, String> entry : saved.entrySet()) {
				if (entry.getValue() != null && !entry.getValue().isEmpty()) {
					defaultValues.put(entry.getKey(), entry.getValue());
				}
			}
			
			final String scriptName = goalNode.name;
			final String scriptContent = goalNode.builtInScript.buildInjectedScript(defaultValues);
			
			try {
				String basePath = currentProject.getBasePath();
				System.out.println("Base path: " + basePath);
				final File file = new File(basePath, goalNode.builtInScript.getScriptName());
				file.deleteOnExit();
				
				byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
				byte[] content = scriptContent.getBytes(StandardCharsets.UTF_8);
				byte[] data = new byte[bom.length + content.length];
				System.arraycopy(bom, 0, data, 0, bom.length);
				System.arraycopy(content, 0, data, bom.length, content.length);
				Files.write(Path.of(file.toURI()), data);
				CommandUtil.fileSaveNotify(currentProject, scriptName);
			} catch (IOException e) {
				CommandUtil.notifyError(currentProject, scriptName, "无法保存文件: " + e.getMessage());
			}
		}
		
		// ──────────────────── Maven 运行配置同步 ────────────────────
		
		/**
		 * 将"模块打包"分组下的所有目标同步为 Maven Run Configuration
		 */
		private void syncMavenRunConfigurations() {
			if (currentProject == null) {
				return;
			}
			String basePath = currentProject.getBasePath();
			if (basePath == null) {
				notify("项目路径未找到，无法同步运行配置。", NotificationType.WARNING);
				return;
			}
			
			RunManager runManager = RunManager.getInstance(currentProject);
			
			// 1. 移除旧的自动生成的配置
			java.util.List<RunnerAndConfigurationSettings> allSettings =
				runManager.getAllSettings();
			java.util.List<RunnerAndConfigurationSettings> toRemove = new java.util.ArrayList<>();
			
			for (RunnerAndConfigurationSettings allSetting : allSettings) {
				// 获取配置类型
				ConfigurationType type = allSetting.getType();
				
				// 判断是否为 Maven
				if (type instanceof MavenRunConfigurationType) {
					toRemove.add(allSetting);
				}
			}
			
			for (RunnerAndConfigurationSettings s : toRemove) {
				runManager.removeConfiguration(s);
			}
			
			// 2. 获取模块打包目标
			CommandGoalService service = CommandGoalService.getInstance(currentProject);
			java.util.List<CommandGoalService.GoalCategory> categories =
				service.getCategorizedGoals(currentProject);
			
			java.util.List<GoalNode> moduleGoals = new java.util.ArrayList<>();
			for (CommandGoalService.GoalCategory cat : categories) {
				if ("模块打包".equals(cat.name)) {
					moduleGoals.addAll(cat.goals);
				}
			}
			
			if (moduleGoals.isEmpty()) {
				notify("未发现 Spring Boot 模块打包目标。"
					+ "\n请确保项目包含 spring-boot-maven-plugin。", NotificationType.INFORMATION);
				return;
			}
			
			// 4. 为每个模块打包目标创建运行配置
			int created = 0;
			for (GoalNode goal : moduleGoals) {
				syncSingleMavenRunConfigurations(goal);
				created++;
			}
			
			notify("已同步 " + created + " 个 Maven 运行配置到 Run/Debug 列表。",
				NotificationType.INFORMATION);
		}
		
		public void syncSingleMavenRunConfigurations(GoalNode goal) {
			String basePath = currentProject.getBasePath();
			RunManager runManager = RunManager.getInstance(currentProject);
			
			// 3. 查找 Maven Run Configuration Type
			org.jetbrains.idea.maven.execution.MavenRunConfigurationType configType;
			try {
				configType = ConfigurationTypeUtil.findConfigurationType(org.jetbrains.idea.maven.execution.MavenRunConfigurationType.class);
			} catch (Exception e) {
				notify("无法获取 Maven 运行配置类型，请确保 Maven 插件已启用。",
					NotificationType.WARNING);
				return;
			}
			
			if (configType == null) {
				notify("找不到 MavenRunConfigurationType，请确保 Maven 插件已加载。",
					NotificationType.WARNING);
				return;
			}
			
			ConfigurationFactory factory = configType.getConfigurationFactories()[0];
			
			try {
				String configName = extractModuleName(goal.name);
				RunnerAndConfigurationSettings settings =
					runManager.createConfiguration(configName, factory);
				
				// 解析 goals，添加 -DskipTests 以跳过测试
				java.util.List<String> goalList = java.util.Arrays.asList(
					goal.name.split("\\s+"));
				java.util.List<String> goals = new java.util.ArrayList<>(goalList);
				goals.add("-DskipTests");
				
				org.jetbrains.idea.maven.execution.MavenRunnerParameters params =
					new org.jetbrains.idea.maven.execution.MavenRunnerParameters();
				assert basePath != null;
				params.setWorkingDirPath(basePath);
				params.setGoals(goals);
				
				// 设置 runner 参数到配置
				if (settings.getConfiguration()
					instanceof org.jetbrains.idea.maven.execution.MavenRunConfiguration mrc) {
					mrc.setRunnerParameters(params);
				}
				
				runManager.addConfiguration(settings);
			} catch (Exception e) {
				// 单个配置创建失败，继续下一个
			}
		}
		
		/**
		 * 通过 YunYou Command Tool 通知组发送通知
		 */
		private void notify(@NotNull String content, @NotNull NotificationType type) {
			com.intellij.notification.Notification notification =
				new com.intellij.notification.Notification("YunYou Command Tool",
					"同步运行配置", content, type);
			Notifications.Bus.notify(notification, currentProject);
		}
		
		/**
		 * 从 goal 命令中提取模块名（如 "clean package -pl yy-service" → "yy-service"）
		 */
		@NotNull
		public static String extractModuleName(@NotNull String goal) {
			String[] parts = goal.split("\\s+");
			for (int i = 0; i < parts.length - 1; i++) {
				if ("-pl".equals(parts[i]) || "--projects".equals(parts[i])) {
					// 提取 -pl 后面的模块路径，取最后一段作为显示名
					String modulePath = parts[i + 1];
					int lastSep = modulePath.lastIndexOf('/');
					return lastSep >= 0 ? modulePath.substring(lastSep + 1) : modulePath;
				}
			}
			return "(root)";
		}
	}
	
	// ──────────────────── 对话框 ────────────────────
	
	static class AddGoalDialog extends DialogWrapper {
		private final JComboBox<CommandType> typeCombo = new JComboBox<>(CommandType.values());
		private final JTextField nameField = new JTextField(30);
		private final JTextField descField = new JTextField(30);
		private final JTextField paramsField = new JTextField(30);
		private final ComboBox<String> groupCombo;
		
		AddGoalDialog(Project project, String name, String desc, String params,
		              CommandType type, String group) {
			super(project, false);
			setTitle("自定义命令");
			
			nameField.setText(name != null ? name : "");
			descField.setText(desc != null ? desc : "");
			paramsField.setText(params != null ? params : "");
			typeCombo.setSelectedItem(type != null ? type : CommandType.MAVEN);
			
			CommandRunnerSettings settings = CommandRunnerSettings.getInstance(project);
			java.util.Set<String> groups = new java.util.LinkedHashSet<>();
			groups.add(""); // 默认（按类型自动分组）
			groups.add("新建分组...");
			for (CommandRunnerSettings.CustomGoal cg : settings.getCustomGoals()) {
				if (cg.group != null && !cg.group.isEmpty()) {
					groups.add(cg.group);
				}
			}
			groupCombo = new ComboBox<>(groups.toArray(new String[0]));
			groupCombo.setEditable(true);
			if (group != null && !group.isEmpty()) {
				groupCombo.setSelectedItem(group);
			} else {
				groupCombo.setSelectedItem("");
			}
			
			init();
		}
		
		@Override
		protected @Nullable JComponent createCenterPanel() {
			JPanel panel = new JPanel(new GridBagLayout());
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.insets = new Insets(4, 4, 4, 4);
			gbc.fill = GridBagConstraints.HORIZONTAL;
			
			gbc.gridx = 0;
			gbc.gridy = 0;
			panel.add(new JLabel("命令类型:"), gbc);
			gbc.gridx = 1;
			gbc.gridy = 0;
			panel.add(typeCombo, gbc);
			
			gbc.gridx = 0;
			gbc.gridy = 1;
			panel.add(new JLabel("命令:"), gbc);
			gbc.gridx = 1;
			gbc.gridy = 1;
			panel.add(nameField, gbc);
			
			gbc.gridx = 0;
			gbc.gridy = 2;
			panel.add(new JLabel("描述:"), gbc);
			gbc.gridx = 1;
			gbc.gridy = 2;
			panel.add(descField, gbc);
			
			gbc.gridx = 0;
			gbc.gridy = 3;
			panel.add(new JLabel("默认参数:"), gbc);
			gbc.gridx = 1;
			gbc.gridy = 3;
			panel.add(paramsField, gbc);
			
			gbc.gridx = 0;
			gbc.gridy = 4;
			panel.add(new JLabel("分组:"), gbc);
			gbc.gridx = 1;
			gbc.gridy = 4;
			panel.add(groupCombo, gbc);
			
			return panel;
		}
		
		@Override
		protected void doOKAction() {
			if (nameField.getText().trim().isEmpty()) {
				Messages.showErrorDialog("命令文本不能为空", "输入错误");
				return;
			}
			Object g = groupCombo.getSelectedItem();
			if ("新建分组...".equals(g)) {
				String ng = Messages.showInputDialog("输入新分组名:", "新建分组",
					Messages.getQuestionIcon());
				if (ng != null && !ng.isBlank()) {
					groupCombo.insertItemAt(ng.trim(), groupCombo.getItemCount() - 1);
					groupCombo.setSelectedItem(ng.trim());
				} else {
					groupCombo.setSelectedItem("");
				}
				return;
			}
			super.doOKAction();
		}
		
		CommandRunnerSettings.CustomGoal getResultGoal() {
			CommandType type = (CommandType) typeCombo.getSelectedItem();
			if (type == null) {
				type = CommandType.MAVEN;
			}
			Object g = groupCombo.getSelectedItem();
			String group = (g != null && !g.toString().isEmpty()
				&& !"新建分组...".equals(g)) ? g.toString() : "";
			return new CommandRunnerSettings.CustomGoal(
				nameField.getText().trim(),
				descField.getText().trim(),
				paramsField.getText().trim(),
				type, group);
		}
	}
	
	// ──────────────────── 脚本参数对话框 ────────────────────
	
	static class ScriptParamDialog extends DialogWrapper {
		private final BuiltInScriptRegistry.ScriptDef scriptDef;
		/**
		 * varName → JComponent 映射，保持与原始参数顺序一致的查找
		 */
		private final Map<String, JComponent> paramFields = new LinkedHashMap<>();
		private final List<BuiltInScriptRegistry.ScriptParam> params;
		private final Map<String, String> defaultValues;
		private final Project project;
		/**
		 * 必填参数但值为空的参数标签列表（用于提示）
		 */
		private final List<String> missingRequiredLabels = new ArrayList<>();
		/**
		 * 基础参数（非高级）
		 */
		private final List<BuiltInScriptRegistry.ScriptParam> basicParams = new ArrayList<>();
		/**
		 * 高级参数
		 */
		private final List<BuiltInScriptRegistry.ScriptParam> advancedParams = new ArrayList<>();
		/**
		 * 高级参数面板（可折叠）
		 */
		private JPanel advancedPanel;
		/** 高级面板的父容器，用于物理 add/remove */
		private JPanel parentPanel;
		/** 高级面板在父容器中的布局约束 */
		private GridBagConstraints advancedPanelGbc;
		/**
		 * 高级参数按钮
		 */
		private JButton toggleAdvancedBtn;
		/**
		 * 高级参数是否展开
		 */
		private boolean advancedVisible = false;
		
		ScriptParamDialog(Project project, BuiltInScriptRegistry.ScriptDef scriptDef,
		                  Map<String, String> defaultValues) {
			super(project, false);
			this.scriptDef = scriptDef;
			this.project = project;
			this.params = scriptDef.params;
			// 加载已持久化的参数值，覆盖默认值
			CommandRunnerSettings settings = CommandRunnerSettings.getInstance(project);
			Map<String, String> saved = settings.getScriptParamValues(scriptDef.name);
			this.defaultValues = new LinkedHashMap<>(defaultValues);
			for (Map.Entry<String, String> entry : saved.entrySet()) {
				if (entry.getValue() != null && !entry.getValue().isEmpty()) {
					this.defaultValues.put(entry.getKey(), entry.getValue());
				}
			}
			// 分离基础参数和高级参数
			for (BuiltInScriptRegistry.ScriptParam p : params) {
				if (p.advanced) {
					advancedParams.add(p);
				} else {
					basicParams.add(p);
				}
			}
			setTitle("配置脚本: " + scriptDef.name);
			init();
		}
		
		@Override
		protected @Nullable JComponent createCenterPanel() {
			JPanel panel = new JPanel(new GridBagLayout());
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.insets = new Insets(4, 4, 4, 4);
			gbc.fill = GridBagConstraints.HORIZONTAL;
			gbc.gridx = 0;
			gbc.weightx = 0;
			
			// 描述行
			JLabel descLabel = new JLabel("<html><i>" + scriptDef.description + "</i></html>");
			gbc.gridy = 0;
			gbc.gridwidth = 2;
			panel.add(descLabel, gbc);
			gbc.gridwidth = 1;
			
			// 基础参数
			int rowOffset = 1;
			for (int i = 0; i < basicParams.size(); i++) {
				BuiltInScriptRegistry.ScriptParam p = basicParams.get(i);
				rowOffset = addParamRow(panel, gbc, rowOffset, p);
			}
			
			// 如果有高级参数，添加"高级"分隔和折叠按钮
			if (!advancedParams.isEmpty()) {
				gbc.gridwidth = 2;
				gbc.gridx = 0;
				gbc.gridy = rowOffset;
				JSeparator separator = new JSeparator();
				separator.setPreferredSize(new Dimension(1, 1));
				panel.add(separator, gbc);
				rowOffset++;
				
				// 高级切换按钮
				gbc.gridy = rowOffset;
				toggleAdvancedBtn = new JButton("高级 >>");
				toggleAdvancedBtn.setFont(toggleAdvancedBtn.getFont().deriveFont(Font.PLAIN));
				toggleAdvancedBtn.setBorderPainted(false);
				toggleAdvancedBtn.setContentAreaFilled(false);
				toggleAdvancedBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				toggleAdvancedBtn.setHorizontalAlignment(SwingConstants.LEFT);
				toggleAdvancedBtn.setForeground(UIManager.getColor("Link.foreground"));
				toggleAdvancedBtn.addActionListener(e -> toggleAdvanced());
				JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
				btnPanel.add(toggleAdvancedBtn);
				panel.add(btnPanel, gbc);
				rowOffset++;
				
				// 不在构造时 add 到父容器，所有高级参数 init 完成后统一 add（确保 Dialog 非 null）
				advancedPanel = new JPanel(new GridBagLayout());
				GridBagConstraints advGbc = new GridBagConstraints();
				advGbc.insets = new Insets(2, 4, 2, 4);
				advGbc.fill = GridBagConstraints.HORIZONTAL;
				advGbc.gridx = 0;
				advGbc.weightx = 0;
				advGbc.gridwidth = 1;
				
				int advRow = 0;
				for (int i = 0; i < advancedParams.size(); i++) {
					BuiltInScriptRegistry.ScriptParam p = advancedParams.get(i);
					String labelText = p.label + ":";
					if (p.required) {
						labelText = "<html><b style=\"color:red\">*</b> " + labelText + " <span style=\"color:gray;font-size:small\">(高级)</span></html>";
					} else {
						labelText = "<html>" + labelText + " <span style=\"color:gray;font-size:small\">(高级)</span></html>";
					}
					
					advGbc.gridy = advRow;
					advGbc.gridx = 0;
					JLabel label = new JLabel(labelText);
					label.setToolTipText("变量 $" + p.varName + (p.required ? "（必填）" : ""));
					advancedPanel.add(label, advGbc);
					
					advGbc.gridx = 1;
					advGbc.weightx = 1.0;
					JComponent field = createParamComponent(p);
					paramFields.put(p.varName, field);
					advancedPanel.add(field, advGbc);
					advGbc.weightx = 0;
					advGbc.gridx = 0;
					advRow++;
				}
				
				// 面板不在布局中（物理折叠），保持 visible=true 以便展开时正常显示
				gbc.weightx = 1.0;
				gbc.gridwidth = 2;
				gbc.gridx = 0;
				gbc.gridy = rowOffset;
				// 保存父容器和约束，供 toggleAdvanced() 中物理 add/remove
				parentPanel = panel;
				advancedPanelGbc = (GridBagConstraints) gbc.clone();
				// 初始状态：不添加到父容器，保持折叠态
				gbc.weightx = 0;
			}
			
			return panel;
		}
		
		/**
		 * 在 GridBagLayout 中添加一行参数（label + field），返回下一行的 gridy
		 */
		private int addParamRow(JPanel panel, GridBagConstraints gbc, int row, BuiltInScriptRegistry.ScriptParam p) {
			gbc.gridy = row;
			gbc.gridwidth = 1;
			gbc.gridx = 0;
			String labelText = p.label + ":";
			if (p.required) {
				labelText = "<html><b style=\"color:red\">*</b> " + labelText + "</html>";
			}
			JLabel label = new JLabel(labelText);
			label.setToolTipText("变量 $" + p.varName + (p.required ? "（必填）" : ""));
			panel.add(label, gbc);
			
			gbc.gridx = 1;
			gbc.weightx = 1.0;
			JComponent field = createParamComponent(p);
			paramFields.put(p.varName, field);
			panel.add(field, gbc);
			gbc.weightx = 0;
			return row + 1;
		}
		
		/**
		 * 切换高级参数的显示/隐藏
		 */
		private void toggleAdvanced() {
			advancedVisible = !advancedVisible;
			toggleAdvancedBtn.setText(advancedVisible ? "高级 <<" : "高级 >>");
			
			java.awt.Window wnd = getWindow();
			if (advancedVisible) {
				// 展开：将高级面板添加到父容器
				parentPanel.add(advancedPanel, advancedPanelGbc);
				parentPanel.revalidate();
				if (wnd != null) wnd.pack();
			} else {
				// 折叠：从父容器移除高级面板
				parentPanel.remove(advancedPanel);
				parentPanel.revalidate();
				parentPanel.repaint();
				if (wnd != null) {
					// 先让窗口接受任意高度，再 pack
					wnd.setMinimumSize(null);
					if (wnd instanceof java.awt.Dialog) {
						((java.awt.Dialog) wnd).setMinimumSize(null);
					}
					wnd.invalidate();
					wnd.pack();
				}
			}
		}
		
		@Override
		protected void doOKAction() {
			missingRequiredLabels.clear();
			for (BuiltInScriptRegistry.ScriptParam p : params) {
				if (p.required) {
					String val = getParamComponentValue(p.varName);
					if (val.isEmpty()) {
						missingRequiredLabels.add(p.label);
					}
				}
			}
			if (!missingRequiredLabels.isEmpty()) {
				Messages.showErrorDialog(
					"以下必填参数尚未配置，请填写后重试：\n  • "
						+ String.join("\n  • ", missingRequiredLabels),
					"缺少必填参数");
				return;
			}
			super.doOKAction();
		}
		
		/**
		 * 获取用户输入的参数值（varName → value）
		 */
		public Map<String, String> getParamValues() {
			Map<String, String> values = new LinkedHashMap<>();
			for (BuiltInScriptRegistry.ScriptParam p : params) {
				String val = getParamComponentValue(p.varName);
				values.put(p.varName, val);
			}
			return values;
		}
		
		/**
		 * 获取缺失的必填参数标签列表（用于外部检查）
		 */
		public List<String> getMissingRequiredLabels() {
			return missingRequiredLabels;
		}
		
		/**
		 * 根据参数定义创建输入组件。
		 * - projectInfoKey 为 "moduleNames" 时返回 JComboBox（下拉单选）
		 * - 其他情况返回 JTextField（文本输入）
		 */
		private JComponent createParamComponent(BuiltInScriptRegistry.ScriptParam p) {
			boolean isModuleSelector = "moduleNames".equals(p.projectInfoKey);
			String defaultVal = defaultValues.get(p.varName);
			if (defaultVal == null || defaultVal.isEmpty()) {
				defaultVal = p.defaultValue;
			}
			if (isModuleSelector) {
				List<String> modules =
					CommandUtil.getModuleNames(project)
						.stream()
						.map(item -> {
							int lastSep = item.lastIndexOf('/');
							return lastSep >= 0 ? item.substring(lastSep + 1) : item;
						})
						.toList();
				String selected = defaultVal;
				if (selected != null) {
					selected = selected.replaceAll("^['\"@( ]+|['\" )]+$", "");
				}
				ComboBox<String> combo = new ComboBox<>();
				boolean hasSelection = false;
				int bmIndex = 0;
				for (int i = 0; i < modules.size(); i++) {
					final String m = modules.get(i);
					String name = m.trim();
					combo.addItem(name);
					if (name.equals(selected)) {
						combo.setSelectedItem(name);
						hasSelection = true;
					}
					if (name.contains("bm")) {
						bmIndex = i;
					}
				}
				if (!hasSelection && modules.size() > 0) {
					combo.setSelectedIndex(bmIndex);
				}
				combo.setToolTipText("变量 $" + p.varName + "（选择一个模块）");
				return combo;
			}
			boolean isOptionSelector = "options".equals(p.projectInfoKey);
			if (isOptionSelector) {
				String modulesStr = p.options;
				String[] modules = modulesStr != null ? modulesStr.split(",") : new String[0];
				String selected = defaultVal;
				ComboBox<String> combo = new ComboBox<>();
				boolean hasSelection = false;
				for (String m : modules) {
					String name = m.trim();
					combo.addItem(name);
					if (name.equals(selected)) {
						combo.setSelectedItem(name);
						hasSelection = true;
					}
				}
				if (!hasSelection && modules.length > 0) {
					combo.setSelectedIndex(0);
				}
				combo.setToolTipText("请选择" + p.label);
				return combo;
			}
			
			JTextField field = new JTextField(35);
			field.setText(defaultVal != null ? defaultVal : "");
			field.setToolTipText("变量 $" + p.varName + (p.required ? "（必填）" : ""));
			return field;
		}
		
		/**
		 * 从 paramFields 中按 varName 获取输入值。
		 * 兼容 JTextField 和 JComboBox 两种组件类型。
		 */
		private String getParamComponentValue(String varName) {
			JComponent comp = paramFields.get(varName);
			if (comp instanceof JTextField tf) {
				return tf.getText().trim();
			}
			if (comp instanceof JComboBox<?> cb) {
				Object selected = cb.getSelectedItem();
				return selected != null ? selected.toString().trim() : "";
			}
			return "";
		}
	}
}
