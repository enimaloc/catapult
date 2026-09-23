package fr.enimaloc.catapult.common.dto;

import java.util.List;

public record ServiceFunctionDto(String namespace, String name, List<String> parameterNames,
                                  List<String> returnKeys, List<String> optionalParameterNames,
                                  boolean isAction) {}
