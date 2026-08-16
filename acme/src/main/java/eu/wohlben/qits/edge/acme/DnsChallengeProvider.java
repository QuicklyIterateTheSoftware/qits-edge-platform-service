package eu.wohlben.qits.edge.acme;

/** Mutates only the TXT values belonging to one ACME authorization. */
public interface DnsChallengeProvider {

  void present(String fqdn, String value) throws Exception;

  void cleanup(String fqdn, String value) throws Exception;
}
