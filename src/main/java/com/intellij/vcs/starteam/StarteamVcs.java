package com.intellij.vcs.starteam;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ChangeProvider;
import com.intellij.openapi.vcs.checkin.CheckinEnvironment;
import com.intellij.openapi.vcs.history.VcsHistoryProvider;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.rollback.RollbackEnvironment;
import com.intellij.openapi.vcs.update.UpdateEnvironment;
import com.intellij.openapi.vfs.LocalFileOperationsHandler;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileListener;
import com.intellij.util.ThrowableConsumer;
import com.intellij.vcsUtil.VcsUtil;
import com.starteam.*;
import com.starteam.exceptions.ServerException;
import com.starteam.util.DateTime;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * todo Use StarteamFinder whereever possible
 */
public class StarteamVcs extends AbstractVcs {
  private static final Logger LOG = Logger.getInstance("#com.intellij.vcs.starteam.StarteamVcs");
  public static final String NAME = "StarTeam";
  private static final VcsKey ourKey = createKey(NAME);

  @NonNls
  private static final String VIEW_NOT_FOUND = "exception.text.configuration.view.not.found";
  @NonNls
  private static final String PROJECT_NOT_FOUND = "exception.text.configuration.project.not.found";
  @NonNls
  private static final String FILE_NOT_FOUND_IN_STARTEAM = "exception.text.configuration.file.not.found";
  @NonNls
  private static final String FOLDER_NOT_FOUND_IN_STARTEAM = "exception.text.configuration.folder.not.found";
  @NonNls
  private static final String FOLDER_NOT_FOUND_ON_DISK = "exception.text.configuration.folder.not.found.on.disk";
  @NonNls
  private static final String FILE_NOT_FOUND_ON_DISK = "exception.text.configuration.file.not.found.on.disk";
  @NonNls
  private static final String REPOSITORY_FILE_NEWER = "current copy of the object you are trying to modify is newer than your copy";

  @NonNls
  public static final String RENAMED_FOLDER_PREFIX = ".IJI.";
  @NonNls
  public static final String VERSIONED_FOLDER_SIG = ".sbas";

  private boolean safeInit = false;
  private Server myServer;
  private Project myStarteamProject;
  private View myView;
  private CheckinManager checkinManager;
  private CheckoutManager checkoutManager;

  private static final char SEP = java.io.File.separatorChar;
  private StarteamCheckinEnvironment myCheckinEnvironment;
  private StarteamEditFileProvider myEditFileProvider;
  private ChangeProvider myChangeProvider;
  private UpdateEnvironment myUpdateEnvironment;
  private VcsHistoryProvider myHistoryProvider;
  private StarteamConfiguration myConfiguration;

  private VcsShowConfirmationOption addConfirmation;
  private VcsShowConfirmationOption delConfirmation;

  private VirtualFileListener listener;
  private LocalFileOperationsHandler localFileDeletionListener;

  public Set<String> removedFiles;
  public Set<String> removedFolders;
  public Set<String> newFiles;
  public Map<String, String> renamedFiles;
  public Map<String, String> renamedDirs;
  public Map<VirtualFile, String> removedFoldersNameMap;

