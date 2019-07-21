/*
 * Copyright 2015-2019 The OpenZipkin Authors
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
package zipkin2.elasticsearch.internal;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import zipkin2.internal.Nullable;

public final class JsonReaders {

  /**
   * Navigates to a field of a JSON-serialized object. For example,
   *
   * <pre>{@code
   * JsonParser status = enterPath(JsonAdapters.jsonParser(stream), "message", "status");
   * if (status != null) throw new IllegalStateException(status.nextString());
   * }</pre>
   */
  @Nullable public static JsonParser enterPath(JsonParser parser, String path1, String path2)
    throws IOException {
    return enterPath(parser, path1) != null ? enterPath(parser, path2) : null;
  }

  @Nullable public static JsonParser enterPath(JsonParser parser, String path) throws IOException {
    if (!currentOrNextTokenEquals(parser, JsonToken.START_OBJECT)) return null;

    JsonToken value;
    while ((value = parser.nextValue()) != JsonToken.END_OBJECT) {
      if (value == null) {
        // End of input so ignore.
        return null;
      }
      if (parser.getCurrentName().equalsIgnoreCase(path) && value != JsonToken.VALUE_NULL) {
        return parser;
      } else {
        parser.skipChildren();
      }
    }
    return null;
  }

  static boolean currentOrNextTokenEquals(JsonParser parser, JsonToken expected)
    throws IOException {
    try {
      // The parser may not be at a token, yet. If that's the case advance.
      if (parser.currentToken() == null) return parser.nextToken() == expected;
      // If we are still not at the expected token, we could be an another or an empty body.
      return parser.currentToken() == expected;
    } catch (JsonParseException e) { // likely not json
      return false;
    }
  }

  public static List<String> collectValuesNamed(JsonParser parser, String name) throws IOException {
    if (!currentOrNextTokenEquals(parser, JsonToken.START_OBJECT)) {
      throw new IllegalArgumentException("Expecting object start, got " + parser.currentToken());
    }
    Set<String> result = new LinkedHashSet<>();
    visitObject(parser, name, result);
    return new ArrayList<>(result);
  }

  static void visitObject(JsonParser parser, String name, Set<String> result) throws IOException {
    if (!currentOrNextTokenEquals(parser, JsonToken.START_OBJECT)) {
      throw new IllegalArgumentException("Expecting object start, got " + parser.currentToken());
    }
    JsonToken value;
    while ((value = parser.nextValue()) != JsonToken.END_OBJECT) {
      if (value == null) {
        // End of input so ignore.
        return;
      }
      if (parser.getCurrentName().equals(name)) {
        result.add(parser.getText());
      } else {
        visitNextOrSkip(parser, name, result);
      }
    }
  }

  static void visitNextOrSkip(JsonParser parser, String name, Set<String> result)
    throws IOException {
    switch (parser.currentToken()) {
      case START_ARRAY:
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
          if (token == null) {
            // End of input so ignore.
            return;
          }
          visitObject(parser, name, result);
        }
        break;
      case START_OBJECT:
        visitObject(parser, name, result);
        break;
      default:
        // Skip current value.
    }
  }

  JsonReaders() {
  }
}
