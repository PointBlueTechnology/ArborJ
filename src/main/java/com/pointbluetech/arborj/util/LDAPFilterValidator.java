package com.pointbluetech.arborj.util;

/**
 * Validates LDAP search filter syntax per RFC 4515.
 */
public class LDAPFilterValidator {

    /**
     * Validate an LDAP filter string.
     *
     * @return null if valid, or an error message describing the problem
     */
    public static String validate(String filter) {
        if (filter == null || filter.isEmpty()) {
            return "Filter cannot be empty";
        }

        filter = filter.trim();

        if (!filter.startsWith("(") || !filter.endsWith(")")) {
            return "Filter must be enclosed in parentheses";
        }

        try {
            int consumed = parseFilter(filter, 0);
            if (consumed != filter.length()) {
                return "Unexpected content after filter at position " + consumed;
            }
            return null; // Valid
        } catch (FilterParseException e) {
            return e.getMessage();
        }
    }

    /**
     * Check if a filter is valid.
     */
    public static boolean isValid(String filter) {
        return validate(filter) == null;
    }

    /**
     * Extract the attribute name token at the given cursor position.
     * Used for autocomplete — returns the partial attribute name being typed.
     */
    public static String attributeTokenAt(int cursor, String filter) {
        if (filter == null || cursor <= 0 || cursor > filter.length()) return null;
        char[] chars = filter.toCharArray();
        // Walk left from cursor to find start of identifier
        int start = cursor - 1;
        while (start > 0 && isAttrChar(chars[start - 1])) start--;
        // Walk right to include rest of identifier
        int end = cursor - 1;
        while (end + 1 < chars.length && isAttrChar(chars[end + 1])) end++;
        if (start > end) return null;
        String token = filter.substring(start, end + 1);
        return token.isEmpty() ? null : token;
    }

    private static boolean isAttrChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '.';
    }

    private static int parseFilter(String filter, int pos) throws FilterParseException {
        if (pos >= filter.length() || filter.charAt(pos) != '(') {
            throw new FilterParseException("Expected '(' at position " + pos);
        }

        pos++; // skip '('

        if (pos >= filter.length()) {
            throw new FilterParseException("Unexpected end of filter");
        }

        char c = filter.charAt(pos);

        if (c == '&' || c == '|') {
            // AND or OR — parse one or more sub-filters
            pos++; // skip operator
            if (pos >= filter.length() || filter.charAt(pos) != '(') {
                throw new FilterParseException("Expected '(' after '" + c + "' at position " + pos);
            }
            while (pos < filter.length() && filter.charAt(pos) == '(') {
                pos = parseFilter(filter, pos);
            }
        } else if (c == '!') {
            // NOT — exactly one sub-filter
            pos++;
            pos = parseFilter(filter, pos);
        } else {
            // Simple filter: attribute operator value
            pos = parseSimpleFilter(filter, pos);
        }

        if (pos >= filter.length() || filter.charAt(pos) != ')') {
            throw new FilterParseException("Expected ')' at position " + pos);
        }

        return pos + 1; // skip ')'
    }

    private static int parseSimpleFilter(String filter, int pos) throws FilterParseException {
        // Find the operator
        int start = pos;
        while (pos < filter.length()) {
            char c = filter.charAt(pos);
            if (c == '=' || c == '~' || c == '>' || c == '<') break;
            if (c == ')') {
                throw new FilterParseException("Missing operator in filter at position " + start);
            }
            pos++;
        }

        if (pos >= filter.length()) {
            throw new FilterParseException("Unexpected end of filter — missing operator");
        }

        String attr = filter.substring(start, pos);
        if (attr.isEmpty()) {
            throw new FilterParseException("Empty attribute name at position " + start);
        }

        // Parse operator
        char op = filter.charAt(pos);
        if (op == '>' || op == '<' || op == '~') {
            pos++; // skip first char of compound operator
            if (pos >= filter.length() || filter.charAt(pos) != '=') {
                throw new FilterParseException("Expected '=' after '" + op + "' at position " + pos);
            }
        }
        pos++; // skip '='

        // Parse value (everything up to closing paren)
        while (pos < filter.length() && filter.charAt(pos) != ')') {
            pos++;
        }

        return pos;
    }

    private static class FilterParseException extends Exception {
        FilterParseException(String message) {
            super(message);
        }
    }
}
