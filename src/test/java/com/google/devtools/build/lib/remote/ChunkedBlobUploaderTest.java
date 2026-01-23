// Copyright 2026 The Bazel Authors. All rights reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import build.bazel.remote.execution.v2.Digest;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.remote.chunking.ChunkingConfig;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.util.List;
import java.util.Random;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link ChunkedBlobUploader}. */
@RunWith(JUnit4.class)
public class ChunkedBlobUploaderTest {
  private static final DigestUtil DIGEST_UTIL = new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);

  @Mock
  private GrpcCacheClient grpcCacheClient;
  @Mock
  private CombinedCache combinedCache;
  @Mock
  private RemoteActionExecutionContext context;

  private FileSystem fs;
  private Path execRoot;
  private ChunkedBlobUploader uploader;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    fs = new InMemoryFileSystem(new JavaClock(), DigestHashFunction.SHA256);
    execRoot = fs.getPath("/execroot");
    execRoot.createDirectoryAndParents();

    ChunkingConfig config = new ChunkingConfig(1024, 2, 0);
    uploader = new ChunkedBlobUploader(grpcCacheClient, combinedCache, config, DIGEST_UTIL);
  }

  @Test
  public void getChunkingThreshold_returnsConfiguredValue() {
    ChunkingConfig config = new ChunkingConfig(512, 2, 0);
    ChunkedBlobUploader uploader =
        new ChunkedBlobUploader(grpcCacheClient, combinedCache, config, DIGEST_UTIL);

    assertThat(uploader.getChunkingThreshold()).isEqualTo(512 * 4);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void uploadChunked_allChunksMissing_uploadsAllChunks() throws Exception {
    Path file = execRoot.getRelative("test.txt");
    byte[] data = new byte[8192];
    new Random(42).nextBytes(data);
    writeFile(file, data);
    Digest blobDigest = DIGEST_UTIL.compute(data);

    ArgumentCaptor<List<Digest>> digestsCaptor = ArgumentCaptor.forClass(List.class);
    when(grpcCacheClient.findMissingDigests(any(), digestsCaptor.capture()))
        .thenAnswer(invocation -> {
          List<Digest> digests = invocation.getArgument(1);
          return Futures.immediateFuture(ImmutableSet.copyOf(digests));
        });
    when(combinedCache.uploadBlob(any(), any(Digest.class), any()))
        .thenReturn(Futures.immediateFuture(null));
    when(grpcCacheClient.spliceBlob(any(), any(), any()))
        .thenReturn(Futures.immediateFuture(null));

    uploader.uploadChunked(context, blobDigest, file);

    List<Digest> chunkDigests = digestsCaptor.getValue();
    assertThat(chunkDigests.size()).isGreaterThan(1);
    long totalSize = chunkDigests.stream().mapToLong(Digest::getSizeBytes).sum();
    assertThat(totalSize).isEqualTo(data.length);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void uploadChunked_noChunksMissing_skipsChunkUpload() throws Exception {
    Path file = execRoot.getRelative("test.txt");
    byte[] data = new byte[8192];
    new Random(42).nextBytes(data);
    writeFile(file, data);
    Digest blobDigest = DIGEST_UTIL.compute(data);

    when(grpcCacheClient.findMissingDigests(any(), any()))
        .thenReturn(Futures.immediateFuture(ImmutableSet.of()));
    when(grpcCacheClient.spliceBlob(any(), any(), any()))
        .thenReturn(Futures.immediateFuture(null));

    uploader.uploadChunked(context, blobDigest, file);

    verify(combinedCache, never()).uploadBlob(any(), any(Digest.class), any());
    verify(grpcCacheClient).spliceBlob(any(), eq(blobDigest), any());
  }

  private void writeFile(Path path, byte[] data) throws IOException {
    path.getOutputStream().write(data);
  }
}
