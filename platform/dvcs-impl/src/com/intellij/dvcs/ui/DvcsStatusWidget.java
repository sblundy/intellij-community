// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.dvcs.ui;

import com.intellij.dvcs.repo.Repository;
import com.intellij.dvcs.repo.VcsRepositoryManager;
import com.intellij.dvcs.repo.VcsRepositoryMappingListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.openapi.wm.impl.status.EditorBasedWidget;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.CalledInAwt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;

public abstract class DvcsStatusWidget<T extends Repository> extends EditorBasedWidget
  implements StatusBarWidget.MultipleTextValuesPresentation, StatusBarWidget.Multiframe
{
  protected static final Logger LOG = Logger.getInstance(DvcsStatusWidget.class);
  private static final String MAX_STRING = "VCS: Rebasing feature-12345 in custom development branch";

  @NotNull private final String myPrefix;

  @Nullable private String myText;
  @Nullable private String myTooltip;

  protected DvcsStatusWidget(@NotNull Project project, @NotNull String prefix) {
    super(project);
    myPrefix = prefix;
  }

  @Nullable
  protected abstract T guessCurrentRepository(@NotNull Project project);

  @NotNull
  protected abstract String getFullBranchName(@NotNull T repository);

  protected abstract boolean isMultiRoot(@NotNull Project project);

  @NotNull
  protected abstract ListPopup getPopup(@NotNull Project project, @NotNull T repository);

  protected abstract void subscribeToRepoChangeEvents(@NotNull Project project);

  protected abstract void rememberRecentRoot(@NotNull String path);

  public void activate() {
    installWidgetToStatusBar(getProject(), this);
  }

  public void deactivate() {
    removeWidgetFromStatusBar();
  }

  @Override
  public void dispose() {
    deactivate();
    super.dispose();
  }

  @NotNull
  @Override
  public String ID() {
    return getClass().getName();
  }

  @Override
  public WidgetPresentation getPresentation() {
    return this;
  }

  @Override
  public void selectionChanged(@NotNull FileEditorManagerEvent event) {
    LOG.debug("selection changed");
    update();
  }

  @Override
  public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
    LOG.debug("file opened");
    update();
  }

  @Override
  public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
    LOG.debug("file closed");
    update();
  }

  @CalledInAwt
  @Nullable
  @Override
  public String getSelectedValue() {
    return StringUtil.isEmpty(myText) ? "" : myPrefix + ": " + myText;
  }

  @Nullable
  @Override
  public String getTooltipText() {
    return myTooltip;
  }

  @Nullable
  @Override
  public ListPopup getPopupStep() {
    Project project = getProject();
    if (project.isDisposed()) return null;
    T repository = guessCurrentRepository(project);
    if (repository == null) return null;

    return getPopup(project, repository);
  }

  @Nullable
  @Override
  public Consumer<MouseEvent> getClickConsumer() {
    // has no effect since the click opens a list popup, and the consumer is not called for the MultipleTextValuesPresentation
    return null;
  }

  protected void updateLater() {
    Project project = getProject();
    if (project.isDisposed()) return;
    ApplicationManager.getApplication().invokeLater(() -> {
      LOG.debug("update after repository change");
      update();
    }, project.getDisposed());
  }

  @CalledInAwt
  private void update() {
    myText = null;
    myTooltip = null;

    Project project = getProject();
    if (project.isDisposed()) return;
    T repository = guessCurrentRepository(project);
    if (repository == null) return;

    int maxLength = MAX_STRING.length() - 1; // -1, because there are arrows indicating that it is a popup
    myText = StringUtil.shortenTextWithEllipsis(getFullBranchName(repository), maxLength, 5);
    myTooltip = getToolTip(project);
    if (myStatusBar != null) {
      myStatusBar.updateWidget(ID());
    }
    rememberRecentRoot(repository.getRoot().getPath());
  }

  @Nullable
  @CalledInAwt
  private String getToolTip(@NotNull Project project) {
    T currentRepository = guessCurrentRepository(project);
    if (currentRepository == null) return null;
    String branchName = myPrefix + " Branch: " + getFullBranchName(currentRepository);
    if (isMultiRoot(project)) {
      return branchName + "\n" + "Root: " + currentRepository.getRoot().getName();
    }
    return branchName;
  }

  private void installWidgetToStatusBar(@NotNull final Project project, @NotNull final StatusBarWidget widget) {
    ApplicationManager.getApplication().invokeLater(() -> {
      StatusBar statusBar = WindowManager.getInstance().getStatusBar(project);
      if (statusBar != null && !isDisposed()) {
        statusBar.addWidget(widget, StatusBar.Anchors.DEFAULT_ANCHOR, project);
        subscribeToMappingChanged(project);
        subscribeToRepoChangeEvents(project);
        update();
      }
    }, project.getDisposed());
  }

  private void removeWidgetFromStatusBar() {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (myStatusBar != null && !isDisposed()) {
        myStatusBar.removeWidget(ID());
      }
    }, getProject().getDisposed());
  }

  private void subscribeToMappingChanged(Project project) {
    project.getMessageBus().connect().subscribe(VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED, new VcsRepositoryMappingListener() {
      @Override
      public void mappingChanged() {
        LOG.debug("repository mappings changed");
        updateLater();
      }
    });
  }
}
