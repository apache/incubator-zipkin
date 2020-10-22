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
package zipkin2.storage.cassandra.internal;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import java.util.Optional;

public final class KeyspaceMetadataUtil {

  public static int getDefaultTtl(KeyspaceMetadata keyspaceMetadata, String table) {
    return (int) keyspaceMetadata.getTable(table)
      .map(TableMetadata::getOptions)
      .flatMap(o -> Optional.ofNullable(o.get(CqlIdentifier.fromCql("default_time_to_live"))))
      .orElse(0);
  }

  KeyspaceMetadataUtil() {
  }
}
