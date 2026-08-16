package eu.wohlben.qits.edge.acme;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.shredzone.acme4j.Account;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.util.KeyPairUtils;

/** One RFC 8555 order. Scheduling and retry policy deliberately live outside this class. */
public final class AcmeCertificateIssuer {

  private static final Duration AUTHORIZATION_TIMEOUT = Duration.ofMinutes(5);
  private static final Duration ORDER_TIMEOUT = Duration.ofMinutes(5);

  private final DnsChallengeProvider dns;
  private final DnsPropagation propagation;
  private final PemCertificateStore certificates;

  public AcmeCertificateIssuer(
      DnsChallengeProvider dns, DnsPropagation propagation, PemCertificateStore certificates) {
    this.dns = dns;
    this.propagation = propagation;
    this.certificates = certificates;
  }

  public void issue(CertificateRequest request) throws Exception {
    Files.createDirectories(request.stateDirectory());
    KeyPair accountKey = accountKey(request.stateDirectory().resolve("account.key"));
    Session session = new Session(request.directory());
    Account account =
        new AccountBuilder()
            .useKeyPair(accountKey)
            .addEmail(request.email())
            .agreeToTermsOfService()
            .create(session);
    Order order = account.newOrder().domains(request.identifiers()).create();
    List<PresentedChallenge> presented = new ArrayList<>();
    try {
      for (Authorization authorization : order.getAuthorizations()) {
        if (authorization.getStatus() == Status.VALID) {
          continue;
        }
        Dns01Challenge challenge =
            authorization
                .findChallenge(Dns01Challenge.class)
                .orElseThrow(
                    () -> new IllegalStateException("ACME server offered no DNS-01 challenge"));
        String name = Dns01Challenge.toRRName(authorization.getIdentifier());
        String value = challenge.getDigest();
        dns.present(name, value);
        presented.add(new PresentedChallenge(name, value, challenge));
      }
      // The apex and its wildcard authorize through the same RRset with different values. Present
      // the complete set before asking the CA to validate either one, otherwise an eager validator
      // can observe the RRset in the brief interval between the two writes.
      for (PresentedChallenge challenge : presented) {
        propagation.await(challenge.name(), challenge.value(), request.challengeTimeout());
      }
      for (PresentedChallenge challenge : presented) {
        challenge.challenge().trigger();
      }
      for (Authorization authorization : order.getAuthorizations()) {
        if (authorization.getStatus() != Status.VALID
            && authorization.waitForCompletion(AUTHORIZATION_TIMEOUT) != Status.VALID) {
          String problem =
              authorization
                  .findChallenge(Dns01Challenge.class)
                  .flatMap(Dns01Challenge::getError)
                  .map(Object::toString)
                  .orElse("no ACME problem detail");
          throw new IllegalStateException(
              "ACME authorization failed for " + authorization.getIdentifier() + ": " + problem);
        }
      }
      if (order.waitUntilReady(ORDER_TIMEOUT) != Status.READY) {
        throw new IllegalStateException("ACME order did not become ready");
      }
      KeyPair certificateKey = KeyPairUtils.createECKeyPair("secp256r1");
      order.execute(certificateKey);
      if (order.waitForCompletion(ORDER_TIMEOUT) != Status.VALID) {
        throw new IllegalStateException("ACME order did not become valid");
      }
      var certificate = order.getCertificate();
      StringWriter pem = new StringWriter();
      certificate.writeCertificate(pem);
      certificates.install(certificateKey, pem.toString());
    } finally {
      for (PresentedChallenge challenge : presented.reversed()) {
        try {
          dns.cleanup(challenge.name(), challenge.value());
        } catch (Exception ignored) {
          // A stale challenge value is harmless and must not hide a successful certificate.
        }
      }
    }
  }

  private static KeyPair accountKey(Path path) throws IOException {
    if (Files.isRegularFile(path)) {
      try (var reader = Files.newBufferedReader(path)) {
        return KeyPairUtils.readKeyPair(reader);
      }
    }
    KeyPair key = KeyPairUtils.createECKeyPair("secp256r1");
    Path temporary = path.resolveSibling(path.getFileName() + ".new");
    try (var writer = Files.newBufferedWriter(temporary)) {
      KeyPairUtils.writeKeyPair(key, writer);
    }
    Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    return key;
  }

  private record PresentedChallenge(String name, String value, Dns01Challenge challenge) {}
}
