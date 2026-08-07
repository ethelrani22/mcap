package nic.meg.mcap.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Builder
public class MeritListRowDTO {

    private Integer rank;

    private Long applicationId;
    private String applicationNo;
    private String applicantName;

    private String category;

    private BigDecimal class12Percentage;
    private BigDecimal ugDegreePercentage;

    private BigDecimal entranceScore;
    private String entranceExamType;

    private BigDecimal normalizedClass12Score;
    private BigDecimal normalizedEntranceScore;

    private BigDecimal meritScore;

    private String shift;

    private String selectionCriteria;
    private String ruleDescription;

    /**
     * 1-based rule number the applicant qualified under (e.g. 1, 2, 3).
     * Displayed as "Rule 1", "Rule 2", etc. in the merit list table.
     * NULL means no rule matched.
     */
    private Integer qualifiedRuleNumber;

    private String tieBreakerReason;

    private String applicantType;
    private List<String> subjectsUsed;
    private Map<String, BigDecimal> subjectScores;

}