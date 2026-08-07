package nic.meg.mcap.dto.request;

import lombok.Data;

@Data
public class QualificationRequirementDTO {

    private Long qualificationId;

    /**
     * Minimum overall percentage required for THIS qualification.
     * Null = no percentage gate for this qualification.
     */
    private Double minPercentage;
}