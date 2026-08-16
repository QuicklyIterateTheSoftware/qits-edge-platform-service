package eu.wohlben.qits.edge.acme;

import java.time.Duration;

/** Establishes that a TXT value is visible outside the provider API before validation starts. */
public interface DnsPropagation {

  void await(String fqdn, String value, Duration timeout) throws Exception;
}
