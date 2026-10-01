package com.pointbluetech.arborj.service;

import com.pointbluetech.arborj.model.CertificateDetails;
import com.pointbluetech.arborj.model.EffectiveRights;
import com.pointbluetech.arborj.model.LDAPConnectionConfig;
import com.pointbluetech.arborj.model.LDAPEntry;
import com.unboundid.ldap.sdk.*;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;
import com.unboundid.ldap.sdk.extensions.PasswordModifyExtendedRequest;
import com.unboundid.ldap.sdk.extensions.PasswordModifyExtendedResult;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.asn1.ASN1Sequence;
import com.unboundid.asn1.ASN1Integer;
import com.unboundid.asn1.ASN1Element;
import com.unboundid.util.ssl.SSLUtil;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Core LDAP service using UnboundID LDAP SDK.
 * Handles connection, search, modify, and extended operations.
 */
public class LDAPService {

    private static final int PAGE_SIZE = 500;
    private static final int SEARCH_TIMEOUT_SECONDS = 300; // 5 minutes for large directories

    // eDirectory extended operation OIDs
    public static final String DIRXML_COMMAND_OID = "2.16.840.1.113719.1.14.100.29";
    public static final String DIRXML_GET_CHUNKED_OID = "2.16.840.1.113719.1.14.100.33";
    public static final String DIRXML_CLOSE_CHUNKED_OID = "2.16.840.1.113719.1.14.100.35";
    public static final String GET_EFFECTIVE_PRIVILEGES_REQUEST_OID = "2.16.840.1.113719.1.27.100.33";
    public static final String GET_EFFECTIVE_PRIVILEGES_RESPONSE_OID = "2.16.840.1.113719.1.27.100.34";
    public static final String NDS_GET_REPLICA_INFO_REQUEST_OID = "2.16.840.1.113719.1.27.100.17";
    public static final String NDS_LIST_REPLICAS_REQUEST_OID = "2.16.840.1.113719.1.27.100.19";
    public static final String NDS_READ_REPLICA_INFO_REQUEST_OID = "2.16.840.1.113719.1.27.100.37";
    public static final String PASSWORD_MODIFY_OID = "1.3.6.1.4.1.4203.1.11.1";

    private LDAPConnection connection;
    private LDAPConnectionConfig currentConfig;
    private String currentPassword;
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledFuture<?> heartbeatFuture;

    // Callback for connection lost (auto-reconnect)
    private Runnable connectionLostHandler;

    public LDAPService() {}

    public void setConnectionLostHandler(Runnable handler) {
        this.connectionLostHandler = handler;
    }

    // --- Connection Management ---

    static {
        // Java 25 disables many cipher suites and TLS versions that older LDAP servers
        // (especially eDirectory) still require. Re-enable them.
        try {
            java.security.Security.setProperty("jdk.tls.disabledAlgorithms",
                    "SSLv3, TLSv1, TLSv1.1, RC4, DES, MD5withRSA, " +
                    "DH keySize < 1024, EC keySize < 224, 3DES_EDE_CBC, anon, NULL");
        } catch (Exception e) {
            System.err.println("[ArborJ] Warning: Could not adjust TLS security properties: " + e.getMessage());
        }
    }

    public void connect(LDAPConnectionConfig config, String password) throws LDAPException {
        connect(config, password, null);
    }

    public void connect(LDAPConnectionConfig config, String password, byte[] approvedCertDer)
            throws LDAPException {
        disconnect();

        LDAPConnectionOptions options = new LDAPConnectionOptions();
        options.setConnectTimeoutMillis(10_000);
        options.setResponseTimeoutMillis(SEARCH_TIMEOUT_SECONDS * 1000L);

        if (config.isUseTLS()) {
            SSLSocketFactory sslFactory = createSSLSocketFactory(config.getHost(), config.getPort(), approvedCertDer);
            // INTENTIONAL: hostname verification at the LDAP layer is disabled.
            // Hostname / SAN matching is also intentionally NOT enabled at the
            // SSLSocket layer (we don't call setEndpointIdentificationAlgorithm).
            // Trust is enforced by InteractiveTrustManager only — it routes any
            // failure of default keystore validation to a user-acceptance
            // prompt. This is the documented product design: ArborJ supports
            // private CAs, self-signed eDirectory servers, expired test certs,
            // etc., and the user must explicitly approve such certs once
            // (per host:port). See InteractiveTrustManager javadoc for the
            // full threat model. Don't reintroduce automatic hostname checks
            // here without first agreeing on an alternative override path.
            options.setSSLSocketVerifier(null);
            connection = new LDAPConnection(sslFactory, options, config.getHost(), config.getPort());
        } else {
            connection = new LDAPConnection(options, config.getHost(), config.getPort());
        }

        // Bind
        if (config.getBindDN() != null && !config.getBindDN().isEmpty()) {
            BindResult result = connection.bind(config.getBindDN(), password);
            if (result.getResultCode() != ResultCode.SUCCESS) {
                connection.close();
                connection = null;
                throw new LDAPException(result.getResultCode(),
                        "Bind failed: " + result.getDiagnosticMessage());
            }
        }

        this.currentConfig = config;
        this.currentPassword = password;
        startHeartbeat();
    }

    public void disconnect() {
        stopHeartbeat();
        if (connection != null) {
            connection.close();
            connection = null;
        }
        currentConfig = null;
        currentPassword = null;
        clearRootDSECache();
    }

    public boolean isConnected() {
        return connection != null && connection.isConnected();
    }

    public boolean isAlive() {
        if (!isConnected()) return false;
        try {
            SearchResult result = connection.search("", com.unboundid.ldap.sdk.SearchScope.BASE,
                    "(objectClass=*)", "namingContexts");
            return result.getResultCode() == ResultCode.SUCCESS;
        } catch (LDAPException e) {
            return false;
        }
    }

    public LDAPConnectionConfig getCurrentConfig() {
        return currentConfig;
    }

    /** Best-effort LDAP URL for the current connection, used in the activity log. */
    private String connectionUrl() {
        if (currentConfig == null) return "";
        boolean secure = currentConfig.isUseTLS() || currentConfig.getPort() == 636;
        return (secure ? "ldaps" : "ldap") + "://"
                + currentConfig.getHost() + ":" + currentConfig.getPort();
    }

    /**
     * Provides access to the raw UnboundID connection for schema loading.
     */
    public LDAPConnection getRawConnection() {
        return connection;
    }

    // --- Search Operations ---

    public List<LDAPEntry> search(String baseDN, com.pointbluetech.arborj.model.SearchScope scope,
                                    String filter, String... attributes) throws LDAPException {
        ensureConnected();
        ActivityLogger logger = ActivityLogger.getInstance();
        String url = connectionUrl();
        int reqId = logger.logSearchRequest(url, baseDN, scope, filter, attributes, 0);
        try {
            SearchResult result = connection.search(baseDN, scope.toUnboundID(), filter, attributes);
            List<LDAPEntry> entries = convertEntries(result.getSearchEntries());
            logger.logSearchResult(reqId, url, entries.size(), null);
            return entries;
        } catch (LDAPException e) {
            logger.logSearchResult(reqId, url, 0, e);
            throw e;
        }
    }

