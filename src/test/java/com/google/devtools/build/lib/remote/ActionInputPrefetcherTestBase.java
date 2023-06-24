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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.NULL_ACTION_OWNER;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.createTreeArtifactWithGeneratingAction;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher.MetadataSupplier;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher.Priority;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileArtifactValue.RemoteFileArtifactValue;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.remote.util.TempPathGenerator;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.testing.vfs.SpiedFileSystem;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;

/** Base test class for {@link AbstractActionInputPrefetcher} implementations. */
public abstract class ActionInputPrefetcherTestBase {
  protected static final DigestHashFunction HASH_FUNCTION = DigestHashFunction.SHA256;

  protected SpiedFileSystem fs;
  protected Path execRoot;
  protected ArtifactRoot artifactRoot;
  protected TempPathGenerator tempPathGenerator;

  protected ActionExecutionMetadata action;

  @Before
  public void setUp() throws IOException {
    action = mock(ActionExecutionMetadata.class);
    when(action.getMnemonic()).thenReturn("DummyAction");
    when(action.getOwner()).thenReturn(NULL_ACTION_OWNER);

    fs = SpiedFileSystem.createInMemorySpy();
    execRoot = fs.getPath("/exec");
    execRoot.createDirectoryAndParents();
    artifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "root");
    artifactRoot.getRoot().asPath().createDirectoryAndParents();
    Path tempDir = fs.getPath("/tmp");
    tempDir.createDirectoryAndParents();
    tempPathGenerator = new TempPathGenerator(tempDir);
  }

  protected Artifact createRemoteArtifact(
      String pathFragment,
      String contents,
      @Nullable PathFragment materializationExecPath,
      Map<ActionInput, FileArtifactValue> metadata,
      @Nullable Map<HashCode, byte[]> cas) {
    Path p = artifactRoot.getRoot().getRelative(pathFragment);
    Artifact a = ActionsTestUtil.createArtifact(artifactRoot, p);
    byte[] contentsBytes = contents.getBytes(UTF_8);
    HashCode hashCode = HASH_FUNCTION.getHashFunction().hashBytes(contentsBytes);
    RemoteFileArtifactValue f =
        RemoteFileArtifactValue.create(
            hashCode.asBytes(),
            contentsBytes.length,
            /* locationIndex= */ 1,
            /* expireAtEpochMilli= */ -1,
            materializationExecPath);
    metadata.put(a, f);
    if (cas != null) {
      cas.put(hashCode, contentsBytes);
    }
    return a;
  }

  protected Artifact createRemoteArtifact(
      String pathFragment,
      String contents,
      Map<ActionInput, FileArtifactValue> metadata,
      @Nullable Map<HashCode, byte[]> cas) {
    return createRemoteArtifact(
        pathFragment, contents, /* materializationExecPath= */ null, metadata, cas);
  }

  protected Pair<SpecialArtifact, ImmutableList<TreeFileArtifact>> createRemoteTreeArtifact(
      String pathFragment,
      Map<String, String> localContentMap,
      Map<String, String> remoteContentMap,
      @Nullable PathFragment materializationExecPath,
      Map<ActionInput, FileArtifactValue> metadata,
      Map<HashCode, byte[]> cas)
      throws IOException {
    SpecialArtifact parent = createTreeArtifactWithGeneratingAction(artifactRoot, pathFragment);

    parent.getPath().createDirectoryAndParents();
    parent.getPath().chmod(0555);

    TreeArtifactValue.Builder treeBuilder = TreeArtifactValue.newBuilder(parent);
    for (Map.Entry<String, String> entry : localContentMap.entrySet()) {
      TreeFileArtifact child =
          TreeFileArtifact.createTreeOutput(parent, PathFragment.create(entry.getKey()));
      byte[] contents = entry.getValue().getBytes(UTF_8);
      HashCode hashCode = HASH_FUNCTION.getHashFunction().hashBytes(contents);
      FileArtifactValue childValue =
          FileArtifactValue.createForNormalFile(
              hashCode.asBytes(), /* proxy= */ null, contents.length);
      treeBuilder.putChild(child, childValue);
      metadata.put(child, childValue);
      cas.put(hashCode, contents);
    }
    for (Map.Entry<String, String> entry : remoteContentMap.entrySet()) {
      TreeFileArtifact child =
          TreeFileArtifact.createTreeOutput(parent, PathFragment.create(entry.getKey()));
      byte[] contents = entry.getValue().getBytes(UTF_8);
      HashCode hashCode = HASH_FUNCTION.getHashFunction().hashBytes(contents);
      RemoteFileArtifactValue childValue =
          RemoteFileArtifactValue.create(
              hashCode.asBytes(),
              contents.length,
              /* locationIndex= */ 1,
              /* expireAtEpochMilli= */ -1);
      treeBuilder.putChild(child, childValue);
      metadata.put(child, childValue);
      cas.put(hashCode, contents);
    }
    if (materializationExecPath != null) {
      treeBuilder.setMaterializationExecPath(materializationExecPath);
    }
    TreeArtifactValue treeValue = treeBuilder.build();

    metadata.put(parent, treeValue.getMetadata());

    return Pair.of(parent, treeValue.getChildren().asList());
  }

  protected Pair<SpecialArtifact, ImmutableList<TreeFileArtifact>> createRemoteTreeArtifact(
      String pathFragment,
      Map<String, String> localContentMap,
      Map<String, String> remoteContentMap,
      Map<ActionInput, FileArtifactValue> metadata,
      Map<HashCode, byte[]> cas)
      throws IOException {
    return createRemoteTreeArtifact(
        pathFragment,
        localContentMap,
        remoteContentMap,
        /* materializationExecPath= */ null,
        metadata,
        cas);
  }

  protected abstract AbstractActionInputPrefetcher createPrefetcher(Map<HashCode, byte[]> cas);

  @Test
  public void prefetchFiles_fileExists_doNotDownload()
      throws IOException, ExecException, InterruptedException {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Artifact a = createRemoteArtifact("file", "hello world", metadata, cas);
    FileSystemUtils.writeContent(a.getPath(), "hello world".getBytes(UTF_8));
    AbstractActionInputPrefetcher prefetcher = spy(createPrefetcher(cas));

    wait(prefetcher.prefetchFiles(action, metadata.keySet(), metadata::get, Priority.MEDIUM));

    verify(prefetcher, never()).doDownloadFile(eq(action), any(), any(), any(), any(), any());
    assertThat(prefetcher.downloadedFiles()).containsExactly(a.getPath());
    assertThat(prefetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void prefetchFiles_fileExistsButContentMismatches_download()
      throws IOException, ExecException, InterruptedException {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Artifact a = createRemoteArtifact("file", "hello world remote", metadata, cas);
    FileSystemUtils.writeContent(a.getPath(), "hello world local".getBytes(UTF_8));
    AbstractActionInputPrefetcher prefetcher = spy(createPrefetcher(cas));

    wait(prefetcher.prefetchFiles(action, metadata.keySet(), metadata::get, Priority.MEDIUM));

    verify(prefetcher).doDownloadFile(eq(action), any(), any(), eq(a.getExecPath()), any(), any());
    assertThat(prefetcher.downloadedFiles()).containsExactly(a.getPath());
    assertThat(prefetcher.downloadsInProgress()).isEmpty();
    assertThat(FileSystemUtils.readContent(a.getPath(), UTF_8)).isEqualTo("hello world remote");
  }

  @Test
  public void prefetchFiles_downloadRemoteFiles() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Artifact a1 = createRemoteArtifact("file1", "hello world", metadata, cas);
    Artifact a2 = createRemoteArtifact("file2", "fizz buzz", metadata, cas);
    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);

    wait(prefetcher.prefetchFiles(action, metadata.keySet(), metadata::get, Priority.MEDIUM));

    assertThat(FileSystemUtils.readContent(a1.getPath(), UTF_8)).isEqualTo("hello world");
    assertReadableNonWritableAndExecutable(a1.getPath());
    assertThat(FileSystemUtils.readContent(a2.getPath(), UTF_8)).isEqualTo("fizz buzz");
    assertReadableNonWritableAndExecutable(a2.getPath());
    assertThat(prefetcher.downloadedFiles()).containsExactly(a1.getPath(), a2.getPath());
    assertThat(prefetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void prefetchFiles_downloadRemoteFiles_withMaterializationExecPath() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    PathFragment targetExecPath = artifactRoot.getExecPath().getChild("target");
    Artifact a = createRemoteArtifact("file", "hello world", targetExecPath, metadata, cas);
    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);

    wait(prefetcher.prefetchFiles(action, metadata.keySet(), metadata::get, Priority.MEDIUM));

    assertThat(a.getPath().isSymbolicLink()).isTrue();
    assertThat(a.getPath().readSymbolicLink())
        .isEqualTo(execRoot.getRelative(targetExecPath).asFragment());
    assertThat(FileSystemUtils.readContent(a.getPath(), UTF_8)).isEqualTo("hello world");
    assertThat(prefetcher.downloadedFiles())
        .containsExactly(a.getPath(), execRoot.getRelative(targetExecPath));
    assertThat(prefetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void prefetchFiles_downloadRemoteTrees() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Pair<SpecialArtifact, ImmutableList<TreeFileArtifact>> treeAndChildren =
        createRemoteTreeArtifact(
            "dir",
            /* localContentMap= */ ImmutableMap.of(),
            /* remoteContentMap= */ ImmutableMap.of(
                "file1", "content1", "nested_dir/file2", "content2"),
            metadata,
            cas);
    SpecialArtifact tree = treeAndChildren.getFirst();
    ImmutableList<TreeFileArtifact> children = treeAndChildren.getSecond();
    Artifact firstChild = children.get(0);
    Artifact secondChild = children.get(1);

    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);

    wait(prefetcher.prefetchFiles(action, children, metadata::get, Priority.MEDIUM));

    assertThat(FileSystemUtils.readContent(firstChild.getPath(), UTF_8)).isEqualTo("content1");
    assertThat(FileSystemUtils.readContent(secondChild.getPath(), UTF_8)).isEqualTo("content2");

    assertTreeReadableNonWritableAndExecutable(tree.getPath());

    assertThat(prefetcher.downloadedFiles())
        .containsExactly(firstChild.getPath(), secondChild.getPath());
    assertThat(prefetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void prefetchFiles_downloadRemoteTrees_partial() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Pair<SpecialArtifact, ImmutableList<TreeFileArtifact>> treeAndChildren =
        createRemoteTreeArtifact(
            "dir",
            /* localContentMap= */ ImmutableMap.of("file1", "content1"),
            /* remoteContentMap= */ ImmutableMap.of("file2", "content2"),
            metadata,
            cas);
    SpecialArtifact tree = treeAndChildren.getFirst();
    ImmutableList<TreeFileArtifact> children = treeAndChildren.getSecond();
    Artifact firstChild = children.get(0);
    Artifact secondChild = children.get(1);

    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);

    wait(
        prefetcher.prefetchFiles(
            action, ImmutableList.of(firstChild, secondChild), metadata::get, Priority.MEDIUM));

    assertThat(firstChild.getPath().exists()).isFalse();
    assertThat(FileSystemUtils.readContent(secondChild.getPath(), UTF_8)).isEqualTo("content2");
    assertTreeReadableNonWritableAndExecutable(tree.getPath());
    assertThat(prefetcher.downloadedFiles()).containsExactly(secondChild.getPath());
  }

  @Test
  public void prefetchFiles_downloadRemoteTrees_withMaterializationExecPath() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    PathFragment targetExecPath = artifactRoot.getExecPath().getChild("target");
    Pair<SpecialArtifact, ImmutableList<TreeFileArtifact>> treeAndChildren =
        createRemoteTreeArtifact(
            "dir",
            /* localContentMap= */ ImmutableMap.of(),
            /* remoteContentMap= */ ImmutableMap.of(
                "file1", "content1", "nested_dir/file2", "content2"),
            targetExecPath,
            metadata,
            cas);
    SpecialArtifact tree = treeAndChildren.getFirst();
    ImmutableList<TreeFileArtifact> children = treeAndChildren.getSecond();
    Artifact firstChild = children.get(0);
    Artifact secondChild = children.get(1);

    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);

    wait(prefetcher.prefetchFiles(action, children, metadata::get, Priority.MEDIUM));

    assertThat(tree.getPath().isSymbolicLink()).isTrue();
    assertThat(tree.getPath().readSymbolicLink())
        .isEqualTo(execRoot.getRelative(targetExecPath).asFragment());
    assertThat(FileSystemUtils.readContent(firstChild.getPath(), UTF_8)).isEqualTo("content1");
    assertThat(FileSystemUtils.readContent(secondChild.getPath(), UTF_8)).isEqualTo("content2");

    assertTreeReadableNonWritableAndExecutable(execRoot.getRelative(targetExecPath));

    assertThat(prefetcher.downloadedFiles())
        .containsExactly(
            tree.getPath(),
            execRoot.getRelative(targetExecPath.getRelative(firstChild.getParentRelativePath())),
            execRoot.getRelative(targetExecPath.getRelative(secondChild.getParentRelativePath())));
    assertThat(prefetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void prefetchFiles_missingFiles_fails() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Artifact a = createRemoteArtifact("file1", "hello world", metadata, /* cas= */ new HashMap<>());
    AbstractActionInputPrefetcher prefetcher = createPrefetcher(new HashMap<>());

    assertThrows(
        Exception.class,
        () ->
            wait(
                prefetcher.prefetchFiles(
                    action, ImmutableList.of(a), metadata::get, Priority.MEDIUM)));

    assertThat(prefetcher.downloadedFiles()).isEmpty();
    assertThat(prefetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void prefetchFiles_ignoreNonRemoteFiles() throws Exception {
    // Test that non-remote files are not downloaded.

    Path p = execRoot.getRelative(artifactRoot.getExecPath()).getRelative("file1");
    FileSystemUtils.writeContent(p, UTF_8, "hello world");
    Artifact a = ActionsTestUtil.createArtifact(artifactRoot, p);
    FileArtifactValue f = FileArtifactValue.createForTesting(a);
    ImmutableMap<ActionInput, FileArtifactValue> metadata = ImmutableMap.of(a, f);
    AbstractActionInputPrefetcher prefetcher = createPrefetcher(new HashMap<>());

    wait(prefetcher.prefetchFiles(action, ImmutableList.of(a), metadata::get, Priority.MEDIUM));

    assertThat(prefetcher.downloadedFiles()).isEmpty();
    assertThat(prefetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void prefetchFiles_ignoreNonRemoteFiles_tree() throws Exception {
    // Test that non-remote tree files are not downloaded, but other files in the tree are.

    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Pair<SpecialArtifact, ImmutableList<TreeFileArtifact>> treeAndChildren =
        createRemoteTreeArtifact(
            "dir",
            ImmutableMap.of("file1", "content1"),
            ImmutableMap.of("file2", "content2"),
            metadata,
            cas);
    SpecialArtifact tree = treeAndChildren.getFirst();
    ImmutableList<TreeFileArtifact> children = treeAndChildren.getSecond();
    Artifact firstChild = children.get(0);
    Artifact secondChild = children.get(1);

    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);

    wait(prefetcher.prefetchFiles(action, children, metadata::get, Priority.MEDIUM));

    assertThat(firstChild.getPath().exists()).isFalse();
    assertThat(FileSystemUtils.readContent(secondChild.getPath(), UTF_8)).isEqualTo("content2");
    assertTreeReadableNonWritableAndExecutable(tree.getPath());
    assertThat(prefetcher.downloadedFiles()).containsExactly(secondChild.getPath());
  }

  @Test
  public void prefetchFiles_treeFiles_minimizeFilesystemOperations() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Pair<SpecialArtifact, ImmutableList<TreeFileArtifact>> treeAndChildren =
        createRemoteTreeArtifact(
            "dir",
            /* localContentMap= */ ImmutableMap.of("subdir/file1", "content1"),
            /* remoteContentMap= */ ImmutableMap.of("subdir/file2", "content2"),
            metadata,
            cas);
    SpecialArtifact tree = treeAndChildren.getFirst();
    ImmutableList<TreeFileArtifact> children = treeAndChildren.getSecond();
    Artifact firstChild = children.get(0);
    Artifact secondChild = children.get(1);

    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);

    wait(
        prefetcher.prefetchFiles(
            action, ImmutableList.of(firstChild, secondChild), metadata::get, Priority.MEDIUM));

    verify(fs, times(1)).createWritableDirectory(tree.getPath().asFragment());
    verify(fs, times(1)).createWritableDirectory(tree.getPath().getChild("subdir").asFragment());
  }

  @Test
  public void prefetchFiles_multipleThreads_downloadIsCancelled() throws Exception {
    // Test shared downloads are cancelled if all threads/callers are interrupted

    // arrange
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Artifact artifact = createRemoteArtifact("file1", "hello world", metadata, cas);

    AbstractActionInputPrefetcher prefetcher = spy(createPrefetcher(cas));
    SettableFuture<Void> downloadThatNeverFinishes = SettableFuture.create();
    mockDownload(prefetcher, cas, () -> downloadThatNeverFinishes);

    Thread cancelledThread1 =
        new Thread(
            () -> {
              try {
                wait(
                    prefetcher.prefetchFiles(
                        action, ImmutableList.of(artifact), metadata::get, Priority.MEDIUM));
              } catch (IOException | ExecException | InterruptedException ignored) {
                // do nothing
              }
            });

    Thread cancelledThread2 =
        new Thread(
            () -> {
              try {
                wait(
                    prefetcher.prefetchFiles(
                        action, ImmutableList.of(artifact), metadata::get, Priority.MEDIUM));
              } catch (IOException | ExecException | InterruptedException ignored) {
                // do nothing
              }
            });

    // act
    cancelledThread1.start();
    cancelledThread2.start();
    cancelledThread1.interrupt();
    cancelledThread2.interrupt();
    cancelledThread1.join();
    cancelledThread2.join();

    // assert
    assertThat(downloadThatNeverFinishes.isCancelled()).isTrue();
    assertThat(artifact.getPath().exists()).isFalse();
    assertThat(tempPathGenerator.getTempDir().getDirectoryEntries()).isEmpty();
  }

  @Test
  public void prefetchFiles_multipleThreads_downloadIsNotCancelledByOtherThreads()
      throws Exception {
    // Test multiple threads can share downloads, but do not cancel each other when interrupted

    // arrange
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Artifact artifact = createRemoteArtifact("file1", "hello world", metadata, cas);
    SettableFuture<Void> download = SettableFuture.create();
    AbstractActionInputPrefetcher prefetcher = spy(createPrefetcher(cas));
    mockDownload(prefetcher, cas, () -> download);
    Thread cancelledThread =
        new Thread(
            () -> {
              try {
                wait(
                    prefetcher.prefetchFiles(
                        action, ImmutableList.of(artifact), metadata::get, Priority.MEDIUM));
              } catch (IOException | ExecException | InterruptedException ignored) {
                // do nothing
              }
            });

    AtomicBoolean successful = new AtomicBoolean(false);
    Thread successfulThread =
        new Thread(
            () -> {
              try {
                wait(
                    prefetcher.prefetchFiles(
                        action, ImmutableList.of(artifact), metadata::get, Priority.MEDIUM));
                successful.set(true);
              } catch (IOException | ExecException | InterruptedException ignored) {
                // do nothing
              }
            });
    cancelledThread.start();
    successfulThread.start();
    while (true) {
      if (prefetcher
              .getDownloadCache()
              .getSubscriberCount(execRoot.getRelative(artifact.getExecPath()))
          == 2) {
        break;
      }
    }

    // act
    cancelledThread.interrupt();
    cancelledThread.join();
    // simulate the download finishing
    assertThat(download.isCancelled()).isFalse();
    download.set(null);
    successfulThread.join();

    // assert
    assertThat(successful.get()).isTrue();
    assertThat(FileSystemUtils.readContent(artifact.getPath(), UTF_8)).isEqualTo("hello world");
  }

  @Test
  public void prefetchFile_interruptingMetadataSupplier_interruptsDownload() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Artifact a1 = createRemoteArtifact("file1", "hello world", metadata, cas);
    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);

    MetadataSupplier interruptedMetadataSupplier =
        unused -> {
          throw new InterruptedException();
        };

    ListenableFuture<Void> future =
        prefetcher.prefetchFiles(
            action, ImmutableList.of(a1), interruptedMetadataSupplier, Priority.MEDIUM);

    assertThrows(CancellationException.class, future::get);
  }

  @Test
  public void prefetchFiles_onInterrupt_deletePartialDownloadedFile() throws Exception {
    Semaphore startSemaphore = new Semaphore(0);
    Semaphore endSemaphore = new Semaphore(0);
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Artifact a1 = createRemoteArtifact("file1", "hello world", metadata, cas);
    AbstractActionInputPrefetcher prefetcher = spy(createPrefetcher(cas));
    mockDownload(
        prefetcher,
        cas,
        () -> {
          startSemaphore.release();
          return SettableFuture.create(); // A future that never complete so we can interrupt later
        });

    AtomicBoolean interrupted = new AtomicBoolean(false);
    Thread t =
        new Thread(
            () -> {
              try {
                getFromFuture(
                    prefetcher.prefetchFiles(
                        action, ImmutableList.of(a1), metadata::get, Priority.MEDIUM));
              } catch (IOException ignored) {
                // Intentionally left empty
              } catch (InterruptedException e) {
                interrupted.set(true);
              }
              endSemaphore.release();
            });
    t.start();
    startSemaphore.acquire();
    t.interrupt();
    endSemaphore.acquire();

    assertThat(interrupted.get()).isTrue();
    assertThat(a1.getPath().exists()).isFalse();
    assertThat(tempPathGenerator.getTempDir().getDirectoryEntries()).isEmpty();
  }

  @Test
  public void missingInputs_addedToList() {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Artifact a = createRemoteArtifact("file", "hello world", metadata, /* cas= */ null);
    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);

    assertThrows(
        Exception.class,
        () ->
            wait(
                prefetcher.prefetchFiles(
                    action, metadata.keySet(), metadata::get, Priority.MEDIUM)));

    assertThat(prefetcher.getMissingActionInputs()).contains(a);
  }

  protected static void wait(ListenableFuture<Void> future)
      throws IOException, ExecException, InterruptedException {
    try {
      future.get();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause != null) {
        throwIfInstanceOf(cause, IOException.class);
        throwIfInstanceOf(cause, ExecException.class);
        throwIfInstanceOf(cause, InterruptedException.class);
        throwIfInstanceOf(cause, RuntimeException.class);
      }
      throw new IOException(e);
    } catch (InterruptedException e) {
      future.cancel(/* mayInterruptIfRunning= */ true);
      throw e;
    }
  }

  protected static void mockDownload(
      AbstractActionInputPrefetcher prefetcher,
      Map<HashCode, byte[]> cas,
      Supplier<ListenableFuture<Void>> resultSupplier)
      throws IOException {
    doAnswer(
            invocation -> {
              Path path = invocation.getArgument(2);
              FileArtifactValue metadata = invocation.getArgument(4);
              byte[] content = cas.get(HashCode.fromBytes(metadata.getDigest()));
              if (content == null) {
                return Futures.immediateFailedFuture(new IOException("Not found"));
              }
              FileSystemUtils.writeContent(path, content);
              return resultSupplier.get();
            })
        .when(prefetcher)
        .doDownloadFile(any(), any(), any(), any(), any(), any());
  }

  private void assertReadableNonWritableAndExecutable(Path path) throws IOException {
    assertWithMessage(path + " should be readable").that(path.isReadable()).isTrue();
    assertWithMessage(path + " should not be writable").that(path.isWritable()).isFalse();
    assertWithMessage(path + " should be executable").that(path.isExecutable()).isTrue();
  }

  private void assertTreeReadableNonWritableAndExecutable(Path path) throws IOException {
    checkState(path.isDirectory());
    assertReadableNonWritableAndExecutable(path);
    for (Dirent dirent : path.readdir(Symlinks.NOFOLLOW)) {
      if (dirent.getType().equals(Dirent.Type.DIRECTORY)) {
        assertTreeReadableNonWritableAndExecutable(path.getChild(dirent.getName()));
      }
    }
  }
}
