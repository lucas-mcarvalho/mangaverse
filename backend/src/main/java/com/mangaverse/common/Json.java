package com.mangaverse.common;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

/** Utilitários para leitura tolerante de JSON (Jackson 3). */
public final class Json {

    private Json() {
    }

    public static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        String s = v.isValueNode() ? v.asString() : v.toString();
        return s == null || s.isBlank() ? null : s;
    }

    public static Integer integer(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        if (v.isNumber()) {
            return v.asInt();
        }
        try {
            return Integer.parseInt(v.asString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean bool(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && v.asBoolean(false);
    }

    public static List<JsonNode> array(JsonNode node, String field) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode v = node == null ? null : node.get(field);
        if (v != null && v.isArray()) {
            v.forEach(out::add);
        }
        return out;
    }
}
