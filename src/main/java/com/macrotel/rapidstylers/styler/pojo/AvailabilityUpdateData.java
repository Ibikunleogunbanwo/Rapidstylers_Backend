package com.macrotel.rapidstylers.styler.pojo;

import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import lombok.Data;

/**
 * Wrapper for availability updates. A {@code @Valid List<AvailabilityData>}
 * parameter does not cascade into the list elements under Spring MVC's
 * @RequestBody validation, so the list is wrapped here — element constraints
 * (day of week range, HH:mm format) are then enforced by bean validation at
 * the controller boundary, and the service-level checks remain as a backstop.
 */
@Data
public class AvailabilityUpdateData {
    @NotNull(message = "Availability slots cannot be null")
    @Valid
    private List<AvailabilityData> slots;
}
