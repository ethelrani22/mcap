package nic.meg.mcap.services.impl;

import lombok.RequiredArgsConstructor;
import nic.meg.mcap.dto.request.*;
import nic.meg.mcap.dto.response.*;
import nic.meg.mcap.entities.*;
import nic.meg.mcap.enums.ScoreSource;
import nic.meg.mcap.repositories.AdmissionWindowRepository;
import nic.meg.mcap.repositories.EligibilityCriteriaRepository;
import nic.meg.mcap.repositories.ProgrammeRepository;
import nic.meg.mcap.repositories.QualificationRepository;
import nic.meg.mcap.services.EligibilityCriteriaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

// --- NEW IMPORTS ---
import nic.meg.mcap.services.ScheduleHelperService;
import nic.meg.mcap.exception.BadRequestException;
import jakarta.persistence.EntityManager;

@Service
@RequiredArgsConstructor
public class EligibilityCriteriaServiceImpl implements EligibilityCriteriaService {

    private final EligibilityCriteriaRepository criteriaRepo;
    private final AdmissionWindowRepository windowRepo;
    private final ProgrammeRepository programmeRepository;
    private final QualificationRepository qualificationRepo;

    // --- INJECT HELPER ---
    private final ScheduleHelperService scheduleHelperService;

    // --- INJECT ENTITY MANAGER for flush-before-reinsert ---
    private final EntityManager entityManager;

    // --- THE DATABASE STRING ---
    private static final String ELIGIBILITY_STEP_NAME = "Set Eligibility Rules";

    @Override
    @Transactional
    public EligibilityCriteriaResponseDTO saveCriteria(EligibilityCriteriaRequestDTO requestDTO) {

        if (requestDTO.getAdmissionCode() == null) {
            throw new RuntimeException("Admission window code is required");
        }
        if (requestDTO.getProgrammeId() == null) {
            throw new RuntimeException("Programme id is required");
        }

        // --- SECURITY LOCK USING DB STRING ---
        if (!scheduleHelperService.isWindowInScheduleStep(requestDTO.getAdmissionCode(), ELIGIBILITY_STEP_NAME)) {
            throw new BadRequestException("The timeline for setting eligibility rules is currently closed or not active for this window.");
        }

        AdmissionWindow window = windowRepo.findByAdmissionCode(requestDTO.getAdmissionCode())
                .orElseThrow(() -> new RuntimeException("Admission window not found"));

        Programme programme = programmeRepository.findById(requestDTO.getProgrammeId())
                .orElseThrow(() -> new RuntimeException("Programme not found"));

        // FIXED: Removed the trailing semicolon to properly chain .orElseGet()
        EligibilityCriteria criteria = criteriaRepo
                .findByAdmissionWindowAdmissionCodeAndProgrammeProgrammeId(window.getAdmissionCode(), programme.getProgrammeId())
                .orElseGet(EligibilityCriteria::new);

        criteria.setAdmissionWindow(window);
        criteria.setProgramme(programme);

        // Persist the parent first so it has an ID (needed for new criteria).
        EligibilityCriteria saved = criteriaRepo.saveAndFlush(criteria);
        Short criteriaId = saved.getEligibilityCriteriaId();

        // ── Bulk-delete all child rows by FK before re-inserting ──────────────
        entityManager.createQuery(
                        "DELETE FROM SubjectRequirement sr WHERE sr IN " +
                                "(SELECT sr2 FROM EligibilityRuleSet rs JOIN rs.subjectRequirements sr2 " +
                                " WHERE rs IN (SELECT rs3 FROM EligibilityCriteria ec JOIN ec.ruleSets rs3 WHERE ec.eligibilityCriteriaId = :id))")
                .setParameter("id", criteriaId).executeUpdate();

        entityManager.createQuery(
                        "DELETE FROM EligibilityRuleSet rs WHERE rs IN " +
                                "(SELECT rs2 FROM EligibilityCriteria ec JOIN ec.ruleSets rs2 WHERE ec.eligibilityCriteriaId = :id)")
                .setParameter("id", criteriaId).executeUpdate();

        entityManager.createQuery(
                        "DELETE FROM EligibilityQualificationRequirement q WHERE q.eligibilityCriteria.eligibilityCriteriaId = :id")
                .setParameter("id", criteriaId).executeUpdate();

        entityManager.createNativeQuery(
                        "DELETE FROM mcap.eligibility_category_relaxation WHERE eligibility_criteria_id = :id")
                .setParameter("id", criteriaId).executeUpdate();

        entityManager.createQuery(
                        "DELETE FROM MeritRuleSet m WHERE m.eligibilityCriteria.eligibilityCriteriaId = :id")
                .setParameter("id", criteriaId).executeUpdate();

        entityManager.flush();
        entityManager.clear();

        // Re-fetch the criteria after cache clear
        criteria = criteriaRepo.findById(criteriaId)
                .orElseThrow(() -> new RuntimeException("Criteria disappeared after flush — this should not happen"));
        criteria.setAdmissionWindow(window);
        criteria.setProgramme(programme);

        // ── Re-add all children ───────────────────────────────────────────────
        updateQualificationRequirements(criteria, requestDTO.getQualificationRequirements());

        criteria.setCuetRequired(Boolean.TRUE.equals(requestDTO.getCuetRequired()));
        criteria.setTiebreakerConfig(requestDTO.getTiebreakerConfig());

        // ---------------- Category relaxations ----------------
        updateCategoryRelaxations(criteria, requestDTO.getCategoryRelaxations());

        // ---------------- Rule sets (Eligibility) ----------------
        updateEligibilityRuleSets(criteria, requestDTO.getRuleSets());

        // ---------------- Merit Rule Sets ----------------
        updateMeritRuleSets(criteria, requestDTO.getMeritRuleSets());

        // Save everything in one go (Cascaded)
        saved = criteriaRepo.save(criteria);

        // NOTE: Eligibility recalculation is no longer triggered automatically on save.
        // Use the 'Recalculate Eligible Candidates' button on the Manage Admissions page instead.

        return mapToResponseDTO(saved);
    }

