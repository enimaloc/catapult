package fr.enimaloc.catapult.service.notification.dto;

import java.util.List;

// A predefined, parameterized full preset template ("quick configuration"). templateJson may
// contain {{baseUrl}} (resolved server-side once, before this ever reaches an HTTP response — see
// ApiTwitchatWidgetController) and {{gameName}}/{{action:TYPE}}/{{param:KEY}} (left untouched;
// {{param:KEY}} is resolved entirely client-side by the streamer filling in `parameters` before
// the result becomes an ordinary, editable preset — {{gameName}}/{{action:TYPE}} are resolved
// only later, at real notification render time, same as any other preset).
public record TwitchatQuickConfig(String key, String label, String description, String eventType,
                                   List<TwitchatQuickConfigParam> parameters, String templateJson) {
}
