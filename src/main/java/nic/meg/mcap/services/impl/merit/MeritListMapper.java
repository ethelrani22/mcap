package nic.meg.mcap.services.impl.merit;

import lombok.RequiredArgsConstructor;
import nic.meg.mcap.dto.response.MeritListMetadataDTO;
import nic.meg.mcap.dto.response.MeritListRowDTO;
import nic.meg.mcap.entities.Applicant;
import nic.meg.mcap.entities.MeritList;
import nic.meg.mcap.entities.MeritListEntry;
import nic.meg.mcap.repositories.CuetScoreRepository;
import nic.meg.mcap.repositories.JeeScoreRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
@RequiredArgsConstructor
public class MeritListMapper {

    private final JeeScoreRepository jeeScoreRepository;
    private final CuetScoreRepository cuetScoreRepository;

    public MeritListMetadataDTO toMetadataDTO(MeritList meritList) {
        String windowName = (meritList.getAdmissionWindow().getStream() != null
                ? meritList.getAdmissionWindow().getStream().getStreamName() : "All Streams")
                + " (" + meritList.getAdmissionWindow().getProgrammeLevel() + ") - "
                + meritList.getAdmissionWindow().getSession();

        return MeritListMetadataDTO.builder()
                .meritListId(meritList.getMeritListId())
                .admissionWindowId(meritList.getAdmissionWindow().getAdmissionId())
                .admissionWindowName(windowName)
                .programmeLevel(meritList.getAdmissionWindow().getProgrammeLevel())
                .streamId(meritList.getStream() != null ? meritList.getStream().getStreamId() : null)
                .streamName(meritList.getStream() != null ? meritList.getStream().getStreamName() : null)
                .programmeId(meritList.getProgramme() != null ? meritList.getProgramme().getProgrammeId() : null)
                .programmeName(meritList.getProgramme() != null ? meritList.getProgramme().getProgrammeName() : null)
                .generatedOn(meritList.getGeneratedOn())
                .status(meritList.getStatus())
                .totalApplicants(meritList.getTotalApplicants())
                .applicantType(meritList.getApplicantType())
                .build();
    }

    public MeritListRowDTO toRowDTO(MeritListEntry entry) {
        Applicant applicant = entry.getApplication().getApplicant();

        String fullName = applicant.getFirstName() + " "
                + (applicant.getMiddleName() != null ? applicant.getMiddleName() + " " : "")
                + (applicant.getLastName() != null ? applicant.getLastName() : "");

        String examType = determineExamType(applicant);

        BigDecimal roundedScore = entry.getMeritScore() != null
                ? entry.getMeritScore().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        String fullCriteria = entry.getSelectionCriteria();
        String displayCriteria = (fullCriteria != null && fullCriteria.contains(":"))
                ? fullCriteria.split(":")[0].trim()
                : fullCriteria;

        return MeritListRowDTO.builder()
                .rank(entry.getRank())
                .applicationId(entry.getApplication().getApplicationId())
                .applicationNo(entry.getApplication().getApplicationNo())
                .applicantName(fullName.trim())
                .category(entry.getCategory())
                .class12Percentage(entry.getClass12Percentage())
                .ugDegreePercentage(entry.getUgDegreePercentage())
                .entranceScore(entry.getEntranceScore())
                .entranceExamType(examType)
                .normalizedClass12Score(entry.getNormalizedClass12Score())
                .normalizedEntranceScore(entry.getNormalizedEntranceScore())
                .meritScore(roundedScore)
                .shift("Regular")
                .selectionCriteria(displayCriteria)
                .ruleDescription(fullCriteria)
                .qualifiedRuleNumber(entry.getQualifiedRuleNumber())  // NEW
                .tieBreakerReason(entry.getTieBreakerReason())
                .applicantType(entry.getApplicantType())
                .subjectsUsed(entry.getSubjectsUsed())
                .subjectScores(entry.getSubjectScores())
                .build();
    }

    private String determineExamType(Applicant applicant) {
        if (jeeScoreRepository.findByApplicant(applicant).isPresent()) return "JEE";
        if (cuetScoreRepository.findByApplicant(applicant).isPresent()) return "CUET";
        return "NA";
    }
}