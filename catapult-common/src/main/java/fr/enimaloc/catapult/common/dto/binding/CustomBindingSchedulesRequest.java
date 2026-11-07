package fr.enimaloc.catapult.common.dto.binding;

import java.util.List;
import java.util.UUID;

/**
 * A binding's whole list of schedule slots, saved at once. {@code known}: the ids of the slots the list was edited
 * from; when the binding's are no longer those (saved elsewhere meanwhile), it is refused rather than deleting what
 * the editor never saw. Null: no check.
 */
public record CustomBindingSchedulesRequest(List<UUID> known, List<CustomBindingScheduleRequest> slots) {
}
