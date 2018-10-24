package com.dovar.gitextender;

import com.dovar.gitextender.ui.GitMultiPullDialog;
import com.intellij.dvcs.DvcsUtil;
import com.intellij.dvcs.repo.VcsRepositoryManager;
import com.intellij.history.Label;
import com.intellij.history.LocalHistory;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.update.ActionInfo;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ObjectUtils;
import com.intellij.vcsUtil.VcsImplUtil;
import git4idea.*;
import git4idea.commands.*;
import git4idea.merge.GitMergeUtil;
import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.util.GitUIUtil;
import git4idea.util.GitUntrackedFilesHelper;
import git4idea.util.LocalChangesWouldBeOverwrittenHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;


public class GitPullAllAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            NotificationUtil.showErrorNotification("Update Failed", "Git Pull Extender failed to retrieve the project");
            return;
        }
        Git git = Git.getInstance();
        GitRepositoryManager manager = getGitRepositoryManager(project);
        if (manager == null) {
            NotificationUtil.showErrorNotification("Update Failed", "Git Pull Extender could not initialize the project's repository manager");
            return;
        }
        //获取项目下的所有Git仓库
        List<GitRepository> gitRoots = manager.getRepositories();
        if (gitRoots.isEmpty()) {
            NotificationUtil.showErrorNotification("Update Failed", "Git Pull Extender could not find any repositories in the current project");
            return;
        }

        //选择想要Pull的仓库
        boolean proceedToUpdate = showSelectModuleDialog(project, gitRoots);
        if (!proceedToUpdate) {
            NotificationUtil.showInfoNotification("Update Canceled", "update was canceled");
            return;
        }

        List<GitRepository> reposToUpdate = getSelectedGitRepos(gitRoots, GitMultiPullDialog.loadSelectedModules(PropertiesComponent.getInstance(project)));
        if (reposToUpdate.isEmpty()) {
            NotificationUtil.showInfoNotification("Update Canceled", "no repository selected in dialog");
            return;
        }

        for (GitRepository repository : reposToUpdate
                ) {
            if (repository == null) {
                continue;
            }
            VirtualFile root = repository.getRoot();

            GitRemote remote;
            //仓库的远程地址
            Collection<GitRemote> remotes = repository.getRemotes();
            if (remotes.isEmpty()) {
                continue;
            }
            //获取当前分支对应的远程分支
            GitBranchTrackInfo trackInfo = GitUtil.getTrackInfoForCurrentBranch(repository);
            StringBuilder tips = new StringBuilder("Pulling changes from ");
            if (trackInfo != null) {
                tips.append(trackInfo.getRemoteBranch().getName())
                        .append(" branch:").append(trackInfo.getLocalBranch().getName());
                remote = trackInfo.getRemote();
            } else {
                GitRemote origin = GitUtil.getDefaultRemote(remotes);
                remote = origin != null ? origin : (GitRemote) remotes.iterator().next();
                if (remote == null) {
                    continue;
                }
                GitLocalBranch localBranch = repository.getCurrentBranch();
                tips.append(remote.getName())
                        .append(" branch:").append(localBranch == null ? "null" : localBranch.getName());
            }
            final Computable<GitLineHandler> handlerProvider = () -> makeHandler(remote, root, project, trackInfo);
            final Label beforeLabel = LocalHistory.getInstance().putSystemLabel(project, "Before update");

            (new Task.Backgroundable(project, tips.toString(), true) {
                public void run(@NotNull ProgressIndicator indicator) {
                    GitLocalChangesWouldBeOverwrittenDetector localChangesDetector = new GitLocalChangesWouldBeOverwrittenDetector(root, GitLocalChangesWouldBeOverwrittenDetector.Operation.MERGE);
                    GitUntrackedFilesOverwrittenByOperationDetector untrackedFilesDetector = new GitUntrackedFilesOverwrittenByOperationDetector(root);
                    GitSimpleEventDetector mergeConflict = new GitSimpleEventDetector(GitSimpleEventDetector.Event.MERGE_CONFLICT);
                    AccessToken token = DvcsUtil.workingTreeChangeStarted(project);

                    try {
                        GitCommandResult result = git.runCommand(() -> {
                            GitLineHandler handler = (GitLineHandler) handlerProvider.compute();
                            handler.addLineListener(localChangesDetector);
                            handler.addLineListener(untrackedFilesDetector);
                            handler.addLineListener(mergeConflict);
                            return handler;
                        });
                        String revision = repository.getCurrentRevision();
                        if (revision == null) {
                            NotificationUtil.showErrorNotification("Update Failed", "revision == null");
                            return;
                        }

                        GitRevisionNumber currentRev = new GitRevisionNumber(revision);
                        handleResult(result, project, mergeConflict, localChangesDetector, untrackedFilesDetector, repository, currentRev, beforeLabel);
                    } finally {
                        token.finish();
                    }

                }
            }).queue();
        }
    }

    private boolean showSelectModuleDialog(Project project, List<GitRepository> repositories) {
        if (repositories.size() <= 1) {
            //single git repo in project, no need for displaying
            return true;
        }

        GitMultiPullDialog selectModuleDialog = new GitMultiPullDialog(project, repositories);
        return selectModuleDialog.showAndGet();
    }

    private void handleResult(GitCommandResult result, Project project, GitSimpleEventDetector mergeConflictDetector, GitLocalChangesWouldBeOverwrittenDetector localChangesDetector, GitUntrackedFilesOverwrittenByOperationDetector untrackedFilesDetector, GitRepository repository, GitRevisionNumber currentRev, Label beforeLabel) {
        GitRepositoryManager repositoryManager = GitUtil.getRepositoryManager(project);
        VirtualFile root = repository.getRoot();
        if (!result.success() && !mergeConflictDetector.hasHappened()) {
            if (localChangesDetector.wasMessageDetected()) {
                LocalChangesWouldBeOverwrittenHelper.showErrorNotification(project, repository.getRoot(), this.getActionName(), localChangesDetector.getRelativeFilePaths());
            } else if (untrackedFilesDetector.wasMessageDetected()) {
                GitUntrackedFilesHelper.notifyUntrackedFilesOverwrittenBy(project, root, untrackedFilesDetector.getRelativeFilePaths(), this.getActionName(), (String) null);
            } else {
                GitUIUtil.notifyError(project, "Git " + this.getActionName() + " Failed", result.getErrorOutputAsJoinedString(), true, (Exception) null);
                repositoryManager.updateRepository(root);
            }
        } else {
            VfsUtil.markDirtyAndRefresh(false, true, false, root);
            List<VcsException> exceptions = new ArrayList<>();
            GitMergeUtil.showUpdates(project, exceptions, root, currentRev, beforeLabel, this.getActionName(), ActionInfo.UPDATE);
            repositoryManager.updateRepository(root);
            showErrors(project, this.getActionName(), exceptions);
        }
    }

    private void showErrors(@NotNull Project project, @NotNull String actionName, @NotNull List<VcsException> exceptions) {
        ((GitVcs) ObjectUtils.notNull(GitVcs.getInstance(project))).showErrors(exceptions, actionName);
    }

    @NotNull
    private String getActionName() {
        return "Pull All";
    }

    private GitLineHandler makeHandler(@NotNull GitRemote remote, VirtualFile gitRoot, Project project, GitBranchTrackInfo trackInfo) {
        GitLineHandler h = new GitLineHandler(project, gitRoot, GitCommand.PULL);
        h.setUrls(remote.getUrls());
        h.addProgressParameter();
        h.addParameters("--no-stat");
      /*  if (this.myNoCommitCheckBox.isSelected()) {
            h.addParameters(new String[]{"--no-commit"});
        } else if (this.myAddLogInformationCheckBox.isSelected()) {
            h.addParameters(new String[]{"--log"});
        }

        if (this.mySquashCommitCheckBox.isSelected()) {
            h.addParameters(new String[]{"--squash"});
        }

        if (this.myNoFastForwardCheckBox.isSelected()) {
            h.addParameters(new String[]{"--no-ff"});
        }

        String strategy = (String)this.myStrategy.getSelectedItem();
        if (!GitMergeUtil.DEFAULT_STRATEGY.equals(strategy)) {
            h.addParameters(new String[]{"--strategy", strategy});
        }*/

        h.addParameters("-v");
        h.addProgressParameter();
        h.addParameters(remote.getName());

        GitRemoteBranch currentRemoteBranch = trackInfo == null ? null : trackInfo.getRemoteBranch();
        if (currentRemoteBranch != null) {
            String branch = currentRemoteBranch.getNameForLocalOperations();
            h.addParameters(removeRemotePrefix(branch, remote.getName()));
        }
        return h;
    }

    private static String removeRemotePrefix(@NotNull String branch, @NotNull String remote) {
        String prefix = remote + "/";
        if (branch.startsWith(prefix)) {
            return branch.substring(prefix.length());
        } else {
            NotificationUtil.showNotification("removeRemotePrefix", String.format("Remote branch name seems to be invalid. Branch: %s, remote: %s", branch, remote), NotificationType.WARNING);
            return branch;
        }
    }

    @Nullable
    private static GitRepositoryManager getGitRepositoryManager(@NotNull Project project) {
        try {
            VcsRepositoryManager vcsManager = project.getComponent(VcsRepositoryManager.class);
            if (vcsManager == null) {
                return null;
            }
            return new GitRepositoryManager(project, vcsManager);
        } catch (Exception e) {
            NotificationUtil.showErrorNotification("getGitRepositoryManager", "exception caught while trying to get git repository manager");
            return null;
        }
    }

    private static List<GitRepository> getSelectedGitRepos(List<GitRepository> repos, List<String> selectedModules) {
        if (repos.size() <= 1) {
            return repos;
        }

        return repos.stream()
                .filter(repo -> selectedModules.contains(getRepoName(repo)))
                .collect(Collectors.toList());
    }

    public static String getRepoName(GitRepository repo) {
        return VcsImplUtil.getShortVcsRootName(repo.getProject(), repo.getRoot());
    }
}
