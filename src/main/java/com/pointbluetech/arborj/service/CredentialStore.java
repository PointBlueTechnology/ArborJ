package com.pointbluetech.arborj.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stores credentials (passwords and certificate trust decisions) in an
 * AES-GCM encrypted file at ~/.arborj/credentials.enc.
 *
 * The encryption key is derived from a per-user random key file and salt using
 * PBKDF2. Store files are restricted to the current OS user on POSIX filesystems.
 * This is still not a replacement for a native keychain, but avoids plaintext
 * passwords and predictable key material while keeping zero native dependencies.
 */
public class CredentialStore {

    private static final Path STORE_DIR = Path.of(System.getProperty("user.home"), ".arborj");
    private static final Path CRED_FILE = STORE_DIR.resolve("credentials.enc");
    private static final Path SALT_FILE = STORE_DIR.resolve("credentials.salt");
    private static final Path KEY_FILE = STORE_DIR.resolve("credentials.key");
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final int SALT_LENGTH = 16;
    private static final int KEY_LENGTH = 256;
    private static final int PBKDF2_ITERATIONS = 600_000;

    private final ObjectMapper mapper;
    private final SecretKey secretKey;
    private final SecretKey legacySecretKey;
    private Map<String, String> store;
    private boolean loadedWithLegacyKey = false;

