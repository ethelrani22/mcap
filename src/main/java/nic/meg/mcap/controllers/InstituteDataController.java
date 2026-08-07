package nic.meg.mcap.controllers;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import nic.meg.mcap.dto.request.AcademicDetailsDTO;
import nic.meg.mcap.dto.request.VerificationRequestDTO;
import nic.meg.mcap.dto.response.ApplicationStatusResponseDTO;
import nic.meg.mcap.dto.response.InstituteAllotmentDTO;
import nic.meg.mcap.dto.response.PagedResponse;
import nic.meg.mcap.dto.response.ProgrammeAllocationSummaryDTO;
import nic.meg.mcap.entities.Application;
import nic.meg.mcap.entities.Document;
import nic.meg.mcap.entities.SeatAllotment;
import nic.meg.mcap.enums.AllotmentStatus;
import nic.meg.mcap.repositories.ApplicationRepository;
import nic.meg.mcap.repositories.SeatAllotmentRepository;
import nic.meg.mcap.services.AcademicService;
import nic.meg.mcap.services.ApplicantService;
import nic.meg.mcap.services.CounselingService;
import nic.meg.mcap.services.DocumentService;
import nic.meg.mcap.services.InstituteService;
import nic.meg.mcap.services.PdfGenerationService;
import nic.meg.mcap.services.SeatAllotmentService;
import org.springframework.http.MediaType;

import java.io.IOException;

@Controller
@RequestMapping("/api/institute/allotments")
@RequiredArgsConstructor
public class InstituteDataController {

    private static final Logger logger = LoggerFactory.getLogger(InstituteDataController.class);

    private final CounselingService counselingService;
    private final InstituteService instituteService;
    private final DocumentService documentService;
    private final SeatAllotmentRepository seatAllotmentRepository;
    private final ApplicationRepository applicationRepository;
    private final SeatAllotmentService seatAllotmentService;
    private final PdfGenerationService pdfGenerationService;
    private final ApplicantService applicantService;
    private final AcademicService academicService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final nic.meg.mcap.repositories.CuetPaperRepository cuetPaperRepository;

    // =========================================================================
    // LEVEL 1: PROGRAMME SUMMARY STATS
    // =========================================================================
    @GetMapping("/programme-summary")
    @ResponseBody
    public ResponseEntity<List<ProgrammeAllocationSummaryDTO>> getProgrammeSummary(@RequestParam("shift") String shift,
                                                                                   Authentication auth) {

        Short instituteId = instituteService.getInstituteIdByUsername(auth.getName());
        List<ProgrammeAllocationSummaryDTO> summary = seatAllotmentService.getInstituteProgrammeSummary(instituteId,
                shift);

        return ResponseEntity.ok(summary);
    }

    // =========================================================================
    // LEVEL 2: SPECIFIC APPLICANT LIST
    // =========================================================================
    @GetMapping("/allotments-by-programme")
    @ResponseBody
    public ResponseEntity<List<InstituteAllotmentDTO>> getApplicantsByProgramme(
            @RequestParam("programmeOfferedId") Integer poId, @RequestParam("status") AllotmentStatus status) {

        List<InstituteAllotmentDTO> applicants = counselingService.getApplicantsForProgrammeAndStatus(poId, status);
        return ResponseEntity.ok(applicants);
    }

    // =========================================================================
    // VERIFICATION SUBMISSION
    // =========================================================================
    @PostMapping("/{allotmentId}/verify")
    @ResponseBody
    public ResponseEntity<Map<String, String>> performVerification(@PathVariable Long allotmentId,
                                                                   @Valid @RequestBody VerificationRequestDTO request, Authentication authentication) {
        String username = authentication.getName();
        Short instituteId = instituteService.getInstituteIdByUsername(username);

        // Ensure the allotmentId in the path matches the DTO
        request.setAllotmentId(allotmentId);

        counselingService.performVerification(allotmentId, request, instituteId, username);
        return ResponseEntity.ok(Map.of("message", "Verification decision has been recorded successfully."));
    }

    // =========================================================================
    // VERIFICATION + EDIT HISTORY (cross-institute, read-only)
    // =========================================================================
    @GetMapping("/history/{applicantId}")
    @ResponseBody
    public ResponseEntity<List<nic.meg.mcap.dto.response.VerificationHistoryDTO>> getVerificationHistory(
            @PathVariable java.util.UUID applicantId) {
        return ResponseEntity.ok(counselingService.getVerificationHistory(applicantId));
    }

    // =========================================================================
    // DOCUMENT REVIEW FRAGMENT (EXISTING)
    // =========================================================================
    @GetMapping("/{allotmentId}/document-review")
    public String getDocumentReviewFragment(Model model, @PathVariable Long allotmentId, Authentication auth) {
        String username = auth.getName();
        Short instituteId = instituteService.getInstituteIdByUsername(username);
        try {
            SeatAllotment allotment = seatAllotmentRepository.findById(allotmentId)
                    .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                            "Allotment not found with ID: " + allotmentId));

