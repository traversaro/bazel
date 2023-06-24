// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.google.devtools.build.lib.remote;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.Streams.stream;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionInputMap;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher.Priority;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileArtifactValue.RemoteFileArtifactValue;
import com.google.devtools.build.lib.actions.FileStatusWithMetadata;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.cache.MetadataInjector;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.vfs.DelegateFileSystem;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.inmemoryfs.FileInfo;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryContentInfo;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.ReadableByteChannel;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * This is a basic implementation and incomplete implementation of an action file system that's been
 * tuned to what internal (non-spawn) actions in Bazel currently use.
 *
 * <p>The implementation mostly delegates to the local file system except for the case where an
 * action input is a remotely stored action output. Most notably {@link
 * #getInputStream(PathFragment)} and {@link #createSymbolicLink(PathFragment, PathFragment)}.
 *
 * <p>This implementation only supports creating local action outputs.
 */
public class RemoteActionFileSystem extends DelegateFileSystem {
  private final PathFragment execRoot;
  private final PathFragment outputBase;
  private final InputMetadataProvider fileCache;
  private final ActionInputMap inputArtifactData;
  private final ImmutableMap<PathFragment, Artifact> outputMapping;
  private final RemoteActionInputFetcher inputFetcher;
  private final RemoteInMemoryFileSystem remoteOutputTree;

  @Nullable private ActionExecutionMetadata action = null;
  @Nullable private MetadataInjector metadataInjector = null;

  RemoteActionFileSystem(
      FileSystem localDelegate,
      PathFragment execRootFragment,
      String relativeOutputPath,
      ActionInputMap inputArtifactData,
      Iterable<Artifact> outputArtifacts,
      InputMetadataProvider fileCache,
      RemoteActionInputFetcher inputFetcher) {
    super(localDelegate);
    this.execRoot = checkNotNull(execRootFragment, "execRootFragment");
    this.outputBase = execRoot.getRelative(checkNotNull(relativeOutputPath, "relativeOutputPath"));
    this.inputArtifactData = checkNotNull(inputArtifactData, "inputArtifactData");
    this.outputMapping =
        stream(outputArtifacts).collect(toImmutableMap(Artifact::getExecPath, a -> a));
    this.fileCache = checkNotNull(fileCache, "fileCache");
    this.inputFetcher = checkNotNull(inputFetcher, "inputFetcher");
    this.remoteOutputTree = new RemoteInMemoryFileSystem(getDigestFunction());
  }

  @VisibleForTesting
  protected RemoteInMemoryFileSystem getRemoteOutputTree() {
    return remoteOutputTree;
  }

  @VisibleForTesting
  protected FileSystem getLocalFileSystem() {
    return delegateFs;
  }

  /** Returns true if {@code path} is a file that's stored remotely. */
  boolean isRemote(Path path) {
    return isRemote(path.asFragment());
  }

  private boolean isRemote(PathFragment path) {
    return getRemoteMetadata(path) != null;
  }

  public void updateContext(ActionExecutionMetadata action, MetadataInjector metadataInjector) {
    this.action = action;
    this.metadataInjector = metadataInjector;
  }

  void injectRemoteFile(PathFragment path, byte[] digest, long size, long expireAtEpochMilli)
      throws IOException {
    if (!isOutput(path)) {
      return;
    }
    var metadata =
        RemoteFileArtifactValue.create(digest, size, /* locationIndex= */ 1, expireAtEpochMilli);
    remoteOutputTree.injectFile(path, metadata);
  }

  void flush() throws IOException, InterruptedException {
    checkNotNull(metadataInjector, "metadataInjector is null");

    for (Map.Entry<PathFragment, Artifact> entry : outputMapping.entrySet()) {
      PathFragment path = execRoot.getRelative(entry.getKey());
      Artifact output = entry.getValue();

      maybeInjectMetadataForSymlinkOrDownload(path, output);
    }
  }

  /**
   * Inject metadata for non-symlink outputs that were materialized as a symlink to a remote
   * artifact, and download the target artifact if required by the remote output mode.
   *
   * <p>If a non-symlink output is materialized as a symlink, the symlink has "copy" semantics,
   * i.e., the output metadata is identical to that of the symlink target. For these artifacts, we
   * inject their metadata instead of collecting it from the filesystem. This is done for two
   * reasons:
   *
   * <ul>
   *   <li>It avoids implementing filesystem operations for resolving symlinks and (in the case of a
   *       tree artifact) listing directories, which are especially tricky since the symlink and its
   *       target may reside on different filesystems;
   *   <li>It lets us add a special field to the output metadata to tell the input prefetcher that
   *       the output should be materialized as a symlink to the original location, which avoids
   *       fetching multiple copies when multiple symlinks to the same artifact are created in the
   *       same build.
   */
  private void maybeInjectMetadataForSymlinkOrDownload(PathFragment linkPath, Artifact output)
      throws IOException {
    if (output.isSymlink()) {
      return;
    }

    Path outputTreePath = remoteOutputTree.getPath(linkPath);

    if (!outputTreePath.exists(Symlinks.NOFOLLOW)) {
      return;
    }

    PathFragment targetPath;
    try {
      targetPath = outputTreePath.readSymbolicLink();
    } catch (NotASymlinkException e) {
      return;
    }

    checkState(
        targetPath.isAbsolute(),
        "non-symlink artifact materialized as symlink must point to absolute path");

    if (output.isTreeArtifact()) {
      TreeArtifactValue metadata = getRemoteTreeMetadata(targetPath);
      if (metadata == null) {
        return;
      }

      SpecialArtifact parent = (SpecialArtifact) output;
      TreeArtifactValue.Builder injectedTree = TreeArtifactValue.newBuilder(parent);
      // Avoid a double indirection when the target is already materialized as a symlink.
      injectedTree.setMaterializationExecPath(
          metadata.getMaterializationExecPath().orElse(targetPath.relativeTo(execRoot)));
      // TODO: Check directory content on the local fs to support mixed tree.
      for (Map.Entry<TreeFileArtifact, FileArtifactValue> entry :
          metadata.getChildValues().entrySet()) {
        TreeFileArtifact child =
            TreeFileArtifact.createTreeOutput(parent, entry.getKey().getParentRelativePath());
        RemoteFileArtifactValue childMetadata = (RemoteFileArtifactValue) entry.getValue();
        injectedTree.putChild(child, childMetadata);
      }

      metadataInjector.injectTree(parent, injectedTree.build());
    } else {
      RemoteFileArtifactValue metadata = getRemoteMetadata(targetPath);
      if (metadata == null) {
        return;
      }

      RemoteFileArtifactValue injectedMetadata =
          RemoteFileArtifactValue.create(
              metadata.getDigest(),
              metadata.getSize(),
              metadata.getLocationIndex(),
              metadata.getExpireAtEpochMilli(),
              // Avoid a double indirection when the target is already materialized as a symlink.
              metadata.getMaterializationExecPath().orElse(targetPath.relativeTo(execRoot)));

      metadataInjector.injectFile(output, injectedMetadata);
    }
  }

  @Override
  public String getFileSystemType(PathFragment path) {
    return "remoteActionFS";
  }

  @Override
  protected boolean delete(PathFragment path) throws IOException {
    boolean deleted = super.delete(path);
    if (isOutput(path)) {
      deleted = remoteOutputTree.getPath(path).delete() || deleted;
    }
    return deleted;
  }

  @Override
  protected InputStream getInputStream(PathFragment path) throws IOException {
    downloadFileIfRemote(path);
    // TODO(tjgq): Consider only falling back to the local filesystem for source (non-output) files.
    // See getMetadata() for why this isn't currently possible.
    return super.getInputStream(path);
  }

  @Override
  protected ReadableByteChannel createReadableByteChannel(PathFragment path) throws IOException {
    downloadFileIfRemote(path);
    return super.createReadableByteChannel(path);
  }

  @Override
  public void setLastModifiedTime(PathFragment path, long newTime) throws IOException {
    FileNotFoundException remoteException = null;
    try {
      // We can't set mtime for a remote file, set mtime of in-memory file node instead.
      remoteOutputTree.setLastModifiedTime(path, newTime);
    } catch (FileNotFoundException e) {
      remoteException = e;
    }

    FileNotFoundException localException = null;
    try {
      super.setLastModifiedTime(path, newTime);
    } catch (FileNotFoundException e) {
      localException = e;
    }

    if (remoteException == null || localException == null) {
      return;
    }

    localException.addSuppressed(remoteException);
    throw localException;
  }

  @Override
  protected byte[] getFastDigest(PathFragment path) throws IOException {
    RemoteFileArtifactValue m = getRemoteMetadata(path);
    if (m != null) {
      return m.getDigest();
    }
    return super.getFastDigest(path);
  }

  @Override
  protected byte[] getDigest(PathFragment path) throws IOException {
    RemoteFileArtifactValue m = getRemoteMetadata(path);
    if (m != null) {
      return m.getDigest();
    }
    return super.getDigest(path);
  }

  // -------------------- File Permissions --------------------
  // Remote files are always readable, writable and executable since we can't control their
  // permissions.

  private boolean existsInMemory(PathFragment path) {
    return remoteOutputTree.getPath(path).isDirectory() || getRemoteMetadata(path) != null;
  }

  @Override
  protected boolean isReadable(PathFragment path) throws IOException {
    return existsInMemory(path) || super.isReadable(path);
  }

  @Override
  protected boolean isWritable(PathFragment path) throws IOException {
    if (existsInMemory(path)) {
      // If path exists locally, also check whether it's writable. We need this check for the case
      // where the action need to delete their local outputs but the parent directory is not
      // writable.
      try {
        return super.isWritable(path);
      } catch (FileNotFoundException e) {
        // Intentionally ignored
        return true;
      }
    }

    return super.isWritable(path);
  }

  @Override
  protected boolean isExecutable(PathFragment path) throws IOException {
    return existsInMemory(path) || super.isExecutable(path);
  }

  @Override
  protected void setReadable(PathFragment path, boolean readable) throws IOException {
    try {
      super.setReadable(path, readable);
    } catch (FileNotFoundException e) {
      // in case of missing in-memory path, re-throw the error.
      if (!existsInMemory(path)) {
        throw e;
      }
    }
  }

  @Override
  public void setWritable(PathFragment path, boolean writable) throws IOException {
    try {
      super.setWritable(path, writable);
    } catch (FileNotFoundException e) {
      // in case of missing in-memory path, re-throw the error.
      if (!existsInMemory(path)) {
        throw e;
      }
    }
  }

  @Override
  protected void setExecutable(PathFragment path, boolean executable) throws IOException {
    try {
      super.setExecutable(path, executable);
    } catch (FileNotFoundException e) {
      // in case of missing in-memory path, re-throw the error.
      if (!existsInMemory(path)) {
        throw e;
      }
    }
  }

  @Override
  protected void chmod(PathFragment path, int mode) throws IOException {
    try {
      super.chmod(path, mode);
    } catch (FileNotFoundException e) {
      // in case of missing in-memory path, re-throw the error.
      if (!existsInMemory(path)) {
        throw e;
      }
    }
  }

  // -------------------- Symlinks --------------------

  @Override
  protected PathFragment readSymbolicLink(PathFragment path) throws IOException {
    RemoteFileArtifactValue m = getRemoteMetadata(path);
    if (m != null) {
      // We don't support symlinks as remote action outputs.
      throw new IOException(path + " is not a symbolic link");
    }
    return super.readSymbolicLink(path);
  }

  @Override
  protected void createSymbolicLink(PathFragment linkPath, PathFragment targetFragment)
      throws IOException {
    super.createSymbolicLink(linkPath, targetFragment);
    if (isOutput(linkPath)) {
      remoteOutputTree.getPath(linkPath).createSymbolicLink(targetFragment);
    }
  }

  // -------------------- Implementations based on stat() --------------------

  @Override
  protected long getLastModifiedTime(PathFragment path, boolean followSymlinks) throws IOException {
    try {
      // We can't get mtime for a remote file, use mtime of in-memory file node instead.
      return remoteOutputTree
          .getPath(path)
          .getLastModifiedTime(followSymlinks ? Symlinks.FOLLOW : Symlinks.NOFOLLOW);
    } catch (FileNotFoundException e) {
      // Intentionally ignored
    }

    return super.getLastModifiedTime(path, followSymlinks);
  }

  @Override
  protected long getFileSize(PathFragment path, boolean followSymlinks) throws IOException {
    FileStatus stat = stat(path, followSymlinks);
    return stat.getSize();
  }

  @Override
  protected boolean isFile(PathFragment path, boolean followSymlinks) {
    FileStatus stat = statNullable(path, followSymlinks);
    return stat != null && stat.isFile();
  }

  @Override
  protected boolean isSymbolicLink(PathFragment path) {
    FileStatus stat = statNullable(path, /* followSymlinks= */ false);
    return stat != null && stat.isSymbolicLink();
  }

  @Override
  protected boolean isDirectory(PathFragment path, boolean followSymlinks) {
    FileStatus stat = statNullable(path, followSymlinks);
    return stat != null && stat.isDirectory();
  }

  @Override
  protected boolean isSpecialFile(PathFragment path, boolean followSymlinks) {
    FileStatus stat = statNullable(path, followSymlinks);
    return stat != null && stat.isDirectory();
  }

  @Override
  protected boolean exists(PathFragment path, boolean followSymlinks) {
    try {
      return statIfFound(path, followSymlinks) != null;
    } catch (IOException e) {
      return false;
    }
  }

  @Override
  public boolean exists(PathFragment path) {
    return exists(path, /* followSymlinks= */ true);
  }

  @Nullable
  @Override
  protected FileStatus statIfFound(PathFragment path, boolean followSymlinks) throws IOException {
    try {
      return stat(path, followSymlinks);
    } catch (FileNotFoundException e) {
      return null;
    }
  }

  @Nullable
  @Override
  protected FileStatus statNullable(PathFragment path, boolean followSymlinks) {
    try {
      return stat(path, followSymlinks);
    } catch (IOException e) {
      return null;
    }
  }

  @Override
  protected FileStatus stat(PathFragment path, boolean followSymlinks) throws IOException {
    RemoteFileArtifactValue m = getRemoteMetadata(path);
    if (m != null) {
      return statFromRemoteMetadata(m);
    }
    return super.stat(path, followSymlinks);
  }

  private static FileStatus statFromRemoteMetadata(RemoteFileArtifactValue m) {
    return new FileStatusWithMetadata() {
      @Override
      public byte[] getDigest() {
        return m.getDigest();
      }

      @Override
      public boolean isFile() {
        return m.getType().isFile();
      }

      @Override
      public boolean isDirectory() {
        return m.getType().isDirectory();
      }

      @Override
      public boolean isSymbolicLink() {
        return m.getType().isSymlink();
      }

      @Override
      public boolean isSpecialFile() {
        return m.getType().isSpecialFile();
      }

      @Override
      public long getSize() {
        return m.getSize();
      }

      @Override
      public long getLastModifiedTime() {
        return m.getModifiedTime();
      }

      @Override
      public long getLastChangeTime() {
        return m.getModifiedTime();
      }

      @Override
      public long getNodeId() {
        throw new UnsupportedOperationException("Cannot get node id for " + m);
      }

      @Override
      public RemoteFileArtifactValue getMetadata() {
        return m;
      }
    };
  }

  @Nullable
  @VisibleForTesting
  ActionInput getInput(String execPath) {
    ActionInput input = inputArtifactData.getInput(execPath);
    if (input != null) {
      return input;
    }
    input = outputMapping.get(PathFragment.create(execPath));
    if (input != null) {
      return input;
    }
    if (!isOutput(execRoot.getRelative(execPath))) {
      return fileCache.getInput(execPath);
    }
    return null;
  }

  @Nullable
  @VisibleForTesting
  FileArtifactValue getInputMetadata(ActionInput input) {
    PathFragment execPath = input.getExecPath();
    return inputArtifactData.getMetadata(execPath);
  }

  @Nullable
  private FileArtifactValue getMetadataByExecPath(PathFragment execPath) {
    FileArtifactValue m = inputArtifactData.getMetadata(execPath);
    if (m != null) {
      return m;
    }

    var stat =
        remoteOutputTree.statNullable(execRoot.getRelative(execPath), /* followSymlinks= */ true);
    if (stat instanceof FileStatusWithMetadata) {
      return ((FileStatusWithMetadata) stat).getMetadata();
    }

    return null;
  }

  @Nullable
  private RemoteFileArtifactValue getRemoteMetadata(PathFragment path) {
    if (!isOutput(path)) {
      return null;
    }
    FileArtifactValue m = getMetadataByExecPath(path.relativeTo(execRoot));
    if (m != null && m.isRemote()) {
      return (RemoteFileArtifactValue) m;
    }
    return null;
  }

  @Nullable
  private TreeArtifactValue getRemoteTreeMetadata(PathFragment path) {
    if (!isOutput(path)) {
      return null;
    }
    TreeArtifactValue m = inputArtifactData.getTreeMetadata(path.relativeTo(execRoot));
    // TODO: Handle partially remote tree artifacts.
    if (m != null && m.isEntirelyRemote()) {
      return m;
    }
    // TODO(tjgq): Synthesize TreeArtifactValue from remoteOutputTree.
    return null;
  }

  private void downloadFileIfRemote(PathFragment path) throws IOException {
    if (!isRemote(path)) {
      return;
    }
    PathFragment execPath = path.relativeTo(execRoot);
    try {
      ActionInput input = getInput(execPath.getPathString());
      if (input == null) {
        // For undeclared outputs, getInput returns null as there's no artifact associated with the
        // path. Therefore, we synthesize one here just so we're able to call prefetchFiles.
        input = ActionInputHelper.fromPath(execPath);
      }
      getFromFuture(
          inputFetcher.prefetchFiles(
              action, ImmutableList.of(input), this::getInputMetadata, Priority.CRITICAL));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(String.format("Received interrupt while fetching file '%s'", path), e);
    }
  }

  private boolean isOutput(PathFragment path) {
    return path.startsWith(outputBase);
  }

  @Override
  public void renameTo(PathFragment sourcePath, PathFragment targetPath) throws IOException {
    checkArgument(isOutput(sourcePath), "sourcePath must be an output path");
    checkArgument(isOutput(targetPath), "targetPath must be an output path");

    FileNotFoundException remoteException = null;
    try {
      remoteOutputTree.renameTo(sourcePath, targetPath);
    } catch (FileNotFoundException e) {
      remoteException = e;
    }

    FileNotFoundException localException = null;
    try {
      delegateFs.renameTo(sourcePath, targetPath);
    } catch (FileNotFoundException e) {
      localException = e;
    }

    if (remoteException == null || localException == null) {
      return;
    }

    localException.addSuppressed(remoteException);
    throw localException;
  }

  @Override
  public void createDirectoryAndParents(PathFragment path) throws IOException {
    super.createDirectoryAndParents(path);
    if (isOutput(path)) {
      remoteOutputTree.createDirectoryAndParents(path);
    }
  }

  @CanIgnoreReturnValue
  @Override
  public boolean createDirectory(PathFragment path) throws IOException {
    boolean created = delegateFs.createDirectory(path);
    if (isOutput(path)) {
      created = remoteOutputTree.createDirectory(path) || created;
    }
    return created;
  }

  @Override
  protected ImmutableList<String> getDirectoryEntries(PathFragment path) throws IOException {
    HashSet<String> entries = new HashSet<>();

    boolean ignoredNotFoundInRemote = false;
    if (isOutput(path)) {
      try {
        delegateFs.getPath(path).getDirectoryEntries().stream()
            .map(Path::getBaseName)
            .forEach(entries::add);
        ignoredNotFoundInRemote = true;
      } catch (FileNotFoundException ignored) {
        // Intentionally ignored
      }
    }

    try {
      remoteOutputTree.getPath(path).getDirectoryEntries().stream()
          .map(Path::getBaseName)
          .forEach(entries::add);
    } catch (FileNotFoundException e) {
      if (!ignoredNotFoundInRemote) {
        throw e;
      }
    }

    // sort entries to get a deterministic order.
    return ImmutableList.sortedCopyOf(entries);
  }

  @Override
  protected Collection<Dirent> readdir(PathFragment path, boolean followSymlinks)
      throws IOException {
    HashMap<String, Dirent> entries = new HashMap<>();

    boolean ignoredNotFoundInRemote = false;
    if (isOutput(path)) {
      try {
        for (var entry :
            delegateFs
                .getPath(path)
                .readdir(followSymlinks ? Symlinks.FOLLOW : Symlinks.NOFOLLOW)) {
          entries.put(entry.getName(), entry);
        }
        ignoredNotFoundInRemote = true;
      } catch (FileNotFoundException ignored) {
        // Intentionally ignored
      }
    }

    try {
      for (var entry :
          remoteOutputTree
              .getPath(path)
              .readdir(followSymlinks ? Symlinks.FOLLOW : Symlinks.NOFOLLOW)) {
        entries.put(entry.getName(), entry);
      }
    } catch (FileNotFoundException e) {
      if (!ignoredNotFoundInRemote) {
        throw e;
      }
    }

    // sort entries to get a deterministic order.
    return ImmutableList.sortedCopyOf(entries.values());
  }

  /*
   * -------------------- TODO(buchgr): Not yet implemented --------------------
   *
   * The below methods have not (yet) been properly implemented due to time constraints mostly and
   * with little risk as they currently don't seem to be used by internal actions in Bazel. However,
   * before making the --experimental_remote_download_outputs flag non-experimental we should make
   * sure to fully implement this file system.
   */

  @Override
  protected void createFSDependentHardLink(PathFragment linkPath, PathFragment originalPath)
      throws IOException {
    super.createFSDependentHardLink(linkPath, originalPath);
  }

  @Override
  protected void createHardLink(PathFragment linkPath, PathFragment originalPath)
      throws IOException {
    super.createHardLink(linkPath, originalPath);
  }

  static class RemoteInMemoryFileSystem extends InMemoryFileSystem {

    public RemoteInMemoryFileSystem(DigestHashFunction hashFunction) {
      super(hashFunction);
    }

    @Override
    protected synchronized OutputStream getOutputStream(PathFragment path, boolean append)
        throws IOException {
      // To get an output stream from remote file, we need to first stage it.
      throw new IllegalStateException("Shouldn't be called directly");
    }

    @Override
    protected FileInfo newFile(Clock clock, PathFragment path) {
      return new RemoteInMemoryFileInfo(clock);
    }

    protected void injectFile(PathFragment path, FileArtifactValue metadata) throws IOException {
      createDirectoryAndParents(path.getParentDirectory());
      InMemoryContentInfo node = getOrCreateWritableInode(path);
      // If a node was already existed and is not a remote file node (i.e. directory or symlink node
      // ), throw an error.
      if (!(node instanceof RemoteInMemoryFileInfo)) {
        throw new IOException("Could not inject into " + node);
      }

      RemoteInMemoryFileInfo remoteInMemoryFileInfo = (RemoteInMemoryFileInfo) node;
      remoteInMemoryFileInfo.set(metadata);
    }

    // Override for access within this class
    @Nullable
    @Override
    protected FileStatus statNullable(PathFragment path, boolean followSymlinks) {
      return super.statNullable(path, followSymlinks);
    }
  }

  static class RemoteInMemoryFileInfo extends FileInfo implements FileStatusWithMetadata {
    private FileArtifactValue metadata;

    RemoteInMemoryFileInfo(Clock clock) {
      super(clock);
    }

    private void set(FileArtifactValue metadata) {
      this.metadata = metadata;
    }

    @Override
    public OutputStream getOutputStream(boolean append) throws IOException {
      throw new IllegalStateException("Shouldn't be called directly");
    }

    @Override
    public InputStream getInputStream() throws IOException {
      throw new IllegalStateException("Shouldn't be called directly");
    }

    @Override
    public byte[] getxattr(String name) throws IOException {
      throw new IllegalStateException("Shouldn't be called directly");
    }

    @Override
    public byte[] getFastDigest() {
      return metadata.getDigest();
    }

    @Override
    public byte[] getDigest() throws IOException {
      return metadata.getDigest();
    }

    @Override
    public long getSize() {
      return metadata.getSize();
    }

    @Override
    public FileArtifactValue getMetadata() {
      return metadata;
    }
  }
}
