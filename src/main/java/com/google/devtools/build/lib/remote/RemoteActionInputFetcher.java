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

import static com.google.common.base.Preconditions.checkArgument;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.RequestMetadata;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TempPathGenerator;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.vfs.OutputPermissions;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;

/**
 * Stages output files that are stored remotely to the local filesystem.
 *
 * <p>This is necessary when a locally executed action consumes outputs produced by a remotely
 * executed action and {@code --experimental_remote_download_outputs=minimal} is specified.
 */
class RemoteActionInputFetcher extends AbstractActionInputPrefetcher {

  private final String buildRequestId;
  private final String commandId;
  private final RemoteCache remoteCache;

  RemoteActionInputFetcher(
      Reporter reporter,
      String buildRequestId,
      String commandId,
      RemoteCache remoteCache,
      Path execRoot,
      TempPathGenerator tempPathGenerator,
      RemoteOutputChecker remoteOutputChecker,
      OutputPermissions outputPermissions) {
    super(reporter, execRoot, tempPathGenerator, remoteOutputChecker, outputPermissions);
    this.buildRequestId = Preconditions.checkNotNull(buildRequestId);
    this.commandId = Preconditions.checkNotNull(commandId);
    this.remoteCache = Preconditions.checkNotNull(remoteCache);
  }

  @Override
  public boolean requiresTreeMetadataWhenTreeFileIsInput() {
    return true;
  }

  @Override
  protected void prefetchVirtualActionInput(VirtualActionInput input) throws IOException {
    input.atomicallyWriteRelativeTo(execRoot, ".fetcher");
  }

  @Override
  protected boolean canDownloadFile(Path path, FileArtifactValue metadata) {
    return metadata.isRemote();
  }

  @Override
  protected ListenableFuture<Void> doDownloadFile(
      ActionExecutionMetadata action,
      Reporter reporter,
      Path tempPath,
      PathFragment execPath,
      FileArtifactValue metadata,
      Priority priority)
      throws IOException {
    checkArgument(metadata.isRemote(), "Cannot download file that is not a remote file.");
    RequestMetadata requestMetadata =
        TracingMetadataUtils.buildMetadata(buildRequestId, commandId, "prefetcher", action);
    RemoteActionExecutionContext context = RemoteActionExecutionContext.create(requestMetadata);

    Digest digest = DigestUtil.buildDigest(metadata.getDigest(), metadata.getSize());

    return remoteCache.downloadFile(
        context,
        tempPath,
        digest,
        new RemoteCache.DownloadProgressReporter(
            progress -> progress.postTo(reporter, action),
            execPath.toString(),
            digest.getSizeBytes()));
  }
}
