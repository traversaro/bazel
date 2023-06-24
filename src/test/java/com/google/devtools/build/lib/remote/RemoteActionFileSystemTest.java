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
package com.google.devtools.build.lib.remote;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionInputMap;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher.Priority;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileArtifactValue.RemoteFileArtifactValue;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.StaticInputMetadataProvider;
import com.google.devtools.build.lib.actions.cache.MetadataInjector;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

/** Tests for {@link RemoteActionFileSystem} */
@RunWith(JUnit4.class)
public final class RemoteActionFileSystemTest extends RemoteActionFileSystemTestBase {
  private static final RemoteOutputChecker DUMMY_REMOTE_OUTPUT_CHECKER =
      new RemoteOutputChecker(
          new JavaClock(), "build", /* downloadToplevel= */ false, ImmutableList.of());

  private static final DigestHashFunction HASH_FUNCTION = DigestHashFunction.SHA256;

  private static final String RELATIVE_OUTPUT_PATH = "out";

  private final RemoteActionInputFetcher inputFetcher = mock(RemoteActionInputFetcher.class);
  private final MetadataInjector metadataInjector = mock(MetadataInjector.class);
  private final FileSystem fs = new InMemoryFileSystem(HASH_FUNCTION);
  private final Path execRoot = fs.getPath("/exec");
  private final ArtifactRoot sourceRoot = ArtifactRoot.asSourceRoot(Root.fromPath(execRoot));
  private final ArtifactRoot outputRoot =
      ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, RELATIVE_OUTPUT_PATH);

  @Before
  public void setUp() throws IOException {
    outputRoot.getRoot().asPath().createDirectoryAndParents();
  }

  @Override
  protected RemoteActionFileSystem createActionFileSystem(
      ActionInputMap inputs, Iterable<Artifact> outputs, InputMetadataProvider fileCache)
      throws IOException {
    doReturn(DUMMY_REMOTE_OUTPUT_CHECKER).when(inputFetcher).getRemoteOutputChecker();
    RemoteActionFileSystem remoteActionFileSystem =
        new RemoteActionFileSystem(
            fs,
            execRoot.asFragment(),
            RELATIVE_OUTPUT_PATH,
            inputs,
            outputs,
            fileCache,
            inputFetcher);
    remoteActionFileSystem.updateContext(mock(ActionExecutionMetadata.class), metadataInjector);
    remoteActionFileSystem.createDirectoryAndParents(outputRoot.getRoot().asPath().asFragment());
    return remoteActionFileSystem;
  }

  @Override
  protected FileSystem getLocalFileSystem(FileSystem actionFs) {
    return ((RemoteActionFileSystem) actionFs).getLocalFileSystem();
  }

  @Override
  protected FileSystem getRemoteFileSystem(FileSystem actionFs) {
    return ((RemoteActionFileSystem) actionFs).getRemoteOutputTree();
  }

  @Override
  protected PathFragment getOutputPath(String outputRootRelativePath) {
    return outputRoot.getRoot().asPath().getRelative(outputRootRelativePath).asFragment();
  }

  private static Answer<ListenableFuture<Void>> mockPrefetchFile(Path path, String contents) {
    return invocationOnMock -> {
      FileSystemUtils.writeContent(path, UTF_8, contents);
      return immediateVoidFuture();
    };
  }

  @Test
  public void testGetInputStream_fromInputArtifactData_forLocalArtifact() throws Exception {
    // arrange
    ActionInputMap inputs = new ActionInputMap(1);
    Artifact artifact = createLocalArtifact("local-file", "local contents", inputs);
    FileSystem actionFs = createActionFileSystem(inputs);

    // act
    Path actionFsPath = actionFs.getPath(artifact.getPath().asFragment());
    String contents = FileSystemUtils.readContent(actionFsPath, UTF_8);

    // assert
    assertThat(actionFsPath.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(contents).isEqualTo("local contents");
  }

  @Test
  public void testGetInputStream_fromInputArtifactData_forRemoteArtifact() throws Exception {
    // arrange
    ActionInputMap inputs = new ActionInputMap(1);
    Artifact artifact = createRemoteArtifact("remote-file", "remote contents", inputs);
    FileSystem actionFs = createActionFileSystem(inputs);
    doAnswer(mockPrefetchFile(artifact.getPath(), "remote contents"))
        .when(inputFetcher)
        .prefetchFiles(any(), eq(ImmutableList.of(artifact)), any(), eq(Priority.CRITICAL));

    // act
    Path actionFsPath = actionFs.getPath(artifact.getPath().asFragment());
    String contents = FileSystemUtils.readContent(actionFsPath, UTF_8);

    // assert
    assertThat(actionFsPath.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(contents).isEqualTo("remote contents");
    verify(inputFetcher)
        .prefetchFiles(any(), eq(ImmutableList.of(artifact)), any(), eq(Priority.CRITICAL));
    verifyNoMoreInteractions(inputFetcher);
  }

  @Test
  public void testGetInputStream_fromRemoteOutputTree_forDeclaredOutput() throws Exception {
    // arrange
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    FileSystem actionFs = createActionFileSystem(new ActionInputMap(0), ImmutableList.of(artifact));
    injectRemoteFile(actionFs, artifact.getPath().asFragment(), "remote contents");
    doAnswer(mockPrefetchFile(artifact.getPath(), "remote contents"))
        .when(inputFetcher)
        .prefetchFiles(any(), eq(ImmutableList.of(artifact)), any(), eq(Priority.CRITICAL));

    // act
    Path actionFsPath = actionFs.getPath(artifact.getPath().asFragment());
    String contents = FileSystemUtils.readContent(actionFsPath, UTF_8);

    // assert
    assertThat(actionFsPath.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(contents).isEqualTo("remote contents");
    verify(inputFetcher)
        .prefetchFiles(any(), eq(ImmutableList.of(artifact)), any(), eq(Priority.CRITICAL));
    verifyNoMoreInteractions(inputFetcher);
  }

  @Test
  public void testGetInputStream_fromRemoteOutputTree_forUndeclaredOutput() throws Exception {
    // arrange
    Path path = outputRoot.getRoot().getRelative("out");
    ActionInput input = ActionInputHelper.fromPath(path.relativeTo(execRoot));
    FileSystem actionFs = createActionFileSystem();
    injectRemoteFile(actionFs, path.asFragment(), "remote contents");
    doAnswer(mockPrefetchFile(path, "remote contents"))
        .when(inputFetcher)
        .prefetchFiles(any(), eq(ImmutableList.of(input)), any(), eq(Priority.CRITICAL));

    // act
    Path actionFsPath = actionFs.getPath(path.asFragment());
    String contents = FileSystemUtils.readContent(actionFsPath, UTF_8);

    // assert
    assertThat(actionFsPath.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(contents).isEqualTo("remote contents");
    verify(inputFetcher)
        .prefetchFiles(any(), eq(ImmutableList.of(input)), any(), eq(Priority.CRITICAL));
    verifyNoMoreInteractions(inputFetcher);
  }

  @Test
  public void getInputStream_fromLocalFilesystem_forSourceFile() throws Exception {
    // arrange
    Artifact artifact = ActionsTestUtil.createArtifact(sourceRoot, "src");
    FileSystem actionFs = createActionFileSystem();
    writeLocalFile(actionFs, artifact.getPath().asFragment(), "local contents");

    // act
    Path actionFsPath = actionFs.getPath(artifact.getPath().asFragment());
    String contents = FileSystemUtils.readContent(actionFsPath, UTF_8);

    // assert
    assertThat(actionFsPath.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(contents).isEqualTo("local contents");
  }

  @Test
  public void getInputStream_fromLocalFilesystem_forOutputFile() throws Exception {
    // arrange
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    FileSystem actionFs = createActionFileSystem();
    writeLocalFile(actionFs, artifact.getPath().asFragment(), "local contents");

    // act
    Path actionFsPath = actionFs.getPath(artifact.getPath().asFragment());
    String contents = FileSystemUtils.readContent(actionFsPath, UTF_8);

    // assert
    assertThat(actionFsPath.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(contents).isEqualTo("local contents");
  }

  @Test
  public void getInput_fromInputArtifactData_forLocalArtifact() throws Exception {
    ActionInputMap inputs = new ActionInputMap(1);
    Artifact artifact = createLocalArtifact("local-file", "local contents", inputs);
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem(inputs);

    assertThat(actionFs.getInput(artifact.getExecPathString())).isEqualTo(artifact);
  }

  @Test
  public void getInput_fromInputArtifactData_forRemoteArtifact() throws Exception {
    ActionInputMap inputs = new ActionInputMap(1);
    Artifact artifact = createRemoteArtifact("remote-file", "remote contents", inputs);
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem(inputs);

    assertThat(actionFs.getInput(artifact.getExecPathString())).isEqualTo(artifact);
  }

  @Test
  public void getInput_fromOutputMapping() throws Exception {
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    RemoteActionFileSystem actionFs =
        (RemoteActionFileSystem)
            createActionFileSystem(new ActionInputMap(0), ImmutableList.of(artifact));

    assertThat(actionFs.getInput(artifact.getExecPathString())).isEqualTo(artifact);
  }

  @Test
  public void getInput_fromFileCache_forSourceFile() throws Exception {
    Artifact artifact = ActionsTestUtil.createArtifact(sourceRoot, "src");
    FileArtifactValue metadata =
        FileArtifactValue.createForNormalFile(new byte[] {1, 2, 3}, /* proxy= */ null, 42);
    RemoteActionFileSystem actionFs =
        createActionFileSystem(
            new ActionInputMap(0),
            ImmutableList.of(),
            new StaticInputMetadataProvider(ImmutableMap.of(artifact, metadata)));

    assertThat(actionFs.getInput(artifact.getExecPathString())).isEqualTo(artifact);
  }

  @Test
  public void getInput_fromFileCache_notForOutputFile() throws Exception {
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    FileArtifactValue metadata =
        FileArtifactValue.createForNormalFile(new byte[] {1, 2, 3}, /* proxy= */ null, 42);
    RemoteActionFileSystem actionFs =
        createActionFileSystem(
            new ActionInputMap(0),
            ImmutableList.of(),
            new StaticInputMetadataProvider(ImmutableMap.of(artifact, metadata)));

    assertThat(actionFs.getInput(artifact.getExecPathString())).isNull();
  }

  @Test
  public void getInput_notFound() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();

    assertThat(actionFs.getInput("some-path")).isNull();
  }

  @Test
  public void getMetadata_fromInputArtifactData_forLocalArtifact() throws Exception {
    ActionInputMap inputs = new ActionInputMap(1);
    Artifact artifact = createLocalArtifact("local-file", "local contents", inputs);
    FileArtifactValue metadata = checkNotNull(inputs.getInputMetadata(artifact));
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem(inputs);

    assertThat(actionFs.getInputMetadata(artifact)).isEqualTo(metadata);
  }

  @Test
  public void getMetadata_fromInputArtifactData_forRemoteArtifact() throws Exception {
    ActionInputMap inputs = new ActionInputMap(1);
    Artifact artifact = createRemoteArtifact("remote-file", "remote contents", inputs);
    FileArtifactValue metadata = checkNotNull(inputs.getInputMetadata(artifact));
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem(inputs);

    assertThat(actionFs.getInputMetadata(artifact)).isEqualTo(metadata);
  }

  @Test
  public void getMetadata_notFound() throws Exception {
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();

    assertThat(actionFs.getInputMetadata(artifact)).isNull();
  }

  @Test
  public void createSymbolicLink_localFileArtifact() throws Exception {
    // arrange
    ActionInputMap inputs = new ActionInputMap(1);
    Artifact localArtifact = createLocalArtifact("local-file", "local contents", inputs);
    Artifact outputArtifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    ImmutableList<Artifact> outputs = ImmutableList.of(outputArtifact);
    FileSystem actionFs = createActionFileSystem(inputs, outputs);

    // act
    PathFragment linkPath = outputArtifact.getPath().asFragment();
    PathFragment targetPath = localArtifact.getPath().asFragment();
    Path symlinkActionFs = actionFs.getPath(linkPath);
    symlinkActionFs.createSymbolicLink(actionFs.getPath(targetPath));

    // assert
    assertThat(symlinkActionFs.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(symlinkActionFs.readSymbolicLink()).isEqualTo(targetPath);
    assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
        .isEqualTo(targetPath);
    assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
        .isEqualTo(targetPath);

    // act
    ((RemoteActionFileSystem) actionFs).flush();

    // assert
    verifyNoInteractions(metadataInjector);
  }

  @Test
  public void createSymbolicLink_remoteFileArtifact() throws Exception {
    // arrange
    ActionInputMap inputs = new ActionInputMap(1);
    Artifact remoteArtifact = createRemoteArtifact("remote-file", "remote contents", inputs);
    Artifact outputArtifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    ImmutableList<Artifact> outputs = ImmutableList.of(outputArtifact);
    FileSystem actionFs = createActionFileSystem(inputs, outputs);

    // act
    PathFragment linkPath = outputArtifact.getPath().asFragment();
    PathFragment targetPath = remoteArtifact.getPath().asFragment();
    Path symlinkActionFs = actionFs.getPath(linkPath);
    symlinkActionFs.createSymbolicLink(actionFs.getPath(targetPath));

    // assert
    assertThat(symlinkActionFs.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(symlinkActionFs.readSymbolicLink()).isEqualTo(targetPath);
    assertThat(outputArtifact.getPath().readSymbolicLink()).isEqualTo(targetPath);
    assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
        .isEqualTo(targetPath);
    assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
        .isEqualTo(targetPath);

    // act
    ((RemoteActionFileSystem) actionFs).flush();

    // assert
    ArgumentCaptor<FileArtifactValue> metadataCaptor =
        ArgumentCaptor.forClass(FileArtifactValue.class);
    verify(metadataInjector).injectFile(eq(outputArtifact), metadataCaptor.capture());
    assertThat(metadataCaptor.getValue()).isInstanceOf(RemoteFileArtifactValue.class);
    assertThat(metadataCaptor.getValue().getMaterializationExecPath())
        .hasValue(targetPath.relativeTo(execRoot.asFragment()));
    verifyNoMoreInteractions(metadataInjector);
  }

  @Test
  public void createSymbolicLink_localTreeArtifact() throws Exception {
    // arrange
    ActionInputMap inputs = new ActionInputMap(1);
    ImmutableMap<String, String> contentMap =
        ImmutableMap.of("foo", "foo contents", "bar", "bar contents");
    Artifact localArtifact = createLocalTreeArtifact("remote-dir", contentMap, inputs);
    SpecialArtifact outputArtifact =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "out");
    ImmutableList<Artifact> outputs = ImmutableList.of(outputArtifact);
    FileSystem actionFs = createActionFileSystem(inputs, outputs);

    // act
    PathFragment linkPath = outputArtifact.getPath().asFragment();
    PathFragment targetPath = localArtifact.getPath().asFragment();
    Path symlinkActionFs = actionFs.getPath(linkPath);
    symlinkActionFs.createSymbolicLink(actionFs.getPath(targetPath));

    // assert
    assertThat(symlinkActionFs.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(symlinkActionFs.readSymbolicLink()).isEqualTo(targetPath);
    assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
        .isEqualTo(targetPath);
    assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
        .isEqualTo(targetPath);

    // act
    ((RemoteActionFileSystem) actionFs).flush();

    // assert
    verifyNoInteractions(metadataInjector);
  }

  @Test
  public void createSymbolicLink_remoteTreeArtifact() throws Exception {
    // arrange
    ActionInputMap inputs = new ActionInputMap(1);
    ImmutableMap<String, String> contentMap =
        ImmutableMap.of("foo", "foo contents", "bar", "bar contents");
    Artifact remoteArtifact = createRemoteTreeArtifact("remote-dir", contentMap, inputs);
    SpecialArtifact outputArtifact =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "out");
    ImmutableList<Artifact> outputs = ImmutableList.of(outputArtifact);
    FileSystem actionFs = createActionFileSystem(inputs, outputs);

    // act
    PathFragment linkPath = outputArtifact.getPath().asFragment();
    PathFragment targetPath = remoteArtifact.getPath().asFragment();
    Path symlinkActionFs = actionFs.getPath(linkPath);
    symlinkActionFs.createSymbolicLink(actionFs.getPath(targetPath));

    // assert
    assertThat(symlinkActionFs.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(symlinkActionFs.readSymbolicLink()).isEqualTo(targetPath);
    assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
        .isEqualTo(targetPath);
    assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
        .isEqualTo(targetPath);

    // act
    ((RemoteActionFileSystem) actionFs).flush();

    // assert
    ArgumentCaptor<TreeArtifactValue> metadataCaptor =
        ArgumentCaptor.forClass(TreeArtifactValue.class);
    verify(metadataInjector).injectTree(eq(outputArtifact), metadataCaptor.capture());
    assertThat(metadataCaptor.getValue().getMaterializationExecPath())
        .hasValue(targetPath.relativeTo(execRoot.asFragment()));
    verifyNoMoreInteractions(metadataInjector);
  }

  @Test
  public void createSymbolicLink_unresolvedSymlink() throws Exception {
    // arrange
    ActionInputMap inputs = new ActionInputMap(1);
    SpecialArtifact outputArtifact =
        ActionsTestUtil.createUnresolvedSymlinkArtifact(outputRoot, "out");
    ImmutableList<Artifact> outputs = ImmutableList.of(outputArtifact);
    FileSystem actionFs = createActionFileSystem(inputs, outputs);
    PathFragment targetPath = PathFragment.create("some/path");

    // act
    PathFragment linkPath = outputArtifact.getPath().asFragment();
    Path symlinkActionFs = actionFs.getPath(linkPath);
    symlinkActionFs.createSymbolicLink(targetPath);

    // assert
    assertThat(symlinkActionFs.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(symlinkActionFs.readSymbolicLink()).isEqualTo(targetPath);
    assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
        .isEqualTo(targetPath);
    assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
        .isEqualTo(targetPath);

    // act
    ((RemoteActionFileSystem) actionFs).flush();

    // assert
    verifyNoInteractions(metadataInjector);
  }

  @Test
  public void renameTo_onlyLocalFile_renameLocalFile() throws Exception {
    FileSystem actionFs = createActionFileSystem();
    PathFragment path = getOutputPath("file");
    writeLocalFile(actionFs, path, "local-content");
    PathFragment newPath = getOutputPath("file-new");

    actionFs.renameTo(path, newPath);

    assertThat(actionFs.exists(path)).isFalse();
    assertThat(actionFs.exists(newPath)).isTrue();
    assertThat(getLocalFileSystem(actionFs).exists(path)).isFalse();
    assertThat(getLocalFileSystem(actionFs).exists(newPath)).isTrue();
  }

  @Override
  @CanIgnoreReturnValue
  protected FileArtifactValue injectRemoteFile(
      FileSystem actionFs, PathFragment path, String content) throws IOException {
    byte[] contentBytes = content.getBytes(UTF_8);
    HashCode hashCode = HASH_FUNCTION.getHashFunction().hashBytes(contentBytes);
    ((RemoteActionFileSystem) actionFs)
        .injectRemoteFile(
            path, hashCode.asBytes(), contentBytes.length, /* expireAtEpochMilli= */ -1);
    return RemoteFileArtifactValue.create(
        hashCode.asBytes(),
        contentBytes.length,
        /* locationIndex= */ 1,
        /* expireAtEpochMilli= */ -1);
  }

  @Override
  protected void writeLocalFile(FileSystem actionFs, PathFragment path, String content)
      throws IOException {
    FileSystemUtils.writeContent(actionFs.getPath(path), UTF_8, content);
  }

  /** Returns a remote artifact and puts its metadata into the action input map. */
  private Artifact createRemoteArtifact(
      String pathFragment, String contents, ActionInputMap inputs) {
    Artifact a = ActionsTestUtil.createArtifact(outputRoot, pathFragment);
    byte[] b = contents.getBytes(UTF_8);
    HashCode h = HASH_FUNCTION.getHashFunction().hashBytes(b);
    RemoteFileArtifactValue f =
        RemoteFileArtifactValue.create(
            h.asBytes(), b.length, /* locationIndex= */ 1, /* expireAtEpochMilli= */ -1);
    inputs.putWithNoDepOwner(a, f);
    return a;
  }

  /** Returns a remote tree artifact and puts its metadata into the action input map. */
  private SpecialArtifact createRemoteTreeArtifact(
      String pathFragment, Map<String, String> contentMap, ActionInputMap inputs) {
    SpecialArtifact a =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, pathFragment);
    inputs.putTreeArtifact(a, createRemoteTreeArtifactValue(a, contentMap), /* depOwner= */ null);
    return a;
  }

  private TreeArtifactValue createRemoteTreeArtifactValue(
      SpecialArtifact a, Map<String, String> contentMap) {
    TreeArtifactValue.Builder builder = TreeArtifactValue.newBuilder(a);
    for (Map.Entry<String, String> entry : contentMap.entrySet()) {
      TreeFileArtifact child = TreeFileArtifact.createTreeOutput(a, entry.getKey());
      byte[] b = entry.getValue().getBytes(UTF_8);
      HashCode h = HASH_FUNCTION.getHashFunction().hashBytes(b);
      RemoteFileArtifactValue childMeta =
          RemoteFileArtifactValue.create(
              h.asBytes(), b.length, /* locationIndex= */ 0, /* expireAtEpochMilli= */ -1);
      builder.putChild(child, childMeta);
    }
    return builder.build();
  }

  /** Returns a local artifact and puts its metadata into the action input map. */
  private Artifact createLocalArtifact(String pathFragment, String contents, ActionInputMap inputs)
      throws IOException {
    Path p = outputRoot.getRoot().asPath().getRelative(pathFragment);
    FileSystemUtils.writeContent(p, UTF_8, contents);
    Artifact a = ActionsTestUtil.createArtifact(outputRoot, p);
    Path path = a.getPath();
    // Caution: there's a race condition between stating the file and computing the
    // digest. We need to stat first, since we're using the stat to detect changes.
    // We follow symlinks here to be consistent with getDigest.
    inputs.putWithNoDepOwner(
        a,
        FileArtifactValue.createFromStat(path, path.stat(Symlinks.FOLLOW), SyscallCache.NO_CACHE));
    return a;
  }

  /** Returns a local tree artifact and puts its metadata into the action input map. */
  private SpecialArtifact createLocalTreeArtifact(
      String pathFragment, Map<String, String> contentMap, ActionInputMap inputs)
      throws IOException {
    Path dir = outputRoot.getRoot().asPath().getRelative(pathFragment);
    dir.createDirectoryAndParents();
    for (Map.Entry<String, String> entry : contentMap.entrySet()) {
      Path child = dir.getRelative(entry.getKey());
      child.getParentDirectory().createDirectoryAndParents();
      FileSystemUtils.writeContent(child, entry.getValue().getBytes(UTF_8));
    }
    SpecialArtifact a =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, pathFragment);
    inputs.putTreeArtifact(a, createLocalTreeArtifactValue(a, contentMap), /* depOwner= */ null);
    return a;
  }

  private TreeArtifactValue createLocalTreeArtifactValue(
      SpecialArtifact a, Map<String, String> contentMap) throws IOException {
    TreeArtifactValue.Builder builder = TreeArtifactValue.newBuilder(a);
    for (String name : contentMap.keySet()) {
      Path path = a.getPath().getRelative(name);
      TreeFileArtifact child = TreeFileArtifact.createTreeOutput(a, name);
      FileArtifactValue childMeta =
          FileArtifactValue.createFromStat(path, path.stat(Symlinks.FOLLOW), SyscallCache.NO_CACHE);
      builder.putChild(child, childMeta);
    }
    return builder.build();
  }
}
