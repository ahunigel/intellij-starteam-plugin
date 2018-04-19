/*
 * Copyright 2000-2006 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.vcs.starteam;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.history.*;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ui.ColumnInfo;
import com.intellij.vcsUtil.VcsUtil;
import com.starteam.File;
import com.starteam.ViewMember;
import com.starteam.ViewMemberCollection;
import com.starteam.exceptions.CommandException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: lloix
 * Date: Nov 15, 2006
 */
public class StarteamHistoryProvider implements VcsHistoryProvider {
  private final StarteamVcs host;

  public StarteamHistoryProvider(StarteamVcs host) {
    this.host = host;
  }

  @NonNls
  @Nullable
  public String getHelpId() {
    return null;
  }

  public boolean supportsHistoryForDirectories() {
    return false;
  }

  @Nullable
  public DiffFromHistoryHandler getHistoryDiffHandler() {
    return null;
  }

  public boolean canShowHistoryFor(@NotNull VirtualFile virtualFile) {
    StarteamVcsAdapter baseHost = StarteamVcsAdapter.getInstance(host.getProject());
    return VcsUtil.isFileForVcs(virtualFile, host.getProject(), baseHost);
  }

  public VcsDependentHistoryComponents getUICustomization(final VcsHistorySession session, JComponent forShortcutRegistration) {
    return VcsDependentHistoryComponents.createOnlyColumns(ColumnInfo.EMPTY_ARRAY);
  }

  public AnAction[] getAdditionalActions(final Runnable refresher) {
    return new AnAction[0];
  }

  public boolean isDateOmittable() {
    return false;
  }

  public VcsHistorySession createSessionFor(FilePath filePath) throws VcsException {
    final File file;
    try {
      file = host.findFile(filePath.getPath());
    } catch (CommandException e) {
      throw new VcsException(e);
    }

    if (file != null) {
      ViewMemberCollection viewMemberCollection = file.getHistory();
      ArrayList<VcsFileRevision> revisions = new ArrayList<>();

      for (int i = 0; i < viewMemberCollection.size(); i++) {
        ViewMember viewMember = viewMemberCollection.getAt(i);
        VcsFileRevision rev = new StarteamFileRevision((File) viewMember);
        revisions.add(rev);
      }
      return new StarteamHistorySession(revisions, file);
    } else {
      throw new VcsException("Can not find file: " + filePath.getPath());
    }
  }

  public void reportAppendableHistory(FilePath path, VcsAppendableHistorySessionPartner partner) throws VcsException {
    final VcsHistorySession session = createSessionFor(path);
    partner.reportCreatedEmptySession((VcsAbstractHistorySession) session);
  }

  private static VcsRevisionNumber getCurrentRevisionNum(File file) {
    try {
      ViewMemberCollection items = file.getHistory();
      if (!items.isEmpty()) {
        ViewMember viewMember = items.getAt(items.size() - 1);
        return new VcsRevisionNumber.Int(file.getContentVersion());
      }
    } catch (Exception e) {
      //  We can catch e.g. com.starbase.starteam.ItemNotFoundException if we
      //  try to show history records for the deleted file.
    }
    return VcsRevisionNumber.NULL;
  }

  private static class StarteamHistorySession extends VcsAbstractHistorySession {
    private final File file;

    public StarteamHistorySession(List<VcsFileRevision> revisions, File file) {
      super(revisions, new VcsRevisionNumber.Int(file.getContentVersion()));
      this.file = file;
    }

    @Nullable
    public VcsRevisionNumber calcCurrentRevisionNumber() {
      return getCurrentRevisionNum(file);
    }

    public HistoryAsTreeProvider getHistoryAsTreeProvider() {
      return null;
    }

    @Override
    public VcsHistorySession copy() {
      return new StarteamHistorySession(getRevisionList(), file);
    }
  }

}