    private void updateQualificationRequirements(EligibilityCriteria criteria, List<QualificationRequirementDTO> dtos) {
        if (criteria.getQualificationRequirements() == null) {
            criteria.setQualificationRequirements(new ArrayList<>());
        } else {
            criteria.getQualificationRequirements().clear();
        }

        if (dtos == null) return;

        Set<Long> seenQualIds = new HashSet<>();
        for (QualificationRequirementDTO reqDto : dtos) {
            if (reqDto == null || reqDto.getQualificationId() == null) continue;

            if (!seenQualIds.add(reqDto.getQualificationId())) {
                throw new IllegalArgumentException(
                        "Duplicate qualification in eligibility criteria: id " + reqDto.getQualificationId());
            }

            Qualification qualification = qualificationRepo.findById(reqDto.getQualificationId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Qualification not found: id " + reqDto.getQualificationId()));

            if (reqDto.getMinPercentage() != null
                    && (reqDto.getMinPercentage() < 0 || reqDto.getMinPercentage() > 100)) {
                throw new IllegalArgumentException(
                        "minPercentage for qualification " + qualification.getName() + " must be between 0 and 100");
            }

            EligibilityQualificationRequirement req = new EligibilityQualificationRequirement();
            req.setEligibilityCriteria(criteria);
            req.setQualification(qualification);
            req.setMinPercentage(reqDto.getMinPercentage());
            criteria.getQualificationRequirements().add(req);
        }
    }

    private void updateCategoryRelaxations(EligibilityCriteria criteria, List<CategoryRelaxationDTO> dtos) {
        if (criteria.getCategoryRelaxations() == null) {
            criteria.setCategoryRelaxations(new ArrayList<>());
        } else {
            criteria.getCategoryRelaxations().clear();
        }

        if (dtos != null) {
            for (CategoryRelaxationDTO relaxDTO : dtos) {
                if (relaxDTO == null) continue;
                if (relaxDTO.getCategoryCode() == null || relaxDTO.getCategoryCode().trim().isBlank()) {
                    throw new IllegalArgumentException("Category relaxation categoryCode is required");
                }

                EligibilityCategoryRelaxation relax = new EligibilityCategoryRelaxation();
                relax.setCategoryCode(relaxDTO.getCategoryCode().trim());
                relax.setRelaxationValue(relaxDTO.getRelaxationValue());
                criteria.getCategoryRelaxations().add(relax);
            }
        }
    }

    private void updateEligibilityRuleSets(EligibilityCriteria criteria, List<EligibilityRuleSetRequestDTO> dtos) {
        if (criteria.getRuleSets() == null) {
            criteria.setRuleSets(new ArrayList<>());
        } else {
            criteria.getRuleSets().clear();
        }

        if (dtos != null) {
            for (EligibilityRuleSetRequestDTO rsReq : dtos) {
                if (rsReq == null) continue;

                EligibilityRuleSet ruleSet = new EligibilityRuleSet();
                ruleSet.setDescription(rsReq.getDescription());

                List<SubjectRequirement> subjectReqs = new ArrayList<>();
                if (rsReq.getSubjectRequirements() != null) {
                    for (SubjectRequirementRequestDTO subReq : rsReq.getSubjectRequirements()) {
                        SubjectRequirement req = createSubjectRequirement(subReq);
                        subjectReqs.add(req);
                    }
                }

                if (subjectReqs.isEmpty()) {
                    throw new IllegalArgumentException("Each rule set must contain at least one valid subject requirement");
                }

                ruleSet.setSubjectRequirements(subjectReqs);
                criteria.getRuleSets().add(ruleSet);
            }
        }

        if (criteria.getRuleSets().isEmpty()) {
            throw new IllegalArgumentException("At least one eligibility rule set is required");
        }
    }

    private SubjectRequirement createSubjectRequirement(SubjectRequirementRequestDTO subReq) {
        if (subReq == null) return null;
        if (subReq.getScoreSource() == null ||
                !(subReq.getScoreSource() == ScoreSource.CUET || subReq.getScoreSource() == ScoreSource.NON_CUET)) {
            throw new IllegalArgumentException("Eligibility scoreSource must be CUET or NON_CUET");
        }

        List<String> names = subReq.getSubjectNames();
        if (names == null || names.isEmpty()) {
            throw new IllegalArgumentException("Subject names are required");
        }

        String[] subjectArray = names.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toArray(String[]::new);

        Double threshold = subReq.getMinScore();

        // FIX: Only validate NON_CUET threshold when a value is actually provided.
        // An empty/null threshold means the user is still filling in the rule row
        // (e.g. Direct Admission route adds a new blank row). The final-save
        // validation on the frontend is responsible for ensuring a value is entered
        // before submission. Enforcing > 0 here blocked the "Add Rule" button entirely.
        if (subReq.getScoreSource() == ScoreSource.NON_CUET) {
            if (threshold != null && threshold <= 0) {
                throw new IllegalArgumentException("NON_CUET minimum threshold must be > 0");
            }
        }

        SubjectRequirement req = new SubjectRequirement();
        req.setSubjectNames(subjectArray);
        req.setMinScore(threshold);
        req.setCalculationType(subReq.getCalculationType());
        req.setScoreSource(subReq.getScoreSource());
        return req;
    }

    private void updateMeritRuleSets(EligibilityCriteria criteria, List<MeritRuleSetRequestDTO> dtos) {
        if (criteria.getMeritRuleSets() == null) {
            criteria.setMeritRuleSets(new ArrayList<>());
        } else {
            criteria.getMeritRuleSets().clear();
        }

        if (dtos != null) {
            for (MeritRuleSetRequestDTO mDto : dtos) {
                if (mDto == null) continue;

                MeritRuleSet meritRule = new MeritRuleSet();

                meritRule.setEligibilityCriteria(criteria);

                meritRule.setSourceType(mDto.getSourceType());
                meritRule.setOptionIndex(mDto.getOptionIndex());
                meritRule.setLabel(mDto.getLabel());

                // The New Explicit Subject List
                if (mDto.getMeritSubjects() != null) {
                    meritRule.setMeritSubjects(mDto.getMeritSubjects().toArray(new String[0]));
                } else {
                    meritRule.setMeritSubjects(new String[0]);
                }

                criteria.getMeritRuleSets().add(meritRule);
            }
        }
    }

    @Override
    public EligibilityCriteriaResponseDTO getCriteriaByWindowAndProgramme(String admissionCode, Short programmeId) {
        if (admissionCode == null || programmeId == null) return null;

        return criteriaRepo
                .findByAdmissionWindowAdmissionCodeAndProgrammeProgrammeId(admissionCode, programmeId)
                .map(this::mapToResponseDTO)
                .orElse(null);
    }

    private EligibilityCriteriaResponseDTO mapToResponseDTO(EligibilityCriteria entity) {
        EligibilityCriteriaResponseDTO dto = new EligibilityCriteriaResponseDTO();
        dto.setEligibilityCriteriaId(entity.getEligibilityCriteriaId());

        if (entity.getProgramme() != null) {
            dto.setProgrammeId(entity.getProgramme().getProgrammeId().longValue());
            dto.setProgrammeName(entity.getProgramme().getProgrammeName());
        }

        if (entity.getQualificationRequirements() != null) {
            dto.setQualificationRequirements(
                    entity.getQualificationRequirements().stream().map(qr -> {
                        QualificationRequirementResponseDTO qrDto = new QualificationRequirementResponseDTO();
                        qrDto.setQualificationId(qr.getQualification().getId());
                        qrDto.setQualificationName(qr.getQualification().getName());
                        qrDto.setMinPercentage(qr.getMinPercentage());
                        return qrDto;
                    }).collect(Collectors.toList())
            );
        }

        dto.setCuetRequired(entity.isCuetRequired());
        dto.setTiebreakerConfig(entity.getTiebreakerConfig());

        if (entity.getCategoryRelaxations() != null) {
            dto.setCategoryRelaxations(
                    entity.getCategoryRelaxations().stream().map(r -> {
                        CategoryRelaxationDTO rDto = new CategoryRelaxationDTO();
                        rDto.setCategoryCode(r.getCategoryCode());
                        rDto.setRelaxationValue(r.getRelaxationValue());
                        return rDto;
                    }).collect(Collectors.toList())
            );
        }

        if (entity.getRuleSets() != null) {
            dto.setRuleSets(entity.getRuleSets().stream().map(rs -> {
                EligibilityRuleSetResponseDTO rsDTO = new EligibilityRuleSetResponseDTO();
                rsDTO.setRuleSetId(rs.getRuleSetId());
                rsDTO.setDescription(rs.getDescription());

                if (rs.getSubjectRequirements() != null) {
                    rsDTO.setSubjectRequirements(
                            rs.getSubjectRequirements().stream().map(sub -> {
                                SubjectRequirementResponseDTO subDTO = new SubjectRequirementResponseDTO();
                                subDTO.setRequirementId(sub.getRequirementId() != null ? sub.getRequirementId().shortValue() : null);
                                subDTO.setSubjectNames(sub.getSubjectNames() == null ? List.of() : Arrays.asList(sub.getSubjectNames()));
                                subDTO.setCalculationType(sub.getCalculationType());
                                subDTO.setMinScore(sub.getMinScore());
                                subDTO.setScoreSource(sub.getScoreSource());
                                return subDTO;
                            }).collect(Collectors.toList())
                    );
                }
                return rsDTO;
            }).collect(Collectors.toList()));
        }

        if (entity.getMeritRuleSets() != null) {
            dto.setMeritRuleSets(entity.getMeritRuleSets().stream().map(m -> {
                MeritRuleSetResponseDTO md = new MeritRuleSetResponseDTO();
                md.setId(m.getId());
                md.setSourceType(m.getSourceType());
                md.setOptionIndex(m.getOptionIndex());
                md.setRuleIndex(m.getRuleIndex());
                md.setLabel(m.getLabel());

                if (m.getMeritSubjects() != null) {
                    md.setMeritSubjects(Arrays.asList(m.getMeritSubjects()));
                } else {
                    md.setMeritSubjects(new ArrayList<>());
                }

                return md;
            }).collect(Collectors.toList()));
        }
        return dto;
    }
}
