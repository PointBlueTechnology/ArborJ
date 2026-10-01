package com.pointbluetech.arborj.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectionProfile {

    private String id;
    private String name;
    private LDAPConnectionConfig connection;
    private boolean savePassword;
    private String group = "";
    private int sortOrder = 0;

    public ConnectionProfile() {
        this.id = UUID.randomUUID().toString();
        this.name = "";
        this.connection = new LDAPConnectionConfig();
        this.savePassword = false;
    }

    public ConnectionProfile(String name, LDAPConnectionConfig connection) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.connection = connection;
        this.savePassword = false;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public LDAPConnectionConfig getConnection() { return connection; }
    public void setConnection(LDAPConnectionConfig connection) { this.connection = connection; }

    public boolean isSavePassword() { return savePassword; }
    public void setSavePassword(boolean savePassword) { this.savePassword = savePassword; }

    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group != null ? group : ""; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
