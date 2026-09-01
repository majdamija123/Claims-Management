package ma.cdg.claims.service;

import ma.cdg.claims.domain.ClaimChannel;
import ma.cdg.claims.domain.ClaimPriority;
import ma.cdg.claims.domain.ClaimType;

/** Everything needed to register a new complaint. */
public record CreateClaimCommand(String customerName,
                                 String customerEmail,
                                 String customerPhone,
                                 String customerReference,
                                 ClaimChannel channel,
                                 String entity,
                                 String subject,
                                 String description,
                                 ClaimType type,
                                 ClaimPriority priority) {
}
