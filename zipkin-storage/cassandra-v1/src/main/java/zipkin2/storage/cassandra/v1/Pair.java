/*
 * Copyright 2015-2020 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.storage.cassandra.v1;

final class Pair {
  final long left, right;

  Pair(long left, long right) {
    this.left = left;
    this.right = right;
  }

  @Override public boolean equals(Object o) {
    if (o == this) return true;
    if (!(o instanceof Pair)) return false;
    Pair that = (Pair) o;
    return left == that.left && right == that.right;
  }

  @Override public int hashCode() {
    int h$ = 1;
    h$ *= 1000003;
    h$ ^= (int) (h$ ^ ((left >>> 32) ^ left));
    h$ *= 1000003;
    h$ ^= (int) (h$ ^ ((right >>> 32) ^ right));
    return h$;
  }
}
