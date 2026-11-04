package com.pointbluetech.arborj.service;

import com.pointbluetech.arborj.model.LDAPAttributeInfo;
import com.pointbluetech.arborj.model.LDAPAttributeSyntax;
import com.pointbluetech.arborj.model.LDAPObjectClassInfo;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.schema.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live eDirectory groupOfNames schema (verbatim) plus a minimal top class.
 * The entry's objectClass values are groupOfNames and Top.
 *
 * <p>The attribute's primary NAME and the class MAY token are both
 * {@code member}. ArborJ indexes that primary name case-insensitively, so
 * Add Attribute suggestions include it. The aliases {@code uniqueMember},
 * {@code group}, and {@code groupOfUniqueNames} are not needed for this entry.
 */
class EDirectoryMemberSuggestTest {

    /** Verbatim objectClasses value from a live eDirectory subschema. */
    static final String GROUP_OF_NAMES =
            "( 2.5.6.9 NAME ( 'groupOfNames' 'group' 'groupOfUniqueNames' ) SUP Top STRUCTURAL MUST cn MAY ( description $ l $ member $ ou $ o $ owner $ seeAlso $ groupID $ fullName $ eMailAddress $ mailboxLocation $ mailboxID $ Profile $ profileMembership $ loginScript $ businessCategory $ nspmPasswordPolicyDN ) X-NDS_NAMING 'cn' X-NDS_CONTAINMENT ( 'Organization' 'organizationalUnit' 'domain' ) X-NDS_NAME 'Group' X-NDS_NOT_CONTAINER '1' X-NDS_NONREMOVABLE '1' )";

    /** Verbatim attributeTypes value from a live eDirectory subschema. */
    static final String MEMBER =
            "( 2.5.4.31 NAME ( 'member' 'uniqueMember' ) SYNTAX 1.3.6.1.4.1.1466.115.121.1.12 X-NDS_NAME 'Member' X-NDS_NONREMOVABLE '1' )";

    static final String TOP =
            "( 2.5.6.0 NAME 'top' ABSTRACT MUST objectClass )";

    @Test
    @DisplayName("groupOfNames + Top Add Attribute suggestions include member")
    void groupOfNamesSuggestsMember() throws Exception {
        Entry subschema = new Entry("cn=schema");
        subschema.addAttribute("attributeTypes", MEMBER);
        subschema.addAttribute("objectClasses", GROUP_OF_NAMES, TOP);
        Schema schema = new Schema(subschema);

        SchemaService service = new SchemaService(null);
        Map<String, LDAPAttributeInfo> attributes = service.loadAttributeMap(schema);
        Map<String, LDAPObjectClassInfo> classes = service.loadObjectClassMap(schema);

        LDAPObjectClassInfo group = classes.get("groupofnames");
        assertNotNull(group, "groupOfNames was not indexed. keys=" + classes.keySet());
        assertEquals("top", group.getSuperClass());
        assertTrue(group.getMustAttributes().contains("cn"),
                "MUST cn missing: " + group.getMustAttributes());
        assertTrue(group.getMustAttributes().contains("objectclass"),
                "inherited MUST objectClass missing: " + group.getMustAttributes());
        assertTrue(group.getMayAttributes().contains("member"),
                "MAY member missing: " + group.getMayAttributes());

        LDAPAttributeInfo member = attributes.get("member");
        assertNotNull(member, "member was not indexed. keys=" + attributes.keySet());
        assertEquals("member", member.getName());
        assertEquals(LDAPAttributeSyntax.DN, member.getSyntax());

        Set<String> allowed = SchemaService.allowedAttributeNames(
                classes, List.of("groupOfNames", "Top"));
        assertNotNull(allowed,
                "groupOfNames/Top did not match; the class filter would have been skipped");
        assertTrue(allowed.contains("member"),
                "member is not allowed on groupOfNames. allowed=" + allowed);

        assertEquals(List.of("member"),
                SchemaService.suggestAttributeNames(attributes, allowed, "m"));
        assertEquals(List.of("member"),
                SchemaService.suggestAttributeNames(attributes, allowed, "me"));
        assertEquals(List.of("member"),
                SchemaService.suggestAttributeNames(attributes, allowed, "mem"));
        assertEquals(List.of("member"),
                SchemaService.suggestAttributeNames(attributes, allowed, "member"));
        assertEquals(List.of("member"),
                SchemaService.suggestAttributeNames(attributes, allowed, "Member"));
    }
}
