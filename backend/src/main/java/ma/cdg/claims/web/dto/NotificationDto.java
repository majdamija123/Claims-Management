package ma.cdg.claims.web.dto;

import java.time.Instant;

public record NotificationDto(Long id,
                              String title,
                              String message,
                              String level,
                              Long claimId,
                              String claimReference,
                              boolean read,
                              Instant createdAt) {
}
