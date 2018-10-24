package com.dovar.gitextender.ui;

import com.dovar.gitextender.GitPullAllAction;
import com.intellij.ide.util.ElementsChooser;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import git4idea.GitLocalBranch;
import git4idea.repo.GitRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class GitMultiPullDialog extends DialogWrapper {
    private static final String SELECTED_MODULES_KEY = "com.dovar.selectedRepository";
    private static final String SPILIT = "  LocalBranch: ";

    private JPanel contentPane;
    protected JButton selectAllBtn;
    protected JButton selectNoneBtn;
    protected ElementsChooser<String> repoChooser;//repos选项卡控件
    private final PropertiesComponent properties;//用于数据持久化
    private final List<String> repos;//仓库名
    private final List<String> repos_branch;//仓库名+当前分支名

    public GitMultiPullDialog(@NotNull Project project, @NotNull List<GitRepository> repos) {
        super(project);
        this.repos = repos.stream()
                .map(GitPullAllAction::getRepoName)
                .sorted()
                .collect(Collectors.toList());
        this.repos_branch = repos.stream()
                .map(this::generateTitle)
                .sorted()
                .collect(Collectors.toList());
        this.properties = PropertiesComponent.getInstance(project);
        init();
        setTitle("Select Repository to Pull");
    }

    @Override
    protected void init() {
        super.init();

        this.selectAllBtn.addActionListener(evt -> {
            setSelectedModules(repos);
            repoChooser.setAllElementsMarked(true);
        });
        this.selectNoneBtn.addActionListener(evt -> {
            clearSelectedModules();
            repoChooser.setAllElementsMarked(false);
        });

        List<String> savedSelection = loadSelectedModules(properties);
        //去除无效的repos
        savedSelection.removeIf(str -> {
            if (!repos.contains(str)) {
                removeSelectedModule(str);
                return true;
            }
            return false;
        });

        //添加选项卡
        repoChooser.setElements(repos_branch, false);
        //选中选项卡
        for (String repo : savedSelection
                ) {
            if (repo == null || repo.equals("")) continue;
            for (String branch : repos_branch
                    ) {
                if (branch == null) continue;
                if (branch.contains(repo)) {
                    repoChooser.setElementMarked(branch, true);
                    break;
                }
            }
        }
        //设置OK键
        setOKActionEnabled(repoChooser.getMarkedElements().size() > 0);
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        return contentPane;
    }

    private String generateTitle(GitRepository repository) {
        if (repository == null) return "repository is null";
        //获取当前分支对应的远程分支
        GitLocalBranch localBranch = repository.getCurrentBranch();

        if (localBranch == null) {
            return GitPullAllAction.getRepoName(repository) + SPILIT + "NoLocalBranch";
        } else {
            return GitPullAllAction.getRepoName(repository) + SPILIT + localBranch.getName();
        }
    }

    //构造方法中会回调到此处
    protected void createUIComponents() {
        repoChooser = new ElementsChooser<>(true);
        repoChooser.addElementsMarkListener((ElementsChooser.ElementsMarkListener<String>) (element, isMarked) -> {
            if (isMarked) {
                addSelectedModule(element.split(SPILIT)[0]);
            } else {
                removeSelectedModule(element.split(SPILIT)[0]);
            }

            setOKActionEnabled(!loadSelectedModules(properties).isEmpty());
        });
    }

    private void addSelectedModule(@NotNull String module) {
        List<String> modules = loadSelectedModules(properties);
        if (!modules.contains(module)) {
            modules.add(module);
            properties.setValues(SELECTED_MODULES_KEY, modules.toArray(new String[modules.size()]));
        }
    }

    private void removeSelectedModule(@NotNull String module) {
        List<String> modules = loadSelectedModules(properties);
        if (modules.contains(module)) {
            modules.remove(module);
            if (modules.isEmpty()) {
                clearSelectedModules();
            } else {
                properties.setValues(SELECTED_MODULES_KEY, modules.toArray(new String[modules.size()]));
            }
        }
    }

    private void setSelectedModules(@NotNull List<String> modules) {
        properties.setValues(SELECTED_MODULES_KEY, modules.toArray(new String[modules.size()]));
    }

    private void clearSelectedModules() {
        properties.unsetValue(SELECTED_MODULES_KEY);
    }

    @NotNull
    public static List<String> loadSelectedModules(@NotNull PropertiesComponent projectComponent) {
        List<String> modules = new ArrayList<>();

        String[] selectedModules = projectComponent.getValues(SELECTED_MODULES_KEY);
        if (selectedModules == null || selectedModules.length == 0) {
            return modules;
        }

        modules.addAll(Arrays.asList(selectedModules));
        return modules;
    }
}
