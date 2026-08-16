package eu.wohlben.qits.edge.acme;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Waits for Hetzner's authoritative servers, avoiding stale recursive NXDOMAIN caches. */
public final class AuthoritativeDnsPropagation implements DnsPropagation {

  private static final List<String> NAMESERVERS =
      List.of("hydrogen.ns.hetzner.com", "oxygen.ns.hetzner.com", "helium.ns.hetzner.de");

  @Override
  public void await(String fqdn, String value, Duration timeout) throws Exception {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      int visible = 0;
      for (String nameserver : NAMESERVERS) {
        if (visibleAt(nameserver, fqdn, value)) {
          visible++;
        }
      }
      if (visible == NAMESERVERS.size()) {
        return;
      }
      Thread.sleep(Duration.ofSeconds(5));
    }
    throw new IllegalStateException("DNS challenge did not reach authoritative servers in time");
  }

  private boolean visibleAt(String nameserver, String fqdn, String expected) {
    try {
      int id = ThreadLocalRandom.current().nextInt(0x10000);
      byte[] query = query(id, fqdn);
      InetAddress address = InetAddress.getAllByName(nameserver)[0];
      try (DatagramSocket socket = new DatagramSocket()) {
        socket.setSoTimeout(3000);
        socket.send(new DatagramPacket(query, query.length, address, 53));
        byte[] bytes = new byte[4096];
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
        socket.receive(packet);
        return containsTxt(ByteBuffer.wrap(bytes, 0, packet.getLength()), id, expected);
      }
    } catch (Exception ignored) {
      return false;
    }
  }

  private static byte[] query(int id, String fqdn) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeShort(id);
      out.writeShort(0); // authoritative query: recursion is deliberately not requested
      out.writeShort(1);
      out.writeShort(0);
      out.writeShort(0);
      out.writeShort(0);
      for (String label : fqdn.split("\\.")) {
        byte[] encoded = label.getBytes(StandardCharsets.US_ASCII);
        out.writeByte(encoded.length);
        out.write(encoded);
      }
      out.writeByte(0);
      out.writeShort(16); // TXT
      out.writeShort(1); // IN
    }
    return bytes.toByteArray();
  }

  private static boolean containsTxt(ByteBuffer message, int id, String expected) {
    if (message.remaining() < 12 || Short.toUnsignedInt(message.getShort()) != id) {
      return false;
    }
    int flags = Short.toUnsignedInt(message.getShort());
    int questions = Short.toUnsignedInt(message.getShort());
    int answers = Short.toUnsignedInt(message.getShort());
    message.position(message.position() + 4); // authority and additional counts
    if ((flags & 0x8000) == 0 || (flags & 0x000f) != 0) {
      return false;
    }
    for (int i = 0; i < questions; i++) {
      skipName(message);
      message.position(message.position() + 4);
    }
    for (int i = 0; i < answers; i++) {
      skipName(message);
      int type = Short.toUnsignedInt(message.getShort());
      message.position(message.position() + 6); // class and TTL
      int length = Short.toUnsignedInt(message.getShort());
      int end = message.position() + length;
      if (type == 16) {
        StringBuilder text = new StringBuilder();
        while (message.position() < end) {
          int partLength = Byte.toUnsignedInt(message.get());
          byte[] part = new byte[partLength];
          message.get(part);
          text.append(new String(part, StandardCharsets.UTF_8));
        }
        if (text.toString().equals(expected)) {
          return true;
        }
      }
      message.position(end);
    }
    return false;
  }

  private static void skipName(ByteBuffer message) {
    while (true) {
      int length = Byte.toUnsignedInt(message.get());
      if (length == 0) {
        return;
      }
      if ((length & 0xc0) == 0xc0) {
        message.get();
        return;
      }
      message.position(message.position() + length);
    }
  }
}
