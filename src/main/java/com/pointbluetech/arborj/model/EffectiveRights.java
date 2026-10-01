package com.pointbluetech.arborj.model;

import java.util.EnumSet;

public class EffectiveRights {

    private final EnumSet<EntryRight> entryRights;
    private final EnumSet<AttributeRight> attributeRights;

    public EffectiveRights(EnumSet<EntryRight> entryRights, EnumSet<AttributeRight> attributeRights) {
        this.entryRights = entryRights;
        this.attributeRights = attributeRights;
    }

    public EnumSet<EntryRight> getEntryRights() { return entryRights; }
    public EnumSet<AttributeRight> getAttributeRights() { return attributeRights; }

    public enum EntryRight {
        BROWSE(1),
        ADD(1 << 1),
        DELETE(1 << 2),
        RENAME(1 << 3),
        SUPERVISOR(1 << 4),
        INHERITABLE(1 << 5);

        private final int mask;
        EntryRight(int mask) { this.mask = mask; }
        public int getMask() { return mask; }

        public static EnumSet<EntryRight> fromBitmask(int bitmask) {
            EnumSet<EntryRight> rights = EnumSet.noneOf(EntryRight.class);
            for (EntryRight r : values()) {
                if ((bitmask & r.mask) != 0) rights.add(r);
            }
            return rights;
        }
    }

    public enum AttributeRight {
        COMPARE(1),
        READ(1 << 1),
        WRITE(1 << 2),
        ADD_SELF(1 << 3),
        SUPERVISOR(1 << 4),
        INHERITABLE(1 << 5);

        private final int mask;
        AttributeRight(int mask) { this.mask = mask; }
        public int getMask() { return mask; }

        public static EnumSet<AttributeRight> fromBitmask(int bitmask) {
            EnumSet<AttributeRight> rights = EnumSet.noneOf(AttributeRight.class);
            for (AttributeRight r : values()) {
                if ((bitmask & r.mask) != 0) rights.add(r);
            }
            return rights;
        }
    }
}
