package com.pointbluetech.arborj.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchHistoryEntry {

    private String id;
    private String filter;
    private SearchScope scope;
    private List<String> selectedAttributes;
    private boolean includeOperational;
    private int sizeLimit;
    private Instant date;

    public SearchHistoryEntry() {
        this.id = UUID.randomUUID().toString();
        this.date = Instant.now();
    }

    public SearchHistoryEntry(String filter, SearchScope scope, List<String> selectedAttributes,
                               boolean includeOperational, int sizeLimit) {
        this.id = UUID.randomUUID().toString();
        this.filter = filter;
        this.scope = scope;
        this.selectedAttributes = selectedAttributes;
        this.includeOperational = includeOperational;
        this.sizeLimit = sizeLimit;
        this.date = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFilter() { return filter; }
    public void setFilter(String filter) { this.filter = filter; }

    public SearchScope getScope() { return scope; }
    public void setScope(SearchScope scope) { this.scope = scope; }

    public List<String> getSelectedAttributes() { return selectedAttributes; }
    public void setSelectedAttributes(List<String> selectedAttributes) { this.selectedAttributes = selectedAttributes; }

    public boolean isIncludeOperational() { return includeOperational; }
    public void setIncludeOperational(boolean includeOperational) { this.includeOperational = includeOperational; }

    public int getSizeLimit() { return sizeLimit; }
    public void setSizeLimit(int sizeLimit) { this.sizeLimit = sizeLimit; }

    public Instant getDate() { return date; }
    public void setDate(Instant date) { this.date = date; }
}
