package com.intellij.vcs.starteam;

import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.DefaultRepositoryLocation;
import com.intellij.openapi.vcs.RepositoryLocation;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.history.VcsFileRevision;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.starteam.CheckoutManager;
import com.starteam.File;
import com.starteam.User;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Date;

/**
 * Created by Nigel.Zheng on 4/19/2018.
 */
public class StarteamFileRevision implements VcsFileRevision {
  private final File file;
  private byte[] contents = null;

  public StarteamFileRevision(File file) {
    this.file = file;
  }

  public VcsRevisionNumber getRevisionNumber() {
    return new VcsRevisionNumber.Int(file.getContentVersion());
  }

  @Nullable
  public String getBranchName() {
    return file.getView().getName();
  }

  @Nullable
  public RepositoryLocation getChangedRepositoryPath() {
    return new DefaultRepositoryLocation(file.getServer().getApplication().toStarTeamURL(file));
  }

  public Date getRevisionDate() {
    return file.getModifiedTime().toJavaDate();
  }

  public String getCommitMessage() {
    return file.getComment();
  }

  public String getAuthor() {
    String userName = StarteamBundle.message("unknown.author.name");
    try {
      User user = file.getModifiedBy();
      userName = user.getName();
    } catch (Exception e) {
      //  Nothing to do - try/catch here is to overcome internal Starteam SDK
      //  problem - it throws NPE inside <server.getUser(int)>.
    }
    return userName;
  }

  public byte[] loadContent() throws VcsException {
    if (file instanceof File && contents == null) {
      try {
        File stFile = file;
        CheckoutManager mgr = stFile.getView().createCheckoutManager();
        java.io.File file = new java.io.File(
            FileUtil.getTempDirectory() + java.io.File.separator + Long.toString(System.currentTimeMillis()) + stFile.getName());
//          stFile.checkoutTo(file, Item.LockType.UNCHANGED, true, true, false);
        mgr.checkoutTo(stFile, file);
        if (mgr.canCommit()) {
          mgr.commit();
        }
        contents = FileUtil.loadFileBytes(file);
        return contents;
      } catch (IOException e) {
        throw new VcsException(e);
      }
    }
    return new byte[0];
  }

  public byte[] getContent() {
    return contents;
  }

  public int compareTo(Object revision) {
    return getRevisionDate().compareTo(((VcsFileRevision) revision).getRevisionDate());
  }

  public String getPresentableName() {
    return file.getDotNotation().toString();
  }
}
