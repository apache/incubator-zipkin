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
package zipkin2.internal;

import static zipkin2.internal.HexCodec.HEX_DIGITS;

/**
 * Writes are unsafe as they do no bounds checks. This means you should take care to allocate or
 * wrap an array at least as big as you need prior to writing. As it is possible to calculate size
 * prior to writing, overrunning a buffer is a programming error.
 */
public final class WriteBuffer {
  public interface Writer<T> {
    int sizeInBytes(T value);

    void write(T value, WriteBuffer buffer);
  }

  public static WriteBuffer wrap(byte[] bytes) {
    return wrap(bytes, 0);
  }

  public static WriteBuffer wrap(byte[] bytes, int pos) {
    return new WriteBuffer(bytes, pos);
  }

  final byte[] buf;
  int pos;

  WriteBuffer(byte[] buf, int pos) {
    this.buf = buf;
    this.pos = pos;
  }

  public void writeByte(int v) {
    buf[pos++] = (byte) (v & 0xff);
  }

  public void write(byte[] v) {
    System.arraycopy(v, 0, buf, pos, v.length);
    pos += v.length;
  }

  void writeBackwards(long v) {
    int lastPos = pos + asciiSizeInBytes(v); // We write backwards from right to left.
    pos = lastPos;
    while (v != 0) {
      int digit = (int) (v % 10);
      buf[--lastPos] = (byte) HEX_DIGITS[digit];
      v /= 10;
    }
  }

  /** Inspired by {@code okio.Buffer.writeLong} */
  public void writeLongHex(long v) {
    int pos = this.pos;
    writeHexByte(buf, pos + 0, (byte) ((v >>> 56L) & 0xff));
    writeHexByte(buf, pos + 2, (byte) ((v >>> 48L) & 0xff));
    writeHexByte(buf, pos + 4, (byte) ((v >>> 40L) & 0xff));
    writeHexByte(buf, pos + 6, (byte) ((v >>> 32L) & 0xff));
    writeHexByte(buf, pos + 8, (byte) ((v >>> 24L) & 0xff));
    writeHexByte(buf, pos + 10, (byte) ((v >>> 16L) & 0xff));
    writeHexByte(buf, pos + 12, (byte) ((v >>> 8L) & 0xff));
    writeHexByte(buf, pos + 14, (byte) (v & 0xff));
    this.pos = pos + 16;
  }

  static void writeHexByte(byte[] data, int pos, byte b) {
    data[pos + 0] = (byte) HEX_DIGITS[(b >> 4) & 0xf];
    data[pos + 1] = (byte) HEX_DIGITS[b & 0xf];
  }

  final int pos() {
    return pos;
  }

  public void writeAscii(String v) {
    for (int i = 0, length = v.length(); i < length; i++) {
      writeByte(v.charAt(i) & 0xff);
    }
  }

  /**
   * This transcodes a UTF-16 Java String to UTF-8 bytes.
   *
   * <p>This looks most similar to {@code io.netty.buffer.ByteBufUtil.writeUtf8(AbstractByteBuf,
   * int, CharSequence, int)} v4.1, modified including features to address ASCII runs of text.
   */
  public void writeUtf8(CharSequence string) {
    for (int i = 0, len = string.length(); i < len; i++) {
      char ch = string.charAt(i);
      if (ch < 0x80) { // 7-bit ASCII character
        writeByte(ch);
        // This could be an ASCII run, or possibly entirely ASCII
        while (i < len - 1) {
          ch = string.charAt(i + 1);
          if (ch >= 0x80) break;
          i++;
          writeByte(ch); // another 7-bit ASCII character
        }
      } else if (ch < 0x800) { // 11-bit character
        writeByte(0xc0 | (ch >> 6));
        writeByte(0x80 | (ch & 0x3f));
      } else if (ch < 0xd800 || ch > 0xdfff) { // 16-bit character
        writeByte(0xe0 | (ch >> 12));
        writeByte(0x80 | ((ch >> 6) & 0x3f));
        writeByte(0x80 | (ch & 0x3f));
      } else { // Possibly a 21-bit character
        if (!Character.isHighSurrogate(ch)) { // Malformed or not UTF-8
          writeByte('?');
          continue;
        }
        if (i == len - 1) { // Truncated or not UTF-8
          writeByte('?');
          break;
        }
        char low = string.charAt(++i);
        if (!Character.isLowSurrogate(low)) { // Malformed or not UTF-8
          writeByte('?');
          writeByte(Character.isHighSurrogate(low) ? '?' : low);
          continue;
        }
        // Write the 21-bit character using 4 bytes
        // See http://www.unicode.org/versions/Unicode7.0.0/ch03.pdf#G2630
        int codePoint = Character.toCodePoint(ch, low);
        writeByte(0xf0 | (codePoint >> 18));
        writeByte(0x80 | ((codePoint >> 12) & 0x3f));
        writeByte(0x80 | ((codePoint >> 6) & 0x3f));
        writeByte(0x80 | (codePoint & 0x3f));
      }
    }
  }

  // Adapted from okio.Buffer.writeDecimalLong
  public void writeAscii(long v) {
    if (v == 0) {
      writeByte('0');
      return;
    }

    if (v == Long.MIN_VALUE) {
      writeAscii("-9223372036854775808");
      return;
    }

    if (v < 0) {
      writeByte('-');
      v = -v; // needs to be positive so we can use this for an array index
    }

    writeBackwards(v);
  }

  // com.squareup.wire.ProtoWriter.writeVarint v2.3.0
  void writeVarint(int v) {
    while ((v & ~0x7f) != 0) {
      writeByte((byte) ((v & 0x7f) | 0x80));
      v >>>= 7;
    }
    writeByte((byte) v);
  }

