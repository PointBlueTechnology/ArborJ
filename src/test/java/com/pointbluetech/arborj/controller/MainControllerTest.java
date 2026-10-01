package com.pointbluetech.arborj.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainControllerTest {

    @Test
    @DisplayName("parentDnOf strips the first RDN for a multi-RDN DN")
    void parentOfMultiRdn() {
        assertEquals("ou=People,dc=example,dc=com",
                MainController.parentDnOf("cn=Alice,ou=People,dc=example,dc=com"));
    }

    @Test
    @DisplayName("parentDnOf returns empty string for a top-level DN")
    void parentOfTopLevel() {
        // Regression: used to return the DN itself, leaving the tree-root
        // node un-refreshed after a top-level container was deleted.
        assertEquals("", MainController.parentDnOf("o=data"));
        assertEquals("", MainController.parentDnOf("dc=com"));
    }

    @Test
    @DisplayName("parentDnOf handles escaped commas in the RDN")
    void parentOfEscapedCommaRdn() {
        assertEquals("ou=People,dc=example,dc=com",
                MainController.parentDnOf("cn=Smith\\, Alice,ou=People,dc=example,dc=com"));
    }

    @Test
    @DisplayName("parentDnOf handles empty and null input")
    void parentOfEmptyOrNull() {
        assertEquals("", MainController.parentDnOf(""));
        assertEquals("", MainController.parentDnOf(null));
    }
}