            Short actualInstituteId = allotment.getProgrammeOffered().getInstituteDepartment().getInstitute()
                    .getInstituteId();

            if (!actualInstituteId.equals(instituteId)) {
                throw new SecurityException("Unauthorized access to allotment documents.");
            }

            Application application = allotment.getApplication();
            String applicantNo = application.getApplicant().getApplicantNo();
            Long applicationId = application.getApplicationId();

            ApplicationStatusResponseDTO status = new ApplicationStatusResponseDTO();
            status.setFormLocked(true);
            model.addAttribute("status", status);

            Map<String, String> requiredDocTypes = documentService.getRequiredDocumentTypes(applicantNo, applicationId);

            List<Document> uploadedDocuments = documentService.getUploadedDocuments(applicantNo);

            Map<String, Document> uploadedDocsMap = uploadedDocuments.stream()
                    .collect(Collectors.toMap(Document::getDocumentType, doc -> doc, (doc1, doc2) -> doc1));

            model.addAttribute("requiredDocTypes", requiredDocTypes);
            model.addAttribute("uploadedDocsMap", uploadedDocsMap);
            model.addAttribute("isDocumentsFinalized", true);

            return "applicant/fragments/document-review";

        } catch (jakarta.persistence.EntityNotFoundException e) {
            model.addAttribute("errorMessage", "Requested record not found.");
            return "fragments/error-message";

        } catch (SecurityException e) {
            model.addAttribute("errorMessage", "You are not authorized to view these documents.");
            return "fragments/error-message";

        } catch (org.springframework.dao.DataAccessException e) {
            model.addAttribute("errorMessage", "Unable to load documents. Please try again.");
            return "fragments/error-message";
        }
    }

    // =========================================================================
    // APPLICATION PDF (view/download the applicant's actual application form)
    // =========================================================================
    @GetMapping("/{allotmentId}/application-pdf")
    @ResponseBody
    public ResponseEntity<?> getApplicationPdf(@PathVariable Long allotmentId,
                                               @RequestParam(defaultValue = "inline") String mode,
                                               Authentication auth) throws IOException {
        String username = auth.getName();
        Short instituteId = instituteService.getInstituteIdByUsername(username);

        SeatAllotment allotment = seatAllotmentRepository.findById(allotmentId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "Allotment not found with ID: " + allotmentId));

        Short actualInstituteId = allotment.getProgrammeOffered().getInstituteDepartment().getInstitute()
                .getInstituteId();

        if (!actualInstituteId.equals(instituteId)) {
            throw new SecurityException("Unauthorized access to this application.");
        }

        Application application = allotment.getApplication();
        String applicantNo = application.getApplicant().getApplicantNo();
        Long applicationId = application.getApplicationId();

        byte[] pdfBytes = pdfGenerationService.generateApplicationPdf(applicationId, applicantNo);

        String disposition = "download".equalsIgnoreCase(mode) ? "attachment" : "inline";
        String filename = "application-" + applicationId + ".pdf";

        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", disposition + "; filename=\"" + filename + "\"")
                .body(pdfBytes);
    }

    // =========================================================================
    // PAGED ALLOTMENTS (UPDATED WITH PROGRAMME FILTER)
    // =========================================================================
    @GetMapping("/paged")
    @ResponseBody
    public ResponseEntity<PagedResponse<InstituteAllotmentDTO>> getPagedAllotments(
            @RequestParam("statuses") List<AllotmentStatus> statuses,
            @RequestParam(value = "programmeId", required = false) Short programmeId,
            @RequestParam(value = "shift", required = false) nic.meg.mcap.enums.Shift shift,
            @RequestParam(value = "admissionRoute", required = false) String admissionRoute,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Authentication auth) {

        Short instituteId = instituteService.getInstituteIdByUsername(auth.getName());

        PagedResponse<InstituteAllotmentDTO> response = counselingService.getPagedAllotmentsByStatus(instituteId,
                statuses, programmeId, shift, admissionRoute, page, size);

        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // DASHBOARD STAT CARD COUNTS (Pending / Verified / Rejected) — honors the
    // same programme/shift/admission-route filters as the paged listing so the
    // cards never show a stale "0" until a tab is manually opened.
    // =========================================================================
    @GetMapping("/counts")
    @ResponseBody
    public ResponseEntity<Map<String, Long>> getAllotmentCounts(
            @RequestParam(value = "programmeId", required = false) Short programmeId,
            @RequestParam(value = "shift", required = false) nic.meg.mcap.enums.Shift shift,
            @RequestParam(value = "admissionRoute", required = false) String admissionRoute,
            Authentication auth) {

        Short instituteId = instituteService.getInstituteIdByUsername(auth.getName());

        Map<String, Long> counts = Map.of(
                "PENDING", counselingService.countAllotmentsByStatus(instituteId,
                        List.of(AllotmentStatus.PENDING_VERIFICATION), programmeId, shift, admissionRoute),
                "VERIFIED", counselingService.countAllotmentsByStatus(instituteId,
                        List.of(AllotmentStatus.PENDING, AllotmentStatus.ACCEPTED), programmeId, shift, admissionRoute),
                "REJECTED", counselingService.countAllotmentsByStatus(instituteId,
                        List.of(AllotmentStatus.INSTITUTE_REJECTED, AllotmentStatus.REJECTED), programmeId, shift, admissionRoute)
        );

        return ResponseEntity.ok(counts);
    }

    private Application resolveApplicationForInstitute(Long allotmentId, Authentication auth) {
        Short instituteId = instituteService.getInstituteIdByUsername(auth.getName());

        SeatAllotment allotment = seatAllotmentRepository.findById(allotmentId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "Allotment not found with ID: " + allotmentId));

        Short actualInstituteId = allotment.getProgrammeOffered().getInstituteDepartment().getInstitute()
                .getInstituteId();

        if (!actualInstituteId.equals(instituteId)) {
            throw new SecurityException("Unauthorized access to allotment " + allotmentId);
        }

        return allotment.getApplication();
    }

    @GetMapping("/{allotmentId}/personal-details")
    @ResponseBody
    public ResponseEntity<?> getPersonalDetailsForEdit(@PathVariable Long allotmentId, Authentication auth) {
        try {
            Application application = resolveApplicationForInstitute(allotmentId, auth);
            String applicantNo = application.getApplicant().getApplicantNo();
            return ResponseEntity.ok(applicantService.getPersonalDetailsForForm(applicantNo));
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("message", "Requested record not found."));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("message", "You are not authorized to view this applicant."));
        }
    }

    @PostMapping("/{allotmentId}/personal-details")
    @ResponseBody
    public ResponseEntity<?> updatePersonalDetailsByInstitute(@PathVariable Long allotmentId,
                                                              @RequestBody nic.meg.mcap.dto.request.PersonalDetailsRequestDTO dto, Authentication auth) {
        try {
            Application application = resolveApplicationForInstitute(allotmentId, auth);
            String applicantNo = application.getApplicant().getApplicantNo();
            Short instituteId = instituteService.getInstituteIdByUsername(auth.getName());

            nic.meg.mcap.dto.request.PersonalDetailsRequestDTO before = applicantService.getPersonalDetailsForForm(applicantNo);
            applicantService.updatePersonalDetails(applicantNo, dto);

            String changedFields = nic.meg.mcap.utils.JsonDiffUtil.diff(objectMapper, before, dto);
            counselingService.logApplicantEdit(allotmentId, instituteId, auth.getName(), changedFields);

            logger.info("Institute user {} edited personal details for applicant {} via allotment {}",
                    auth.getName(), applicantNo, allotmentId);
            return ResponseEntity.ok(Map.of("message", "Personal details updated successfully."));
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("message", "Requested record not found."));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("message", "You are not authorized to edit this applicant."));
        } catch (Exception e) {
            logger.error("Failed to update personal details for allotment {}", allotmentId, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Unable to save changes. Please try again."));
        }
    }

    @GetMapping("/{allotmentId}/academic-details")
    @ResponseBody
    public ResponseEntity<?> getAcademicDetailsForEdit(@PathVariable Long allotmentId, Authentication auth) {
        try {
            Application application = resolveApplicationForInstitute(allotmentId, auth);
            String applicantNo = application.getApplicant().getApplicantNo();
            return ResponseEntity.ok(academicService.getAcademicDetails(applicantNo));
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("message", "Requested record not found."));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("message", "You are not authorized to view this applicant."));
        }
    }

    @PostMapping("/{allotmentId}/academic-details")
    @ResponseBody
    public ResponseEntity<?> updateAcademicDetailsByInstitute(@PathVariable Long allotmentId,
                                                              @RequestBody AcademicDetailsDTO dto, Authentication auth) {
        try {
            Application application = resolveApplicationForInstitute(allotmentId, auth);
            String applicantNo = application.getApplicant().getApplicantNo();
            Short instituteId = instituteService.getInstituteIdByUsername(auth.getName());

            Object before = academicService.getAcademicDetails(applicantNo);
            academicService.saveOrUpdateAcademicDetails(applicantNo, dto);

            String changedFields = nic.meg.mcap.utils.JsonDiffUtil.diff(objectMapper, before, dto);
            counselingService.logApplicantEdit(allotmentId, instituteId, auth.getName(), changedFields);

            logger.info("Institute user {} edited academic details for applicant {} via allotment {}",
                    auth.getName(), applicantNo, allotmentId);
            return ResponseEntity.ok(Map.of("message", "Academic details updated successfully."));
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("message", "Requested record not found."));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("message", "You are not authorized to edit this applicant."));
        } catch (Exception e) {
            logger.error("Failed to update academic details for allotment {}", allotmentId, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Unable to save changes. Please try again."));
        }
    }

    // =========================================================================
    // ENTRANCE DETAILS — separate tab in the edit modal (JEE/CUET/GATE/NET)
    // The JS sends/receives these as a subset of AcademicDetailsDTO (jeeScore,
    // cuetScore, gateScore, netScore fields).  We reuse the same DTO and service.
    // =========================================================================
    // =========================================================================
    // CUET MASTER PAPER LIST — feeds the paper picker on the entrance tab so
    // institute users select from valid papers (with auto-filled name/code)
    // instead of free-typing them, same as the applicant-side CUET selector.
    // Exposed here (under /api/institute/**) rather than the admin-only
    // /api/data/eligibility endpoint, since SecurityConfig is never modified.
    // =========================================================================
    @GetMapping("/cuet-papers")
    @ResponseBody
    public ResponseEntity<List<Map<String, String>>> getCuetPapersForInstitute() {
        List<Map<String, String>> papers = java.util.Arrays.stream(nic.meg.mcap.enums.ProgrammeLevel.values())
                .flatMap(level -> cuetPaperRepository
                        .findByProgrammeLevelAndIsActiveOrderBySpecAscSortOrderAscPaperNameAsc(level, true)
                        .stream())
                .map(p -> Map.of("paperCode", p.getPaperCode(), "paperName", p.getPaperName()))
                .distinct()
                .collect(Collectors.toList());
        return ResponseEntity.ok(papers);
    }

    @GetMapping("/{allotmentId}/entrance-details")
    @ResponseBody
    public ResponseEntity<?> getEntranceDetailsForEdit(@PathVariable Long allotmentId, Authentication auth) {
        try {
            Application application = resolveApplicationForInstitute(allotmentId, auth);
            String applicantNo = application.getApplicant().getApplicantNo();
            return ResponseEntity.ok(academicService.getAcademicDetails(applicantNo));
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("message", "Requested record not found."));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("message", "You are not authorized to view this applicant."));
        }
    }

    @PostMapping("/{allotmentId}/entrance-details")
    @ResponseBody
    public ResponseEntity<?> updateEntranceDetailsByInstitute(@PathVariable Long allotmentId,
                                                              @RequestBody AcademicDetailsDTO dto, Authentication auth) {
        try {
            Application application = resolveApplicationForInstitute(allotmentId, auth);
            String applicantNo = application.getApplicant().getApplicantNo();
            Short instituteId = instituteService.getInstituteIdByUsername(auth.getName());

            Object before = academicService.getAcademicDetails(applicantNo);
            academicService.saveOrUpdateAcademicDetails(applicantNo, dto);

            String changedFields = nic.meg.mcap.utils.JsonDiffUtil.diff(objectMapper, before, dto);
            counselingService.logApplicantEdit(allotmentId, instituteId, auth.getName(), changedFields);

            logger.info("Institute user {} edited entrance details for applicant {} via allotment {}",
                    auth.getName(), applicantNo, allotmentId);
            return ResponseEntity.ok(Map.of("message", "Entrance details updated successfully."));
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("message", "Requested record not found."));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("message", "You are not authorized to edit this applicant."));
        } catch (Exception e) {
            logger.error("Failed to update entrance details for allotment {}", allotmentId, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Unable to save changes. Please try again."));
        }
    }

    @PostMapping("/{allotmentId}/documents/upload")
    @ResponseBody
    public ResponseEntity<?> uploadDocumentByInstitute(@PathVariable Long allotmentId,
                                                       @RequestParam("documentFile") org.springframework.web.multipart.MultipartFile file,
                                                       @RequestParam("documentType") String documentType, Authentication auth) {
        try {
            Application application = resolveApplicationForInstitute(allotmentId, auth);
            String applicantNo = application.getApplicant().getApplicantNo();
            Document savedDoc = documentService.saveDocument(file, applicantNo, documentType);
            logger.info("Institute user {} replaced document '{}' for applicant {} via allotment {}",
                    auth.getName(), documentType, applicantNo, allotmentId);
            return ResponseEntity.ok(Map.of("message", "Document uploaded successfully.",
                    "documentId", savedDoc.getId(), "fileName", savedDoc.getFileName()));
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("message", "Requested record not found."));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("message", "You are not authorized to edit this applicant."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to upload document for allotment {}", allotmentId, e);
            return ResponseEntity.internalServerError().body(Map.of("message",
                    "The server could not process your file upload. Please ensure the file is not corrupt and try again."));
        }
    }
}