  // com.squareup.wire.ProtoWriter.writeVarint v2.3.0
  void writeVarint(long v) {
    while ((v & ~0x7fL) != 0) {
      writeByte((byte) ((v & 0x7f) | 0x80));
      v >>>= 7;
    }
    writeByte((byte) v);
  }

  void writeLongLe(long v) {
    writeByte((byte) (v & 0xff));
    writeByte((byte) ((v >> 8) & 0xff));
    writeByte((byte) ((v >> 16) & 0xff));
    writeByte((byte) ((v >> 24) & 0xff));
    writeByte((byte) ((v >> 32) & 0xff));
    writeByte((byte) ((v >> 40) & 0xff));
    writeByte((byte) ((v >> 48) & 0xff));
    writeByte((byte) ((v >> 56) & 0xff));
  }

  /**
   * This returns the bytes needed to transcode a UTF-16 Java String to UTF-8 bytes.
   *
   * <p>Originally based on
   * http://stackoverflow.com/questions/8511490/calculating-length-in-utf-8-of-java-string-without-actually-encoding-it
   *
   * <p>Later, ASCII run and malformed surrogate logic borrowed from okio.Utf8
   */
  // TODO: benchmark vs https://github.com/protocolbuffers/protobuf/blob/master/java/core/src/main/java/com/google/protobuf/Utf8.java#L240
  // there seem to be less branches for for strings without surrogates
  public static int utf8SizeInBytes(CharSequence string) {
    int sizeInBytes = 0;
    for (int i = 0, len = string.length(); i < len; i++) {
      char ch = string.charAt(i);
      if (ch < 0x80) {
        sizeInBytes++; // 7-bit ASCII character
        // This could be an ASCII run, or possibly entirely ASCII
        while (i < len - 1) {
          ch = string.charAt(i + 1);
          if (ch >= 0x80) break;
          i++;
          sizeInBytes++; // another 7-bit ASCII character
        }
      } else if (ch < 0x800) {
        sizeInBytes += 2; // 11-bit character
      } else if (ch < 0xd800 || ch > 0xdfff) {
        sizeInBytes += 3; // 16-bit character
      } else {
        int low = i + 1 < len ? string.charAt(i + 1) : 0;
        if (ch > 0xdbff || low < 0xdc00 || low > 0xdfff) {
          sizeInBytes++; // A malformed surrogate, which yields '?'.
        } else {
          // A 21-bit character
          sizeInBytes += 4;
          i++;
        }
      }
    }
    return sizeInBytes;
  }

  /**
   * Binary search for character width which favors matching lower numbers.
   *
   * <p>Adapted from okio.Buffer
   */
  public static int asciiSizeInBytes(long v) {
    if (v == 0) return 1;
    if (v == Long.MIN_VALUE) return 20;

    boolean negative = false;
    if (v < 0) {
      v = -v; // making this positive allows us to compare using less-than
      negative = true;
    }
    int width =
      v < 100000000L
        ? v < 10000L
        ? v < 100L ? v < 10L ? 1 : 2 : v < 1000L ? 3 : 4
        : v < 1000000L ? v < 100000L ? 5 : 6 : v < 10000000L ? 7 : 8
        : v < 1000000000000L
          ? v < 10000000000L ? v < 1000000000L ? 9 : 10 : v < 100000000000L ? 11 : 12
          : v < 1000000000000000L
            ? v < 10000000000000L ? 13 : v < 100000000000000L ? 14 : 15
            : v < 100000000000000000L
              ? v < 10000000000000000L ? 16 : 17
              : v < 1000000000000000000L ? 18 : 19;
    return negative ? width + 1 : width; // conditionally add room for negative sign
  }

  /**
   * A base 128 varint encodes 7 bits at a time, this checks how many bytes are needed to represent
   * the value.
   *
   * <p>See https://developers.google.com/protocol-buffers/docs/encoding#varints
   *
   * <p>This logic is the same as {@code com.squareup.wire.ProtoWriter.varint32Size} v2.3.0 which
   * benchmarked faster than loop variants of the frequently copy/pasted VarInt.varIntSize
   */
  public static int varintSizeInBytes(int value) {
    if ((value & (0xffffffff << 7)) == 0) return 1;
    if ((value & (0xffffffff << 14)) == 0) return 2;
    if ((value & (0xffffffff << 21)) == 0) return 3;
    if ((value & (0xffffffff << 28)) == 0) return 4;
    return 5;
  }

  /** Like {@link #varintSizeInBytes(int)}, except for uint64. */
  // TODO: benchmark vs https://github.com/protocolbuffers/protobuf/blob/master/java/core/src/main/java/com/google/protobuf/CodedOutputStream.java#L770
  // Since trace IDs are random, I guess they cover the entire spectrum of varint sizes and probably would especially benefit from this.
  public static int varintSizeInBytes(long v) {
    if ((v & (0xffffffffffffffffL << 7)) == 0) return 1;
    if ((v & (0xffffffffffffffffL << 14)) == 0) return 2;
    if ((v & (0xffffffffffffffffL << 21)) == 0) return 3;
    if ((v & (0xffffffffffffffffL << 28)) == 0) return 4;
    if ((v & (0xffffffffffffffffL << 35)) == 0) return 5;
    if ((v & (0xffffffffffffffffL << 42)) == 0) return 6;
    if ((v & (0xffffffffffffffffL << 49)) == 0) return 7;
    if ((v & (0xffffffffffffffffL << 56)) == 0) return 8;
    if ((v & (0xffffffffffffffffL << 63)) == 0) return 9;
    return 10;
  }
}
