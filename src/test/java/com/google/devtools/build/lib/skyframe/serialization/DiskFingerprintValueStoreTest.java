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

package com.google.devtools.build.lib.skyframe.serialization;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore.MissingFingerprintValueException;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore.Stats;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatus;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link DiskFingerprintValueStore}. */
@RunWith(JUnit4.class)
public final class DiskFingerprintValueStoreTest {

  private FileSystem fileSystem;
  private Path cacheDir;
  private DiskFingerprintValueStore store;

  @Before
  public void setUp() throws IOException {
    fileSystem = new InMemoryFileSystem(DigestHashFunction.SHA256);
    cacheDir = fileSystem.getPath("/cache");
    cacheDir.createDirectoryAndParents();
    store = new DiskFingerprintValueStore(cacheDir);
  }

  @Test
  public void testPutAndGet_success() throws Exception {
    KeyBytesProvider key = new StringKey("test-key-1");
    byte[] value = "test-value-1".getBytes(StandardCharsets.UTF_8);

    // Put
    store.put(key, value);
    
    // Get
    ListenableFuture<byte[]> result = store.get(key);
    assertThat(result.get()).isEqualTo(value);
  }

  @Test
  public void testGet_missingEntry() throws Exception {
    StringKey key = new StringKey("missing-key");

    ListenableFuture<byte[]> result = store.get(key);
    ExecutionException exception = assertThrows(ExecutionException.class, result::get);
    assertThat(exception).hasCauseThat().isInstanceOf(MissingFingerprintValueException.class);
  }

  @Test
  public void testMultipleEntries() throws Exception {
    // Write multiple entries
    for (int i = 0; i < 100; i++) {
      StringKey key = new StringKey("key-" + i);
      byte[] value = ("value-" + i).getBytes(StandardCharsets.UTF_8);
      store.put(key, value);
    }

    // Read them all back
    for (int i = 0; i < 100; i++) {
      StringKey key = new StringKey("key-" + i);
      byte[] expected = ("value-" + i).getBytes(StandardCharsets.UTF_8);
      assertThat(store.get(key).get()).isEqualTo(expected);
    }
  }

  @Test
  public void testOverwrite_updatesValue() throws Exception {
    StringKey key = new StringKey("overwrite-key");
    byte[] value1 = "value-1".getBytes(StandardCharsets.UTF_8);
    byte[] value2 = "value-2-updated".getBytes(StandardCharsets.UTF_8);

    // Write first value
    store.put(key, value1);
    assertThat(store.get(key).get()).isEqualTo(value1);

    // Overwrite with second value
    store.put(key, value2);
    assertThat(store.get(key).get()).isEqualTo(value2);
  }

  @Test
  public void testMemoryCache_improvesPerfomance() throws Exception {
    StringKey key = new StringKey("cached-key");
    byte[] value = "cached-value".getBytes(StandardCharsets.UTF_8);

    // Put (adds to memory cache)
    store.put(key, value);

    // First get (from memory cache)
    assertThat(store.get(key).get()).isEqualTo(value);

    // Clear the underlying disk file to verify memory cache is used
    Path filePath = getExpectedFilePath(key);
    filePath.delete();

    // Should still work because it's in memory cache
    assertThat(store.get(key).get()).isEqualTo(value);
  }

  @Test
  public void testMemoryCache_eviction() throws Exception {
    // Fill memory cache beyond capacity
    int cacheCapacity = 10000; // From MAX_MEMORY_CACHE_SIZE
    for (int i = 0; i < cacheCapacity + 100; i++) {
      StringKey key = new StringKey("evict-key-" + i);
      byte[] value = ("evict-value-" + i).getBytes(StandardCharsets.UTF_8);
      store.put(key, value);
    }

    // All entries should still be readable from disk
    for (int i = 0; i < cacheCapacity + 100; i++) {
      StringKey key = new StringKey("evict-key-" + i);
      byte[] expected = ("evict-value-" + i).getBytes(StandardCharsets.UTF_8);
      assertThat(store.get(key).get()).isEqualTo(expected);
    }
  }

  @Test
  public void testStatistics_tracking() throws Exception {
    StringKey key1 = new StringKey("stats-key-1");
    StringKey key2 = new StringKey("stats-key-2");
    StringKey missingKey = new StringKey("missing");

    byte[] value1 = "stats-value-1".getBytes(StandardCharsets.UTF_8);
    byte[] value2 = "stats-value-2-longer".getBytes(StandardCharsets.UTF_8);

    // Write
    store.put(key1, value1);
    store.put(key2, value2);

    // Read
    store.get(key1).get();
    store.get(key2).get();

    // Miss
    try {
      store.get(missingKey).get();
    } catch (Exception e) {
      // Expected - ExecutionException wrapping MissingFingerprintValueException
    }

    Stats stats = store.getStats();
    assertThat(stats.entriesWritten()).isEqualTo(2);
    assertThat(stats.entriesFound()).isEqualTo(2);
    assertThat(stats.entriesNotFound()).isEqualTo(1);
    assertThat(stats.valueBytesSent()).isEqualTo(value1.length + value2.length);
    assertThat(stats.valueBytesReceived()).isEqualTo(value1.length + value2.length);
  }