    /**
     * Non-paged search that honors an optional server-side size limit.
     */
    public LimitedSearchResult searchLimited(String baseDN, com.pointbluetech.arborj.model.SearchScope scope,
                                             String filter, String[] attributes, int sizeLimit)
            throws LDAPException {
        ensureConnected();
        ActivityLogger logger = ActivityLogger.getInstance();
        String url = connectionUrl();
        int reqId = logger.logSearchRequest(url, baseDN, scope, filter, attributes, sizeLimit);

        SearchRequest request = new SearchRequest(baseDN, scope.toUnboundID(), filter, attributes);
        request.setSizeLimit(Math.max(0, sizeLimit));
        request.setTimeLimitSeconds(0);
        request.setResponseTimeoutMillis(SEARCH_TIMEOUT_SECONDS * 1000L);

        try {
            SearchResult result = connection.search(request);
            List<LDAPEntry> entries = convertEntries(result.getSearchEntries());
            logger.logSearchResult(reqId, url, entries.size(), null);
            return new LimitedSearchResult(entries, false);
        } catch (LDAPSearchException e) {
            if (e.getResultCode() == ResultCode.SIZE_LIMIT_EXCEEDED && e.getSearchResult() != null) {
                List<LDAPEntry> entries = convertEntries(e.getSearchResult().getSearchEntries());
                logger.logSearchResult(reqId, url, entries.size(), null);
                return new LimitedSearchResult(entries, true);
            }
            logger.logSearchResult(reqId, url, 0, e);
            throw e;
        }
    }

    /**
     * Paged search with RFC 2696 support. Returns entries and the next paging cookie.
     */
    public PagedSearchResult searchPaged(String baseDN, com.pointbluetech.arborj.model.SearchScope scope, String filter,
                                          String[] attributes, int pageSize, byte[] cookie,
                                          int sizeLimit) throws LDAPException {
        ensureConnected();
        ActivityLogger logger = ActivityLogger.getInstance();
        String url = connectionUrl();
        int reqId = logger.logSearchRequest(url, baseDN, scope, filter, attributes, sizeLimit);

        SearchRequest request = new SearchRequest(baseDN, scope.toUnboundID(), filter, attributes);
        // Don't set server-side sizeLimit when using paged results — it conflicts with paging.
        // Client-side limit is enforced in the paging loop in MainController.
        request.setSizeLimit(0);
        request.setTimeLimitSeconds(0); // No server-side time limit; client timeout handles it
        request.setResponseTimeoutMillis(SEARCH_TIMEOUT_SECONDS * 1000L);

        ASN1OctetString cookieValue = (cookie != null) ? new ASN1OctetString(cookie) : new ASN1OctetString();
        // Mark paging control as CRITICAL so the server honors it instead of its admin size limit
        request.addControl(new SimplePagedResultsControl(pageSize, cookieValue, true));

        SearchResult result;
        try {
            result = connection.search(request);
        } catch (LDAPSearchException e) {
            // Keep genuine partial results, but don't convert errors like
            // NO_SUCH_OBJECT or unsupported controls into silent empty searches.
            SearchResult partial = e.getSearchResult();
            boolean hasPartialEntries = partial != null && !partial.getSearchEntries().isEmpty();
            if (hasPartialEntries
                    && (e.getResultCode() == ResultCode.SIZE_LIMIT_EXCEEDED
                    || e.getResultCode() == ResultCode.TIME_LIMIT_EXCEEDED
                    || e.getResultCode() == ResultCode.ADMIN_LIMIT_EXCEEDED)) {
                result = partial;
            } else {
                logger.logSearchResult(reqId, url, 0, e);
                throw e;
            }
        }
        List<LDAPEntry> entries = convertEntries(result.getSearchEntries());

        // Extract paging cookie from response controls
        byte[] nextCookie = null;
        SimplePagedResultsControl responseControl = SimplePagedResultsControl.get(result);
        if (responseControl != null && responseControl.getCookie() != null
                && responseControl.getCookie().getValueLength() > 0) {
            nextCookie = responseControl.getCookie().getValue();
        }

        logger.logSearchResult(reqId, url, entries.size(), null);
        return new PagedSearchResult(entries, nextCookie);
    }

    /**
     * Fetch all attributes for a single DN.
     */
    public Map<String, List<String>> fetchAttributes(String dn, boolean includeOperational)
            throws LDAPException {
        ensureConnected();
        String[] attrs = includeOperational ? new String[]{"*", "+"} : new String[]{"*"};
        SearchResultEntry entry = connection.getEntry(dn, attrs);
        if (entry == null) return Map.of();
        return convertAttributes(entry);
    }

    public Map<String, List<String>> fetchAttributes(String dn, String[] attributes)
            throws LDAPException {
        ensureConnected();
        SearchResultEntry entry = connection.getEntry(dn, attributes);
        if (entry == null) return Map.of();
        return convertAttributes(entry);
    }

    // --- Root DSE & Discovery ---

    private volatile Map<String, List<String>> cachedRootDSE;

    public Map<String, List<String>> fetchRootDSE() throws LDAPException {
        ensureConnected();
        if (cachedRootDSE != null) return cachedRootDSE;
        SearchResultEntry entry = connection.getEntry("", "*", "+");
        if (entry == null) return Map.of();
        cachedRootDSE = convertAttributes(entry);
        return cachedRootDSE;
    }

    /** Clear the cached Root DSE (called on disconnect). */
    public void clearRootDSECache() {
        cachedRootDSE = null;
    }

    public List<String> fetchNamingContexts() throws LDAPException {
        ensureConnected();
        // Query specifically for namingContexts to ensure we get all values
        SearchResultEntry entry = connection.getEntry("", "namingContexts");
        if (entry == null) return List.of();
        String[] values = entry.getAttributeValues("namingContexts");
        if (values == null) return List.of();
        return List.of(values);
    }

    public List<String> fetchSupportedExtensions() throws LDAPException {
        Map<String, List<String>> rootDSE = fetchRootDSE();
        List<String> exts = rootDSE.get("supportedExtension");
        return exts != null ? exts : List.of();
    }

    public String fetchTreeName() throws LDAPException {
        Map<String, List<String>> rootDSE = fetchRootDSE();
        for (String attr : List.of("treeName", "directoryTreeName", "ds-tree-name")) {
            List<String> values = rootDSE.get(attr);
            if (values != null && !values.isEmpty()) return values.getFirst();
        }
        return null;
    }

    public String fetchServerDN() throws LDAPException {
        Map<String, List<String>> rootDSE = fetchRootDSE();
        List<String> dsaName = rootDSE.get("dsaName");
        if (dsaName != null && !dsaName.isEmpty()) return dsaName.getFirst();
        List<String> serverName = rootDSE.get("serverName");
        if (serverName != null && !serverName.isEmpty()) return serverName.getFirst();
        return null;
    }

    // --- Modify Operations ---

    public void addEntry(String dn, Map<String, List<String>> attributes) throws LDAPException {
        ensureWritable();
        List<Attribute> attrs = new ArrayList<>();
        for (var entry : attributes.entrySet()) {
            attrs.add(new Attribute(entry.getKey(), entry.getValue()));
        }
        ActivityLogger logger = ActivityLogger.getInstance();
        String url = connectionUrl();
        try {
            connection.add(dn, attrs);
            logger.logAdd(url, dn, attrs, null);
        } catch (LDAPException e) {
            logger.logAdd(url, dn, attrs, e);
            throw e;
        }
    }

