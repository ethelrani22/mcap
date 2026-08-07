package nic.meg.mcap.dto.response;

import lombok.Data;

@Data
public class QualificationRequirementResponseDTO {

    private Long qualificationId;
    private String qualificationName;
    private Double minPercentage;
}