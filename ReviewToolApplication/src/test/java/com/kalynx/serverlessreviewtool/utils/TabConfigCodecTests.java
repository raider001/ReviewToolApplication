package com.kalynx.serverlessreviewtool.utils;

import com.kalynx.serverlessreviewtool.configuration.AppSettings;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TabConfigCodec}.
 */
class TabConfigCodecTests {

    private static AppSettings.ReviewTabConfig tab(String name, List<String> statuses, String involvement) {
        return new AppSettings.ReviewTabConfig(
            "id-" + name, name, "", "", new ArrayList<>(), new ArrayList<>(),
            new ArrayList<>(statuses), involvement
        );
    }

    // -------------------------------------------------------------------------
    // encode / decode round-trips
    // -------------------------------------------------------------------------

    @Test
    void roundTrip_singleTab_preservesAllFields() {
        AppSettings.ReviewTabConfig original = new AppSettings.ReviewTabConfig(
            "abc-123", "My Reviews",
            "feature", "alice",
            List.of("*frontend*"),
            List.of("bob"),
            List.of("OPEN", "IN_PROGRESS"),
            "MINE"
        );
        List<AppSettings.ReviewTabConfig> tabs = List.of(original);

        String encoded = TabConfigCodec.encode(tabs);
        List<AppSettings.ReviewTabConfig> decoded = TabConfigCodec.decode(encoded);

        assertEquals(1, decoded.size());
        AppSettings.ReviewTabConfig result = decoded.getFirst();
        assertEquals("abc-123",           result.getId());
        assertEquals("My Reviews",         result.getName());
        assertEquals("feature",            result.getTitleContains());
        assertEquals("alice",              result.getAuthorContains());
        assertEquals(List.of("*frontend*"), result.getReviewerPatterns());
        assertEquals(List.of("bob"),        result.getRepositories());
        assertEquals(List.of("OPEN", "IN_PROGRESS"), result.getStatusFilters());
        assertEquals("MINE",               result.getInvolvementFilter());
    }

    @Test
    void roundTrip_emptyList_decodesBackToEmptyList() {
        String encoded = TabConfigCodec.encode(new ArrayList<>());
        List<AppSettings.ReviewTabConfig> decoded = TabConfigCodec.decode(encoded);
        assertNotNull(decoded);
        assertTrue(decoded.isEmpty());
    }

    @Test
    void roundTrip_multipleTabs_preservesOrder() {
        List<AppSettings.ReviewTabConfig> tabs = List.of(
            tab("Alpha", List.of("OPEN"), "ANY"),
            tab("Beta",  List.of("COMPLETED"), "MINE"),
            tab("Gamma", List.of(), "OTHERS")
        );

        List<AppSettings.ReviewTabConfig> decoded = TabConfigCodec.decode(TabConfigCodec.encode(tabs));

        assertEquals(3, decoded.size());
        assertEquals("Alpha", decoded.get(0).getName());
        assertEquals("Beta",  decoded.get(1).getName());
        assertEquals("Gamma", decoded.get(2).getName());
    }

    @Test
    void encode_producesOnlyUrlSafeCharacters() {
        List<AppSettings.ReviewTabConfig> tabs = AppSettings.createDefaultTabs();
        String encoded = TabConfigCodec.encode(tabs);
        assertTrue(encoded.matches("[A-Za-z0-9_-]+"),
            "Encoded string should only contain URL-safe Base64 characters");
    }

    // -------------------------------------------------------------------------
    // validate — happy paths
    // -------------------------------------------------------------------------

    @Test
    void validate_validEncodedString_returnsValid() {
        String encoded = TabConfigCodec.encode(AppSettings.createDefaultTabs());
        assertTrue(TabConfigCodec.validate(encoded).isValid());
    }

    @Test
    void validate_emptyList_returnsValid() {
        String encoded = TabConfigCodec.encode(new ArrayList<>());
        assertTrue(TabConfigCodec.validate(encoded).isValid());
    }

    @Test
    void validate_leadingAndTrailingWhitespace_returnsValid() {
        String encoded = "  " + TabConfigCodec.encode(List.of(tab("T", List.of("OPEN"), "ANY"))) + "  ";
        assertTrue(TabConfigCodec.validate(encoded).isValid());
    }

    @Test
    void validate_allStatusValues_returnValid() {
        for (String status : List.of("OPEN", "IN_PROGRESS", "CHANGES_REQUESTED", "COMPLETED", "CANCELLED", "ACTIVE")) {
            String encoded = TabConfigCodec.encode(List.of(tab("T", List.of(status), "ANY")));
            Validator.ValidationResult result = TabConfigCodec.validate(encoded);
            assertTrue(result.isValid(), "Expected valid for status: " + status);
        }
    }

