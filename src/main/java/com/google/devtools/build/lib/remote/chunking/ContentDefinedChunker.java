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

package com.google.devtools.build.lib.remote.chunking;

import build.bazel.remote.execution.v2.Digest;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Interface for content-defined chunking algorithms (FastCDC, RepMaxCDC). */
public interface ContentDefinedChunker {

  /**
   * Chunks a stream and returns chunk digests in order.
   *
   * <p>The input stream is consumed fully. Each returned digest corresponds to a contiguous slice
   * of the input; concatenating the slices in order reproduces the original data.
   */
  List<Digest> chunkToDigests(InputStream input) throws IOException;
}
