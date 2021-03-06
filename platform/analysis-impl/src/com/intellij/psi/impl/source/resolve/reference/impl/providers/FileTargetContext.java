// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.psi.impl.source.resolve.reference.impl.providers;

import com.intellij.psi.PsiFileSystemItem;
import com.intellij.util.ArrayUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;

/**
 * Describes existing or non-existing location in file system where new files can be created.
 */
public class FileTargetContext {

  private final PsiFileSystemItem myContext;
  private final String[] myPathToCreate;

  /**
   * Constructs new target context.
   *
   * @param context file system item that will be used as target directory
   * @param pathToCreate additional existing or non-existing paths
   */
  public FileTargetContext(@NotNull PsiFileSystemItem context, @NotNull String[] pathToCreate) {
    myContext = context;
    myPathToCreate = pathToCreate;
  }

  /**
   * Constructs new target context.
   *
   * @param context file system item that will be used as target directory
   */
  public FileTargetContext(@NotNull PsiFileSystemItem context) {
    this(context, ArrayUtil.EMPTY_STRING_ARRAY);
  }

  public PsiFileSystemItem getFileSystemItem() {
    return myContext;
  }

  @NotNull
  public String[] getPathToCreate() {
    return myPathToCreate;
  }

  public static Collection<FileTargetContext> toTargetContexts(Collection<PsiFileSystemItem> items) {
    return ContainerUtil.map(items,FileTargetContext::new);
  }

  public static Collection<FileTargetContext> toTargetContexts(PsiFileSystemItem item) {
    return Collections.singletonList(new FileTargetContext(item));
  }
}
