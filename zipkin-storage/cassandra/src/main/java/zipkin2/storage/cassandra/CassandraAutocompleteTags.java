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
package zipkin2.storage.cassandra;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import zipkin2.Call;
import zipkin2.storage.AutocompleteTags;

class CassandraAutocompleteTags implements AutocompleteTags {
  final boolean enabled;
  final Call<List<String>> keysCall;
  final SelectAutocompleteValues.Factory valuesCallFactory;

  CassandraAutocompleteTags(CassandraStorage storage) {
    enabled = storage.searchEnabled
      && !storage.autocompleteKeys.isEmpty()
      && storage.metadata().hasAutocompleteTags;
    keysCall = Call.create(Collections.unmodifiableList(new ArrayList<>(storage.autocompleteKeys)));
    valuesCallFactory = enabled ? new SelectAutocompleteValues.Factory(storage.session()) : null;
  }

  @Override public Call<List<String>> getKeys() {
    if (!enabled) return Call.emptyList();
    return keysCall.clone();
  }

  @Override public Call<List<String>> getValues(String key) {
    if (key == null) throw new NullPointerException("key == null");
    if (key.isEmpty()) throw new IllegalArgumentException("key was empty");
    if (!enabled) return Call.emptyList();
    return valuesCallFactory.create(key);
  }
}
