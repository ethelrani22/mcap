package nic.meg.mcap.utils;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Produces a compact {field: {old, new}} JSON diff between two DTOs of the same
 * shape, used to populate applicant_verification_history.changed_fields when an
 * institute edits applicant details. Only fields that actually changed are included.
 */
public final class JsonDiffUtil {

    private JsonDiffUtil() {
    }

    @SuppressWarnings("unchecked")
    public static String diff(ObjectMapper mapper, Object before, Object after) {
        Map<String, Object> beforeMap = mapper.convertValue(before, Map.class);
        Map<String, Object> afterMap = mapper.convertValue(after, Map.class);

        Map<String, Object> changes = new LinkedHashMap<>();
        for (String key : afterMap.keySet()) {
            Object oldVal = beforeMap.get(key);
            Object newVal = afterMap.get(key);
            if (!Objects.equals(oldVal, newVal)) {
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("old", oldVal);
                pair.put("new", newVal);
                changes.put(key, pair);
            }
        }

        if (changes.isEmpty()) {
            return null;
        }

        try {
            return mapper.writeValueAsString(changes);
        } catch (Exception e) {
            return null;
        }
    }
}