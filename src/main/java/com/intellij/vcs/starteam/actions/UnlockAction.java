package com.intellij.vcs.starteam.actions;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcs.starteam.StarteamBundle;
import com.intellij.vcs.starteam.StarteamVcs;

/**
 * @author mike
 */
public class UnlockAction extends BasicAction {
  protected String getActionName() {
    return StarteamBundle.message("local.vcs.action.name.unlocking.files");
  }

  protected boolean isEnabled(Project project, AbstractVcs vcs, VirtualFile file) {
    return FileStatusManager.getInstance(project).getStatus(file) != FileStatus.ADDED;
  }

  protected void perform(Project project, final StarteamVcs activeVcs, final VirtualFile file) throws VcsException {
    if (file.isDirectory()) {
      final VcsException[] errors = new VcsException[1];
      final ProjectFileIndex fileIndex = ProjectRootManager.getInstance(project).getFileIndex();

      //-----------------------------------------------------------------------
      //  Iterate over the diferctory structure starting from the given package
      //  root, and unlock each file.
      //  NB: an exception is thrown only for the first failure.
      //-----------------------------------------------------------------------
      fileIndex.iterateContentUnderDirectory(file, vf -> {
        if (!vf.isDirectory()) {
          try {
            activeVcs.unlockFile(vf.getPresentableUrl());
          } catch (VcsException e) {
            if (errors[0] != null) errors[0] = e;
          }
        }
        return true;
      });

      if (errors[0] != null) {
        throw errors[0];
      } else {
        Notifications.Bus.notify(new Notification(activeVcs.getDisplayName(), "Unlocked",
            file.getPath(), NotificationType.INFORMATION));
      }
    } else {
      activeVcs.unlockFile(file.getPresentableUrl());
      Notifications.Bus.notify(new Notification(activeVcs.getDisplayName(), "Unlocked",
          file.getName(), NotificationType.INFORMATION));
    }
  }
}
