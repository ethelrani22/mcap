package nic.meg.mcap.controllers.pageControllers;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.persistence.EntityNotFoundException;
import nic.meg.mcap.entities.ProgrammeOffered;
import nic.meg.mcap.enums.Shift;
import nic.meg.mcap.repositories.ProgrammesOfferedRepository;
import nic.meg.mcap.services.SeatAllotmentService;

@Controller
@RequestMapping("/institute")
public class InstituteStudentPageController {

    private static final Logger logger = LoggerFactory.getLogger(InstituteStudentPageController.class);

    @Autowired
    private SeatAllotmentService seatAllotmentService;

    @Autowired
    private ProgrammesOfferedRepository programmesOfferedRepository;

    /**
     * LEVEL 1: Programme summary table (with Shift tabs, if the institute offers
     * more than one shift) — total seats vs. seats occupied (ACCEPTED) per programme.
     * Row data itself is loaded client-side via
     * GET /api/institute/allotments/programme-summary?shift=X
     */
    @GetMapping("/view-applications")
    public String viewStudentApplications(Model model, Authentication authentication) {

        Short instituteId = getLoggedInInstituteId(authentication);

        if (instituteId == null) {
            model.addAttribute("errorMessage", "Unable to identify institute. Please login again.");
            return "institute/view-applications";
        }

        try {
            List<ProgrammeOffered> offered = programmesOfferedRepository
                    .findByInstituteDepartment_Institute_InstituteId(instituteId);

            // Preserve enum declaration order (MORNING, DAY, EVENING, NIGHT) rather than
            // whatever order rows happen to come back from the DB in.
            Set<Shift> distinctShifts = new LinkedHashSet<>();
            for (Shift s : Shift.values()) {
                if (offered.stream().anyMatch(po -> po.getShift() == s)) {
                    distinctShifts.add(s);
                }
            }

            model.addAttribute("shifts", distinctShifts);

        } catch (EntityNotFoundException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
        }

        return "institute/view-applications";
    }

    /**
     * LEVEL 2: Read-only list of applicants (ACCEPTED / seat-occupying) for a
     * single programme_offered row. Row data loaded client-side via
     * GET /api/institute/allotments/allotments-by-programme?programmeOfferedId=X&status=ACCEPTED
     */
    @GetMapping("/view-applications/{programmeOfferedId}")
    public String viewProgrammeApplicants(@PathVariable Integer programmeOfferedId, Model model,
                                          Authentication authentication) {

        Short instituteId = getLoggedInInstituteId(authentication);

        if (instituteId == null) {
            model.addAttribute("errorMessage", "Unable to identify institute. Please login again.");
            return "institute/programme-applicants";
        }

        ProgrammeOffered po = programmesOfferedRepository.findById(programmeOfferedId).orElse(null);

        if (po == null || !po.getInstituteDepartment().getInstitute().getInstituteId().equals(instituteId)) {
            model.addAttribute("errorMessage", "Programme not found for your institute.");
            return "institute/programme-applicants";
        }

        model.addAttribute("programmeOfferedId", programmeOfferedId);
        model.addAttribute("programmeName", po.getProgramme().getProgrammeName());
        model.addAttribute("shiftName", po.getShift() != null ? po.getShift().getDisplayName() : "Day");

        return "institute/programme-applicants";
    }

    private Short getLoggedInInstituteId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object principal = authentication.getPrincipal();

        if (principal instanceof nic.meg.mcap.entities.User) {
            nic.meg.mcap.entities.User user = (nic.meg.mcap.entities.User) principal;

            // Get the enum value
            nic.meg.mcap.enums.OrgOwnerType orgOwnerType = user.getOrgOwnerType();

            // Compare with INSTITUTE enum value
            if (orgOwnerType == nic.meg.mcap.enums.OrgOwnerType.INSTITUTE) {
                Short orgOwnerId = user.getOrgOwnerId();
                if (orgOwnerId != null) {
                    return orgOwnerId;
                } else {
                    logger.info("OrgOwnerId is null for user: {}", user.getUsername());
                }
            } else {
                logger.info("User {} is not an INSTITUTE user. OrgOwnerType: {}", user.getUsername(), orgOwnerType);
            }
        } else {
            logger.info("Principal is not a User entity. Class: {}",
                    principal != null ? principal.getClass().getName() : "null");
        }

        return null;
    }
}