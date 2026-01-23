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
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.SplitBlobResponse;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.remote.FastCDCChunker.ChunkRef;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.disk.DiskCacheClient;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.vfs.Path;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;

/**
 * Uploads blobs in chunks using CDC (Content-Defined Chunking).
 *
 * <p>Upload flow for blobs above threshold:
 * <ol>
 *   <li>Chunk file with FastCDC (stream to disk cache if available)
 *   <li>Call findMissingDigests on chunk digests
 *   <li>Upload only missing chunks
 *   <li>Call SpliceBlob to register the blob as the concatenation of chunks
 * </ol>
 */
public class ChunkedBlobUploader {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final GrpcCacheClient grpcCacheClient;
  private final FastCDCChunker chunker;

  @Nullable private DiskCacheClient diskCacheClient;

  public ChunkedBlobUploader(GrpcCacheClient grpcCacheClient, DigestUtil digestUtil) {
    this.grpcCacheClient = grpcCacheClient;
    this.chunker = new FastCDCChunker(digestUtil);
  }

  public void setDiskCacheClient(@Nullable DiskCacheClient diskCacheClient) {
    this.diskCacheClient = diskCacheClient;
  }

  public ListenableFuture<Void> uploadChunked(
      RemoteActionExecutionContext context, Digest blobDigest, Path file) {
        
    ListenableFuture<SplitBlobResponse> splitFuture = grpcCacheClient.getSplitBlob(context, blobDigest);
    if (splitFuture == null) {
      return doChunkedUpload(context, blobDigest, file);
    }

    return Futures.catchingAsync(
        Futures.transformAsync(
            splitFuture,
            response -> {
              if (response != null && response.getChunkDigestsCount() > 0) {
                return immediateVoidFuture();
              }
              return doChunkedUpload(context, blobDigest, file);
            },
            directExecutor()),
        Exception.class,
        e -> doChunkedUpload(context, blobDigest, file),
        directExecutor());
  }

  private ListenableFuture<Void> doChunkedUpload(
      RemoteActionExecutionContext context, Digest blobDigest, Path file) {
    try {
      ImmutableList<ChunkRef> chunkRefs = chunkFile(file);
      if (chunkRefs.isEmpty()) {
        return immediateVoidFuture();
      }

      return uploadChunksAndSplice(context, blobDigest, chunkRefs, file);
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("ChunkedBlobUploader: Failed to chunk %s", blobDigest.getHash());
      return immediateFailedFuture(e);
    }
  }

  private ImmutableList<ChunkRef> chunkFile(Path file) throws IOException {
    ImmutableList<ChunkRef> chunkRefs;
    try (InputStream input = file.getInputStream()) {
      chunkRefs = chunker.chunkToRefs(input);
    }

    if (diskCacheClient != null) {
      for (ChunkRef chunk : chunkRefs) {
        ByteString data = readChunkData(file, chunk);
        try {
          diskCacheClient.uploadBlob(chunk.digest(), data).get();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while writing chunk to disk cache", e);
        } catch (ExecutionException e) {
          throw new IOException("Failed to write chunk to disk cache", e.getCause());
        }
      }
    }

    return chunkRefs;
  }

  private ListenableFuture<Void> uploadChunksAndSplice(
      RemoteActionExecutionContext context,
      Digest blobDigest,
      ImmutableList<ChunkRef> chunkRefs,
      Path file) {

    ImmutableList<Digest> chunkDigests =
        chunkRefs.stream().map(ChunkRef::digest).collect(ImmutableList.toImmutableList());

    ListenableFuture<ImmutableSet<Digest>> missingFuture =
        grpcCacheClient.findMissingDigests(context, chunkDigests);

    return Futures.transformAsync(
        missingFuture,
        missingDigests -> {
          ListenableFuture<Void> uploadFuture =
              uploadMissingChunks(context, missingDigests, chunkRefs, file);

          return Futures.transformAsync(
              uploadFuture,
              unused -> grpcCacheClient.spliceBlob(context, blobDigest, chunkDigests),
              directExecutor());
        },
        directExecutor());
  }

  private ListenableFuture<Void> uploadMissingChunks(
      RemoteActionExecutionContext context,
      ImmutableSet<Digest> missingDigests,
      ImmutableList<ChunkRef> chunkRefs,
      Path file) {
    if (missingDigests.isEmpty()) {
      return immediateVoidFuture();
    }

    Map<Digest, ChunkRef> digestToRef = new HashMap<>();
    for (ChunkRef ref : chunkRefs) {
      if (missingDigests.contains(ref.digest())) {
        digestToRef.put(ref.digest(), ref);
      }
    }

    List<ListenableFuture<Void>> uploadFutures = new ArrayList<>(missingDigests.size());
    for (Digest chunkDigest : missingDigests) {
      ChunkRef ref = digestToRef.get(chunkDigest);
      uploadFutures.add(uploadChunk(context, ref, file));
    }

    return Futures.whenAllSucceed(uploadFutures).call(() -> null, directExecutor());
  }

  private ListenableFuture<Void> uploadChunk(
      RemoteActionExecutionContext context, ChunkRef chunk, Path file) {
    try {
      ByteString data = readChunkData(file, chunk);
      return grpcCacheClient.uploadBlob(context, chunk.digest(), data::newInput);
    } catch (IOException e) {
      return immediateFailedFuture(e);
    }
  }

  private ByteString readChunkData(Path file, ChunkRef chunk) throws IOException {
    try (InputStream input = file.getInputStream()) {
      long skipped = input.skip(chunk.offset());
      if (skipped != chunk.offset()) {
        throw new IOException(
            String.format(
                "Failed to skip to offset %d in file %s (skipped %d)",
                chunk.offset(), file, skipped));
      }
      byte[] buf = new byte[chunk.length()];
      int read = 0;
      while (read < chunk.length()) {
        int n = input.read(buf, read, chunk.length() - read);
        if (n == -1) {
          throw new IOException(
              String.format(
                  "Unexpected EOF reading chunk at offset %d, expected %d bytes, got %d",
                  chunk.offset(), chunk.length(), read));
        }
        read += n;
      }
      return ByteString.copyFrom(buf);
    }
  }

}
