package nic.meg.mcap.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstituteAllotmentDTO {
    private Long allotmentId;
    private UUID applicantId;
    private String applicantName;
    private String applicationNo;
    private String programmeName;
    private String allottedCategory;
    private String roundAndPhase;
    private String remarks;
}