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
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Uploads blobs in chunks using Content-Defined Chunking with FastCDC 2020.
 * In the future, we should check the server chunking algorithm configuration
 * and adapt accordingly.
 *
 * <p>Upload flow for blobs above threshold:
 *
 * <ol>
 *   <li>Chunk file with FastCDC (stream to disk cache if available)
 *   <li>Call findMissingDigests on chunk digests
 *   <li>Upload only missing chunks
 *   <li>Call SpliceBlob to register the blob as the concatenation of chunks
 * </ol>
 *
 * <p>This class should be run on virtual threads.
 */
public class ChunkedBlobUploader {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static class InFlightUpload {
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicReference<Throwable> error = new AtomicReference<>();
  }

  private final GrpcCacheClient grpcCacheClient;
  private final FastCDCChunker chunker;
  private final ConcurrentHashMap<Digest, InFlightUpload> inFlightChunkUploads =
      new ConcurrentHashMap<>();

  @SuppressWarnings("AllowVirtualThreads")
  private final ExecutorService uploadExecutor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("chunk-upload-", 0).factory());

  @Nullable private DiskCacheClient diskCacheClient;

  public ChunkedBlobUploader(GrpcCacheClient grpcCacheClient, DigestUtil digestUtil) {
    this.grpcCacheClient = grpcCacheClient;
    this.chunker = new FastCDCChunker(digestUtil);
  }

  public void setDiskCacheClient(@Nullable DiskCacheClient diskCacheClient) {
    this.diskCacheClient = diskCacheClient;
  }

  /**
   * Uploads a blob using chunked upload via CDC. This should be called from a virtual thread.
   */
  public void uploadChunked(RemoteActionExecutionContext context, Digest blobDigest, Path file)
      throws IOException, InterruptedException {
    if (isAlreadyChunkedOnServer(context, blobDigest)) {
      return;
    }
    doChunkedUpload(context, blobDigest, file);
  }

  private boolean isAlreadyChunkedOnServer(
      RemoteActionExecutionContext context, Digest blobDigest) throws InterruptedException {
    ListenableFuture<SplitBlobResponse> splitFuture =
        grpcCacheClient.getSplitBlob(context, blobDigest);
    if (splitFuture == null) {
      return false;
    }
    try {
      SplitBlobResponse response = splitFuture.get();
      return isTrulyChunked(response, blobDigest);
    } catch (ExecutionException e) {
      return false;
    }
  }

  private static boolean isTrulyChunked(SplitBlobResponse response, Digest blobDigest) {
    if (response == null || response.getChunkDigestsCount() == 0) {
      return false;
    }
    if (response.getChunkDigestsCount() == 1 && response.getChunkDigests(0).equals(blobDigest)) {
      return false;
    }
    return true;
  }

  private void doChunkedUpload(RemoteActionExecutionContext context, Digest blobDigest, Path file)
      throws IOException, InterruptedException {
    ImmutableList<ChunkRef> chunkRefs = chunkFile(file);
    if (chunkRefs.isEmpty()) {
      return;
    }

    uploadChunksAndSplice(context, blobDigest, chunkRefs, file);
  }

  private ImmutableList<ChunkRef> chunkFile(Path file) throws IOException, InterruptedException {
    ImmutableList<ChunkRef> chunkRefs;
    try (InputStream input = file.getInputStream()) {
      chunkRefs = chunker.chunkToRefs(input);
    }

    if (diskCacheClient != null) {
      for (ChunkRef chunk : chunkRefs) {
        ByteString data = readChunkData(file, chunk);
        try {
          diskCacheClient.uploadBlob(chunk.digest(), data).get();
        } catch (ExecutionException e) {
          throw new IOException("Failed to write chunk to disk cache", e.getCause());
        }
      }
    }

    return chunkRefs;
  }

  private void uploadChunksAndSplice(
      RemoteActionExecutionContext context,
      Digest blobDigest,
      ImmutableList<ChunkRef> chunkRefs,
      Path file)
      throws IOException, InterruptedException {

    ImmutableList<Digest> chunkDigests =
        chunkRefs.stream().map(ChunkRef::digest).collect(ImmutableList.toImmutableList());

    ImmutableSet<Digest> missingDigests;
    try {
      missingDigests = grpcCacheClient.findMissingDigests(context, chunkDigests).get();
    } catch (ExecutionException e) {
      throw new IOException("Failed to find missing digests", e.getCause());
    }

    uploadMissingChunks(context, missingDigests, chunkRefs, file);

    try {
      grpcCacheClient.spliceBlob(context, blobDigest, chunkDigests).get();
    } catch (ExecutionException e) {
      throw new IOException("Failed to splice blob", e.getCause());
    }
  }

  private void uploadMissingChunks(
      RemoteActionExecutionContext context,
      ImmutableSet<Digest> missingDigests,
      ImmutableList<ChunkRef> chunkRefs,
      Path file)
      throws IOException, InterruptedException {
    if (missingDigests.isEmpty()) {
      return;
    }

    Map<Digest, ChunkRef> digestToRef = new HashMap<>();
    for (ChunkRef ref : chunkRefs) {
      if (missingDigests.contains(ref.digest())) {
        digestToRef.put(ref.digest(), ref);
      }
    }

    List<Future<Void>> futures = new ArrayList<>(missingDigests.size());
    for (Digest chunkDigest : missingDigests) {
      ChunkRef ref = digestToRef.get(chunkDigest);
      futures.add(
          uploadExecutor.submit(
              () -> {
                uploadChunk(context, ref, file);
                return null;
              }));
    }

    List<IOException> errors = new ArrayList<>();
    for (Future<Void> future : futures) {
      try {
        future.get();
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof IOException ioException) {
          errors.add(ioException);
        } else if (cause instanceof InterruptedException) {
          Thread.currentThread().interrupt();
          throw (InterruptedException) cause;
        } else {
          errors.add(new IOException("Chunk upload failed", cause));
        }
      }
    }

    if (!errors.isEmpty()) {
      IOException first = errors.get(0);
      for (int i = 1; i < errors.size(); i++) {
        first.addSuppressed(errors.get(i));
      }
      throw first;
    }
  }

  private void uploadChunk(RemoteActionExecutionContext context, ChunkRef chunk, Path file)
      throws IOException, InterruptedException {
    Digest digest = chunk.digest();

    InFlightUpload newUpload = new InFlightUpload();
    InFlightUpload activeDuplicateUpload = inFlightChunkUploads.putIfAbsent(digest, newUpload);

    if (activeDuplicateUpload != null) {
      activeDuplicateUpload.latch.await();
      if (activeDuplicateUpload.error.get() == null) {
        return;
      }

      // Replace failed upload with new one
      newUpload = new InFlightUpload();
      if (inFlightChunkUploads.putIfAbsent(digest, newUpload) != null) {
        return;
      }
    }

    try {
      ByteString data = readChunkData(file, chunk);
      grpcCacheClient.uploadBlob(context, digest, data::newInput).get();
    } catch (ExecutionException e) {
      newUpload.error.set(e.getCause());
      throw new IOException("Failed to upload chunk", e.getCause());
    } finally {
      newUpload.latch.countDown();
      inFlightChunkUploads.remove(digest, newUpload);
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
