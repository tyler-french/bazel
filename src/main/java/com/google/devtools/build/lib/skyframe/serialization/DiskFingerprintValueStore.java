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

import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.immediateWriteStatus;

import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatus;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A simple disk-based {@link FingerprintValueStore} for persistent analysis caching.
 *
 * <p>This implementation stores each fingerprint as a separate file in a directory structure
 * based on the fingerprint hash. This is intentionally kept simple for the initial implementation.
 *
 * <p>File layout: {@code <cacheDir>/<first2chars>/<fingerprint>}
 *
 * <p>Example: {@code analysis-cache/ab/abcdef123456...}
 *
 * <p>Thread-safe.
 */
@ThreadSafe
public final class DiskFingerprintValueStore implements FingerprintValueStore {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final Path cacheRoot;

  // Statistics tracking
  private final AtomicLong valueBytesReceived = new AtomicLong(0);
  private final AtomicLong valueBytesSent = new AtomicLong(0);
  private final AtomicLong keyBytesSent = new AtomicLong(0);
  private final AtomicLong entriesWritten = new AtomicLong(0);
  private final AtomicLong entriesFound = new AtomicLong(0);
  private final AtomicLong entriesNotFound = new AtomicLong(0);

  // Simple in-memory cache to avoid redundant disk I/O
  // TODO(b/358347099): Consider LRU eviction policy if memory usage becomes an issue
  private final ConcurrentHashMap<String, byte[]> memoryCache = new ConcurrentHashMap<>();
  private static final int MAX_MEMORY_CACHE_SIZE = 10000;

  /**
   * Creates a new disk-based fingerprint value store.
   *
   * @param cacheRoot the root directory where cache files will be stored
   * @throws IOException if the cache directory cannot be created
   */
  public DiskFingerprintValueStore(Path cacheRoot) throws IOException {
    this.cacheRoot = cacheRoot;
    cacheRoot.createDirectoryAndParents();
    logger.atInfo().log("Initialized disk analysis cache at: %s", cacheRoot);
  }

  @Override
  public WriteStatus put(KeyBytesProvider fingerprint, byte[] serializedBytes) {
    try {
      String fingerprintStr = bytesToHex(fingerprint.toBytes());
      keyBytesSent.addAndGet(fingerprint.toBytes().length);

      // Write to disk
      Path targetPath = getPathForFingerprint(fingerprintStr);
      Path parentDir = targetPath.getParentDirectory();
      parentDir.createDirectoryAndParents();

      // Write to temp file first, then atomic rename for safety
      Path tempPath = parentDir.getRelative(fingerprintStr + ".tmp");

      try {
        // Write using Bazel's VFS
        try (var out = tempPath.getOutputStream()) {
          out.write(serializedBytes);
        }
        // Atomic rename
        tempPath.renameTo(targetPath);

        valueBytesSent.addAndGet(serializedBytes.length);
        entriesWritten.incrementAndGet();

        // Also cache in memory
        if (memoryCache.size() < MAX_MEMORY_CACHE_SIZE) {
          memoryCache.put(fingerprintStr, serializedBytes);
        }

        return immediateWriteStatus();
      } finally {
        // Clean up temp file if it still exists
        if (tempPath.exists()) {
          tempPath.delete();
        }
      }
    } catch (IOException e) {
      logger.atWarning().withCause(e).log(
          "Failed to write fingerprint to disk analysis cache");
      return WriteStatuses.immediateFailedWriteStatus(e);
    }
  }

  @Override
  public ListenableFuture<byte[]> get(KeyBytesProvider fingerprint) throws IOException {
    String fingerprintStr = bytesToHex(fingerprint.toBytes());

    // Check memory cache first
    byte[] cached = memoryCache.get(fingerprintStr);
    if (cached != null) {
      entriesFound.incrementAndGet();
      valueBytesReceived.addAndGet(cached.length);
      return immediateFuture(cached);
    }

    // Check disk
    Path targetPath = getPathForFingerprint(fingerprintStr);
    if (!targetPath.exists()) {
      entriesNotFound.incrementAndGet();
      return immediateFailedFuture(
          new MissingFingerprintValueException(fingerprint));
    }

    try {
      byte[] bytes = FileSystemUtils.readContent(targetPath);
      entriesFound.incrementAndGet();
      valueBytesReceived.addAndGet(bytes.length);

      // Cache in memory for future reads
      if (memoryCache.size() < MAX_MEMORY_CACHE_SIZE) {
        memoryCache.put(fingerprintStr, bytes);
      }

      return immediateFuture(bytes);
    } catch (IOException e) {
      logger.atWarning().withCause(e).log(
          "Failed to read fingerprint from disk analysis cache: %s", targetPath);
      entriesNotFound.incrementAndGet();
      return immediateFailedFuture(
          new MissingFingerprintValueException(fingerprint, e));
    }
  }

  @Override
  public Stats getStats() {
    return new Stats(
        valueBytesReceived.get(),
        valueBytesSent.get(),
        keyBytesSent.get(),
        entriesWritten.get(),
        entriesFound.get(),
        entriesNotFound.get(),
        0, // getBatches - not tracked for disk store
        0  // setBatches - not tracked for disk store
    );
  }

  /**
   * Returns the file path for a given fingerprint.
   *
   * <p>Uses the first 2 characters to create a subdirectory to avoid having too many files
   * in a single directory (which can slow down filesystem operations).
   */
  private Path getPathForFingerprint(String fingerprint) {
    // Use first 2 characters for subdirectory to distribute files
    String subdir = fingerprint.length() >= 2 ? fingerprint.substring(0, 2) : "00";
    return cacheRoot.getRelative(subdir).getRelative(fingerprint);
  }

  /**
   * Converts a byte array to a hex string for use as a filename.
   */
  private static String bytesToHex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      result.append(String.format("%02x", b));
    }
    return result.toString();
  }

  /**
   * Clears the cache directory.
   *
   * <p>This is useful for testing or when cache corruption is detected.
   */
  public void clear() throws IOException {
    logger.atInfo().log("Clearing disk analysis cache at: %s", cacheRoot);
    cacheRoot.deleteTree();
    cacheRoot.createDirectoryAndParents();
    memoryCache.clear();

    // Reset stats
    valueBytesReceived.set(0);
    valueBytesSent.set(0);
    keyBytesSent.set(0);
    entriesWritten.set(0);
    entriesFound.set(0);
    entriesNotFound.set(0);
  }

  /**
   * Returns the cache root directory.
   *
   * <p>Useful for debugging and testing.
   */
  public Path getCacheRoot() {
    return cacheRoot;
  }

  /**
   * Returns the number of entries currently in the memory cache.
   *
   * <p>Useful for monitoring memory usage.
   */
  public int getMemoryCacheSize() {
    return memoryCache.size();
  }
}