  public StarteamVcs(com.intellij.openapi.project.Project project,
                     StarteamConfiguration starteamConfiguration) {
    super(project, NAME);

    try {
      safeInit = true;
      myConfiguration = starteamConfiguration;
      myCheckinEnvironment = new StarteamCheckinEnvironment(project, this);
      myEditFileProvider = new StarteamEditFileProvider(this);
      myUpdateEnvironment = new StarteamUpdateEnvironment(this);
      myHistoryProvider = new StarteamHistoryProvider(this);
      myChangeProvider = new StarteamChangeProvider(myProject, this);

      removedFiles = new HashSet<>();
      removedFolders = new HashSet<>();
      renamedFiles = new HashMap<>();
      renamedDirs = new HashMap<>();
      removedFoldersNameMap = new HashMap<>();
      newFiles = new HashSet<>();
    } catch (Throwable e) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("FATAL ERROR", e);
      }
    }
  }

  public String getDisplayName() {
    return NAME;
  }

  @Override
  public String getMenuItemText() {
    return StarteamBundle.message("starteam.menu.group.text");
  }

  public static AbstractVcs getInstance(com.intellij.openapi.project.Project project) {
    return project.getComponent(StarteamVcs.class);
  }

  @Override
  public CheckinEnvironment getCheckinEnvironment() {
    return myCheckinEnvironment;
  }

  @Override
  public RollbackEnvironment getRollbackEnvironment() {
    return myCheckinEnvironment;
  }

  @Override
  public UpdateEnvironment getUpdateEnvironment() {
    return myUpdateEnvironment;
  }

  @Override
  public VcsHistoryProvider getVcsHistoryProvider() {
    return myHistoryProvider;
  }

  @Override
  public ChangeProvider getChangeProvider() {
    return safeInit ? myChangeProvider : null;
  }

  @Override
  public EditFileProvider getEditFileProvider() {
    return myEditFileProvider;
  }

  private boolean haveAlternativePath() {
    return !"".equals(myConfiguration.ALTERNATIVE_WORKING_PATH);
  }

  public Configurable getConfigurable() {
    return safeInit ? new StarteamConfigurable(myProject) : new StarteamVcsAdapter.MyConfigurable();
  }

  public VcsShowConfirmationOption getAddConfirmation() {
    return addConfirmation;
  }

  public VcsShowConfirmationOption getDelConfirmation() {
    return delConfirmation;
  }

  public void add2NewFile(VirtualFile file) {
    add2NewFile(file.getPath());
  }

  public void add2NewFile(String path) {
    newFiles.add(path.toLowerCase());
  }

  public void deleteNewFile(VirtualFile file) {
    deleteNewFile(file.getPath());
  }

  public void deleteNewFile(String path) {
    newFiles.remove(path.toLowerCase());
  }

  public boolean containsNew(String path) {
    return newFiles.contains(path.toLowerCase());
  }

  public void projectOpened() {
    if (safeInit) {
      initConfirmationOptions();
    }
  }

  public void projectClosed() {
  }

  public void disposeComponent() {
    myCheckinEnvironment = null;
  }

  @Override
  public void activate() {
    try {
      startMe();
    } catch (VcsException e) {
      LOG.info(e);
    }
    registerListeners();
    initConfirmationOptions();
  }

  @Override
  public void deactivate() {
    LocalFileSystem.getInstance().removeVirtualFileListener(listener);
    if (localFileDeletionListener != null) {
      // if null -> was not activated
      LocalFileSystem.getInstance().unregisterAuxiliaryFileOperationsHandler(localFileDeletionListener);
    }
    try {
      shutdownMe();
    } catch (VcsException e) {
      LOG.info(e);
    }
  }

  public void startMe() throws VcsException {
    try {
      LOG.debug("enter: start()");

      connect();
      findProject();

      if (myStarteamProject == null) return;
      findView();
      if (myView != null && myConfiguration.ALTERNATIVE_WORKING_PATH.length() != 0) {
        myView.setAlternatePath(myConfiguration.ALTERNATIVE_WORKING_PATH);
        if (myView.hasPermissions(new PermissionCollection(Permission.GENERIC_MODIFY_OBJECT))) {
          myView.update();
        }

        Folder root = myView.getRootFolder();
        root.setAlternatePathFragment(myConfiguration.ALTERNATIVE_WORKING_PATH);
        root.update();
      }

      LOG.debug("exit: start()");
    } catch (Throwable e) {
      if (LOG.isDebugEnabled()) LOG.debug(e);
      throw new VcsException(e);
    }
  }

  public void shutdownMe() throws VcsException {
    disconnect();
  }

  /**
   * Initialization for confirmation options may be called out from two places -
   * when project is opened and when ST is assigned for a project. Avoid duplication.
   */
  private void initConfirmationOptions() {
    StarteamVcsAdapter baseHost = StarteamVcsAdapter.getInstance(myProject);
    if (addConfirmation == null || delConfirmation == null) {
      final ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(myProject);
      addConfirmation = vcsManager.getStandardConfirmation(VcsConfiguration.StandardConfirmation.ADD, baseHost);
      delConfirmation = vcsManager.getStandardConfirmation(VcsConfiguration.StandardConfirmation.REMOVE, baseHost);
    }
  }

  private void registerListeners() {
    //  Control the appearance of project items so that we can easily
    //  track down potential changes in the repository.
    listener = new VFSListener(this, myProject);
    LocalFileSystem.getInstance().addVirtualFileListener(listener);

    //  Track changes in the file system. Control the folder deletion -
    //  do not allow direct removal without special actions.
    localFileDeletionListener = new STFileSystemListener();
    LocalFileSystem.getInstance().registerAuxiliaryFileOperationsHandler(localFileDeletionListener);
  }

  private void connect() {
    if (LOG.isDebugEnabled()) {
      LOG.debug("creating server instance: " + myConfiguration.SERVER + ":" + myConfiguration.PORT);
    }
    myServer = new Server(myConfiguration.SERVER, myConfiguration.PORT);

    if (LOG.isDebugEnabled()) {
      LOG.debug("logging in: " + myConfiguration.USER + "@" + "**********");
    }
    myServer.logOn(myConfiguration.USER, myConfiguration.getPassword());
    if (myConfiguration.ENABLE_CACHE_AGENT) {
      LOG.debug("locate cache agent instance: " + myConfiguration.CACHE_AGENT_SERVER + ":" + myConfiguration.CACHE_AGENT_PORT);
      myServer.locateCacheAgent(myConfiguration.CACHE_AGENT_SERVER, myConfiguration.CACHE_AGENT_PORT);
    }

    Notifications.Bus.notify(new Notification("StarTeam", "StarTeam connected",
        myConfiguration.SERVER + ":" + myConfiguration.PORT,
        NotificationType.INFORMATION));
  }

  private void disconnect() {
    myView = null;

    LOG.debug("disconnecting");
    if (myServer != null) {
      myServer.disconnect();
    }
    myServer = null;

    Notifications.Bus.notify(new Notification("StarTeam", "StarTeam disconnected",
        "Successfully disconnected to server", NotificationType.INFORMATION));
  }

  private void findView() throws VcsException {
    final View[] views = myStarteamProject.getViews();
    for (View view : views) {
      if (view.getName().equals(myConfiguration.VIEW)) {
        myView = view;

        CheckinOptions ciOptions = new CheckinOptions(myView);
        ciOptions.setLockType(ViewMember.LockType.UNCHANGED);
        ciOptions.setEOLFormat(File.EOLFormat.PLATFORM);
        ciOptions.setUpdateStatus(true);
        ciOptions.setRestoreFileOnError(false);
        checkinManager = myView.createCheckinManager();

        CheckoutOptions coOptions = new CheckoutOptions(myView);
        coOptions.setLockType(Item.LockType.UNCHANGED);
        coOptions.setEOLFormat(File.EOLFormat.PLATFORM);
        coOptions.setUpdateStatus(true);
        coOptions.setTimeStampNow(false);
        coOptions.setMarkUnlockedFilesReadOnly(true);
        checkoutManager = myView.createCheckoutManager(coOptions);
        if (LOG.isDebugEnabled()) {
          LOG.debug("found view: " + myConfiguration.VIEW);
        }
        return;
      }
    }

    error(VIEW_NOT_FOUND, myConfiguration.VIEW);
  }

  private void findProject() throws VcsException {
    final Project[] projects = myServer.getProjects();
    for (Project project : projects) {
      if (project.getName().equals(myConfiguration.PROJECT)) {
        myStarteamProject = project;
        if (LOG.isDebugEnabled()) {
          LOG.debug("found project: " + myConfiguration.PROJECT);
        }
        return;
      }
    }

    error(PROJECT_NOT_FOUND, myConfiguration.PROJECT);
  }

  public boolean checkinFile(String path, Object parameters, Map userData) throws VcsException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("enter: checkinFile(file='" + path + "')");
    }

    File f = getFile(path);

    try {
//      updateStatus( f );  !!! do not uncomment !!!
      /** Explicitely set modification date to the current one so that server
       * can determine that this file is newer than that in the repository.
       * Otherwise server responds <code>"update operation could not be completed because
       * the current copy of the object you are trying to modify is newer than your copy.
       * Please refresh and try again"</code>.
       *
       * NB: DONOT (!) call an updateStatus method before or after the modification
       *     time is set because this produces a new revision in the repository.
       *     [Complete shit behavior]
       */
      f.setContentModifiedTime(new DateTime(new Date()));

      if (LOG.isDebugEnabled()) {
        LOG.debug("fileStatus:" + f.getStatus().getDisplayName());
      }

      File.Status status = f.getStatus();
      if (status == File.Status.MERGE || status == File.Status.OUT_OF_DATE) {
        return false;
      } else if (status != File.Status.CURRENT) {
        //This is workaround for the following ST 5.1 bug:
        // Create a file. Add it to ST. Modify file. Check it in.
        // Change the file content back to 1 revision (e.g. delete a line added in previous step)
        // ST will report that file status is "Out of Date" or "Unknown".
        // This seems to be a StarGate SDK problem - Win32 client works OK
        // This force check in shouldn't cause any problem, cause IDEA is quite sure that status is Modified.
        // If the file were really out of date - the status would be Merge
        // todo Any hints on fixing it other way?
        String comment = (String) parameters;
        CheckinOptions ciOptions = new CheckinOptions(myView);
        ciOptions.setCheckinReason(comment);
        ciOptions.setLockType(Item.LockType.UNCHANGED);
        ciOptions.setForceCheckin(status == File.Status.UNKNOWN);
        ciOptions.setEOLFormat(File.EOLFormat.PLATFORM);
        ciOptions.setUpdateStatus(true);
        ciOptions.setRestoreFileOnError(false);
        checkinManager.setOptions(ciOptions);
        checkinManager.checkinFrom(f, new java.io.File(path.replace('/', SEP)));
        commitCheckin();

        if (myConfiguration.UNLOCK_ON_CHECKIN) {
          unlockFile(f);
        }
      }
    } catch (Exception e) {
      LOG.debug(e);

      //  In the case exception shows only the conflict between local and
      //  repository versions of the file - just return "false" in order to
      //  notify the user about checkin failure. Otherwise (smth serious like
      //  broken connection) - propagate the exception further.
      if (e.getMessage().indexOf(REPOSITORY_FILE_NEWER) != -1) {
        return false;
      }

      throw new VcsException(e);
    }
    return true;
  }

  public boolean checkoutFile(String path) throws VcsException {
    return checkoutFile(path, true);
  }

  public boolean checkoutFile(String path, boolean verbose) throws VcsException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("enter: checkoutFile(file='" + path + "')");
    }

    File f = getFile(path);

    return checkoutFile(f, verbose);
  }

  public boolean checkoutFile(File file, boolean verbose) throws VcsException {
    @NonNls final String message = "The revision being added is the same as the most recent revision";
    try {
      //  Ignore the exception on updateStatus when the file has been just modified
      //  and does not require status update at all.
      //  NB: do we need this call to "updateStatus" at all? What are the particular
      //      cases when file statuses are really not up to date?
      try {
        updateStatus(file);
      } catch (ServerException e) {
        if (e.getErrorMessage().indexOf(message) == -1) {
          throw e;
        }
      }

      final File.Status status = file.getStatus();
      if (status != File.Status.CURRENT && status != File.Status.OUT_OF_DATE && verbose) {
        int result = Messages.showYesNoDialog(StarteamBundle.message("confirmation.text.checkout.file.changed", file.getFullName()),
            StarteamBundle.message("confirmation.title"),
            Messages.getWarningIcon());
        if (result != 0) {
          return false;
        } else {
          checkoutManager.getOptions().setForceCheckout(true);
        }
      }

      if (!"".equals(myConfiguration.ALTERNATIVE_WORKING_PATH)) {
        java.io.File checkoutTo = new java.io.File(file.getFullName());
        checkoutManager.checkoutTo(file, checkoutTo);
//        file.checkoutTo(checkoutTo, Item.LockType.UNCHANGED, true, false, true);
      } else {
        checkoutManager.checkout(file);
//        file.checkout(Item.LockType.UNCHANGED, true, false, true);
      }

      commitCheckout();
      checkoutManager.getOptions().setForceCheckout(false);

      if (myConfiguration.LOCK_ON_CHECKOUT) {
        lockFile(file);
      }

      return true;
    } catch (Exception e) {
      LOG.debug(e);
      throw new VcsException(e);
    }
  }

  public void checkoutFolder(Folder folder) throws VcsException {
    File[] files = getFiles(folder);
    for (File file : files) {
      checkoutFile(file, false);
    }

    Folder[] subfolders = getSubFolders(folder);
    for (Folder sub : subfolders) {
      checkoutFolder(sub);
    }
  }

  public VcsRevisionNumber getFileRevision(File f) {
    return new VcsRevisionNumber.Int(f.getContentVersion());
  }

  public byte[] getFileContent(String path) throws VcsException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("enter: getFileContent(file='" + path + "')");
    }

    File f = getFile(path);

    return getFileContent(f);
  }

  public byte[] getFileContent(File f) throws VcsException {
    ByteArrayOutputStream inputStream = new ByteArrayOutputStream();

    try {
      checkoutManager.checkoutTo(f, inputStream);
      commitCheckout();
    } catch (Exception e) {
      LOG.debug(e);
      throw new VcsException(e);
    }

    return inputStream.toByteArray();
  }

  @NotNull
  public File getFile(String path) throws VcsException {
    refresh();
    File f = findFile(path);
    if (f == null) {
      error(FILE_NOT_FOUND_IN_STARTEAM, path);
    }
    return f;
  }

  public void lockFile(String path) throws VcsException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("enter: lockFile(file='" + path + "')");
    }

    File f = getFile(path);

    lockFile(f);
  }

  private static void lockFile(File file) throws VcsException {
    try {
      file.lockNonExclusive();
    } catch (Exception e) {
      LOG.debug(e);
      throw new VcsException(e);
    }
  }

  public void unlockFile(String path) throws VcsException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("enter: unlockFile(file='" + path + "')");
    }

    File f = getFile(path);

    unlockFile(f);
  }

  private static void unlockFile(File file) throws VcsException {
    try {
      file.unlock();
    } catch (Exception e) {
      LOG.debug(e);
      throw new VcsException(e);
    }
  }

  public boolean existsFile(String path) {
    return findFile(path.replace('/', java.io.File.separatorChar)) != null;
  }

  public boolean existsFolder(String path) {
    return findFolder(path.replace('/', java.io.File.separatorChar)) != null;
  }

  @Nullable
  public File findFile(String path) {
    if (myView == null) return null;

    boolean sensitive = SystemInfo.isFileSystemCaseSensitive;
    path = path.replace('/', SEP);

    Folder folder;
    if (path.indexOf(SEP) >= 0) {
      String folderPath = path.substring(0, path.lastIndexOf(SEP));
      folder = findFolder(folderPath);
      if (folder == null) return null;
    } else {
      folder = myView.getRootFolder();
    }

    String fileName = path.substring(path.lastIndexOf(SEP) + 1);

    final File[] files = getFiles(folder);
    for (File f : files) {
      if (sensitive && f.getName().equals(fileName)) return f;
      if (!sensitive && f.getName().equalsIgnoreCase(fileName)) return f;
    }

    return null;
  }

  @Nullable
  public Folder findFolder(String path) {
    Folder folder = null;
    if (myView == null) return null;

    //  Convert a path to a Starbase uniform representation.
    path = path.replace('/', java.io.File.separatorChar);
    path = normalizePath(path);

    folder = myView.getRootFolder();
    String currentPath = haveAlternativePath() ? myConfiguration.ALTERNATIVE_WORKING_PATH : folder.getPath();

    currentPath = normalizePath(currentPath);

    if (!path.startsWith(currentPath)) return null;

    main:
    while (folder != null && !currentPath.equals(path)) {
      final Folder[] folders = getSubFolders(folder);
      folder = null;

      for (Folder f : folders) {
        String p = normalizePath(currentPath + f.getName());

        if (path.startsWith(p)) {
          folder = f;
          currentPath = p;
          continue main;
        }
      }
    }

    if (folder != null && haveAlternativePath())
      folder.setAlternatePathFragment(currentPath);

    return folder;
  }

  private static String normalizePath(String path) {
    if (!SystemInfo.isFileSystemCaseSensitive) path = path.toLowerCase();
    if (!path.endsWith(java.io.File.separator)) path += java.io.File.separator;
    return path;
  }

  public File.Status updateStatus(VirtualFile file) throws IOException {
    File f = findFile(file.getPath().replace('/', java.io.File.separatorChar));
    if (f != null) {
      updateStatus(f);
    }
    return f.getStatus();
  }

  private static void updateStatus(File f) throws IOException {
    f.updateStatus();
    f.update();
  }

  public void addFile(String folderPath, String fileName, Object parameters, Map userData) throws VcsException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("enter: addFile(folderPath='" + folderPath + "' name='" + fileName + "')");
    }

    String comment = (String) parameters;
    refresh();
    final Folder folder = findFolder(folderPath);
    if (folder == null) error(FOLDER_NOT_FOUND_IN_STARTEAM, folderPath);

    folder.refreshItems(myServer.getTypes().FILE, null, 0);

    if (findFile(folderPath + SEP + fileName) != null) return;

    java.io.File ioFolder = new java.io.File(folderPath);
    if (!ioFolder.exists()) error(FOLDER_NOT_FOUND_ON_DISK, folderPath);
    java.io.File ioFile = new java.io.File(ioFolder, fileName);
    if (!ioFile.exists()) error(FILE_NOT_FOUND_ON_DISK, folderPath + SEP + fileName);


    try {
      final File file = makeFile(folder, fileName);
      CheckinOptions ciOptions = new CheckinOptions(myView);
      ciOptions.setCheckinReason(comment);
      ciOptions.setLockType(ViewMember.LockType.UNLOCKED);
      ciOptions.setUpdateStatus(true);
      ciOptions.setEOLFormat(File.EOLFormat.PLATFORM);
      ciOptions.setRestoreFileOnError(false);
      checkinManager.checkin(file, ciOptions);
      commitCheckin();
//      file.addAndReturn(ioFile, fileName, "", comment, Item.LockType.UNLOCKED, false, true);
      folder.refreshItems(myServer.getTypes().FILE, null, 0);
    } catch (Exception e) {
      LOG.debug(e);
      throw new VcsException(e);
    }
  }


  public void renameAndCheckInFile(String filePath, String newName, Object parameters) throws VcsException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("enter: renameFile(filePath='{}' newName='{}')", filePath, newName);
    }
    String comment = (String) parameters;

    final File f = getFile(filePath);

    try {
      final Folder folder = f.getParentFolder();
      f.setName(newName);
      boolean forceCheckIn = false;
      if (f.getStatus() == File.Status.UNKNOWN) {
        forceCheckIn = true;
      }
      CheckinOptions ciOptions = new CheckinOptions(myView);
      ciOptions.setCheckinReason(comment);
      ciOptions.setLockType(ViewMember.LockType.UNCHANGED);
      ciOptions.setForceCheckin(forceCheckIn);
      ciOptions.setEOLFormat(File.EOLFormat.PLATFORM);
      ciOptions.setUpdateStatus(true);
      ciOptions.setRestoreFileOnError(false);
      checkinManager.checkin(f, ciOptions);
      commitCheckin();
//      f.checkinFrom(new java.io.File((folder.getPath() + "/" + newName).replace('/', SEP)), comment, Item.LockType.UNCHANGED, forceCheckIn, false, true);
      folder.refreshItems(myServer.getTypes().FILE, null, 0);
    } catch (Exception e) {
      LOG.debug(e);
      throw new VcsException(e);
    }
  }

  public void setWorkingFolderName(String path, String newName) throws VcsException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("enter: setWorkingFolderName(path='{}' newName='{}')", path, newName);
    }

    refresh();
    final Folder folder = findFolder(path);
    if (folder == null) error(FOLDER_NOT_FOUND_IN_STARTEAM, path);

    final String newPath = folder.getParentFolder().getPath() + SEP + newName;
    final Folder newFolder = findFolder(newPath);
    if (newFolder != null) return;

    try {
      folder.setDefaultPathFragment(newName);
      folder.update();

      final File[] files = getFiles(folder);
      for (File file : files) updateStatus(file);
      folder.refreshItems(myServer.getTypes().FILE, null, 0);
    } catch (Exception e) {
      LOG.debug(e);
      throw new VcsException(e);
    }
  }

  public void renameDirectoryNew(String path, String newName) throws VcsException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("enter: setWorkingFolderName(path='" + path + "' newName='" + newName + "')");
    }

    refresh();
    final Folder folder = findFolder(path);
    if (folder == null) error(FOLDER_NOT_FOUND_IN_STARTEAM, path);

    /*
    final String newPath = folder.getParentFolder().getPath() + SEP + newName;
    final Folder newFolder = findFolder(newPath);
    if (newFolder != null) error(FOLDER_ALREADY_PRESENT_IN_STARTEAM, path);
    */

    try {
      folder.setName(newName);
      folder.update();

      final File[] files = getFiles(folder);
      for (int i = 0; i < files.length; i++)
        updateStatus(files[i]);
      folder.refreshItems(myServer.getTypes().FILE, null, 0);
    } catch (Exception e) {
      LOG.debug(e);
      throw new VcsException(e);
    }
  }

  public void renameDirectory(String path, String newName, Object parameters) throws VcsException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("enter: renameDirectory(path='" + path + "' newName='" + newName + "')");
    }

    refresh();
    final Folder folder = findFolder(path);
    if (folder == null) error(FOLDER_NOT_FOUND_IN_STARTEAM, path);

    final String newPath = folder.getParentFolder().getPath() + SEP + newName;
    final Folder newFolder = findFolder(newPath);
    if (newFolder != null) {
      moveContent(folder, path, newPath, newName, parameters);
      return;
    }

    try {
//      String oldName = folder.getName();
//      final Folder parentFolder = folder.getParentFolder();
      folder.setName(newName);
      folder.setDefaultPathFragment(newName);
      folder.update();

      //todo check the option about leaving empty folders here
//      copyDirectoryStructure(folder, addFolder(parentFolder, oldName));

      //  Problem: we use "delayed" operation of folder rename, so
      //  it is really hard to synchronize file statuses BEFORE the StarTeam
      //  folder is really renamed.
      /*
      final File[] files = getFiles(folder);
      for(int i = 0; i < files.length; i++){
        File file = files[i];
        moveRenameAndCheckInFile(folderPath + SEP + file.getName(), newFolderPath, file.getName(), parameters);
      }
      */
    } catch (Exception e) {
      LOG.debug(e);
      throw new VcsException(e);
    }
  }

  public void moveAndRenameDirectory(String path, String newParentPath, String name, Object parameters) throws VcsException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("enter: moveFile(path='" + path + "' newParentPath='" + newParentPath + "')");
    }

    refresh();
    final Folder folder = findFolder(path);
    if (folder == null) error(FOLDER_NOT_FOUND_IN_STARTEAM, path);

    final Folder newParent = findFolder(newParentPath);
    if (newParent == null) error(FOLDER_NOT_FOUND_IN_STARTEAM, newParentPath);

    final String newFolderPath = newParentPath + SEP + folder.getName();
    final Folder newFolder = findFolder(newFolderPath);
    if (newFolder != null) {
      moveContent(folder, path, newFolderPath, name, parameters);
      return;
    }

    Folder oldFolder = folder.getParentFolder();

    try {
      folder.moveTo(newParent);
//      folder.update();

      newParent.refreshItems(myServer.getTypes().FILE, null, 0);
      oldFolder.refreshItems(myServer.getTypes().FILE, null, 0);
//      newParent.refreshItems(newParent.getTypeNames().FOLDER, null, 1);
//      oldFolder.refreshItems(oldFolder.getTypeNames().FOLDER, null, 1);


      //todo check the option about leaving empty folders here
      copyDirectoryStructure(folder, addFolder(oldFolder, folder.getName()));
    } catch (Exception e) {
      LOG.debug(e);
      throw new VcsException(e);
    }
  }

  private void moveContent(final Folder folder, String folderPath, final String newFolderPath,
                           String newName, Object parameters) throws VcsException {
    final Folder[] subFolders = getSubFolders(folder);
    java.io.File newParent = new java.io.File(newFolderPath).getParentFile();
    java.io.File newDirectory = new java.io.File(newParent, newName);

    for (Folder subFolder : subFolders) {
      moveAndRenameDirectory(folderPath + SEP + subFolder.getName(), newDirectory.getPath(), newName, parameters);
    }

    final File[] files = getFiles(folder);
    for (File file : files) {
      moveRenameAndCheckInFile(folderPath + SEP + file.getName(), newFolderPath, file.getName(), parameters);
    }
  }

  private void copyDirectoryStructure(Folder fromFolder, Folder toFolder) {
    final Folder[] folders = getSubFolders(fromFolder);
    for (Folder f : folders)
      copyDirectoryStructure(f, addFolder(toFolder, f.getName()));
  }

  public void moveRenameAndCheckInFile(String filePath, String newParentPath, String newName, Object parameters) throws VcsException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("enter: moveRenameAndCheckInFile(filePath='" + filePath + "' newFilePath='" + newParentPath + "', newName='" + newName + "')");
    }

    String comment = (String) parameters;
    final File f = getFile(filePath);

    final Folder newFolder = findFolder(newParentPath);
    if (newFolder == null) error(FOLDER_NOT_FOUND_IN_STARTEAM, newParentPath);

    Folder oldFolder = f.getParentFolder();

    try {
      f.moveTo(newFolder);
      f.setName(newName);

      CheckinOptions ciOptions = new CheckinOptions(myView);
      ciOptions.setCheckinReason(comment);
      ciOptions.setLockType(ViewMember.LockType.UNCHANGED);
      ciOptions.setEOLFormat(File.EOLFormat.PLATFORM);
      ciOptions.setUpdateStatus(true);
      ciOptions.setRestoreFileOnError(false);
      checkinManager.setOptions(ciOptions);
      checkinManager.checkinFrom(f, new java.io.File((newParentPath + "/" + newName).replace('/', SEP)));
//      f.checkinFrom(new java.io.File((newParentPath + "/" + newName).replace('/', SEP)), comment, Item.LockType.UNCHANGED, true, false, true);
      commitCheckin();
      newFolder.refreshItems(myServer.getTypes().FILE, null, 0);
      oldFolder.refreshItems(myServer.getTypes().FILE, null, 0);
    } catch (Exception e) {
      LOG.debug(e);
      throw new VcsException(e);
    }
  }

  public void addDirectory(String parentPath, String name, Object parameters) throws VcsException {
    if (LOG.isDebugEnabled())
      LOG.debug("enter: addDirectory(parentPath='" + parentPath + "' name='" + name + "')");

    refresh();
    final Folder parent = findFolder(parentPath);
    if (parent == null) error(FOLDER_NOT_FOUND_IN_STARTEAM, parentPath);

    try {
      addFolder(parent, name);
    } catch (Exception e) {
      LOG.debug(e);
      throw new VcsException(e);
    }
  }

  public void refresh() throws VcsException {
    if (LOG.isDebugEnabled()) LOG.debug("enter: refresh()");

    try {
      myView.refreshFolders();
    } catch (Exception e) {
      LOG.debug(e);
      throw new VcsException(e);
    }
  }

  private Folder addFolder(final Folder parentFolder, String name) {
    final Folder[] folders = getSubFolders(parentFolder);

    for (Folder folder : folders) {
      if (folder.getName().equals(name) && folder.getParentFolder().equals(parentFolder))
        return folder;
    }

    final Folder folder = new Folder(parentFolder);
    folder.setName(name);
    folder.setDefaultPathFragment(name);
    folder.update();
    return folder;
  }

  public Folder[] getSubFolders(Folder folder) {
    final ViewMemberCollection items = folder.getItems(myServer.getTypes().FOLDER);
    if (!items.getCache().isPopulated()) items.getCache().populate();
    Folder[] result = new Folder[items.size()];
    System.arraycopy(items.toArray(), 0, result, 0, items.size());
    return result;
  }

  public File[] getFiles(Folder folder) {
    final ViewMemberCollection items = folder.getItems(myServer.getTypes().FILE);
    if (!items.getCache().isPopulated()) items.getCache().populate();
    File[] result = new File[items.size()];
    System.arraycopy(items.toArray(), 0, result, 0, items.size());
    return result;
  }

  public void refreshFolder(Folder folder) {
    folder.refreshItems(myServer.getTypes().FILE, null, -1);
  }

  public boolean isFileIgnored(VirtualFile file) {
    ChangeListManager mgr = ChangeListManager.getInstance(myProject);
    return (file != null) && mgr.isIgnoredFile(file);
  }

  @Override
  public boolean fileIsUnderVcs(FilePath path) {
    return fileIsUnderVcs(path.getVirtualFile());
  }

  public boolean fileIsUnderVcs(VirtualFile file) {
    //  Pay attention to the cases when no Starteam configuration has been made yet
    //  (or a new project is created after some other Starteam project was closed).
    if (myView == null)
      return false;

    String path = file.getPath().replace('/', java.io.File.separatorChar);
    String rootPath = getRootFolderPath();
    return (rootPath != null) && path.startsWith(rootPath);
  }

  @Nullable
  private String getRootFolderPath() {
    if (myView == null) return null;

    Folder folder = myView.getRootFolder();
    return haveAlternativePath() ? myConfiguration.ALTERNATIVE_WORKING_PATH : folder.getPath();
  }

  public static String getMessage(Throwable e) {
    String message = e.getLocalizedMessage();
    if (message == null) message = e.getMessage();
    if (message == null) message = e.getClass().getName();
    return message;
  }

  @Override
  public boolean isVersionedDirectory(VirtualFile dir) {
    final VirtualFile versionFile = dir.findChild(VERSIONED_FOLDER_SIG);
    return (versionFile != null && versionFile.isDirectory());
  }

  private static void error(String key, String param1) throws VcsException {
    throw new VcsException(StarteamBundle.message(key, param1));
  }

  private class STFileSystemListener implements LocalFileOperationsHandler {
    public boolean delete(VirtualFile file) throws IOException {
      StarteamVcsAdapter baseHost = StarteamVcsAdapter.getInstance(myProject);
      if (myStarteamProject != null && file.isDirectory() &&
          VcsUtil.isFileForVcs(file, myProject, baseHost)) {
        String newFolderName = file.getParent().getPath() + java.io.File.separatorChar +
            RENAMED_FOLDER_PREFIX + file.getName();
        java.io.File oldFolder = new java.io.File(file.getPath());
        java.io.File newFolder = new java.io.File(newFolderName);

        try {
          FileUtil.rename(oldFolder, newFolder);
        } catch (IOException e) {
          LOG.error("Cannot rename " + oldFolder.getName() + " to " + newFolderName, e);
          return false;
        }
        return true;
      }
      return false;
    }

    public boolean move(VirtualFile file, VirtualFile toDir) throws IOException {
      return false;
    }

    public java.io.File copy(VirtualFile file, VirtualFile toDir, final String copyName) throws IOException {
      return null;
    }

    public boolean rename(VirtualFile file, String newName) throws IOException {
      return false;
    }

    public boolean createFile(VirtualFile dir, String name) throws IOException {
      return false;
    }

    public boolean createDirectory(VirtualFile dir, String name) throws IOException {
      return false;
    }

    public void afterDone(final ThrowableConsumer<LocalFileOperationsHandler, IOException> invoker) {
    }
  }

  public static VcsKey getKey() {
    return ourKey;
  }

  /*
  //
  // JDOMExternalizable methods
  //

  public void readExternal(final Element element) throws InvalidDataException
  {
      java.util.List files = element.getChildren( PERSISTENCY_REMOVED_TAG );
      for (Object cclObj : files)
      {
        if (cclObj instanceof Element)
        {
          final Element currentCLElement = ((Element)cclObj);
          final String path = currentCLElement.getValue();

          // Safety check - file can be added again between IDE sessions.
          if( ! new java.io.File( path ).exists() )
            removedFiles.add( path );
        }
      }

      files = element.getChildren( PERSISTENCY_RENAMED_TAG );
      for (Object cclObj : files)
      {
        if (cclObj instanceof Element)
        {
          final Element currentCLElement = ((Element)cclObj);
          final String pathPair = currentCLElement.getValue();
          int delimIndex = pathPair.indexOf( PATH_DELIMITER );
          if( delimIndex != -1 )
          {
            final String newName = pathPair.substring( 0, delimIndex );
            final String oldName = pathPair.substring( delimIndex + PATH_DELIMITER.length() );

            // Safety check - file can be deleted or changed between IDE sessions.
            if( new java.io.File( newName ).exists() )
              renamedFiles.put( newName, oldName );
          }
        }
      }

      files = element.getChildren( PERSISTENCY_NEW_FILE_TAG );
      for (Object cclObj : files)
      {
        if (cclObj instanceof Element)
        {
          final Element currentCLElement = ((Element)cclObj);
          final String path = currentCLElement.getValue();

          // Safety check - file can be deleted or changed between IDE sessions.
          if( new java.io.File( path ).exists() )
            newFiles.add( path.toLowerCase() );
        }
      }
  }

  public void writeExternal(final Element element) throws WriteExternalException
  {
    writeExternalElement( element, removedFiles, PERSISTENCY_REMOVED_TAG );
    writeExternalElement( element, removedFolders, PERSISTENCY_REMOVED_TAG );
    writeExternalElement( element, newFiles, PERSISTENCY_NEW_FILE_TAG );

    for( String file : renamedFiles.keySet() )
    {
      final Element listElement = new Element( PERSISTENCY_RENAMED_TAG );
      final String pathPair = file.concat( PATH_DELIMITER ).concat( renamedFiles.get( file ) );

      listElement.addContent( pathPair );
      element.addContent( listElement );
    }
  }

  private static void writeExternalElement( final Element element, HashSet<String> files, String tag )
  {
    //  Sort elements of the list so that there is no perturbation in .ipr/.iml
    //  files in the case when no data has changed.
    String[] sorted = files.toArray( new String[ files.size() ] );
    Arrays.sort( sorted );

    for( String file : sorted )
    {
      final Element listElement = new Element( tag );
      listElement.addContent( file );
      element.addContent( listElement );
    }
  }
  */
  private static void initPropertyValue(Item item, Property p, String itemname) {
    if (p instanceof TextProperty) {
      String value = item.getStringValue(p);
      if ("".equals(value)) {
        int n = ((TextProperty) p).getMaxLength();
        if (itemname.length() > n) {
          itemname = itemname.substring(0, n);
        }
        item.setStringValue(p, itemname);
        return;
      }
    }
    updatePropertyValue(item, p);
  }

  private static void updatePropertyValue(Item item, Property p) {
    if (p instanceof TextProperty) {
      String value = item.getStringValue(p);
      int n = ((TextProperty) p).getMaxLength() - 1;
      if (value.length() > n) {
        value = value.substring(0, n);
      }
      item.setStringValue(p, value + "!");

    } else if (p instanceof IntegerProperty) {
      IntegerProperty property = (IntegerProperty) p;
      int n = item.getIntegerValue(p);
      if (n < property.getMaxValue()) {
        item.setIntegerValue(p, n + 1);
      } else if (n > property.getMaxValue()) {
        item.setIntegerValue(p, n - 1);
      }

    } else if (p instanceof LongIntegerProperty) {
      LongIntegerProperty property = (LongIntegerProperty) p;
      long n = item.getLongValue(p);
      if (n < property.getMaxValue()) {
        item.setLongValue(p, n + 1);
      } else if (n > property.getMaxValue()) {
        item.setLongValue(p, n - 1);
      }

    } else if (p instanceof BooleanProperty) {
      item.setBooleanValue(p, !item.getBooleanValue(p));

    } else if (p instanceof EnumeratedProperty) {
      EnumeratedValue[] vv = item.getEnumeratedValues(p);
      EnumeratedValue oldvalue = vv.length == 0 ? null : vv[0];
      EnumeratedValue[] values = ((EnumeratedProperty) p).getAllValues();
      for (int i = 0; i < values.length; i++) {
        EnumeratedValue next = values[i];
        if (next.isEnabled() && !next.equals(oldvalue)) {
          item.setEnumeratedValues(p, new EnumeratedValue[]{next});
          break;
        }
      }
    }
  }

  public static Property[] initProperties(Item item, String name) {
    List<Property> prprtys = new ArrayList();
    Property[] properties = (Property[]) item.getType().getProperties()
        .toArray(new Property[0]);
    for (int i = 0; i < properties.length; i++) {
      Property p = properties[i];
      if (p.isEnabled() && !p.isCalculated()) {
        if (p.isPrimaryDescriptor() || p.isDescriptor()
            || p.isRequired()) {
          initPropertyValue(item, p, name);
          prprtys.add(p);
        }
      }
      // if -trash is used, a DateTimeProperty is being created
      // which causes all Topic Tests to break
      // This workaround allows the test suite to succeed
      if (p.isUserCustomized() && p instanceof DateTimeProperty) {
        item.setDateTimeValue(p, com.starteam.util.DateTime.now());
      }
    }
    return prprtys.toArray(new Property[prprtys.size()]);
  }

  public File makeFile(Folder parent, String name) {
    File.Type flTyp = myServer.getTypes().FILE;
    File file = (File) flTyp.createItem(parent);
    file.setName(name);
    return file;
  }


  private void commitCheckin() {
    if (checkinManager.canCommit()) {
      checkinManager.commit();
    } else {
      LOG.debug("checkinManager cannot commit, force commit");
      checkinManager.commit();
    }
  }

  private void commitCheckout() {
    if (checkoutManager.canCommit()) {
      checkoutManager.commit();
    } else {
      LOG.debug("checkoutManager cannot commit, force commit");
      checkoutManager.commit();
    }
  }

}