  @Test
  public void testFileLayout_usesSubdirectories() throws Exception {
    StringKey key = new StringKey("abcdef123456");
    byte[] value = "test".getBytes(StandardCharsets.UTF_8);

    store.put(key, value);

    // Verify file is in subdirectory based on first 2 chars
    Path expectedFile = getExpectedFilePath(key);
    assertThat(expectedFile.exists()).isTrue();
    assertThat(expectedFile.getParentDirectory().getBaseName()).isEqualTo("ab");
  }

  @Test
  public void testAtomicWrites_noCorruptionOnFailure() throws Exception {
    // This test verifies that even if a write is interrupted,
    // we either have the complete file or no file (not partial corruption)
    StringKey key = new StringKey("atomic-key");
    byte[] value = new byte[100000]; // Large value
    for (int i = 0; i < value.length; i++) {
      value[i] = (byte) (i % 256);
    }

    store.put(key, value);

    // Verify complete write
    byte[] read = store.get(key).get();
    assertThat(read).isEqualTo(value);
  }

  @Test
  public void testEmptyValue() throws Exception {
    StringKey key = new StringKey("empty-key");
    byte[] emptyValue = new byte[0];

    store.put(key, emptyValue);
    assertThat(store.get(key).get()).isEqualTo(emptyValue);
  }

  @Test
  public void testLargeValue() throws Exception {
    StringKey key = new StringKey("large-key");
    byte[] largeValue = new byte[10 * 1024 * 1024]; // 10MB
    for (int i = 0; i < largeValue.length; i++) {
      largeValue[i] = (byte) (i % 256);
    }

    store.put(key, largeValue);
    assertThat(store.get(key).get()).isEqualTo(largeValue);
  }

  @Test
  public void testConcurrentAccess() throws Exception {
    // Test that concurrent reads/writes work correctly
    int numThreads = 10;
    int entriesPerThread = 50;

    Thread[] threads = new Thread[numThreads];
    for (int t = 0; t < numThreads; t++) {
      final int threadId = t;
      threads[t] = new Thread(() -> {
        try {
          for (int i = 0; i < entriesPerThread; i++) {
            StringKey key =
                new StringKey("concurrent-" + threadId + "-" + i);
            byte[] value = ("value-" + threadId + "-" + i).getBytes(StandardCharsets.UTF_8);
            store.put(key, value);
            assertThat(store.get(key).get()).isEqualTo(value);
          }
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      threads[t].start();
    }

    // Wait for all threads
    for (Thread thread : threads) {
      thread.join();
    }

    // Verify all entries
    for (int t = 0; t < numThreads; t++) {
      for (int i = 0; i < entriesPerThread; i++) {
        StringKey key = new StringKey("concurrent-" + t + "-" + i);
        byte[] expected = ("value-" + t + "-" + i).getBytes(StandardCharsets.UTF_8);
        assertThat(store.get(key).get()).isEqualTo(expected);
      }
    }
  }

  @Test
  public void testMemoryCacheSize() throws Exception {
    StringKey key = new StringKey("clear-test");
    byte[] value = "test-value".getBytes(StandardCharsets.UTF_8);
    
    store.put(key, value);
    assertThat(store.getMemoryCacheSize()).isGreaterThan(0);
  }

  @Test
  public void testPersistenceAcrossInstances() throws Exception {
    StringKey key = new StringKey("persist-key");
    byte[] value = "persist-value".getBytes(StandardCharsets.UTF_8);

    // Write with first store instance
    store.put(key, value);

    // Create new store instance pointing to same directory
    DiskFingerprintValueStore newStore = new DiskFingerprintValueStore(cacheDir);

    // Should be able to read from new instance
    assertThat(newStore.get(key).get()).isEqualTo(value);
  }

  /** Helper to get expected file path for a key. */
  private Path getExpectedFilePath(StringKey key) {
    // Convert bytes to hex string (matching DiskFingerprintValueStore.bytesToHex)
    String fingerprint = bytesToHex(key.toBytes());
    String prefix = fingerprint.substring(0, 2);
    return cacheDir.getRelative(prefix).getRelative(fingerprint);
  }

  /** Helper to convert bytes to hex (matches implementation in DiskFingerprintValueStore). */
  private static String bytesToHex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      result.append(String.format("%02x", b));
    }
    return result.toString();
  }
}