    public CredentialStore() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.secretKey = deriveKey(loadOrCreateKeyMaterial());
        this.legacySecretKey = deriveLegacyKey();
        this.store = load();
        if (loadedWithLegacyKey) {
            persist();
        }
    }

    // --- Password operations ---

    public void savePassword(String profileId, String password) {
        store.put("pwd:" + profileId, password);
        persist();
    }

    public String loadPassword(String profileId) {
        return store.get("pwd:" + profileId);
    }

    public void deletePassword(String profileId) {
        store.remove("pwd:" + profileId);
        persist();
    }

    // --- Certificate trust operations ---

    public void saveApprovedCert(String host, int port, byte[] derData) {
        store.put("cert:" + host + ":" + port, Base64.getEncoder().encodeToString(derData));
        persist();
    }

    public byte[] loadApprovedCert(String host, int port) {
        String encoded = store.get("cert:" + host + ":" + port);
        if (encoded == null) return null;
        return Base64.getDecoder().decode(encoded);
    }

    public void deleteApprovedCert(String host, int port) {
        store.remove("cert:" + host + ":" + port);
        persist();
    }

    /**
     * Info about an approved certificate.
     */
    public record ApprovedCert(String hostPort, String subject) {}

    /**
     * Returns all approved certificate entries with host:port and subject name.
     */
    public java.util.List<ApprovedCert> listApprovedCerts() {
        java.util.List<ApprovedCert> certs = new java.util.ArrayList<>();
        for (var entry : store.entrySet()) {
            if (entry.getKey().startsWith("cert:")) {
                String hostPort = entry.getKey().substring(5);
                String subject = "";
                try {
                    byte[] der = Base64.getDecoder().decode(entry.getValue());
                    java.security.cert.CertificateFactory cf =
                            java.security.cert.CertificateFactory.getInstance("X.509");
                    java.security.cert.X509Certificate cert =
                            (java.security.cert.X509Certificate) cf.generateCertificate(
                                    new java.io.ByteArrayInputStream(der));
                    subject = cert.getSubjectX500Principal().getName();
                } catch (Exception ignored) {}
                certs.add(new ApprovedCert(hostPort, subject));
            }
        }
        certs.sort((a, b) -> a.hostPort().compareToIgnoreCase(b.hostPort()));
        return certs;
    }

    /**
     * Delete an approved certificate by "host:port" string.
     */
    public void deleteApprovedCertByKey(String hostPort) {
        store.remove("cert:" + hostPort);
        persist();
    }

    // --- Encryption internals ---

    private SecretKey deriveKey(char[] keyMaterial) {
        try {
            byte[] salt = loadOrCreateSalt();
            KeySpec spec = new PBEKeySpec(keyMaterial, salt, PBKDF2_ITERATIONS, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to derive encryption key", e);
        }
    }

    private SecretKey deriveLegacyKey() {
        String seed = System.getProperty("user.name", "arborj")
                + System.getProperty("user.home", "")
                + System.getProperty("os.name", "");
        return deriveKey(seed.toCharArray());
    }

    private char[] loadOrCreateKeyMaterial() {
        try {
            prepareStoreDir();
            if (Files.exists(KEY_FILE)) {
                String existing = Files.readString(KEY_FILE, StandardCharsets.US_ASCII).trim();
                if (!existing.isEmpty()) {
                    lockDownPath(KEY_FILE, false);
                    return existing.toCharArray();
                }
            }

            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            String token = Base64.getEncoder().encodeToString(random);
            Files.writeString(KEY_FILE, token, StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            lockDownPath(KEY_FILE, false);
            return token.toCharArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to manage credential key file", e);
        }
    }

    private byte[] loadOrCreateSalt() {
        try {
            if (Files.exists(SALT_FILE)) {
                lockDownPath(SALT_FILE, false);
                return Files.readAllBytes(SALT_FILE);
            }
            prepareStoreDir();
            byte[] salt = new byte[SALT_LENGTH];
            new SecureRandom().nextBytes(salt);
            Files.write(SALT_FILE, salt);
            lockDownPath(SALT_FILE, false);
            return salt;
        } catch (IOException e) {
            throw new RuntimeException("Failed to manage salt file", e);
        }
    }

    private void prepareStoreDir() throws IOException {
        Files.createDirectories(STORE_DIR);
        lockDownPath(STORE_DIR, true);
    }

    private void lockDownPath(Path path, boolean directory) {
        try {
            EnumSet<PosixFilePermission> perms = directory
                    ? EnumSet.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE)
                    : EnumSet.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems use platform defaults.
        }
    }

    private byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            // Prepend IV to ciphertext
            byte[] result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            return result;
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    private byte[] decrypt(byte[] data) {
        return decrypt(data, secretKey);
    }

    private byte[] decrypt(byte[] data, SecretKey key) {
        if (data.length <= GCM_IV_LENGTH) {
            throw new RuntimeException("Invalid encrypted credential file");
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(data, 0, iv, 0, iv.length);
            byte[] ciphertext = new byte[data.length - iv.length];
            System.arraycopy(data, iv.length, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    private Map<String, String> load() {
        if (!Files.exists(CRED_FILE)) return new LinkedHashMap<>();
        try {
            byte[] encrypted = Files.readAllBytes(CRED_FILE);
            byte[] plaintext;
            try {
                plaintext = decrypt(encrypted);
            } catch (RuntimeException newKeyFailure) {
                plaintext = decrypt(encrypted, legacySecretKey);
                loadedWithLegacyKey = true;
            }
            String json = new String(plaintext, StandardCharsets.UTF_8);
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            System.err.println("Failed to load credentials (may need to re-enter passwords): " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void persist() {
        try {
            prepareStoreDir();
            String json = mapper.writeValueAsString(store);
            byte[] encrypted = encrypt(json.getBytes(StandardCharsets.UTF_8));
            // Write to a temp file in the same directory then atomically rename,
            // so a crash mid-write cannot truncate the existing credential store.
            Path tmp = Files.createTempFile(STORE_DIR, "credentials-", ".enc.tmp");
            try {
                Files.write(tmp, encrypted);
                lockDownPath(tmp, false);
                try {
                    Files.move(tmp, CRED_FILE,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, CRED_FILE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                lockDownPath(CRED_FILE, false);
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
        } catch (IOException e) {
            System.err.println("Failed to save credentials: " + e.getMessage());
        }
    }
}