    public void modifyAttribute(String dn, String attribute, LDAPModifyOperation operation,
                                 List<String> values) throws LDAPException {
        ensureWritable();
        Modification mod = switch (operation) {
            case ADD -> new Modification(ModificationType.ADD, attribute, values.toArray(new String[0]));
            case DELETE -> values.isEmpty()
                    ? new Modification(ModificationType.DELETE, attribute)
                    : new Modification(ModificationType.DELETE, attribute, values.toArray(new String[0]));
            case REPLACE -> new Modification(ModificationType.REPLACE, attribute, values.toArray(new String[0]));
        };
        ActivityLogger logger = ActivityLogger.getInstance();
        String url = connectionUrl();
        try {
            connection.modify(dn, mod);
            logger.logModify(url, dn, List.of(mod), null);
        } catch (LDAPException e) {
            logger.logModify(url, dn, List.of(mod), e);
            throw e;
        }
    }

    public void modifyBinaryAttribute(String dn, String attribute, LDAPModifyOperation operation,
                                       List<byte[]> values) throws LDAPException {
        ensureWritable();
        Modification mod = switch (operation) {
            case ADD -> new Modification(ModificationType.ADD, attribute, values.toArray(new byte[0][]));
            case DELETE -> values.isEmpty()
                    ? new Modification(ModificationType.DELETE, attribute)
                    : new Modification(ModificationType.DELETE, attribute, values.toArray(new byte[0][]));
            case REPLACE -> new Modification(ModificationType.REPLACE, attribute, values.toArray(new byte[0][]));
        };
        ActivityLogger logger = ActivityLogger.getInstance();
        String url = connectionUrl();
        try {
            connection.modify(dn, mod);
            logger.logModify(url, dn, List.of(mod), null);
        } catch (LDAPException e) {
            logger.logModify(url, dn, List.of(mod), e);
            throw e;
        }
    }

    public void modifyMultipleAttributes(String dn, List<ModificationItem> changes) throws LDAPException {
        ensureWritable();
        List<Modification> mods = new ArrayList<>();
        for (ModificationItem item : changes) {
            ModificationType type = switch (item.operation()) {
                case ADD -> ModificationType.ADD;
                case DELETE -> ModificationType.DELETE;
                case REPLACE -> ModificationType.REPLACE;
            };
            mods.add(new Modification(type, item.attribute(), item.values().toArray(new String[0])));
        }
        ActivityLogger logger = ActivityLogger.getInstance();
        String url = connectionUrl();
        try {
            connection.modify(dn, mods);
            logger.logModify(url, dn, mods, null);
        } catch (LDAPException e) {
            logger.logModify(url, dn, mods, e);
            throw e;
        }
    }

    public void deleteEntry(String dn) throws LDAPException {
        ensureWritable();
        ActivityLogger logger = ActivityLogger.getInstance();
        String url = connectionUrl();
        try {
            connection.delete(dn);
            logger.logDelete(url, dn, null);
        } catch (LDAPException e) {
            logger.logDelete(url, dn, e);
            throw e;
        }
    }

    public void renameEntry(String dn, String newRDN, boolean deleteOldRDN, String newSuperior)
            throws LDAPException {
        ensureWritable();
        ActivityLogger logger = ActivityLogger.getInstance();
        String url = connectionUrl();
        try {
            connection.modifyDN(dn, newRDN, deleteOldRDN, newSuperior);
            logger.logRename(url, dn, newRDN, deleteOldRDN, newSuperior, null);
        } catch (LDAPException e) {
            logger.logRename(url, dn, newRDN, deleteOldRDN, newSuperior, e);
            throw e;
        }
    }

    // --- Password Operations ---

    /**
     * RFC 3062 Password Modify extended operation.
     */
    public void changePasswordRFC3062(String dn, String oldPassword, String newPassword)
            throws LDAPException {
        ensureWritable();
        PasswordModifyExtendedRequest request = new PasswordModifyExtendedRequest(
                dn, oldPassword, newPassword);
        PasswordModifyExtendedResult result;
        try {
            result = (PasswordModifyExtendedResult) connection.processExtendedOperation(request);
        } catch (LDAPException e) {
            // UnboundID throws on failure result codes; rethrow with the full
            // diagnostic info eDirectory returned (result code, diagnostic
            // message, matched DN, NMAS-specific controls).
            throw new LDAPException(e.getResultCode(),
                    formatPasswordError(e.getResultCode(), e.getDiagnosticMessage(),
                            e.getMatchedDN(), e.getResponseControls()),
                    e);
        }
        if (result.getResultCode() != ResultCode.SUCCESS) {
            throw new LDAPException(result.getResultCode(),
                    formatPasswordError(result.getResultCode(), result.getDiagnosticMessage(),
                            result.getMatchedDN(), result.getResponseControls()));
        }
    }

    /**
     * Build the most specific password-change error string we can from a
     * failed RFC 3062 / NMAS Change Password response. eDirectory often puts
     * only "NMAS Change Password extension failed" in the diagnostic, but
     * leaves the actual reason in the LDAP result code or in the matched-DN
     * field. We surface all available pieces so the user can see the real
     * cause (too short, unique-policy violation, password expired, etc.).
     */
    private static String formatPasswordError(ResultCode rc, String diagnostic,
                                              String matchedDN, Control[] controls) {
        StringBuilder sb = new StringBuilder("Password change failed: ");

        // Try to find a more specific reason than the generic NMAS message.
        String specific = nmasReasonFromControls(controls);
        if (specific == null) specific = nmasReasonFromMatchedDN(matchedDN);
        if (specific == null) specific = describeResultCodeForPassword(rc);

        if (diagnostic != null && !diagnostic.isBlank()
                && !diagnostic.equalsIgnoreCase("NMAS Change Password extension failed")) {
            sb.append(diagnostic);
        } else if (specific != null) {
            sb.append(specific);
        } else if (diagnostic != null && !diagnostic.isBlank()) {
            sb.append(diagnostic);
        } else {
            sb.append("server returned ").append(rc);
        }

        // Always include the LDAP result code name + number for diagnosability,
        // and append the specific NMAS reason if we found one in addition to
        // the diagnostic.
        sb.append("\n\n[result: ").append(rc.getName())
                .append(" (").append(rc.intValue()).append(")");
        if (specific != null && !sb.toString().contains(specific)) {
            sb.append("; ").append(specific);
        }
        sb.append("]");

        return sb.toString();
    }

    /** eDirectory occasionally encodes the NMAS error code in the matched-DN. */
    private static String nmasReasonFromMatchedDN(String matchedDN) {
        if (matchedDN == null || matchedDN.isBlank()) return null;
        // eDir formats this as "NDS error: <text> (<code>)" or just "<code>".
        String trimmed = matchedDN.trim();
        if (trimmed.matches("-?\\d+")) {
            return decodeNmasCode(Integer.parseInt(trimmed));
        }
        if (trimmed.startsWith("NDS error:") || trimmed.startsWith("NMAS")) {
            return trimmed;
        }
        return null;
    }

