package com.mangaverse.common;

import java.util.Arrays;
import java.util.List;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Persiste listas de texto curtas (gêneros, autores) como texto separado por quebra de linha. */
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        return String.join("\n", attribute.stream().map(s -> s.replace('\n', ' ').trim()).filter(s -> !s.isEmpty()).toList());
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        return Arrays.stream(dbData.split("\n")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
