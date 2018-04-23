package com.intellij.vcs.starteam;

import com.intellij.openapi.vcs.DefaultRepositoryLocation;
import com.intellij.openapi.vcs.RepositoryLocation;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.history.VcsFileRevision;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.starteam.File;
import com.starteam.User;
import org.jetbrains.annotations.Nullable;

import java.util.Date;

/**
 * Created by Nigel.Zheng on 4/19/2018.
 */
public class StarteamFileRevision implements VcsFileRevision {
  private final StarteamVcs host;
  private final File file;
  private byte[] contents = null;

  public StarteamFileRevision(StarteamVcs host, File file) {
    this.host = host;
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
    if (file != null && contents == null) {
      contents = host.getFileContent(file);
      return contents;
    }
    return new byte[0];
  }

  public byte[] getContent() {
    return contents;
  }

  public int compareTo(Object revision) {
    return getRevisionDate().compareTo(((VcsFileRevision) revision).getRevisionDate());
  }

  public String getDotNotation() {
    return file.getDotNotation().toString();
  }
}
