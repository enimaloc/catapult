package fr.esportline.catapult.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

@Converter
public class TwitchCclSetConverter implements AttributeConverter<Set<TwitchCcl>, String> {

    @Override
    public String convertToDatabaseColumn(Set<TwitchCcl> ccls) {
        if (ccls == null || ccls.isEmpty()) return "";
        return ccls.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    @Override
    public Set<TwitchCcl> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return Collections.emptySet();
        return Arrays.stream(dbData.split(","))
            .map(TwitchCcl::valueOf)
            .collect(Collectors.toSet());
    }
}
