// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.actionSystem.ex;

import com.intellij.openapi.application.PreloadingActivity;
import com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.annotations.NotNull;

public class QuickListPreloaded extends PreloadingActivity {
  @Override
  public void preload(@NotNull ProgressIndicator indicator) {
    QuickListsManager.getInstance();
  }
}
