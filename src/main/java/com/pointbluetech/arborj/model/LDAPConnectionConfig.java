package com.pointbluetech.arborj.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * LDAP connection parameters. Maps to Swift LDAPConnection struct.
 * Named LDAPConnectionConfig to avoid collision with UnboundID LDAPConnection.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LDAPConnectionConfig {

    private String host = "";
    private int port = 389;
    private boolean useTLS = false;
    private String baseDN = "";
    private String bindDN = "";
    private DirectoryType directoryType = DirectoryType.AUTO;
    private boolean usePagedResults = true;
    private boolean syncSecurityEquivalence = true;
    private boolean readOnly = false;

    public LDAPConnectionConfig() {}

    public LDAPConnectionConfig(String host, int port, boolean useTLS, String baseDN, String bindDN) {
        this.host = host;
        this.port = port;
        this.useTLS = useTLS;
        this.baseDN = baseDN;
        this.bindDN = bindDN;
    }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public boolean isUseTLS() { return useTLS; }
    public void setUseTLS(boolean useTLS) { this.useTLS = useTLS; }

    public String getBaseDN() { return baseDN; }
    public void setBaseDN(String baseDN) { this.baseDN = baseDN; }

    public String getBindDN() { return bindDN; }
    public void setBindDN(String bindDN) { this.bindDN = bindDN; }

    public DirectoryType getDirectoryType() { return directoryType; }
    public void setDirectoryType(DirectoryType directoryType) { this.directoryType = directoryType; }

    public boolean isUsePagedResults() { return usePagedResults; }
    public void setUsePagedResults(boolean usePagedResults) { this.usePagedResults = usePagedResults; }

    public boolean isSyncSecurityEquivalence() { return syncSecurityEquivalence; }
    public void setSyncSecurityEquivalence(boolean syncSecurityEquivalence) { this.syncSecurityEquivalence = syncSecurityEquivalence; }

    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
}
