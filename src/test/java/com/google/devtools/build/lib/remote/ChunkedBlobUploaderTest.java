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
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import build.bazel.remote.execution.v2.ChunkingFunction;
import build.bazel.remote.execution.v2.Digest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.remote.chunking.ChunkingConfig;
import com.google.devtools.build.lib.remote.chunking.ContentDefinedChunker;
import com.google.devtools.build.lib.remote.chunking.FastCdcChunker;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link ChunkedBlobUploader}. */
@RunWith(JUnit4.class)
public class ChunkedBlobUploaderTest {
  private static final DigestUtil DIGEST_UTIL =
      new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private GrpcCacheClient grpcCacheClient;
  @Mock private CombinedCache combinedCache;
  @Mock private RemoteActionExecutionContext context;

  private FileSystem fs;
  private Path execRoot;
  private ChunkedBlobUploader uploader;

  @Before
  public void setUp() throws Exception {
    fs = new InMemoryFileSystem(new JavaClock(), DigestHashFunction.SHA256);
    execRoot = fs.getPath("/execroot");
    execRoot.createDirectoryAndParents();

    ChunkingConfig.FastCdc config = new ChunkingConfig.FastCdc(1024, 2, 0);
    uploader =
        new ChunkedBlobUploader(
            grpcCacheClient, combinedCache, config, DIGEST_UTIL, /* concurrency= */ 8);
  }

  @Test
  public void getChunkingThreshold_returnsConfiguredValue() {
    ChunkingConfig.FastCdc config = new ChunkingConfig.FastCdc(512, 2, 0);
    ChunkedBlobUploader uploader =
        new ChunkedBlobUploader(
            grpcCacheClient, combinedCache, config, DIGEST_UTIL, /* concurrency= */ 8);

    assertThat(uploader.getChunkingThreshold()).isEqualTo(512 * 4);
  }

