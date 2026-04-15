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

import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;

import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A fixed-capacity sliding window of in-flight {@link ListenableFuture}s. Used to pipeline tasks
 * with bounded parallelism while consuming results in submission order: the caller adds a new
 * future for each task, and takes the head future (blocking until it completes) whenever the
 * window is full or to drain at the end.
 *
 * <p>On {@link #close()}, any futures still in flight are cancelled. Not thread-safe.
 */
final class FutureWindow<T> implements AutoCloseable {

  private final int maxSize;
  private final Deque<ListenableFuture<T>> window = new ArrayDeque<>();

  FutureWindow(int maxSize) {
    this.maxSize = maxSize;
  }

  boolean isFull() {
    return window.size() >= maxSize;
  }

  boolean isEmpty() {
    return window.isEmpty();
  }

  void add(ListenableFuture<T> future) {
    window.add(future);
  }

  /** Blocks on the head future and returns its result. */
  T take() throws IOException, InterruptedException {
    return getFromFuture(window.removeFirst());
  }

  @Override
  public void close() {
    for (ListenableFuture<T> future : window) {
      future.cancel(true);
    }
  }
}
