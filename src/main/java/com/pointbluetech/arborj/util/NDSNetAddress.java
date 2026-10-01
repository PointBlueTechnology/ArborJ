package com.pointbluetech.arborj.util;

/**
 * Decodes eDirectory NDS Net Address from its binary representation.
 * Format: ASCII decimal type + '#' (0x23) + binary address data.
 */
public class NDSNetAddress {

    public enum AddressType {
        IPX(0), IP(1), SDLC(2), TOKEN_RING(3), OSI(4), APPLETALK(5),
        NETBEUI(6), SOCKET(7), UDP(8), TCP(9), UDP6(10), TCP6(11), URL(12), COUNT(13);

        private final int value;

        AddressType(int value) { this.value = value; }
        public int getValue() { return value; }

        public static AddressType fromValue(int v) {
            for (AddressType t : values()) {
                if (t.value == v) return t;
            }
            return null;
        }

        public String getDisplayName() {
            return switch (this) {
                case IPX -> "IPX";
                case IP -> "IP";
                case SDLC -> "SDLC";
                case TOKEN_RING -> "Token Ring";
                case OSI -> "OSI";
                case APPLETALK -> "AppleTalk";
                case NETBEUI -> "NetBEUI";
                case SOCKET -> "Socket";
                case UDP -> "UDP";
                case TCP -> "TCP";
                case UDP6 -> "UDP6";
                case TCP6 -> "TCP6";
                case URL -> "URL";
                case COUNT -> "Count";
            };
        }
    }

    private final AddressType type;
    private final byte[] addressData;

    public NDSNetAddress(AddressType type, byte[] addressData) {
        this.type = type;
        this.addressData = addressData;
    }

    public AddressType getType() { return type; }
    public byte[] getAddressData() { return addressData; }

    /**
     * Human-readable display string.
     */
    public String getDisplayString() {
        return switch (type) {
            case IP, TCP, UDP -> {
                if (addressData.length < 6) yield type.getDisplayName() + " (invalid)";
                int port = ((addressData[0] & 0xFF) << 8) | (addressData[1] & 0xFF);
                String ip = (addressData[2] & 0xFF) + "." + (addressData[3] & 0xFF) + "."
                        + (addressData[4] & 0xFF) + "." + (addressData[5] & 0xFF);
                yield port > 0 ? type.getDisplayName() + " " + ip + ":" + port
                               : type.getDisplayName() + " " + ip;
            }
            case TCP6, UDP6 -> {
                if (addressData.length < 18) yield type.getDisplayName() + " (invalid)";
                int port = ((addressData[0] & 0xFF) << 8) | (addressData[1] & 0xFF);
                StringBuilder ipv6 = new StringBuilder();
                for (int i = 2; i < 18; i += 2) {
                    if (i > 2) ipv6.append(":");
                    ipv6.append(String.format("%02x%02x", addressData[i], addressData[i + 1]));
                }
                yield port > 0 ? type.getDisplayName() + " [" + ipv6 + "]:" + port
                               : type.getDisplayName() + " " + ipv6;
            }
            case IPX -> {
                StringBuilder hex = new StringBuilder();
                for (byte b : addressData) hex.append(String.format("%02X", b));
                yield "IPX " + hex;
            }
            case URL -> {
                yield new String(addressData, java.nio.charset.StandardCharsets.UTF_8);
            }
            default -> type.getDisplayName() + " (" + addressData.length + " bytes)";
        };
    }

    /**
     * Decode from raw binary bytes.
     * eDirectory encodes as: ASCII decimal type + '#' (0x23) + binary address data.
     */
    public static NDSNetAddress decode(byte[] bytes) {
        // Find the '#' separator
        int hashIndex = -1;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0x23) { hashIndex = i; break; }
        }
        if (hashIndex <= 0) return null;

        // Parse type number from ASCII digits before '#'
        String typeStr = new String(bytes, 0, hashIndex, java.nio.charset.StandardCharsets.US_ASCII);
        try {
            int typeVal = Integer.parseInt(typeStr);
            AddressType addrType = AddressType.fromValue(typeVal);
            if (addrType == null) return null;
            byte[] data = new byte[bytes.length - hashIndex - 1];
            System.arraycopy(bytes, hashIndex + 1, data, 0, data.length);
            return new NDSNetAddress(addrType, data);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Decode from a hex-encoded string (as stored in our attribute model).
     */
    public static NDSNetAddress decodeFromHex(String hexValue) {
        if (hexValue == null || hexValue.isEmpty() || hexValue.length() % 2 != 0) return null;
        byte[] bytes = new byte[hexValue.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hexValue.substring(i * 2, i * 2 + 2), 16);
        }
        return decode(bytes);
    }
}
