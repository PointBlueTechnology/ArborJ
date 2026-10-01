package com.pointbluetech.arborj.model;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;
import java.util.UUID;

public class LDAPNode {

    private final String id;
    private final StringProperty dn = new SimpleStringProperty();
    private final StringProperty rdn = new SimpleStringProperty();
    private final List<String> objectClasses;
    private final ObservableList<LDAPNode> children = FXCollections.observableArrayList();
    private final BooleanProperty expanded = new SimpleBooleanProperty(false);
    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final BooleanProperty hasMore = new SimpleBooleanProperty(false);
    private final BooleanProperty loadingMore = new SimpleBooleanProperty(false);
    private final BooleanProperty leaf = new SimpleBooleanProperty(false);
    private final ObjectProperty<NodeChildrenMode> childrenMode =
            new SimpleObjectProperty<>(NodeChildrenMode.ALL);
    private final boolean isNamingContext;
    private byte[] pagingCookie;
    private boolean childrenLoaded = false;
    private final SimpleIntegerProperty totalChildCount = new SimpleIntegerProperty(-1); // -1 = unknown

    public LDAPNode(String dn, String rdn, List<String> objectClasses, boolean isNamingContext) {
        this.id = UUID.randomUUID().toString();
        this.dn.set(dn);
        this.rdn.set(rdn);
        this.objectClasses = objectClasses;
        this.isNamingContext = isNamingContext;
    }

    public String getId() { return id; }

    public String getDn() { return dn.get(); }
    public void setDn(String value) { dn.set(value); }
    public StringProperty dnProperty() { return dn; }

    public String getRdn() { return rdn.get(); }
    public void setRdn(String value) { rdn.set(value); }
    public StringProperty rdnProperty() { return rdn; }

    public List<String> getObjectClasses() { return objectClasses; }

    public ObservableList<LDAPNode> getChildren() { return children; }

    public boolean isExpanded() { return expanded.get(); }
    public void setExpanded(boolean value) { expanded.set(value); }
    public BooleanProperty expandedProperty() { return expanded; }

    public boolean isLoading() { return loading.get(); }
    public void setLoading(boolean value) { loading.set(value); }
    public BooleanProperty loadingProperty() { return loading; }

    public boolean isHasMore() { return hasMore.get(); }
    public void setHasMore(boolean value) { hasMore.set(value); }
    public BooleanProperty hasMoreProperty() { return hasMore; }

    public boolean isLoadingMore() { return loadingMore.get(); }
    public void setLoadingMore(boolean value) { loadingMore.set(value); }
    public BooleanProperty loadingMoreProperty() { return loadingMore; }

    public boolean isLeaf() { return leaf.get(); }
    public void setLeaf(boolean value) { leaf.set(value); }
    public BooleanProperty leafProperty() { return leaf; }

    public NodeChildrenMode getChildrenMode() { return childrenMode.get(); }
    public void setChildrenMode(NodeChildrenMode value) { childrenMode.set(value); }
    public ObjectProperty<NodeChildrenMode> childrenModeProperty() { return childrenMode; }

    public boolean isNamingContext() { return isNamingContext; }

    public byte[] getPagingCookie() { return pagingCookie; }
    public void setPagingCookie(byte[] pagingCookie) { this.pagingCookie = pagingCookie; }

    public int getTotalChildCount() { return totalChildCount.get(); }
    public void setTotalChildCount(int value) { totalChildCount.set(value); }
    public IntegerProperty totalChildCountProperty() { return totalChildCount; }

    public boolean isChildrenLoaded() { return childrenLoaded; }
    public void setChildrenLoaded(boolean childrenLoaded) { this.childrenLoaded = childrenLoaded; }

    @Override
    public String toString() {
        return rdn.get();
    }
}
