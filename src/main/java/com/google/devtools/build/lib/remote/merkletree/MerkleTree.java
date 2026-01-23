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
package com.google.devtools.build.lib.remote.merkletree;

import build.bazel.remote.execution.v2.Digest;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.remote.FastCDCChunker.ChunkRef;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemotePathResolver;
import com.google.devtools.build.lib.vfs.Path;
import java.util.Optional;

/**
 * A representation of the inputs to a remotely executed action represented as a Merkle tree.
 *
 * <p>Every tree has a digest, which is the digest of the tree's root directory. The subtrees and
 * the blobs they contain may have been discarded or never computed in the first place, for example,
 * because they have already been uploaded to the remote cache or because the tree is being built
 * only to check for a remote cache hit.
 */
public sealed interface MerkleTree {
  /** The digest of the tree's root directory. */
  Digest digest();

  /** The total number of regular files and symlinks in this tree, including all subtrees. */
  long inputFiles();

  /**
   * The total number of content bytes in this tree, including all subtrees. This includes both file
   * contents and the protos describing directories.
   */
  long inputBytes();

  /** Returns the root of this tree, which may be the current instance. */
  RootOnly root();

  /**
   * A large file that will be uploaded as CDC (Content-Defined Chunking) chunks.
   *
   * <p>Instead of uploading the file as a single blob, we upload individual chunks and then call
   * SpliceBlob to register the blob as the concatenation of those chunks.
   *
   * <p>Note: We don't store chunk data here - just offset+length references into the original file.
   * This allows the MerkleTree to be memory-efficient even for large files.
   */
  record ChunkedFile(Path path, Digest blobDigest, ImmutableList<ChunkRef> chunks) {}

  /**
   * A {@link MerkleTree} that doesn't retain any blobs, either because they have already been
   * uploaded or because only the root digest is needed (e.g., for a remote cache check).
   */
  sealed interface RootOnly extends MerkleTree {
    @Override
    default RootOnly root() {
      return this;
    }

    /**
     * A {@link MerkleTree} that retains no blobs since all of them have recently been uploaded to
     * the remote cache.
     */
    record BlobsUploaded(Digest digest, long inputFiles, long inputBytes) implements RootOnly {}

    /**
     * A {@link MerkleTree} that retains no blobs since they were discarded during the computation
     * (e.g., because they aren't needed for a remote cache check).
     */
    record BlobsDiscarded(Digest digest, long inputFiles, long inputBytes) implements RootOnly {}
  }

  /** A {@link MerkleTree} that retains all blobs that still need to be uploaded. */
  final class Uploadable implements MerkleTree {
    private final RootOnly.BlobsUploaded root;
    private final ImmutableMap<Digest, /* byte[] | Path | VirtualActionInput */ Object> blobs;
    private final ImmutableList<ChunkedFile> chunkedFiles;
    private final ImmutableMap<Digest, ChunkSource> chunkSources;
    record ChunkSource(Path file, ChunkRef chunk) {}

    Uploadable(
        RootOnly.BlobsUploaded root,
        ImmutableMap<Digest, Object> blobs,
        ImmutableList<ChunkedFile> chunkedFiles) {
      this.root = root;
      this.blobs = blobs;
      this.chunkedFiles = chunkedFiles;
      this.chunkSources = buildChunkSources(chunkedFiles);
    }

    private static ImmutableMap<Digest, ChunkSource> buildChunkSources(
        ImmutableList<ChunkedFile> chunkedFiles) {
      if (chunkedFiles.isEmpty()) {
        return ImmutableMap.of();
      }
      ImmutableMap.Builder<Digest, ChunkSource> builder = ImmutableMap.builder();
      for (ChunkedFile file : chunkedFiles) {
        for (ChunkRef chunk : file.chunks()) {
          builder.put(chunk.digest(), new ChunkSource(file.path(), chunk));
        }
      }
      return builder.buildKeepingLast();
    }

    @Override
    public Digest digest() {
      return root().digest();
    }

    @Override
    public long inputFiles() {
      return root().inputFiles();
    }

    @Override
    public long inputBytes() {
      return root().inputBytes();
    }

    public ImmutableSet<Digest> allDigests() {
      return ImmutableSet.<Digest>builder()
          .addAll(blobs.keySet())
          .addAll(chunkSources.keySet())
          .build();
    }

    public ImmutableList<ChunkedFile> chunkedFiles() {
      return chunkedFiles;
    }

    @VisibleForTesting
    public ImmutableMap<Digest, Object> blobs() {
      return blobs;
    }

    @Override
    public RootOnly root() {
      return root;
    }

    /**
     * Returns a future that tracks the upload of the blob with the given digest, or {@link
     * Optional#empty()} if there is no blob with the given digest.
     */
    public Optional<ListenableFuture<Void>> upload(
        MerkleTreeUploader uploader,
        RemoteActionExecutionContext context,
        RemotePathResolver remotePathResolver,
        Digest digest) {
      Object blob = blobs.get(digest);
      if (blob != null) {
        return switch (blob) {
          case byte[] data -> Optional.of(uploader.uploadBlob(context, digest, data));
          case Path path ->
              Optional.of(uploader.uploadFile(context, remotePathResolver, digest, path));
          case VirtualActionInput virtualActionInput ->
              Optional.of(uploader.uploadVirtualActionInput(context, digest, virtualActionInput));
          default -> throw new IllegalStateException("Unexpected blob type: " + blob);
        };
      }

      ChunkSource source = chunkSources.get(digest);
      if (source != null) {
        return Optional.of(
            uploader.uploadChunk(context, digest, source.file(), source.chunk()));
      }

      return Optional.empty();
    }
  }
}
