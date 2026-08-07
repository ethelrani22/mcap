package nic.meg.mcap.controllers;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import nic.meg.mcap.dto.request.ScheduleRequestDTO;
import nic.meg.mcap.dto.response.NextScheduleStepDTO;
import nic.meg.mcap.dto.response.ScheduleResponseDTO;
import nic.meg.mcap.entities.AdmissionWindow;
import nic.meg.mcap.repositories.AdmissionWindowRepository;
import nic.meg.mcap.services.ScheduleService;

@RestController
@PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN','CONTROLLER')")
@RequestMapping("/schedule-data")
@RequiredArgsConstructor
public class ScheduleDataController {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleDataController.class);
    private final ScheduleService scheduleService;
    private final AdmissionWindowRepository admissionWindowRepository;

    @GetMapping("/active-windows")
    public ResponseEntity<List<Map<String, Object>>> getActiveAdmissionWindows() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();

        // Fetch Active, Upcoming, AND Closed windows — schedules (especially
        // COUNSELLING-category steps) are routinely created for windows that
        // have already closed, since counselling must start after the window
        // ends. Excluding closed windows here would make it impossible to
        // prepare a counselling schedule for them.
        List<AdmissionWindow> activeWindows = admissionWindowRepository.findActiveWindows(now);
        List<AdmissionWindow> upcomingWindows = admissionWindowRepository.findUpcomingWindows(now);
        List<AdmissionWindow> closedWindows = admissionWindowRepository.findClosedWindowsWaitingForCounselling(now);

        // Combine them using a Set to automatically prevent any duplicates
        java.util.Set<AdmissionWindow> combinedWindows = new java.util.HashSet<>();
        if (activeWindows != null)
            combinedWindows.addAll(activeWindows);
        if (upcomingWindows != null)
            combinedWindows.addAll(upcomingWindows);
        if (closedWindows != null)
            combinedWindows.addAll(closedWindows);

        // The existing mapWindows() helper will automatically sort them for the UI!
        return ResponseEntity.ok(mapWindows(new java.util.ArrayList<>(combinedWindows)));
    }

    // --- NEW: Endpoint for selecting windows ready for Counseling ---
    @GetMapping("/closed-windows")
    public ResponseEntity<List<Map<String, Object>>> getClosedWindows() {
        List<AdmissionWindow> windows = admissionWindowRepository
                .findClosedWindowsWaitingForCounselling(java.time.LocalDateTime.now());
        return ResponseEntity.ok(mapWindows(windows));
    }

    // Helper method to ensure consistency and include extension data
    private List<Map<String, Object>> mapWindows(List<AdmissionWindow> windows) {

        return windows.stream().sorted((w1, w2) -> {
            if (w1.getStartDate() == null)
                return 1;
            if (w2.getStartDate() == null)
                return -1;
            return w2.getStartDate().compareTo(w1.getStartDate());
        }).map(w -> {
            Map<String, Object> data = new HashMap<>();

            data.put("admissionCode", w.getAdmissionCode());

            String streamName = w.getStream() != null ? w.getStream().getStreamName() : "";
            String programme = w.getProgrammeLevel() != null ? w.getProgrammeLevel().name() : "";
            String session = w.getSession() != null ? w.getSession() : "";

            data.put("windowName", streamName + " - " + programme + " - " + session);

            data.put("startDate", w.getStartDate());
            data.put("endDate", w.getEndDate());
            data.put("originalEndDate", w.getOriginalEndDate() != null ? w.getOriginalEndDate() : w.getEndDate());
            data.put("isExtended", w.isExtended());
            data.put("windowStatus", computeWindowStatus(w));

            return data;
        }).collect(Collectors.toList());
    }

    private String computeWindowStatus(AdmissionWindow w) {
        LocalDateTime now = LocalDateTime.now();
        if (w.getStartDate() != null && now.isBefore(w.getStartDate())) {
            return "UPCOMING";
        }
        if (w.getEndDate() != null && now.isAfter(w.getEndDate())) {
            return "CLOSED";
        }
        return "ACTIVE";
    }

    @GetMapping("/schedules/{admissionCode}")
    public ResponseEntity<List<ScheduleResponseDTO>> getSchedulesByWindow(@PathVariable String admissionCode,
                                                                          @RequestParam(required = false) String category) {

        // Lookup the window using the code to safely get the internal ID for the
        // service
        AdmissionWindow window = admissionWindowRepository.findByAdmissionCode(admissionCode)
                .orElseThrow(() -> new IllegalArgumentException("Admission window not found"));
        Short admissionId = window.getAdmissionId();

        List<ScheduleResponseDTO> schedules;
        if (category != null && !category.trim().isEmpty()) {
            schedules = scheduleService.getSchedulesByWindowAndCategory(admissionId, category);
        } else {
            schedules = scheduleService.getSchedulesByAdmissionWindow(admissionId);
        }
        return ResponseEntity.ok(schedules);
    }

    @GetMapping("/next-step/{admissionCode}")
    public ResponseEntity<NextScheduleStepDTO> getNextAvailableStep(@PathVariable String admissionCode) {

        // Lookup the window using the code
        AdmissionWindow window = admissionWindowRepository.findByAdmissionCode(admissionCode)
                .orElseThrow(() -> new IllegalArgumentException("Admission window not found"));

        NextScheduleStepDTO nextStep = scheduleService.getNextAvailableStep(window.getAdmissionId());
        return ResponseEntity.ok(nextStep);
    }

    @PostMapping("/schedules")
    public ResponseEntity<?> createScheduleStep(@RequestBody ScheduleRequestDTO dto) {
        try {
            ScheduleResponseDTO created = scheduleService.createScheduleStep(dto);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));

        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/schedules/detail/{scheduleId}")
    public ResponseEntity<?> getScheduleById(@PathVariable Long scheduleId) {
        ScheduleResponseDTO schedule = scheduleService.getScheduleById(scheduleId);

        return ResponseEntity.ok(schedule);
    }

    @PutMapping("/schedules/{scheduleId}")
    public ResponseEntity<?> updateScheduleStep(@PathVariable Long scheduleId, @RequestBody ScheduleRequestDTO dto) {
        ScheduleResponseDTO updated = scheduleService.updateScheduleStep(scheduleId, dto);

        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/schedules/{scheduleId}")
    public ResponseEntity<?> deleteScheduleStep(@PathVariable Long scheduleId) {
        scheduleService.deleteScheduleStep(scheduleId);

        return ResponseEntity.ok(Map.of("message", "Schedule deleted successfully"));
    }

    @GetMapping("/upcoming-windows")
    public ResponseEntity<List<Map<String, Object>>> getUpcomingAdmissionWindows() {

        LocalDateTime now = LocalDateTime.now();

        // Prepare Schedule needs to offer Upcoming windows (initial setup),
        // Active windows (application window still open, e.g. pre-admission
        // steps or amendments), and Closed windows (counselling steps must be
        // scheduled after the window closes) — not just Upcoming.
        List<AdmissionWindow> upcomingWindows = admissionWindowRepository.findUpcomingWindows(now);
        List<AdmissionWindow> activeWindows = admissionWindowRepository.findActiveWindows(now);
        List<AdmissionWindow> closedWindows = admissionWindowRepository.findClosedWindowsWaitingForCounselling(now);

        java.util.Set<AdmissionWindow> combinedWindows = new java.util.HashSet<>();
        if (upcomingWindows != null)
            combinedWindows.addAll(upcomingWindows);
        if (activeWindows != null)
            combinedWindows.addAll(activeWindows);
        if (closedWindows != null)
            combinedWindows.addAll(closedWindows);

        List<Map<String, Object>> mapped = mapWindows(new java.util.ArrayList<>(combinedWindows));
        return ResponseEntity.ok(mapped);
    }
}