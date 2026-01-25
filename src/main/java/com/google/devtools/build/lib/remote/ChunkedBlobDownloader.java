// Copyright 2025 The Bazel Authors. All rights reserved.
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

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.SplitBlobResponse;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.remote.common.CacheNotFoundException;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.disk.DiskCacheClient;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;

/**
 * Downloads blobs by sequentially fetching chunks via the SplitBlob API, reading through the disk
 * cache when available. This class should be run on virtual threads.
 */
public class ChunkedBlobDownloader {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final GrpcCacheClient grpcCacheClient;

  @Nullable private DiskCacheClient diskCacheClient;

  public ChunkedBlobDownloader(GrpcCacheClient grpcCacheClient) {
    this.grpcCacheClient = grpcCacheClient;
  }

  public void setDiskCacheClient(@Nullable DiskCacheClient diskCacheClient) {
    this.diskCacheClient = diskCacheClient;
  }

  /**
   * Downloads a blob using chunked download via the SplitBlob API. This should be called from a
   * virtual thread.
   */
  public void downloadChunked(
      RemoteActionExecutionContext context, Digest blobDigest, OutputStream out)
      throws CacheNotFoundException, InterruptedException {
    try {
      doDownloadChunked(context, blobDigest, out);
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Chunked download failed for %s", blobDigest.getHash());
      throw new CacheNotFoundException(blobDigest);
    }
  }

  private void doDownloadChunked(
      RemoteActionExecutionContext context, Digest blobDigest, OutputStream out)
      throws IOException, InterruptedException {
    ListenableFuture<SplitBlobResponse> splitResponseFuture =
        grpcCacheClient.getSplitBlob(context, blobDigest);
    if (splitResponseFuture == null) {
      throw new CacheNotFoundException(blobDigest);
    }

    SplitBlobResponse splitResponse;
    try {
      splitResponse = splitResponseFuture.get();
    } catch (ExecutionException e) {
      throw new IOException("Failed to get split blob info", e.getCause());
    }

    if (splitResponse == null || splitResponse.getChunkDigestsCount() == 0) {
      throw new CacheNotFoundException(blobDigest);
    }

    ImmutableList<Digest> chunkDigests =
        ImmutableList.copyOf(splitResponse.getChunkDigestsList());

    downloadAndReassembleChunks(context, chunkDigests, out);
  }

  private void downloadAndReassembleChunks(
      RemoteActionExecutionContext context, ImmutableList<Digest> chunkDigests, OutputStream out)
      throws IOException, InterruptedException {
    for (Digest chunkDigest : chunkDigests) {
      downloadChunk(context, chunkDigest, out);
    }
  }

  private void downloadChunk(
      RemoteActionExecutionContext context, Digest chunkDigest, OutputStream out)
      throws IOException, InterruptedException {
    if (!loadFromDiskCache(context, chunkDigest, out)) {
      downloadFromRemote(context, chunkDigest, out);
    }
  }

  private boolean loadFromDiskCache(
      RemoteActionExecutionContext context, Digest digest, OutputStream out)
      throws IOException, InterruptedException {
    if (diskCacheClient == null || !context.getReadCachePolicy().allowDiskCache()) {
      return false;
    }
    try {
      diskCacheClient.downloadBlob(digest, out).get();
      return true;
    } catch (ExecutionException e) {
      if (e.getCause() instanceof CacheNotFoundException) {
        return false;
      }
      throw new IOException("Disk cache read failed", e.getCause());
    }
  }

  private void downloadFromRemote(
      RemoteActionExecutionContext context, Digest digest, OutputStream out)
      throws IOException, InterruptedException {
    try {
      grpcCacheClient.downloadBlob(context, digest, out).get();
    } catch (ExecutionException e) {
      throw new IOException("Remote download failed", e.getCause());
    }
  }
}
