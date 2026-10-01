package com.pointbluetech.arborj.model;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class CertificateDetails {

    private final String id;
    private final String subject;
    private final String issuer;
    private final Instant notBefore;
    private final Instant notAfter;
    private final String sha256Fingerprint;
    private final List<X509Certificate> chain;

    public CertificateDetails(String subject, String issuer, Instant notBefore, Instant notAfter,
                               String sha256Fingerprint, List<X509Certificate> chain) {
        this.id = UUID.randomUUID().toString();
        this.subject = subject;
        this.issuer = issuer;
        this.notBefore = notBefore;
        this.notAfter = notAfter;
        this.sha256Fingerprint = sha256Fingerprint;
        this.chain = chain;
    }

    public String getId() { return id; }
    public String getSubject() { return subject; }
    public String getIssuer() { return issuer; }
    public Instant getNotBefore() { return notBefore; }
    public Instant getNotAfter() { return notAfter; }
    public String getSha256Fingerprint() { return sha256Fingerprint; }
    public List<X509Certificate> getChain() { return chain; }
}
