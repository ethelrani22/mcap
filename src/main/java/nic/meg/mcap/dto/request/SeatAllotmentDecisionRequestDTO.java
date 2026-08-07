package nic.meg.mcap.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SeatAllotmentDecisionRequestDTO {
    @NotNull(message = "Allotment ID cannot be null.")
    private Long allotmentId;

    // Only used for REJECT — optional free-text reason from the applicant.
    // Must NOT be mandatory here since this DTO is shared by accept/reject/slide-up.
    @Size(max = 500, message = "Reason cannot exceed 500 characters.")
    private String reason;
}