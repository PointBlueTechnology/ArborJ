package com.pointbluetech.arborj.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SavedSearch {

    private String id;
    private String name;
    private String filter;
    private String baseDn;
    private SearchScope scope;
    private List<String> selectedAttributes;
    private int sizeLimit;

    public SavedSearch() {
        this.id = UUID.randomUUID().toString();
    }

    public SavedSearch(String name, String filter, String baseDn, SearchScope scope,
                       List<String> selectedAttributes, int sizeLimit) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.filter = filter;
        this.baseDn = baseDn;
        this.scope = scope;
        this.selectedAttributes = selectedAttributes;
        this.sizeLimit = sizeLimit;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getFilter() { return filter; }
    public void setFilter(String filter) { this.filter = filter; }

    public String getBaseDn() { return baseDn; }
    public void setBaseDn(String baseDn) { this.baseDn = baseDn; }

    public SearchScope getScope() { return scope; }
    public void setScope(SearchScope scope) { this.scope = scope; }

    public List<String> getSelectedAttributes() { return selectedAttributes; }
    public void setSelectedAttributes(List<String> selectedAttributes) { this.selectedAttributes = selectedAttributes; }

    public int getSizeLimit() { return sizeLimit; }
    public void setSizeLimit(int sizeLimit) { this.sizeLimit = sizeLimit; }
}
