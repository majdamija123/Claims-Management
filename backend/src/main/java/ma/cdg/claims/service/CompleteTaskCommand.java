package ma.cdg.claims.service;

import ma.cdg.claims.domain.ClaimPriority;
import ma.cdg.claims.domain.ClaimType;
import ma.cdg.claims.domain.TaskDecision;

/**
 * What an agent submits when finishing a user task.
 *
 * @param resolution      the answer proposed to the customer (FO / MO / BO steps)
 * @param rejectionReason why the complaint is not admissible (qualification only)
 * @param type            corrected category, accepted at the qualification step
 * @param priority        corrected priority, accepted at the qualification step
 */
public record CompleteTaskCommand(TaskDecision decision,
                                  String comment,
                                  String resolution,
                                  String rejectionReason,
                                  ClaimType type,
                                  ClaimPriority priority) {
}
