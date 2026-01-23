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

import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import build.bazel.remote.execution.v2.Digest;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.remote.common.CacheNotFoundException;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.disk.DiskCacheClient;
import java.io.OutputStream;
import javax.annotation.Nullable;

/**
 * Downloads blobs by sequentially fetching chunks via the SplitBlob API, reading through the disk
 * cache when available.
 */
public class ChunkedBlobDownloader {
  private final GrpcCacheClient grpcCacheClient;

  @Nullable
  private DiskCacheClient diskCacheClient;

  public ChunkedBlobDownloader(GrpcCacheClient grpcCacheClient) {
    this.grpcCacheClient = grpcCacheClient;
  }

  public void setDiskCacheClient(@Nullable DiskCacheClient diskCacheClient) {
    this.diskCacheClient = diskCacheClient;
  }

  /**
   * Downloads a blob using chunked download via the SplitBlob API.
   *
   * <p>Throws {@link CacheNotFoundException} (via the returned future) if chunked download is not
   * available for this blob, allowing the caller to fall back to regular download.
   */
  public ListenableFuture<Void> downloadChunked(
      RemoteActionExecutionContext context, Digest blobDigest, OutputStream out) {
    var splitResponseFuture = grpcCacheClient.getSplitBlob(context, blobDigest);
    if (splitResponseFuture == null) {
      return immediateFailedFuture(new CacheNotFoundException(blobDigest));
    }

    return Futures.catchingAsync(
        Futures.transformAsync(
            splitResponseFuture,
            splitResponse -> {
              if (splitResponse == null || splitResponse.getChunkDigestsCount() == 0) {
                throw new CacheNotFoundException(blobDigest);
              }

              ImmutableList<Digest> chunkDigests =
                  ImmutableList.copyOf(splitResponse.getChunkDigestsList());

              return downloadAndReassembleChunks(context, chunkDigests, out);
            },
            directExecutor()),
        io.grpc.StatusRuntimeException.class,
        e -> {
          throw new CacheNotFoundException(blobDigest);
        },
        directExecutor());
  }

  private ListenableFuture<Void> downloadAndReassembleChunks(
      RemoteActionExecutionContext context,
      ImmutableList<Digest> chunkDigests,
      OutputStream out) {

    ListenableFuture<Void> chain = Futures.immediateVoidFuture();
    for (Digest chunkDigest : chunkDigests) {
      chain = Futures.transformAsync(
          chain,
          unused -> downloadChunk(context, chunkDigest, out),
          directExecutor());
    }
    return chain;
  }

  private ListenableFuture<Void> downloadChunk(
      RemoteActionExecutionContext context, Digest chunkDigest, OutputStream out) {
    if (diskCacheClient != null && context.getReadCachePolicy().allowDiskCache()) {
      return Futures.catchingAsync(
          diskCacheClient.downloadBlob(chunkDigest, out),
          CacheNotFoundException.class,
          unused -> grpcCacheClient.downloadBlob(context, chunkDigest, out),
          directExecutor());
    }
    return grpcCacheClient.downloadBlob(context, chunkDigest, out);
  }
}
