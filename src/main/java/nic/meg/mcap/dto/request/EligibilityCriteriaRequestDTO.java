package nic.meg.mcap.dto.request;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class EligibilityCriteriaRequestDTO {

    private String admissionCode;
    private Short programmeId;

    /**
     * All qualifications acceptable for this programme, each with its own
     * minimum overall percentage requirement.
     * E.g. [(Class XII Science, 60%), (Class XII Arts, 55%)] means an
     * applicant is eligible if they hold Science with >= 60% OR Arts with >= 55%.
     * Empty list = no qualification gate.
     */
    private List<QualificationRequirementDTO> qualificationRequirements = new ArrayList<>();

    private List<CategoryRelaxationDTO> categoryRelaxations;

    private Boolean cuetRequired;

    // One place for both CUET and NON-CUET
    private List<EligibilityRuleSetRequestDTO> ruleSets;

    private List<MeritRuleSetRequestDTO> meritRuleSets;

    private String tiebreakerConfig;
}