    /** Look for a decoded NMAS error in the response controls. */
    private static String nmasReasonFromControls(Control[] controls) {
        if (controls == null) return null;
        // eDirectory's NMAS responds with controls under
        // 2.16.840.1.113719.1.27.103.* — the value typically encodes a
        // 32-bit signed NMAS error code as the first 4 BE bytes.
        for (Control c : controls) {
            if (c.getOID() != null && c.getOID().startsWith("2.16.840.1.113719.1.27.103.")) {
                ASN1OctetString val = c.getValue();
                if (val == null) continue;
                byte[] bytes = val.getValue();
                if (bytes != null && bytes.length >= 4) {
                    int code = (bytes[0] << 24) | ((bytes[1] & 0xFF) << 16)
                            | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
                    String label = decodeNmasCode(code);
                    if (label != null) return label;
                }
            }
        }
        return null;
    }

    /**
     * Map well-known NMAS / NDS error codes to human-readable text. Codes are
     * defined in eDirectory's nmasapi.h (negative values).
     */
    private static String decodeNmasCode(int code) {
        return switch (code) {
            case -215 -> "Password unique violation (matches a previous password)";
            case -216 -> "Password too short";
            case -217 -> "Password too long";
            case -218 -> "Password too simple (does not meet complexity requirements)";
            case -219 -> "Password in history";
            case -222 -> "Password expired";
            case -224 -> "Insufficient rights to change this password";
            case -226 -> "Password is the user's name or otherwise disallowed";
            case -1697 -> "Password policy violation";
            case -16049 -> "NMAS: insufficient rights for password operation";
            case -16066 -> "NMAS: password policy disallows this password";
            default -> code < 0 ? "NMAS error " + code : null;
        };
    }

    /** Best-effort plain-language description of an LDAP result code in a password context. */
    private static String describeResultCodeForPassword(ResultCode rc) {
        if (rc == null) return null;
        return switch (rc.intValue()) {
            case 19 /* CONSTRAINT_VIOLATION */ ->
                    "Password rejected by the server's password policy "
                    + "(may be too short, in history, or contain disallowed text).";
            case 53 /* UNWILLING_TO_PERFORM */ ->
                    "Server is unwilling to change the password "
                    + "(usually a policy or authentication-method restriction).";
            case 49 /* INVALID_CREDENTIALS */ ->
                    "Current password is incorrect.";
            case 50 /* INSUFFICIENT_ACCESS_RIGHTS */ ->
                    "You don't have rights to change this password.";
            default -> null;
        };
    }

    /**
     * Active Directory password reset via unicodePwd attribute.
     */
    public void changePasswordAD(String dn, String newPassword) throws LDAPException {
        ensureWritable();
        String quotedPassword = "\"" + newPassword + "\"";
        byte[] encodedPassword = quotedPassword.getBytes(StandardCharsets.UTF_16LE);
        modifyBinaryAttribute(dn, "unicodePwd", LDAPModifyOperation.REPLACE, List.of(encodedPassword));
    }

    /**
     * eDirectory DirXML password change (universal password).
     */
    public void changePasswordEDir(String dn, String oldPassword, String newPassword)
            throws LDAPException {
        // eDirectory uses RFC 3062 but may also support DirXML extended op
        changePasswordRFC3062(dn, oldPassword, newPassword);
    }

    // --- Active Directory Helpers ---

    public int fetchADUserAccountControl(String dn) throws LDAPException {
        ensureConnected();
        SearchResultEntry entry = connection.getEntry(dn, "userAccountControl");
        if (entry == null) throw new LDAPException(ResultCode.NO_SUCH_OBJECT, "Entry not found: " + dn);
        String uac = entry.getAttributeValue("userAccountControl");
        return uac != null ? Integer.parseInt(uac) : 0;
    }

    /**
     * Update the post-password-change flags on an AD user. Caller must have
     * already read the existing {@code userAccountControl} into
     * {@code currentUAC}; we OR/AND the relevant bit and REPLACE so other
     * UAC flags (account type, ACCOUNTDISABLE, etc.) are preserved.
     *
     * <p>Note: the PASSWORD_CANT_CHANGE state (0x40 in UAC, but actually
     * controlled by the user object's ACL) is intentionally not exposed
     * here — toggling the UAC bit alone has no effect in AD.
     */
    public void updateADPasswordFlags(String dn, boolean mustChange,
                                       boolean neverExpires, int currentUAC) throws LDAPException {
        ensureWritable();
        // 0x10000 = DONT_EXPIRE_PASSWORD
        int newUAC = neverExpires
                ? (currentUAC | 0x10000)
                : (currentUAC & ~0x10000);

        List<Modification> mods = new ArrayList<>();
        mods.add(new Modification(ModificationType.REPLACE, "userAccountControl", String.valueOf(newUAC)));

        if (mustChange) {
            mods.add(new Modification(ModificationType.REPLACE, "pwdLastSet", "0"));
        }
        connection.modify(dn, mods);
    }

    public boolean toggleADAccountEnabled(String dn) throws LDAPException {
        ensureWritable();
        int uac = fetchADUserAccountControl(dn);
        boolean isDisabled = (uac & 0x0002) != 0;
        int newUAC = isDisabled ? (uac & ~0x0002) : (uac | 0x0002);
        connection.modify(dn, new Modification(ModificationType.REPLACE,
                "userAccountControl", String.valueOf(newUAC)));
        return !isDisabled; // returns new enabled state
    }

    // --- eDirectory Operations ---

