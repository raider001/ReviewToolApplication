package com.kalynx.serverlessreviewtool.utils;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.kalynx.serverlessreviewtool.configuration.AppSettings;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * TabConfigCodec - encodes and decodes a list of {@link AppSettings.ReviewTabConfig} to and from
 * a compact, shareable Base64-URL string.
 * <p>
 * The encoded string is UTF-8 JSON serialised with Gson, then Base64-URL encoded without padding.
 * It can be safely copied into a single line, shared via chat or email, and pasted back to import.
 */
public final class TabConfigCodec {

    private static final Gson GSON = new Gson();

    private static final Set<String> VALID_STATUSES = Set.of(
        "OPEN", "IN_PROGRESS", "CHANGES_REQUESTED", "COMPLETED", "CANCELLED", "ACTIVE"
    );
    private static final Set<String> VALID_INVOLVEMENTS = Set.of("ANY", "MINE", "OTHERS");

    private TabConfigCodec() {}

    /**
     * Encodes a list of tab configurations to a single-line Base64-URL string.
     *
     * @param tabs the list of tab configurations to encode; must not be {@code null}
     * @return a non-padded Base64-URL string representing the JSON-serialised tabs
     */
    public static String encode(List<AppSettings.ReviewTabConfig> tabs) {
        String json = GSON.toJson(tabs);
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a Base64-URL string previously produced by {@link #encode} back into a list of
     * tab configurations.
     * <p>
     * Callers should always call {@link #validate(String)} first.
     *
     * @param encoded the Base64-URL encoded string
     * @return the decoded list of tab configurations
     * @throws IllegalArgumentException if the string is not valid Base64 or valid JSON
     */
    public static List<AppSettings.ReviewTabConfig> decode(String encoded) {
        byte[] bytes = Base64.getUrlDecoder().decode(encoded.trim());
        String json = new String(bytes, StandardCharsets.UTF_8);
        Type listType = new TypeToken<List<AppSettings.ReviewTabConfig>>() {}.getType();
        return GSON.fromJson(json, listType);
    }

    /**
     * Validates that {@code input} is a well-formed tab configuration string.
     * <p>
     * Checks performed in order:
     * <ol>
     *   <li>Input is non-blank</li>
     *   <li>Input is valid Base64-URL</li>
     *   <li>Decoded bytes are valid JSON</li>
     *   <li>Top-level JSON element is an array</li>
     *   <li>Each array element is a JSON object with a non-blank {@code name}</li>
     *   <li>Each {@code statusFilters} entry is a recognised value</li>
     *   <li>Each {@code involvementFilter} value is a recognised value</li>
     * </ol>
     *
     * @param input the candidate encoded string
     * @return a {@link Validator.ValidationResult} describing the outcome
     */
    public static Validator.ValidationResult validate(String input) {
        if (input == null || input.isBlank()) {
            return Validator.ValidationResult.invalid("Tab configuration cannot be empty.");
        }

        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(input.trim());
        } catch (IllegalArgumentException e) {
            return Validator.ValidationResult.invalid("Not a valid tab configuration string.");
        }

        String json = new String(bytes, StandardCharsets.UTF_8);

        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (Exception e) {
            return Validator.ValidationResult.invalid("Decoded content is not valid JSON.");
        }

        if (!root.isJsonArray()) {
            return Validator.ValidationResult.invalid("Configuration must be a JSON array of tabs.");
        }

        JsonArray array = root.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement item = array.get(i);
            if (!item.isJsonObject()) {
                return Validator.ValidationResult.invalid("Tab entry " + (i + 1) + " is not a valid object.");
            }
            Validator.ValidationResult result = validateTabObject(item.getAsJsonObject(), i + 1);
            if (!result.isValid()) {
                return result;
            }
        }

        return Validator.ValidationResult.valid();
    }

    private static Validator.ValidationResult validateTabObject(JsonObject obj, int position) {
        if (!obj.has("name") || obj.get("name").isJsonNull()
                || obj.get("name").getAsString().isBlank()) {
            return Validator.ValidationResult.invalid(
                "Tab entry " + position + " is missing a name.");
        }

        String tabName = obj.get("name").getAsString();

        if (obj.has("statusFilters") && obj.get("statusFilters").isJsonArray()) {
            for (JsonElement status : obj.get("statusFilters").getAsJsonArray()) {
                String value = status.getAsString();
                if (!VALID_STATUSES.contains(value)) {
                    return Validator.ValidationResult.invalid(
                        "Tab '" + tabName + "' has unrecognised status filter: \"" + value + "\".");
                }
            }
        }

        if (obj.has("involvementFilter") && !obj.get("involvementFilter").isJsonNull()) {
            String inv = obj.get("involvementFilter").getAsString();
            if (!VALID_INVOLVEMENTS.contains(inv)) {
                return Validator.ValidationResult.invalid(
                    "Tab '" + tabName + "' has unrecognised involvement filter: \"" + inv + "\".");
            }
        }

        return Validator.ValidationResult.valid();
    }
}