  @Test
  public void uploadChunked_createsFreshChunkerPerUpload() throws Exception {
    AtomicInteger chunkersCreated = new AtomicInteger();
    ChunkingConfig config =
        new ChunkingConfig() {
          @Override
          public ChunkingFunction.Value chunkingFunction() {
            return ChunkingFunction.Value.FAST_CDC_2020;
          }

          @Override
          public long chunkingThreshold() {
            return 0;
          }

          @Override
          public ContentDefinedChunker createChunker(DigestUtil digestUtil) {
            chunkersCreated.incrementAndGet();
            return input -> ImmutableList.of(digestUtil.compute(input.readAllBytes()));
          }
        };
    ChunkedBlobUploader uploader =
        new ChunkedBlobUploader(
            grpcCacheClient, combinedCache, config, DIGEST_UTIL, /* concurrency= */ 8);
    Path file1 = execRoot.getRelative("one.txt");
    Path file2 = execRoot.getRelative("two.txt");
    byte[] data1 = new byte[] {1, 2, 3};
    byte[] data2 = new byte[] {4, 5, 6};
    writeFile(file1, data1);
    writeFile(file2, data2);

    when(grpcCacheClient.findMissingDigests(any(), any()))
        .thenReturn(immediateFuture(ImmutableSet.of()));
    when(grpcCacheClient.spliceBlob(any(), any(), any(), any()))
        .thenReturn(immediateVoidFuture());

    uploader.uploadChunked(context, DIGEST_UTIL.compute(data1), file1);
    uploader.uploadChunked(context, DIGEST_UTIL.compute(data2), file2);

    assertThat(chunkersCreated.get()).isEqualTo(2);
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
        .thenAnswer(
            invocation -> {
              List<Digest> digests = invocation.getArgument(1);
              return immediateFuture(ImmutableSet.copyOf(digests));
            });
    when(combinedCache.uploadBlob(any(), any(Digest.class), any()))
        .thenReturn(immediateVoidFuture());
    when(grpcCacheClient.spliceBlob(any(), any(), any(), any())).thenReturn(immediateVoidFuture());

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
        .thenReturn(immediateFuture(ImmutableSet.of()));
    when(grpcCacheClient.spliceBlob(any(), any(), any(), any())).thenReturn(immediateVoidFuture());

    uploader.uploadChunked(context, blobDigest, file);

    verify(combinedCache, never()).uploadBlob(any(), any(Digest.class), any());
    verify(grpcCacheClient)
        .spliceBlob(any(), eq(blobDigest), any(), eq(ChunkingFunction.Value.FAST_CDC_2020));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void uploadChunked_someChunksMissing_uploadsOnlyMissingWithCorrectData() throws Exception {
    Path file = execRoot.getRelative("test_partial.txt");
    byte[] fileData = new byte[16384];
    new Random(42).nextBytes(fileData);
    writeFile(file, fileData);
    Digest blobDigest = DIGEST_UTIL.compute(fileData);

    ChunkingConfig.FastCdc config = new ChunkingConfig.FastCdc(1024, 2, 0);
    FastCdcChunker testChunker = new FastCdcChunker(config, DIGEST_UTIL);
    List<Digest> allChunkDigests;
    try (InputStream input = file.getInputStream()) {
      allChunkDigests = testChunker.chunkToDigests(input);
    }
    assertThat(allChunkDigests.size()).isAtLeast(5);

    Set<Digest> digestsToReportMissing = new LinkedHashSet<>();
    for (int i = 0; i < allChunkDigests.size(); i++) {
      boolean isFirst = i == 0;
      boolean isLast = i == allChunkDigests.size() - 1;
      boolean isOdd = i % 2 == 1;
      if (isFirst || isLast || isOdd) {
        digestsToReportMissing.add(allChunkDigests.get(i));
      }
    }

    Map<Digest, ByteString> expectedChunkData = new LinkedHashMap<>();
    try (InputStream input = file.getInputStream()) {
      for (Digest digest : allChunkDigests) {
        byte[] chunkBytes = input.readNBytes((int) digest.getSizeBytes());
        if (digestsToReportMissing.contains(digest)) {
          expectedChunkData.put(digest, ByteString.copyFrom(chunkBytes));
        }
      }
    }

    when(grpcCacheClient.findMissingDigests(any(), any()))
        .thenReturn(immediateFuture(ImmutableSet.copyOf(digestsToReportMissing)));
    Map<Digest, ByteString> actualUploads = new HashMap<>();
    when(combinedCache.uploadBlob(any(), any(Digest.class), any()))
        .thenAnswer(
            invocation -> {
              Digest d = invocation.getArgument(1);
              ByteString bs = invocation.getArgument(2);
              actualUploads.put(d, bs);
              return immediateVoidFuture();
            });
    when(grpcCacheClient.spliceBlob(any(), any(), any(), any())).thenReturn(immediateVoidFuture());

    uploader.uploadChunked(context, blobDigest, file);

    assertThat(actualUploads.keySet()).isEqualTo(expectedChunkData.keySet());
    for (Map.Entry<Digest, ByteString> entry : expectedChunkData.entrySet()) {
      assertThat(actualUploads.get(entry.getKey())).isEqualTo(entry.getValue());
    }
    verify(grpcCacheClient)
        .spliceBlob(
            any(),
            eq(blobDigest),
            eq(allChunkDigests),
            eq(ChunkingFunction.Value.FAST_CDC_2020));
  }

  @Test
  public void uploadChunked_repMaxCdcConfig_splicesWithRepMaxCdc() throws Exception {
    Path file = execRoot.getRelative("repmax.txt");
    byte[] data = new byte[1024];
    new Random(42).nextBytes(data);
    writeFile(file, data);
    Digest blobDigest = DIGEST_UTIL.compute(data);
    ChunkedBlobUploader uploader =
        new ChunkedBlobUploader(
            grpcCacheClient,
            combinedCache,
            new ChunkingConfig.RepMaxCdc(256, 0),
            DIGEST_UTIL,
            /* concurrency= */ 8);

    when(grpcCacheClient.findMissingDigests(any(), any()))
        .thenReturn(immediateFuture(ImmutableSet.of()));
    when(grpcCacheClient.spliceBlob(any(), any(), any(), any())).thenReturn(immediateVoidFuture());

    uploader.uploadChunked(context, blobDigest, file);

    verify(grpcCacheClient)
        .spliceBlob(any(), eq(blobDigest), any(), eq(ChunkingFunction.Value.REP_MAX_CDC));
  }

  private void writeFile(Path path, byte[] data) throws IOException {
    try (var out = path.getOutputStream()) {
      out.write(data);
    }
  }
}
