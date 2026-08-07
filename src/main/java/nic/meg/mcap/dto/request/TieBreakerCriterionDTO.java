package nic.meg.mcap.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TieBreakerCriterionDTO {
    private String field;
    private int priority;
    // Only used when field == "subjectMarks" -- id of the Subject to compare marks for.
    private Integer subjectId;
}