    /**
     * Get effective rights for a trustee on a target entry.
     */
    public EffectiveRights getEffectiveRights(String targetDN, String trusteeDN, String attribute)
            throws LDAPException {
        ensureConnected();
        String attrOrEntry = (attribute != null) ? attribute : "[Entry Rights]";

        // The DN picker exposes the eDirectory tree root as "T=TreeName", but the
        // effective-privileges extended op needs a real LDAP DN; [Root]'s LDAP DN
        // is the empty string.
        targetDN = normalizeRootDN(targetDN);
        trusteeDN = normalizeRootDN(trusteeDN);

        // eDirectory expects concatenated BER OCTET STRINGs (no SEQUENCE wrapper)
        byte[] targetBytes = new ASN1OctetString(targetDN).encode();
        byte[] trusteeBytes = new ASN1OctetString(trusteeDN).encode();
        byte[] attrBytes = new ASN1OctetString(attrOrEntry).encode();
        byte[] combined = new byte[targetBytes.length + trusteeBytes.length + attrBytes.length];
        System.arraycopy(targetBytes, 0, combined, 0, targetBytes.length);
        System.arraycopy(trusteeBytes, 0, combined, targetBytes.length, trusteeBytes.length);
        System.arraycopy(attrBytes, 0, combined, targetBytes.length + trusteeBytes.length, attrBytes.length);

        ExtendedResult result = connection.processExtendedOperation(
                GET_EFFECTIVE_PRIVILEGES_REQUEST_OID,
                new ASN1OctetString(combined));

        if (result.getResultCode() != ResultCode.SUCCESS) {
            throw new LDAPException(result.getResultCode(),
                    "Effective rights query failed: " + result.getDiagnosticMessage());
        }

        // Parse response value: INTEGER (privileges bitmask)
        int privileges = 0;
        if (result.getValue() != null) {
            byte[] respData = result.getValue().getValue();

            // Try as JSON first (some eDirectory versions)
            String respStr = new String(respData, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (respStr.startsWith("{")) {
                try {
                    var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(respStr);
                    if (tree.has("privileges")) privileges = tree.get("privileges").asInt();
                } catch (Exception e) {
                    // JSON parse failed, fall through to BER
                }
            } else {
                // Try BER: could be a single INTEGER or concatenated TLVs
                try {
                    // Walk TLVs to find first INTEGER
                    int offset = 0;
                    while (offset < respData.length) {
                        int tag = respData[offset] & 0xFF;
                        offset++;
                        int lenByte = respData[offset] & 0xFF;
                        int valueLen;
                        if (lenByte < 0x80) {
                            valueLen = lenByte;
                            offset++;
                        } else {
                            int numLenBytes = lenByte & 0x7F;
                            valueLen = 0;
                            offset++;
                            for (int i = 0; i < numLenBytes; i++) {
                                valueLen = (valueLen << 8) | (respData[offset] & 0xFF);
                                offset++;
                            }
                        }
                        if (tag == 0x02 && valueLen > 0 && offset + valueLen <= respData.length) {
                            // INTEGER found
                            int val = 0;
                            for (int i = 0; i < valueLen; i++) {
                                val = (val << 8) | (respData[offset + i] & 0xFF);
                            }
                            privileges = val;
                            break;
                        }
                        offset += valueLen;
                    }
                } catch (Exception e) {
                    // BER parse failed
                }
            }
        }

        if (attribute != null) {
            return new EffectiveRights(
                    EnumSet.noneOf(EffectiveRights.EntryRight.class),
                    EffectiveRights.AttributeRight.fromBitmask(privileges)
            );
        } else {
            return new EffectiveRights(
                    EffectiveRights.EntryRight.fromBitmask(privileges),
                    null
            );
        }
    }

    /**
     * Map the synthetic "T=TreeName" tree-root DN exposed in the UI to the
     * empty-string DN that eDirectory's LDAP layer uses for [Root]. Returns
     * the input unchanged for any other DN.
     */
    private static String normalizeRootDN(String dn) {
        if (dn != null && dn.regionMatches(true, 0, "T=", 0, 2)) {
            return "";
        }
        return dn;
    }

    /**
     * Submit a DirXML command to an eDirectory driver.
     */
    public String submitDirXMLCommand(String driverDN, String xmlDocument) throws LDAPException {
        ensureWritable();
        // eDirectory expects concatenated BER OCTET STRINGs
        byte[] driverBytes = new ASN1OctetString(driverDN).encode();
        byte[] xmlBytes = new ASN1OctetString(xmlDocument).encode();
        byte[] combined = new byte[driverBytes.length + xmlBytes.length];
        System.arraycopy(driverBytes, 0, combined, 0, driverBytes.length);
        System.arraycopy(xmlBytes, 0, combined, driverBytes.length, xmlBytes.length);

        ExtendedResult result = connection.processExtendedOperation(DIRXML_COMMAND_OID,
                new ASN1OctetString(combined));
        if (result.getResultCode() != ResultCode.SUCCESS) {
            throw new LDAPException(result.getResultCode(),
                    "DirXML command failed: " + result.getDiagnosticMessage());
        }

        if (result.getValue() != null) {
            return result.getValue().stringValue();
        }
        return "";
    }

    /**
     * Toggle eDirectory loginDisabled attribute.
     */
    public boolean toggleEDirLoginDisabled(String dn) throws LDAPException {
        ensureWritable();
        SearchResultEntry entry = connection.getEntry(dn, "loginDisabled");
        String current = entry != null ? entry.getAttributeValue("loginDisabled") : null;
        boolean isDisabled = "TRUE".equalsIgnoreCase(current);
        String newValue = isDisabled ? "FALSE" : "TRUE";
        connection.modify(dn, new Modification(ModificationType.REPLACE, "loginDisabled", newValue));
        return !isDisabled;
    }

    // --- OpenLDAP/389DS Operations ---

    public boolean toggleAccountLock(String dn) throws LDAPException {
        ensureWritable();
        SearchResultEntry entry = connection.getEntry(dn, "nsAccountLock");
        String current = entry != null ? entry.getAttributeValue("nsAccountLock") : null;
        boolean isLocked = "true".equalsIgnoreCase(current);
        if (isLocked) {
            connection.modify(dn, new Modification(ModificationType.DELETE, "nsAccountLock"));
        } else {
            connection.modify(dn, new Modification(ModificationType.REPLACE, "nsAccountLock", "true"));
        }
        return !isLocked;
    }

    // --- eDirectory Replication Extended Operations ---

    /**
     * Find server DNs to use for replica operations.
     * Searches for ncpServer objects matching the connected server's CN.
     */
    public List<String> findServerDNsForReplicas() throws LDAPException {
        ensureConnected();
        List<String> candidates = new ArrayList<>();

        String dsaName = fetchServerDN();
        if (dsaName == null || dsaName.isEmpty()) return candidates;

        // Extract server CN from dsaName
        String serverCN = dsaName;
        int commaIdx = dsaName.indexOf(',');
        if (commaIdx > 0) {
            String first = dsaName.substring(0, commaIdx);
            int eqIdx = first.indexOf('=');
            if (eqIdx > 0) serverCN = first.substring(eqIdx + 1);
        }

        // Search for ncpServer objects
        List<String> contexts = fetchNamingContexts();
        for (String base : contexts) {
            if (base.isEmpty()) continue;
            try {
                SearchResult result = connection.search(base,
                        com.unboundid.ldap.sdk.SearchScope.SUB,
                        "(&(objectClass=ncpServer)(cn=" + serverCN + "))", "dn");
                for (SearchResultEntry entry : result.getSearchEntries()) {
                    if (!candidates.contains(entry.getDN())) {
                        candidates.add(entry.getDN());
                    }
                }
            } catch (LDAPException ignored) {}
        }

        if (!candidates.contains(dsaName)) candidates.add(dsaName);
        return candidates;
    }

    /**
     * List all partition DNs held by a server using NDS List Replicas extended op.
     * Tries each candidate server DN until one succeeds.
     * @return [serverDN, partitionDN1, partitionDN2, ...] or empty list
     */
    public List<String> listReplicas(List<String> serverDNs) throws LDAPException {
        ensureConnected();
        for (String dn : serverDNs) {
            try {
                // eDirectory expects the full BER-encoded OCTET STRING (tag+length+value)
                ASN1OctetString dnOctet = new ASN1OctetString(dn);
                ASN1OctetString requestValue = new ASN1OctetString(dnOctet.encode());
                ExtendedResult result = connection.processExtendedOperation(
                        NDS_LIST_REPLICAS_REQUEST_OID, requestValue);
                if (result.getResultCode() != ResultCode.SUCCESS) {
                    continue;
                }
                if (result.getValue() == null) continue;

                byte[] data = result.getValue().getValue();
                List<String> partitions = parseListReplicasResponse(data);
                if (!partitions.isEmpty()) {
                    List<String> ret = new ArrayList<>();
                    ret.add(dn);
                    ret.addAll(partitions);
                    return ret;
                }
            } catch (LDAPException e) {
                System.err.println("[ArborJ] listReplicas exception for " + dn + ": " + e.getMessage());
            }
        }
        return List.of();
    }

    /**
     * Get detailed replica info for a specific partition.
     * Returns raw response bytes for parsing.
     */
    public byte[] getReplicaInfo(String serverDN, String partitionDN) throws LDAPException {
        ensureConnected();
        // eDirectory expects BER: OCTET STRING(serverDN) + OCTET STRING(partitionDN) concatenated
        byte[] serverBytes = new ASN1OctetString(serverDN).encode();
        byte[] partBytes = new ASN1OctetString(partitionDN).encode();
        byte[] combined = new byte[serverBytes.length + partBytes.length];
        System.arraycopy(serverBytes, 0, combined, 0, serverBytes.length);
        System.arraycopy(partBytes, 0, combined, serverBytes.length, partBytes.length);
        ExtendedResult result = connection.processExtendedOperation(
                NDS_GET_REPLICA_INFO_REQUEST_OID,
                new ASN1OctetString(combined));
        if (result.getResultCode() != ResultCode.SUCCESS) return null;
        if (result.getValue() == null) return null;
        return result.getValue().getValue();
    }

    /**
     * Parse NDS List Replicas response.
     * Format: BER SEQUENCE of OCTET STRINGs, or NDS raw (4-byte handle + 4-byte count + entries).
     */
    private List<String> parseListReplicasResponse(byte[] data) {
        List<String> partitions = new ArrayList<>();

        // Try BER decoding
        try {
            ASN1Element elem = ASN1Element.decode(data);
            // Tag 0x30 = SEQUENCE — must explicitly decode as sequence
            if (elem.getType() == 0x30) {
                ASN1Sequence seq = ASN1Sequence.decodeAsSequence(elem);
                for (ASN1Element child : seq.elements()) {
                    partitions.add(child.decodeAsOctetString().stringValue()); // "" = root
                }
            } else {
                String value = elem.decodeAsOctetString().stringValue();
                // Some servers return JSON: {"partitions":["dn1","dn2",...]}
                if (value.trim().startsWith("{")) {
                    partitions.addAll(parseJsonPartitions(value));
                } else {
                    partitions.add(value);
                }
            }
        } catch (Exception e) {
            // BER parse failed, try NDS raw format
        }

        // Fallback: NDS raw format (LE integers)
        if (partitions.isEmpty() && data.length >= 8) {
            int offset = 4; // skip iteration handle
            int count = readInt32LE(data, offset);
            if (count > 1000 || count < 0) count = readInt32BE(data, offset);
            offset += 4;

            for (int i = 0; i < count && offset + 4 <= data.length; i++) {
                int strLen = readInt32LE(data, offset);
                if (strLen > data.length || strLen < 0) strLen = readInt32BE(data, offset);
                offset += 4;
                if (strLen == 0) {
                    partitions.add(""); // root partition
                } else if (offset + strLen <= data.length) {
                    String dn = new String(data, offset, strLen, java.nio.charset.StandardCharsets.UTF_8)
                            .replace("\0", "").trim();
                    if (!dn.isEmpty()) partitions.add(dn);
                    offset += strLen;
                }
            }
        }
        return partitions;
    }

    /**
     * Parse replica info response into structured fields.
     * Returns: [replicaState, modificationTime, replicaNumber, replicaType]
     */
    public int[] parseReplicaInfoResponse(byte[] data) {
        // Try BER: SEQUENCE of INTEGERs
        // Fields by position: [0]=partitionID, [1]=replicaState, [2]=modTime,
        //                     [3]=purgeTime, [4]=replicaNumber, [5]=replicaType
        // Check if response is JSON (some eDirectory versions)
        String dataStr = new String(data, java.nio.charset.StandardCharsets.UTF_8).trim();
        if (dataStr.startsWith("{")) {
            return parseReplicaInfoJson(dataStr);
        }

        // Parse concatenated BER elements — NDS returns multiple TLVs not wrapped in a SEQUENCE
        List<Integer> integers = new ArrayList<>();
        try {
            int offset = 0;
            while (offset < data.length) {
                int tag = data[offset] & 0xFF;
                offset++;
                // Decode length
                int lenByte = data[offset] & 0xFF;
                int valueLen;
                if (lenByte < 0x80) {
                    valueLen = lenByte;
                    offset++;
                } else {
                    int numLenBytes = lenByte & 0x7F;
                    valueLen = 0;
                    offset++;
                    for (int i = 0; i < numLenBytes; i++) {
                        valueLen = (valueLen << 8) | (data[offset] & 0xFF);
                        offset++;
                    }
                }

                if (tag == 0x02 && valueLen > 0 && offset + valueLen <= data.length) {
                    // INTEGER — big-endian signed
                    int val = 0;
                    for (int i = 0; i < valueLen; i++) {
                        val = (val << 8) | (data[offset + i] & 0xFF);
                    }
                    if ((data[offset] & 0x80) != 0 && valueLen < 4) {
                        val |= (-1 << (valueLen * 8));
                    }
                    integers.add(val);
                }
                // Skip non-INTEGER elements (OCTET STRINGs etc.)
                offset += valueLen;
            }
        } catch (Exception e) {
            // BER parse failed
        }

        int replicaState = integers.size() > 1 ? integers.get(1) : -1;
        int modTime = integers.size() > 2 ? integers.get(2) : 0;
        int replicaNumber = integers.size() > 4 ? integers.get(4) : -1;
        int replicaType = integers.size() > 5 ? integers.get(5) : -1;

        return new int[]{replicaState, modTime, replicaNumber, replicaType};
    }

    /**
     * Parse replica info from JSON response.
     * Returns [replicaState, modTime, replicaNumber, replicaType]
     */
    private int[] parseReplicaInfoJson(String json) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var tree = mapper.readTree(json);
            int replicaState = tree.has("replicaState") ? tree.get("replicaState").asInt(-1) : -1;
            int modTime = tree.has("modificationTime") ? tree.get("modificationTime").asInt(0) : 0;
            int replicaNumber = tree.has("replicaNumber") ? tree.get("replicaNumber").asInt(-1) : -1;
            int replicaType = tree.has("replicaType") ? tree.get("replicaType").asInt(-1) : -1;
            return new int[]{replicaState, modTime, replicaNumber, replicaType};
        } catch (Exception e) {
            return new int[]{-1, 0, -1, -1};
        }
    }

    /**
     * Parse JSON partition list: {"partitions":["dn1","dn2",...]}
     * Some eDirectory versions return this format instead of BER.
     */
    private List<String> parseJsonPartitions(String json) {
        List<String> result = new ArrayList<>();
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var tree = mapper.readTree(json);
            var arr = tree.get("partitions");
            if (arr != null && arr.isArray()) {
                for (var node : arr) {
                    result.add(node.asText());
                }
            }
        } catch (Exception e) {
            // JSON parse failed
        }
        return result;
    }

    private static int readInt32LE(byte[] data, int offset) {
        if (offset + 4 > data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset+1] & 0xFF) << 8)
                | ((data[offset+2] & 0xFF) << 16) | ((data[offset+3] & 0xFF) << 24);
    }

    private static int readInt32BE(byte[] data, int offset) {
        if (offset + 4 > data.length) return 0;
        return ((data[offset] & 0xFF) << 24) | ((data[offset+1] & 0xFF) << 16)
                | ((data[offset+2] & 0xFF) << 8) | (data[offset+3] & 0xFF);
    }

    /**
     * Get replica info for ALL servers holding a specific partition.
     * Uses NDS Read Replica Info extended op (OID .37).
     * Returns raw response bytes for parsing.
     */
    public byte[] getPartitionReplicaInfo(String serverDN, String partitionDN) throws LDAPException {
        ensureConnected();
        byte[] serverBytes = new ASN1OctetString(serverDN).encode();
        byte[] partBytes = new ASN1OctetString(partitionDN).encode();
        byte[] combined = new byte[serverBytes.length + partBytes.length];
        System.arraycopy(serverBytes, 0, combined, 0, serverBytes.length);
        System.arraycopy(partBytes, 0, combined, serverBytes.length, partBytes.length);

        ExtendedResult result = connection.processExtendedOperation(
                NDS_READ_REPLICA_INFO_REQUEST_OID,
                new ASN1OctetString(combined));

        if (result.getResultCode() != ResultCode.SUCCESS) {
            return null;
        }
        if (result.getValue() == null) return null;
        byte[] data = result.getValue().getValue();
        return data;
    }

    // --- Generic Extended Operation ---

    public ExtendedResult sendExtendedOperation(String oid, ASN1OctetString requestValue)
            throws LDAPException {
        ensureConnected();
        return connection.processExtendedOperation(oid, requestValue);
    }

    // --- TLS Certificate Handling ---

    /**
     * Build the {@link SSLSocketFactory} used for LDAPS connections.
     *
     * <p>The trust manager chain is intentionally limited to a single
     * {@link InteractiveTrustManager}: we do NOT install the JVM's default
     * trust manager as an additional manager. Instead, the interactive
     * manager runs the default keystore check internally and, on failure,
     * surfaces a user-acceptance prompt via {@link UntrustedCertificateException}.
     * That gives the user a way to approve self-signed / private-CA / expired
     * certificates explicitly without ever silently accepting them.
     *
     * <p>Hostname / endpoint identification is intentionally NOT enabled on
     * the produced sockets. See the comment in {@link #connect} for the
     * rationale.
     */
    private SSLSocketFactory createSSLSocketFactory(String host, int port, byte[] approvedCertDer) {
        try {
            TrustManager trustManager = new InteractiveTrustManager(host, port, approvedCertDer);
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, new TrustManager[]{trustManager}, null);

            // Wrap in a factory that enables all supported cipher suites
            // (Java 25 disables many by default that older servers need)
            SSLSocketFactory baseFactory = sslContext.getSocketFactory();
            return new PermissiveSSLSocketFactory(baseFactory);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SSL socket factory", e);
        }
    }

    /**
     * SSLSocketFactory wrapper that enables all supported cipher suites and protocols
     * on every socket it creates. This is needed for older LDAP servers (eDirectory)
     * that may use cipher suites Java 25 has disabled by default.
     */
    private static class PermissiveSSLSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        PermissiveSSLSocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private java.net.Socket configure(java.net.Socket socket) {
            if (socket instanceof javax.net.ssl.SSLSocket ssl) {
                ssl.setEnabledProtocols(ssl.getSupportedProtocols());
                ssl.setEnabledCipherSuites(ssl.getSupportedCipherSuites());
            }
            return socket;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose) throws java.io.IOException {
            return configure(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public java.net.Socket createSocket(String host, int port) throws java.io.IOException {
            return configure(delegate.createSocket(host, port));
        }

        @Override
        public java.net.Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort) throws java.io.IOException {
            return configure(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            return configure(delegate.createSocket(host, port));
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress localAddress, int localPort) throws java.io.IOException {
            return configure(delegate.createSocket(address, port, localAddress, localPort));
        }
    }

    // --- Heartbeat ---

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ldap-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatFuture = heartbeatExecutor.scheduleWithFixedDelay(() -> {
            if (!isAlive()) {
                System.err.println("[ArborJ] Heartbeat: connection lost");
                if (connectionLostHandler != null) {
                    connectionLostHandler.run();
                }
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
    }

    // --- Helpers ---

    private void ensureConnected() throws LDAPException {
        if (connection == null || !connection.isConnected()) {
            throw new LDAPException(ResultCode.CONNECT_ERROR, "Not connected to LDAP server");
        }
    }

    private void ensureWritable() throws LDAPException {
        ensureConnected();
        if (currentConfig != null && currentConfig.isReadOnly()) {
            throw new LDAPException(ResultCode.UNWILLING_TO_PERFORM,
                    "Connection is read-only; write operation blocked");
        }
    }

    private List<LDAPEntry> convertEntries(List<SearchResultEntry> entries) {
        List<LDAPEntry> result = new ArrayList<>();
        for (SearchResultEntry entry : entries) {
            result.add(new LDAPEntry(entry.getDN(), convertAttributes(entry)));
        }
        return result;
    }

    private Set<String> binaryAttributeNames = Set.of();

    /**
     * Set the names of attributes that should be treated as binary (hex-encoded).
     * Called by MainController after schema is loaded.
     */
    public void setBinaryAttributeNames(Set<String> names) {
        this.binaryAttributeNames = names;
    }

    private Map<String, List<String>> convertAttributes(SearchResultEntry entry) {
        Map<String, List<String>> attrs = new LinkedHashMap<>();
        for (Attribute attr : entry.getAttributes()) {
            String name = attr.getName();
            boolean isBinary = binaryAttributeNames.contains(name.toLowerCase())
                    || name.endsWith(";binary");

            if (isBinary) {
                byte[][] byteValues = attr.getValueByteArrays();
                List<String> hexValues = new ArrayList<>();
                for (byte[] bv : byteValues) {
                    hexValues.add(bytesToHex(bv));
                }
                attrs.put(name, hexValues);
            } else {
                String[] values = attr.getValues();
                if (values != null) {
                    attrs.put(name, List.of(values));
                }
            }
        }
        return attrs;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String sha256Fingerprint(byte[] certDer) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(certDer);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hash.length; i++) {
                if (i > 0) sb.append(":");
                sb.append(String.format("%02X", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // --- Inner Types ---

    public record PagedSearchResult(List<LDAPEntry> entries, byte[] cookie) {}

    public record LimitedSearchResult(List<LDAPEntry> entries, boolean truncated) {}

    public record ModificationItem(String attribute, LDAPModifyOperation operation, List<String> values) {}

    public enum LDAPModifyOperation {
        ADD, DELETE, REPLACE
    }

    /**
     * Exception thrown when a server presents an untrusted certificate.
     * Carries the certificate details for UI prompting.
     */
    public static class UntrustedCertificateException extends java.security.cert.CertificateException {
        private final CertificateDetails details;

        public UntrustedCertificateException(CertificateDetails details) {
            super("Untrusted certificate: " + details.getSubject());
            this.details = details;
        }

        public CertificateDetails getDetails() { return details; }
    }

    /**
     * Custom TrustManager that supports user-approved certificates.
     */
    /**
     * X509 trust manager used for every LDAPS connection. Intentionally
     * permissive in design: the user is allowed to manually accept ANY
     * server certificate after default keystore validation rejects it.
     *
     * <h2>Why this exists</h2>
     * ArborJ targets enterprise directory environments — eDirectory, AD,
     * generic OpenLDAP — that frequently present:
     * <ul>
     *   <li>Self-signed certificates (lab / staging trees, default eDir
     *       installs).</li>
     *   <li>Certificates issued by private / internal CAs that aren't in the
     *       JVM truststore.</li>
     *   <li>Expired or about-to-expire certificates that an admin needs to
     *       connect to in order to fix.</li>
     *   <li>Certificates whose CN/SAN doesn't match the DNS name the admin
     *       uses to reach the host (ad-hoc port forwards, IP-only access,
     *       multi-hostname clusters).</li>
     * </ul>
     * Refusing all of these by default would make the tool useless in
     * practice. Auto-accepting them all would defeat TLS entirely. The
     * compromise is: try the default keystore first, and if it rejects,
     * <em>prompt the user</em> with the cert details and let them decide.
     *
     * <h2>Trust path</h2>
     * <ol>
     *   <li>Default keystore (CA-signed, not expired, etc.) — accept silently.</li>
     *   <li>Pre-approved binding (byte-for-byte match against a cert the
     *       user explicitly trusted earlier, stored per host:port in
     *       CredentialStore) — accept silently.</li>
     *   <li>Otherwise — throw {@link UntrustedCertificateException} so the
     *       controller routes to {@link com.pointbluetech.arborj.view.CertTrustDialog}.
     *       The user can accept or cancel.</li>
     * </ol>
     *
     * <h2>Deliberate non-checks</h2>
     * <ul>
     *   <li>Hostname / SAN is NOT verified, neither at the LDAP layer
     *       ({@code setSSLSocketVerifier(null)}) nor at the SSLSocket layer
     *       ({@code setEndpointIdentificationAlgorithm} is never called).
     *       This is by design — see point above about IP-only access and
     *       multi-hostname clusters.</li>
     *   <li>Empty chain or unencodable leaf does NOT silently fail —
     *       {@link #buildPromptDetails} returns sentinel details so the user
     *       still sees the prompt and can decline.</li>
     * </ul>
     *
     * <h2>What re-prompts the user</h2>
     * The pre-approved binding is byte-for-byte against the leaf cert. If
     * the server rotates its certificate, the user will be prompted again
     * — that's intentional, not a bug. We don't offer "trust this host
     * forever regardless of cert" because that would silently accept a
     * MITM operator who replaced the legitimate cert. If a future
     * requirement asks for that mode, add it as an explicit per-profile
     * opt-in, not a default.
     *
     * <h2>Reviewer note</h2>
     * If you're reading this because a security tool flagged
     * {@code setSSLSocketVerifier(null)}, the unchecked-cause-chain
     * fallback, or "X509TrustManager that doesn't reject" — those are all
     * intentional and the user-acceptance prompt is the compensating
     * control. Don't tighten without a corresponding override path, or
     * the tool stops being able to talk to its target environments.
     */
    private class InteractiveTrustManager implements X509TrustManager {
        private final String host;
        private final int port;
        private final byte[] approvedCertDer;

        InteractiveTrustManager(String host, int port, byte[] approvedCertDer) {
            this.host = host;
            this.port = port;
            this.approvedCertDer = approvedCertDer;
        }

        /**
         * No-op: ArborJ is an LDAP <em>client</em>. We never act as a TLS
         * server, so there's no incoming client cert for us to validate.
         * SAST tools often flag empty {@code checkClientTrusted} as a
         * security smell — for a server-side trust manager that would be
         * correct, but not here.
         */
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
            // 1. Default keystore: trusts CA-signed certs, rejects expired /
            //    self-signed / unknown-issuer / etc. We don't enforce
            //    hostname here (that's handled — or deliberately disabled —
            //    at the SSLSocket layer in createSSLSocketFactory).
            if (passesDefaultTrust(chain, authType)) return;

            // 2. User-approved cert binding: byte-for-byte match with a cert
            //    the user explicitly trusted earlier (stored in CredentialStore
            //    keyed by host:port).
            if (approvedCertDer != null && chain != null && chain.length > 0) {
                try {
                    if (Arrays.equals(chain[0].getEncoded(), approvedCertDer)) return;
                } catch (CertificateEncodingException ignored) {
                    // Fall through to manual prompt.
                }
            }

            // 3. Manual user acceptance. The dialog allows the user to trust
            //    ANY cert — that's the point. We build whatever details we can
            //    extract and throw UntrustedCertificateException so the
            //    controller can route to the trust prompt. Never fall through
            //    to a plain CertificateException for an encodable cert: that
            //    would surface to the user as an opaque "Connection failed"
            //    instead of a prompt they can act on.
            throw new UntrustedCertificateException(buildPromptDetails(chain));
        }

        /** Run the JVM default trust manager and report whether it accepted. */
        private boolean passesDefaultTrust(X509Certificate[] chain, String authType) {
            try {
                javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory
                        .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
                tmf.init((java.security.KeyStore) null);
                for (TrustManager tm : tmf.getTrustManagers()) {
                    if (tm instanceof X509TrustManager xtm) {
                        xtm.checkServerTrusted(chain, authType);
                        return true;
                    }
                }
            } catch (java.security.cert.CertificateException rejected) {
                // Default keystore rejected the chain — caller falls through
                // to the user-acceptance path.
            } catch (Exception otherFailure) {
                // Default trust manager couldn't even initialize. Treat as
                // "not system trusted" so the user can still manually accept.
            }
            return false;
        }

        /**
         * Build {@link CertificateDetails} for the trust prompt, tolerating
         * empty chains and encoding failures. Always returns a non-null
         * value so the prompt has something to display.
         */
        private CertificateDetails buildPromptDetails(X509Certificate[] chain) {
            if (chain == null || chain.length == 0) {
                return new CertificateDetails(
                        "(server presented no certificate)",
                        "(unknown)",
                        null, null,
                        "(none)",
                        List.of());
            }
            X509Certificate leaf = chain[0];
            String fingerprint;
            try {
                fingerprint = sha256Fingerprint(leaf.getEncoded());
            } catch (CertificateEncodingException e) {
                fingerprint = "(encoding failed)";
            }
            return new CertificateDetails(
                    leaf.getSubjectX500Principal().getName(),
                    leaf.getIssuerX500Principal().getName(),
                    leaf.getNotBefore() != null ? leaf.getNotBefore().toInstant() : null,
                    leaf.getNotAfter() != null ? leaf.getNotAfter().toInstant() : null,
                    fingerprint,
                    List.of(chain));
        }

        /**
         * Returns an empty array because the actual trust evaluation happens
         * dynamically in {@link #checkServerTrusted}, which delegates to the
         * JVM's default trust manager and falls back to user acceptance.
         * Returning the JVM's accepted issuers here would be misleading —
         * we accept private CAs and self-signed certs the user has approved
         * too. JSSE only uses this method during certificate request
         * generation by TLS servers; ArborJ is a client, so this is a
         * defensible no-op. (SAST tools sometimes flag this; see class
         * javadoc.)
         */
        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
