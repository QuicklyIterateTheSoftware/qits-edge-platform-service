package eu.wohlben.qits.edge.acme;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.shredzone.acme4j.util.KeyPairUtils;

/** Installs an immutable certificate generation and switches {@code current} in one rename. */
public final class PemCertificateStore {

  public record InstalledCertificate(Instant expiresAt, Set<String> dnsNames, String issuer) {}

  private final Path root;

  public PemCertificateStore(Path root) {
    this.root = root;
  }

  public Optional<InstalledCertificate> current() throws Exception {
    Path certificate = root.resolve("current/lets-encrypt.crt");
    if (!Files.isRegularFile(certificate)) {
      return Optional.empty();
    }
    try (var input = Files.newInputStream(certificate)) {
      X509Certificate leaf =
          (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      Collection<java.util.List<?>> alternatives = leaf.getSubjectAlternativeNames();
      Set<String> names =
          alternatives == null
              ? Set.of()
              : alternatives.stream()
                  .filter(entry -> Integer.valueOf(2).equals(entry.getFirst()))
                  .map(entry -> entry.get(1).toString())
                  .collect(Collectors.toUnmodifiableSet());
      return Optional.of(
          new InstalledCertificate(
              leaf.getNotAfter().toInstant(), names, leaf.getIssuerX500Principal().getName()));
    }
  }

  public void install(KeyPair keyPair, String certificatePem) throws Exception {
    verifyMatches(keyPair, certificatePem);
    Files.createDirectories(root.resolve("versions"));
    String generation = Instant.now().toEpochMilli() + "-" + UUID.randomUUID();
    Path directory = root.resolve("versions").resolve(generation);
    Files.createDirectory(directory);

    Path key = directory.resolve("lets-encrypt.key");
    Path certificate = directory.resolve("lets-encrypt.crt");
    try (StringWriter writer = new StringWriter()) {
      KeyPairUtils.writeKeyPair(keyPair, writer);
      Files.writeString(key, writer.toString(), StandardCharsets.US_ASCII);
    }
    Files.writeString(certificate, certificatePem, StandardCharsets.US_ASCII);
    setPermissions(key, "rw-r-----");
    setPermissions(certificate, "rw-r--r--");

    Path next = root.resolve(".current-" + UUID.randomUUID());
    Files.createSymbolicLink(next, root.relativize(directory));
    move(next, root.resolve("current"));
  }

  private static void verifyMatches(KeyPair keyPair, String certificatePem) throws Exception {
    X509Certificate leaf;
    try (var input =
        new java.io.ByteArrayInputStream(certificatePem.getBytes(StandardCharsets.US_ASCII))) {
      leaf = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
    }
    if (!java.util.Arrays.equals(
        leaf.getPublicKey().getEncoded(), keyPair.getPublic().getEncoded())) {
      throw new IllegalArgumentException("issued certificate does not match its private key");
    }
    leaf.checkValidity();
  }

  private static void setPermissions(Path path, String permissions) throws IOException {
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    } catch (UnsupportedOperationException ignored) {
      // The production volume is POSIX. Tests may deliberately use another file system.
    }
  }

  private static void move(Path source, Path target) throws IOException {
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
