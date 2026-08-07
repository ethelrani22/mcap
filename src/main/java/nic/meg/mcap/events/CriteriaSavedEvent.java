package nic.meg.mcap.events;

/**
 * Published by AdmissionCriteriaServiceImpl after criteria are saved/updated.
 * CriteriaSavedEventListener picks this up asynchronously and re-evaluates
 * eligibility for all paid (SUBMITTED) applicants of the affected programme.
 */
public record CriteriaSavedEvent(Short admissionWindowId, Short programmeId) {}
