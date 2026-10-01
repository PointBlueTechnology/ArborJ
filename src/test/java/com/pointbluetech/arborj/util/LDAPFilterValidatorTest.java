package com.pointbluetech.arborj.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LDAPFilterValidatorTest {

    // --- valid filters ---

    @Test
    @DisplayName("simple equality filter is valid")
    void simpleEquality() {
        assertNull(LDAPFilterValidator.validate("(cn=alice)"));
        assertTrue(LDAPFilterValidator.isValid("(cn=alice)"));
    }

    @Test
    @DisplayName("presence filter is valid")
    void presence() {
        assertNull(LDAPFilterValidator.validate("(mail=*)"));
    }

    @Test
    @DisplayName("substring filter is valid")
    void substring() {
        assertNull(LDAPFilterValidator.validate("(cn=a*b*c)"));
    }

    @Test
    @DisplayName("AND with multiple children is valid")
    void andMultipleChildren() {
        assertNull(LDAPFilterValidator.validate("(&(objectClass=person)(uid=jsmith)(mail=*))"));
    }

    @Test
    @DisplayName("nested AND/OR/NOT is valid")
    void nestedComposite() {
        assertNull(LDAPFilterValidator.validate("(&(|(cn=a)(cn=b))(!(uid=disabled)))"));
    }

    @Test
    @DisplayName("compound operators >=, <=, ~= are valid")
    void compoundOperators() {
        assertNull(LDAPFilterValidator.validate("(age>=18)"));
        assertNull(LDAPFilterValidator.validate("(age<=65)"));
        assertNull(LDAPFilterValidator.validate("(cn~=smith)"));
    }

    @Test
    @DisplayName("leading/trailing whitespace is tolerated")
    void trimsWhitespace() {
        assertNull(LDAPFilterValidator.validate("  (cn=alice)  "));
    }

    // --- invalid filters ---

    @Test
    @DisplayName("null or empty is rejected")
    void rejectsEmpty() {
        assertNotNull(LDAPFilterValidator.validate(null));
        assertNotNull(LDAPFilterValidator.validate(""));
        assertFalse(LDAPFilterValidator.isValid(""));
    }

    @Test
    @DisplayName("missing parentheses is rejected")
    void rejectsBareFilter() {
        assertNotNull(LDAPFilterValidator.validate("cn=alice"));
    }

    @Test
    @DisplayName("mismatched parentheses is rejected")
    void rejectsMismatchedParens() {
        assertNotNull(LDAPFilterValidator.validate("(cn=alice"));
        assertNotNull(LDAPFilterValidator.validate("cn=alice)"));
    }

    @Test
    @DisplayName("empty attribute name is rejected")
    void rejectsEmptyAttribute() {
        assertNotNull(LDAPFilterValidator.validate("(=foo)"));
    }

    @Test
    @DisplayName("missing operator is rejected")
    void rejectsMissingOperator() {
        assertNotNull(LDAPFilterValidator.validate("(cn)"));
    }

    @Test
    @DisplayName("AND/OR with no sub-filter is rejected")
    void rejectsEmptyComposite() {
        assertNotNull(LDAPFilterValidator.validate("(&)"));
        assertNotNull(LDAPFilterValidator.validate("(|)"));
    }

    @Test
    @DisplayName("> without = is rejected")
    void rejectsDanglingGreaterThan() {
        assertNotNull(LDAPFilterValidator.validate("(age>18)"));
    }

    @Test
    @DisplayName("content after closing paren is rejected")
    void rejectsTrailingContent() {
        assertNotNull(LDAPFilterValidator.validate("(cn=alice)extra"));
    }

    // --- attributeTokenAt ---

    @Test
    @DisplayName("attributeTokenAt returns the attribute around the cursor")
    void attributeTokenAtReturnsToken() {
        assertEquals("cn", LDAPFilterValidator.attributeTokenAt(2, "(cn=alice)"));
        assertEquals("objectClass",
                LDAPFilterValidator.attributeTokenAt(12, "(objectClass=person)"));
    }

    @Test
    @DisplayName("attributeTokenAt handles bad cursor positions")
    void attributeTokenAtHandlesBadCursor() {
        assertNull(LDAPFilterValidator.attributeTokenAt(0, "(cn=alice)"));
        assertNull(LDAPFilterValidator.attributeTokenAt(5, null));
        // Cursor past the end of string => null.
        assertNull(LDAPFilterValidator.attributeTokenAt(50, "(cn=alice)"));
    }

    @Test
    @DisplayName("attributeTokenAt stops at the assertion operator")
    void attributeTokenAtStopsAfterEquals() {
        String filter = "(cn=bob)";
        assertEquals("cn", LDAPFilterValidator.attributeTokenAt(2, filter));
        assertEquals("cn", LDAPFilterValidator.attributeTokenAt(3, filter)); // caret before '='
        assertNull(LDAPFilterValidator.attributeTokenAt(4, filter));          // caret just after '='
        assertNull(LDAPFilterValidator.attributeTokenAt(5, filter));          // caret in the value
        assertNull(LDAPFilterValidator.attributeTokenAt(filter.length(), filter));
    }

    @Test
    @DisplayName("attributeTokenAt ignores DN components inside an assertion value")
    void attributeTokenAtIgnoresDnValues() {
        String filter = "(member=cn=admin,ou=users)";
        assertEquals("member", LDAPFilterValidator.attributeTokenAt(filter.indexOf("member") + 3, filter));
        assertNull(LDAPFilterValidator.attributeTokenAt(filter.indexOf("=cn=") + 2, filter));
        assertNull(LDAPFilterValidator.attributeTokenAt(filter.indexOf("admin") + 1, filter));
        assertNull(LDAPFilterValidator.attributeTokenAt(filter.indexOf("ou=") + 1, filter));
        assertNull(LDAPFilterValidator.attributeTokenAt(filter.length(), filter));
    }

    @Test
    @DisplayName("attributeTokenAt still finds attributes in later filter items")
    void attributeTokenAtFindsLaterAssertions() {
        String filter = "(&(objectClass=person)(!(cn=bob)))";
        assertEquals("objectClass",
                LDAPFilterValidator.attributeTokenAt(filter.indexOf("objectClass") + 4, filter));
        assertNull(LDAPFilterValidator.attributeTokenAt(filter.indexOf("person") + 1, filter));
        int cn = filter.indexOf("(cn=") + 1;
        assertEquals("cn", LDAPFilterValidator.attributeTokenAt(cn + 2, filter));
        assertNull(LDAPFilterValidator.attributeTokenAt(cn + 3, filter));
        assertNull(LDAPFilterValidator.attributeTokenAt(filter.indexOf("bob") + 1, filter));
    }

    @Test
    @DisplayName("attributeTokenAt treats compound operators as the value boundary")
    void attributeTokenAtCompoundOperators() {
        assertEquals("age", LDAPFilterValidator.attributeTokenAt(3, "(age>=18)"));
        assertNull(LDAPFilterValidator.attributeTokenAt("(age>=18)".indexOf('=') + 1, "(age>=18)"));
        assertNull(LDAPFilterValidator.attributeTokenAt("(cn~=smith)".indexOf("smith") + 1, "(cn~=smith)"));
        String spaced = "( cn = bob )";
        assertEquals("cn", LDAPFilterValidator.attributeTokenAt(spaced.indexOf("cn") + 1, spaced));
        assertNull(LDAPFilterValidator.attributeTokenAt(spaced.indexOf("bob") + 1, spaced));
    }

    @Test
    @DisplayName("attributeTokenAt still returns a partial name before any operator")
    void attributeTokenAtPartialName() {
        assertEquals("mail", LDAPFilterValidator.attributeTokenAt(5, "(mail"));
        assertEquals("cn", LDAPFilterValidator.attributeTokenAt(2, "cn"));
        assertNull(LDAPFilterValidator.attributeTokenAt(3, "cn="));
        String ext = "(cn:caseExactMatch:=Fred)";
        assertEquals("cn", LDAPFilterValidator.attributeTokenAt(ext.indexOf("cn") + 1, ext));
        assertNull(LDAPFilterValidator.attributeTokenAt(ext.indexOf("Fred") + 1, ext));
    }
}
