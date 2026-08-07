package nic.meg.mcap.entities;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class MeritListEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long entryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(nullable = false)
    private MeritList meritList;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(nullable = false)
    private Application application;

    @Column(nullable = false)
    private Integer rank;

    @Column(precision = 10, scale = 4, nullable = false)
    private BigDecimal meritScore;

    /** Human-readable label of the rule that qualified this applicant (e.g. "Rule #1") */
    @Column(length = 255)
    private String selectionCriteria;

    /**
     * 1-based index of the merit rule that produced a qualifying score for this
     * applicant. Rule 1 applicants always rank above Rule 2, etc.
     * NULL / 0 means no rule matched (score = 0).
     */
    @Column(name = "qualified_rule_number")
    private Integer qualifiedRuleNumber;

    @Column(precision = 5, scale = 2)
    private BigDecimal class12Percentage;

    @Column(precision = 5, scale = 2)
    private BigDecimal ugDegreePercentage;

    @Column(precision = 5, scale = 2)
    private BigDecimal entranceScore;

    @Column(length = 20)
    private String category;

    @Column(length = 500)
    private String tieBreakerReason;

    @Column(length = 20, nullable = false)
    private String applicantType = "WITH_ENTRANCE";

    @Column(precision = 5, scale = 2)
    private BigDecimal normalizedClass12Score;

    @Column(precision = 5, scale = 2)
    private BigDecimal normalizedUgDegreeScore;

    @Column(precision = 5, scale = 2)
    private BigDecimal normalizedEntranceScore;

    @Column(name = "subjects_used", columnDefinition = "TEXT")
    private String subjectsUsedJson;

    @Column(name = "subject_scores", columnDefinition = "TEXT")
    private String subjectScoresJson;

    @Transient
    public Map<String, BigDecimal> getSubjectScores() {
        if (subjectScoresJson == null || subjectScoresJson.isBlank()) return Map.of();
        try {
            return new ObjectMapper().readValue(
                    subjectScoresJson, new TypeReference<Map<String, BigDecimal>>() {});
        } catch (Exception e) { return Map.of(); }
    }

    public void setSubjectScores(Map<String, BigDecimal> scores) {
        try { this.subjectScoresJson = new ObjectMapper().writeValueAsString(scores); }
        catch (Exception e) { this.subjectScoresJson = null; }
    }

    @Transient
    public List<String> getSubjectsUsed() {
        if (subjectsUsedJson == null || subjectsUsedJson.isBlank()) return List.of();
        try {
            return new ObjectMapper().readValue(
                    subjectsUsedJson, new TypeReference<List<String>>() {});
        } catch (Exception e) { return List.of(); }
    }

    public void setSubjectsUsed(List<String> subjects) {
        try { this.subjectsUsedJson = new ObjectMapper().writeValueAsString(subjects); }
        catch (Exception e) { this.subjectsUsedJson = null; }
    }
}