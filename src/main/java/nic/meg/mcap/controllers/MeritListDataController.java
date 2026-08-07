package nic.meg.mcap.controllers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nic.meg.mcap.dto.response.ApplicantCountDTO;
import nic.meg.mcap.dto.response.MeritListMetadataDTO;
import nic.meg.mcap.dto.response.MeritListResponseDTO;
import nic.meg.mcap.dto.response.MeritListRowDTO;
import nic.meg.mcap.dto.response.PagedResponse;
import nic.meg.mcap.dto.response.PreferenceApplicantDTO;
import nic.meg.mcap.enums.ApplicantType;
import nic.meg.mcap.services.MeritListService;
import nic.meg.mcap.services.ProgrammePreferenceService;
import nic.meg.mcap.services.impl.merit.MeritListExportService;
import nic.meg.mcap.services.impl.merit.RoundPhaseNormalizer;

@RestController
@RequestMapping("/merit-list/data")
@RequiredArgsConstructor
@Slf4j
public class MeritListDataController {

    private final MeritListService meritListService;
    private final ProgrammePreferenceService programmePreferenceService;
    private final RoundPhaseNormalizer roundPhaseNormalizer;
    private final MeritListExportService meritListExportService;

    // ---------- GENERATE ----------

    @PostMapping("/generate/ug")
    public ResponseEntity<?> generateUGMeritList(
            @RequestParam("admissionWindowCode") String admissionWindowCode, @RequestParam Short programmeId,
            @RequestParam(required = false) String roundType, @RequestParam(required = false) Integer phaseNo) {
        try {
            String rt = roundPhaseNormalizer.normalizeRoundType(roundType);
            Integer ph = roundPhaseNormalizer.normalizePhaseNo(phaseNo);

            log.info("Generating UG merit list: windowCode={}, programme={}, roundType={}, phaseNo={}",
                    admissionWindowCode, programmeId, rt, ph);

            MeritListMetadataDTO metadata = meritListService.generateUGMeritList(admissionWindowCode, programmeId, rt,
                    ph);

            if (metadata == null) {
                return ResponseEntity.ok(Map.of("status", "NO_ELIGIBLE", "admissionWindowCode", admissionWindowCode,
                        "programmeId", programmeId, "roundType", rt, "phaseNo", ph));
            }

            return ResponseEntity.ok(metadata);

        } catch (IllegalStateException | IllegalArgumentException e) {
            log.error("Error generating UG merit list: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/generate/pg")
    public ResponseEntity<?> generatePGMeritList(
            @RequestParam("admissionWindowCode") String admissionWindowCode, @RequestParam Short programmeId,
            @RequestParam(required = false) String roundType, @RequestParam(required = false) Integer phaseNo) {
        try {
            String rt = roundPhaseNormalizer.normalizeRoundType(roundType);
            Integer ph = roundPhaseNormalizer.normalizePhaseNo(phaseNo);

            MeritListMetadataDTO metadata = meritListService.generatePGMeritList(admissionWindowCode, programmeId, rt,
                    ph);

            if (metadata == null) {
                return ResponseEntity.ok(Map.of("status", "NO_ELIGIBLE", "admissionWindowCode", admissionWindowCode,
                        "programmeId", programmeId, "roundType", rt, "phaseNo", ph));
            }

            return ResponseEntity.ok(metadata);

        } catch (IllegalStateException | IllegalArgumentException e) {
            log.error("Error generating PG merit list: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ---------- FETCH ONE LIST (PAGINATED) ----------

    @GetMapping("/{meritListId}")
    public ResponseEntity<PagedResponse<MeritListRowDTO>> getMeritList(@PathVariable Long meritListId,
                                                                       @RequestParam(name = "page", defaultValue = "0") int page,
                                                                       @RequestParam(name = "size", defaultValue = "20") int size) {
        PagedResponse<MeritListRowDTO> response = meritListService.getPagedMeritListById(meritListId, page, size);
        return ResponseEntity.ok(response);
    }

    // ---------- FETCH LIST FOR ROUND+PHASE ----------

    @GetMapping("/for-round-phase/{level}")
    public ResponseEntity<Map<String, Object>> getMeritListForRoundPhase(@PathVariable String level,
                                                                         @RequestParam("admissionWindowCode") String admissionWindowCode, @RequestParam Short programmeId,
                                                                         @RequestParam(required = false) Short streamId, @RequestParam(required = false) String roundType,
                                                                         @RequestParam(required = false) Integer phaseNo) {
        String rt = roundPhaseNormalizer.normalizeRoundType(roundType);
        Integer ph = roundPhaseNormalizer.normalizePhaseNo(phaseNo);

        boolean isUG = "ug".equalsIgnoreCase(level);

        ApplicantCountDTO counts = isUG
                ? meritListService.countApplicantsForUG(admissionWindowCode, programmeId, rt, ph)
                : meritListService.countApplicantsForPG(admissionWindowCode, programmeId, rt, ph);

        int eligible = (counts != null) ? counts.getEligible() : 0;

        Map<String, Object> base = new HashMap<>();
        base.put("admissionWindowCode", admissionWindowCode);
        base.put("programmeId", programmeId);
        base.put("roundType", rt);
        base.put("phaseNo", ph);
        base.put("eligible", eligible);

        try {
            MeritListResponseDTO match = meritListService.getLatestMeritListByProgramme(admissionWindowCode,
                    programmeId, rt, ph);

            if (match != null && match.getMetadata() != null) {
                MeritListMetadataDTO md = match.getMetadata();
                base.put("status", "FOUND");
                base.put("meritListId", md.getMeritListId());
                base.put("ruleSetId", md.getRuleSetId());
                base.put("ruleSetLabel", md.getRuleSetLabel());
                base.put("sourceType", md.getSourceType());
                base.put("applicantType", md.getApplicantType());
                return ResponseEntity.ok(base);
            }
        } catch (jakarta.persistence.EntityNotFoundException e) {
            log.debug("No merit list exists yet for programme: {}", programmeId);
        }

        if (eligible <= 0) {
            base.put("status", "NO_ELIGIBLE");
            return ResponseEntity.ok(base);
        }

        base.put("status", "NOT_GENERATED");
        return ResponseEntity.ok(base);
    }

    // ---------- CHECK / SUMMARY ----------

    @GetMapping("/check/ug")
    public ResponseEntity<Map<String, Object>> checkUGMeritList(
            @RequestParam("admissionWindowCode") String admissionWindowCode, @RequestParam Short programmeId,
            @RequestParam(required = false) String roundType, @RequestParam(required = false) Integer phaseNo) {
        String rt = roundPhaseNormalizer.normalizeRoundType(roundType);
        Integer ph = roundPhaseNormalizer.normalizePhaseNo(phaseNo);

        boolean hasMeritList = meritListService.hasMeritListForUG(admissionWindowCode, programmeId, rt, ph);
        ApplicantCountDTO counts = meritListService.countApplicantsForUG(admissionWindowCode, programmeId, rt, ph);

        Map<String, Object> response = new HashMap<>();
        response.put("hasMeritList", hasMeritList);
        response.put("totalComplete", counts.getTotalComplete());
        response.put("eligible", counts.getEligible());
        response.put("eligibilityRate", counts.getEligibilityRate());
        response.put("canGenerate", counts.getEligible() > 0);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/check/pg")
    public ResponseEntity<Map<String, Object>> checkPGMeritList(
            @RequestParam("admissionWindowCode") String admissionWindowCode, @RequestParam Short programmeId,
            @RequestParam(required = false) String roundType, @RequestParam(required = false) Integer phaseNo) {
        String rt = roundPhaseNormalizer.normalizeRoundType(roundType);
        Integer ph = roundPhaseNormalizer.normalizePhaseNo(phaseNo);

        boolean hasMeritList = meritListService.hasMeritListForPG(admissionWindowCode, programmeId, rt, ph);
        ApplicantCountDTO counts = meritListService.countApplicantsForPG(admissionWindowCode, programmeId, rt, ph);

        Map<String, Object> response = new HashMap<>();
        response.put("hasMeritList", hasMeritList);
        response.put("totalComplete", counts.getTotalComplete());
        response.put("eligible", counts.getEligible());
        response.put("eligibilityRate", counts.getEligibilityRate());
        response.put("canGenerate", counts.getEligible() > 0);

        return ResponseEntity.ok(response);
    }

    // ---------- APPLIED RULES ----------

    /**
     * Returns the merit rules that were applied when generating the merit list,
     * along with the count of applicants that qualified under each rule.
     * Used by the "View Applied Rules" modal in the merit list view page.
     */
    @GetMapping("/applied-rules")
    public ResponseEntity<List<Map<String, Object>>> getAppliedRules(
            @RequestParam("admissionWindowCode") String admissionWindowCode,
            @RequestParam Integer programmeId,
            @RequestParam ApplicantType applicantType) {
        try {
            MeritListResponseDTO list = meritListService.getLatestMeritListByProgramme(
                    admissionWindowCode, programmeId.shortValue(), null, null);

            if (list == null || list.getEntries() == null || list.getEntries().isEmpty()) {
                return ResponseEntity.ok(List.of());
            }

            // Filter entries by applicant type match (list is already filtered, but guard)
            List<MeritListRowDTO> entries = list.getEntries().stream()
                    .filter(e -> e.getQualifiedRuleNumber() != null)
                    .toList();

            // Group by rule number and count
            Map<Integer, Long> countByRule = new java.util.LinkedHashMap<>();
            Map<Integer, String> descByRule = new java.util.LinkedHashMap<>();

            for (MeritListRowDTO entry : entries) {
                int ruleNo = entry.getQualifiedRuleNumber();
                countByRule.merge(ruleNo, 1L, Long::sum);
                if (!descByRule.containsKey(ruleNo) && entry.getRuleDescription() != null) {
                    descByRule.put(ruleNo, entry.getRuleDescription());
                }
            }

            List<Map<String, Object>> result = new ArrayList<>();
            countByRule.forEach((ruleNo, count) -> {
                Map<String, Object> ruleMap = new HashMap<>();
                ruleMap.put("ruleNumber", ruleNo);
                ruleMap.put("ruleName", "Rule " + ruleNo);
                ruleMap.put("ruleDescription", descByRule.getOrDefault(ruleNo, ""));
                ruleMap.put("applicantCount", count);
                result.add(ruleMap);
            });

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Failed to fetch applied rules for programme={}, type={}: {}",
                    programmeId, applicantType, e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    // ---------- PUBLISH ----------

    @PutMapping("/{meritListId}/publish")
    public ResponseEntity<?> publishMeritList(@PathVariable Long meritListId) {
        try {
            meritListService.publishMeritList(meritListId);
            return ResponseEntity.ok(Map.of("message", "Merit list published successfully"));

        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ---------- EXPORT ----------

    @GetMapping("/{meritListId}/export/pdf")
    public void exportToPdf(@PathVariable Long meritListId, HttpServletResponse response) throws IOException {
        MeritListResponseDTO data = meritListService.getMeritListById(meritListId);

        response.setContentType("application/pdf");
        String fileName = "MeritList_" + meritListId + ".pdf";
        response.setHeader("Content-Disposition", "attachment; filename=" + fileName);

        meritListExportService.exportToPdf(data, response.getOutputStream());
    }

    // ---------- PREFERENCES ----------

    @GetMapping("/preferences/programme")
    public List<PreferenceApplicantDTO> getProgrammePreferences(
            @RequestParam("admissionWindowCode") String admissionWindowCode, @RequestParam Integer programmeId,
            @RequestParam ApplicantType applicantType) {
        return programmePreferenceService.getApplicantsForProgramme(admissionWindowCode, programmeId, applicantType);
    }

    private Object safeGet(Object target, String getterName) {
        if (target == null || getterName == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(getterName).invoke(target);
        } catch (NoSuchMethodException | IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
            return null;
        }
    }
}
