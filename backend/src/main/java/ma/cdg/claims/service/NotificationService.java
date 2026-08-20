package ma.cdg.claims.service;

import java.util.List;
import ma.cdg.claims.config.ApplicationProperties;
import ma.cdg.claims.domain.AppUser;
import ma.cdg.claims.domain.Claim;
import ma.cdg.claims.domain.Notification;
import ma.cdg.claims.domain.NotificationLevel;
import ma.cdg.claims.domain.UserRole;
import ma.cdg.claims.domain.WorkflowStep;
import ma.cdg.claims.repository.AppUserRepository;
import ma.cdg.claims.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** In-app notifications for CDG staff, and e-mail notifications for the customer. */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final AppUserRepository users;
    private final MailSenderAdapter mail;
    private final ApplicationProperties properties;

    public NotificationService(NotificationRepository notifications,
                               AppUserRepository users,
                               MailSenderAdapter mail,
                               ApplicationProperties properties) {
        this.notifications = notifications;
        this.users = users;
        this.mail = mail;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<Notification> recentFor(String username, int limit) {
        return notifications.findByRecipientOrderByCreatedAtDesc(
                username, PageRequest.of(0, Math.clamp(limit, 1, 200)));
    }

    @Transactional(readOnly = true)
    public long unreadCount(String username) {
        return notifications.countByRecipientAndReadFalse(username);
    }

    @Transactional
    public void markRead(Long id, String username) {
        notifications.findById(id)
                .filter(n -> n.getRecipient().equals(username))
                .ifPresent(n -> {
                    n.setRead(true);
                    notifications.save(n);
                });
    }

    @Transactional
    public int markAllRead(String username) {
        return notifications.markAllRead(username);
    }

    // ------------------------------------------------------------------ writing

    /** Tells everyone in the receiving unit that a complaint is waiting for them. */
    @Transactional
    public void notifyStepAvailable(Claim claim, WorkflowStep step) {
        String title = "New complaint to handle - " + step.getLabel();
        String message = "%s (%s) is waiting in the %s queue."
                .formatted(claim.getReference(), claim.getSubject(), step.getLabel());
        NotificationLevel level = switch (claim.getPriority()) {
            case URGENT -> NotificationLevel.DANGER;
            case HIGH -> NotificationLevel.WARNING;
            default -> NotificationLevel.INFO;
        };
        broadcastToRole(step.getRole(), claim, title, message, level);
    }

    /** Warns the unit holding a complaint that its deadline has passed. */
    @Transactional
    public void notifySlaBreached(Claim claim) {
        if (claim.getCurrentStep() == null) {
            return;
        }
        String title = "SLA breached - " + claim.getReference();
        String message = "The %s step of %s has passed its deadline."
                .formatted(claim.getCurrentStep().getLabel(), claim.getReference());

        broadcastToRole(claim.getCurrentStep().getRole(), claim, title, message, NotificationLevel.DANGER);
        broadcastToRole(UserRole.SUPERVISOR, claim, title, message, NotificationLevel.DANGER);
    }

    /** Confirms to the registering agent that their complaint was closed. */
    @Transactional
    public void notifyClaimClosed(Claim claim) {
        if (claim.getCreatedBy() == null) {
            return;
        }
        boolean resolved = claim.getResolution() != null && claim.getRejectionReason() == null;
        create(claim.getCreatedBy(), claim,
                (resolved ? "Complaint resolved - " : "Complaint rejected - ") + claim.getReference(),
                resolved
                        ? "%s has been validated and closed.".formatted(claim.getReference())
                        : "%s was rejected: %s".formatted(claim.getReference(),
                                nullSafe(claim.getRejectionReason())),
                resolved ? NotificationLevel.SUCCESS : NotificationLevel.WARNING);
    }

    @Transactional
    public void create(String recipient, Claim claim, String title, String message,
                       NotificationLevel level) {
        Notification notification = new Notification();
        notification.setRecipient(recipient);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setLevel(level);
        if (claim != null) {
            notification.setClaimId(claim.getId());
            notification.setClaimReference(claim.getReference());
        }
        notifications.save(notification);
    }

    private void broadcastToRole(UserRole role, Claim claim, String title, String message,
                                 NotificationLevel level) {
        List<AppUser> recipients = users.findByRoleAndActiveTrue(role);
        if (recipients.isEmpty()) {
            log.debug("No active user with role {} to notify about {}", role, claim.getReference());
            return;
        }
        recipients.forEach(user -> create(user.getUsername(), claim, title, message, level));
    }

    // --------------------------------------------------------------- customer mail

    /** Sends the closing letter to the customer. Returns true when a mail was actually sent. */
    public boolean notifyCustomerResolved(Claim claim) {
        String body = """
                Dear %s,

                Your complaint %s has been reviewed and resolved.

                Subject: %s

                Our answer:
                %s

                Thank you for your trust.
                %s
                """.formatted(nullSafe(claim.getCustomerName()), claim.getReference(),
                nullSafe(claim.getSubject()), nullSafe(claim.getResolution()),
                properties.getMail().getSignature());

        return mail.send(claim.getCustomerEmail(),
                "Your complaint %s has been resolved".formatted(claim.getReference()), body);
    }

    /** Informs the customer that the complaint was not admissible. */
    public boolean notifyCustomerRejected(Claim claim) {
        String body = """
                Dear %s,

                After review, your complaint %s could not be admitted.

                Subject: %s

                Reason:
                %s

                You may contact us again with additional information.
                %s
                """.formatted(nullSafe(claim.getCustomerName()), claim.getReference(),
                nullSafe(claim.getSubject()), nullSafe(claim.getRejectionReason()),
                properties.getMail().getSignature());

        return mail.send(claim.getCustomerEmail(),
                "About your complaint %s".formatted(claim.getReference()), body);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
