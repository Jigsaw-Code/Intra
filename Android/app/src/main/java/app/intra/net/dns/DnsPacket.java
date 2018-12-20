/*
Copyright 2018 Jigsaw Operations LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package app.intra.net.dns;

import androidx.annotation.Nullable;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.net.UnknownHostException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A representation of a DNS query or response packet.  This class provides access to
 * the relevant contents of a DNS packet.
 */
public class DnsPacket {

  private byte[] data;
  private ByteBuffer body;

  private static final short TYPE_A = 1;
  private static final short TYPE_AAAA = 28;

  private static class DnsRecord {
    String name;
    short rtype;
    short rclass;
    int ttl;
    byte[] data;
    ByteBuffer buffer;
    int ttlOffset;

    void reduceTTL(int amount) {
      if (amount == 0) {
        return;
      }
      ttl = Math.max(0, ttl - amount);
      buffer.position(ttlOffset);
      buffer.putInt(ttl);
    }
  }

  private short id;
  private boolean qr;
  private byte opcode;
  private boolean aa;
  private boolean tc;
  private boolean rd;
  private boolean ra;
  private byte z;
  private byte rcode;
  private Question[] question;
  private DnsRecord[] answer;
  private DnsRecord[] authority;
  private DnsRecord[] additional;

  private static String readName(ByteBuffer buffer) throws BufferUnderflowException,
      ProtocolException {
    StringBuilder nameBuffer = new StringBuilder();
    byte labelLength = buffer.get();
    while (labelLength > 0) {
      byte[] labelBytes = new byte[labelLength];
      buffer.get(labelBytes);
      String label = new String(labelBytes);
      nameBuffer.append(label);
      nameBuffer.append(".");

      labelLength = buffer.get();
    }
    if (labelLength < 0) {
      // This is a compressed label, starting with a 14-bit backwards offset consisting of
      // the lower 6 bits from the first byte and all 8 from the second.
      final int OFFSET_HIGH_BITS_START = 0;
      final int OFFSET_HIGH_BITS_SIZE = 6;
      byte offsetHighBits = getBits(labelLength, OFFSET_HIGH_BITS_START,
          OFFSET_HIGH_BITS_SIZE);
      byte offsetLowBits = buffer.get();
      int offset = (offsetHighBits << 8) | (offsetLowBits & 0xFF);
      byte[] data = buffer.array();
      // Only allow references that terminate before the current point, to avoid stack
      // overflow attacks.
      int delta = buffer.position() - offset;
      if (offset < 0 || delta < 0) {
        throw new ProtocolException("Bad compressed name");
      }
      ByteBuffer referenceBuffer = ByteBuffer.wrap(data, offset, delta);
      String suffix = readName(referenceBuffer);
      nameBuffer.append(suffix);
    }
    return nameBuffer.toString();

  }

  private static DnsRecord[] readRecords(ByteBuffer src, short numRecords)
      throws BufferUnderflowException, ProtocolException {
    DnsRecord[] dest = new DnsRecord[numRecords];
    for (int i = 0; i < dest.length; ++i) {
      DnsRecord r = new DnsRecord();
      r.buffer = src.slice();
      int startPosition = src.position();
      r.name = readName(src);
      r.rtype = src.getShort();
      r.rclass = src.getShort();
      r.ttlOffset = src.position() - startPosition;
      r.ttl = src.getInt();
      r.data = new byte[src.getShort()];
      src.get(r.data);
      dest[i] = r;
    }
    return dest;
  }

  private static boolean getBit(byte src, int index) {
    return (src & (1 << index)) != 0;
  }

  private static byte getBits(byte src, int start, int size) {
    int mask = (1 << size) - 1;
    return (byte) ((src >>> start) & mask);
  }

  public DnsPacket(byte[] data) throws ProtocolException {
    this.data = data;
    ByteBuffer buffer = ByteBuffer.wrap(data);
    try {
      this.body = ByteBuffer.wrap(data, 2, data.length - 2);
    } catch (IndexOutOfBoundsException e) {
      throw new ProtocolException("Packet extremely short");
    }
    try {
      id = buffer.getShort();
      // First flag byte: QR, Opcode (4 bits), AA, RD, RA
      byte flags1 = buffer.get();
      final int QR_BIT = 7;
      final int OPCODE_SIZE = 4;
      final int OPCODE_START = 3;
      final int AA_BIT = 2;
      final int TC_BIT = 1;
      final int RD_BIT = 0;
      qr = getBit(flags1, QR_BIT);
      opcode = getBits(flags1, OPCODE_START, OPCODE_SIZE);
      aa = getBit(flags1, AA_BIT);
      tc = getBit(flags1, TC_BIT);
      rd = getBit(flags1, RD_BIT);

      // Second flag byte: RA, 0, 0, 0, Rcode
      final int RA_BIT = 7;
      final int ZEROS_START = 4;
      final int ZEROS_SIZE = 3;
      final int RCODE_START = 0;
      final int RCODE_SIZE = 4;
      byte flags2 = buffer.get();
      ra = getBit(flags2, RA_BIT);
      z = getBits(flags2, ZEROS_START, ZEROS_SIZE);
      rcode = getBits(flags2, RCODE_START, RCODE_SIZE);

      short numQuestions = buffer.getShort();
      short numAnswers = buffer.getShort();
      short numAuthorities = buffer.getShort();
      short numAdditional = buffer.getShort();

      question = new Question[numQuestions];
      for (short i = 0; i < numQuestions; ++i) {
        question[i] = new Question(readName(buffer), buffer.getShort(), buffer.getShort());
      }
      answer = readRecords(buffer, numAnswers);
      authority = readRecords(buffer, numAuthorities);
      additional = readRecords(buffer, numAdditional);
    } catch (BufferUnderflowException e) {
      throw new ProtocolException("Packet too short");
    }
  }

  public short getId() {
    return id;
  }

  public boolean isNormalQuery() {
    return !qr && question.length > 0 && z == 0 && authority.length == 0 && answer.length == 0;
  }

  public boolean isResponse() {
    return qr;
  }


  public Question getQuestion() {
    if (question.length > 0) {
      return question[0];
    }
    return null;
  }

  public List<InetAddress> getResponseAddresses() {
    List<InetAddress> addresses = new ArrayList<>();
    for (DnsRecord[] src : new DnsRecord[][]{answer, authority}) {
      for (DnsRecord r : src) {
        if (r.rtype == TYPE_A || r.rtype == TYPE_AAAA) {
          try {
            addresses.add(InetAddress.getByAddress(r.data));
          } catch (UnknownHostException e) {
          }
        }
      }
    }
    return addresses;
  }

  public int getTTL() {
    boolean hasTTL = false;
    int minTTL = Integer.MAX_VALUE;
    for (DnsRecord[] src : new DnsRecord[][]{answer, authority, additional}) {
      for (DnsRecord r : src) {
        minTTL = Math.min(minTTL, r.ttl);
        hasTTL = true;
      }
    }
    if (hasTTL) {
      return minTTL;
    }
    return 0;
  }

  public void reduceTTL(int amount) {
    for (DnsRecord[] src : new DnsRecord[][]{answer, authority, additional}) {
      for (DnsRecord r : src) {
        r.reduceTTL(amount);
      }
    }
  }

  public byte[] getData() {
    return data;
  }

  public int length() {
    return data.length;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (!(obj instanceof DnsPacket)) {
      return false;
    }
    DnsPacket other = (DnsPacket)obj;
    // Equality ignores ID.
    return body.equals(other.body);
  }

  @Override
  public int hashCode() {
    return body.hashCode();
  }
}
