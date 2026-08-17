package fr.enimaloc.catapult.service.notification.dto;

import java.util.List;

// A predefined, parameterized full preset template ("quick configuration"). templateJson may
// contain {{baseUrl}} (resolved server-side once, before this ever reaches an HTTP response — see
// ApiTwitchatWidgetController) and {{gameName}}/{{action:TYPE}}/{{param:KEY}} (left untouched;
// {{param:KEY}} is resolved entirely client-side by the streamer filling in `parameters` before
// the result becomes an ordinary, editable preset — {{gameName}}/{{action:TYPE}} are resolved
// only later, at real notification render time, same as any other preset).
//
// groupKey/groupLabel/variant back the quick-config modal's "Lien"/"Trigger" picker: the modal
// picks a delivery mechanism (variant) once, then offers one checkbox per groupKey (the action,
// e.g. "disable the bot on stream start") — each checked groupKey resolves, together with the
// chosen variant, to exactly one TwitchatQuickConfig to apply to its own eventType.
public record TwitchatQuickConfig(String key, String label, String description, String eventType,
                                   String groupKey, String groupLabel, String variant,
                                   List<TwitchatQuickConfigParam> parameters, String templateJson) {
}
