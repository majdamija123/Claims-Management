package ma.cdg.claims.service;

import ma.cdg.claims.config.ApplicationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends customer e-mails when a mail server is configured.
 *
 * <p>Delivery is off by default ({@code cdg.mail.enabled=false}) so that running the
 * application against production-like data never mails a real customer by accident; the
 * message is logged instead, which is enough to demonstrate the flow.
 */
@Component
public class MailSenderAdapter {

    private static final Logger log = LoggerFactory.getLogger(MailSenderAdapter.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final ApplicationProperties properties;

    public MailSenderAdapter(ObjectProvider<JavaMailSender> mailSender,
                             ApplicationProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /** @return true when the message was handed to a mail server */
    public boolean send(String to, String subject, String body) {
        if (to == null || to.isBlank()) {
            log.debug("No e-mail address available, skipping '{}'", subject);
            return false;
        }
        if (!properties.getMail().isEnabled()) {
            log.info("[mail disabled] to={} subject='{}'\n{}", to, subject, body);
            return false;
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn("cdg.mail.enabled=true but no mail server is configured; '{}' was not sent", subject);
            return false;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(properties.getMail().getFrom());
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            sender.send(message);
            log.info("Sent '{}' to {}", subject, to);
            return true;
        } catch (RuntimeException e) {
            log.error("Could not send '{}' to {}: {}", subject, to, e.getMessage());
            return false;
        }
    }
}