    @Test
    void validate_allInvolvementValues_returnValid() {
        for (String involvement : List.of("ANY", "MINE", "OTHERS")) {
            String encoded = TabConfigCodec.encode(List.of(tab("T", List.of(), involvement)));
            assertTrue(TabConfigCodec.validate(encoded).isValid(),
                "Expected valid for involvement: " + involvement);
        }
    }

    // -------------------------------------------------------------------------
    // validate — failure paths
    // -------------------------------------------------------------------------

    @Test
    void validate_nullInput_returnsInvalid() {
        Validator.ValidationResult result = TabConfigCodec.validate(null);
        assertFalse(result.isValid());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    void validate_blankInput_returnsInvalid() {
        assertFalse(TabConfigCodec.validate("   ").isValid());
    }

    @Test
    void validate_randomGarbage_returnsInvalid() {
        assertFalse(TabConfigCodec.validate("not-a-valid-hash!!!").isValid());
    }

    @Test
    void validate_validBase64ButNotJson_returnsInvalid() {
        String notJson = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("this is not json".getBytes(StandardCharsets.UTF_8));
        Validator.ValidationResult result = TabConfigCodec.validate(notJson);
        assertFalse(result.isValid());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    void validate_validJsonButNotArray_returnsInvalid() {
        String notArray = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"name\":\"test\"}".getBytes(StandardCharsets.UTF_8));
        Validator.ValidationResult result = TabConfigCodec.validate(notArray);
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("array"));
    }

    @Test
    void validate_tabMissingName_returnsInvalid() {
        String missingName = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("[{\"id\":\"x\",\"involvementFilter\":\"ANY\"}]"
                .getBytes(StandardCharsets.UTF_8));
        Validator.ValidationResult result = TabConfigCodec.validate(missingName);
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("missing a name"));
    }

    @Test
    void validate_tabBlankName_returnsInvalid() {
        String blankName = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("[{\"name\":\"  \",\"involvementFilter\":\"ANY\"}]"
                .getBytes(StandardCharsets.UTF_8));
        assertFalse(TabConfigCodec.validate(blankName).isValid());
    }

    @Test
    void validate_invalidStatusFilter_returnsInvalidWithMessage() {
        String encoded = TabConfigCodec.encode(List.of(tab("T", List.of("MERGED"), "ANY")));
        Validator.ValidationResult result = TabConfigCodec.validate(encoded);
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("MERGED"));
    }

    @Test
    void validate_invalidInvolvementFilter_returnsInvalidWithMessage() {
        String json = "[{\"name\":\"T\",\"involvementFilter\":\"EVERYONE\"}]";
        String encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        Validator.ValidationResult result = TabConfigCodec.validate(encoded);
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("EVERYONE"));
    }

    @Test
    void validate_wildcardPatternsInRepositories_areAccepted() {
        AppSettings.ReviewTabConfig t = new AppSettings.ReviewTabConfig(
            "id", "T", "", "", List.of("*frontend*", "exact-repo"),
            new ArrayList<>(), List.of("OPEN"), "ANY"
        );
        String encoded = TabConfigCodec.encode(List.of(t));
        assertTrue(TabConfigCodec.validate(encoded).isValid());
    }

    @Test
    void validate_wildcardPatternsInReviewers_areAccepted() {
        AppSettings.ReviewTabConfig t = new AppSettings.ReviewTabConfig(
            "id", "T", "", "", new ArrayList<>(),
            List.of("*alice*", "bob"), List.of("OPEN"), "ANY"
        );
        String encoded = TabConfigCodec.encode(List.of(t));
        assertTrue(TabConfigCodec.validate(encoded).isValid());
    }

    @Test
    void validate_mixedValidAndInvalidStatusInSameTab_returnsInvalid() {
        String encoded = TabConfigCodec.encode(List.of(tab("T", List.of("OPEN", "BOGUS"), "ANY")));
        assertFalse(TabConfigCodec.validate(encoded).isValid());
    }

    @Test
    void errorMessage_containsTabName_forStatusError() {
        String encoded = TabConfigCodec.encode(List.of(tab("MySpecialTab", List.of("INVALID"), "ANY")));
        Validator.ValidationResult result = TabConfigCodec.validate(encoded);
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("MySpecialTab"));
    }
}


