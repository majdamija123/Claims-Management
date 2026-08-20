package ma.cdg.claims.bootstrap;

import java.util.Optional;
import ma.cdg.claims.config.ApplicationProperties;
import ma.cdg.claims.domain.AppUser;
import ma.cdg.claims.domain.Claim;
import ma.cdg.claims.domain.ClaimChannel;
import ma.cdg.claims.domain.ClaimPriority;
import ma.cdg.claims.domain.ClaimType;
import ma.cdg.claims.domain.TaskDecision;
import ma.cdg.claims.domain.UserRole;
import ma.cdg.claims.repository.AppUserRepository;
import ma.cdg.claims.repository.ClaimRepository;
import ma.cdg.claims.service.ClaimService;
import ma.cdg.claims.service.CompleteTaskCommand;
import ma.cdg.claims.service.CreateClaimCommand;
import ma.cdg.claims.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the accounts, and a small set of complaints spread across the process, so the
 * application is demonstrable the moment it starts. Both are skipped as soon as the
 * corresponding table already holds data, and can be turned off entirely with
 * {@code cdg.demo.seed-users} / {@code cdg.demo.seed-claims}.
 */
@Component
@Order(2)
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final AppUserRepository users;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final TaskService taskService;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationProperties properties;

    public DataInitializer(AppUserRepository users,
                           ClaimRepository claims,
                           ClaimService claimService,
                           TaskService taskService,
                           PasswordEncoder passwordEncoder,
                           ApplicationProperties properties) {
        this.users = users;
        this.claims = claims;
        this.claimService = claimService;
        this.taskService = taskService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.getDemo().isSeedUsers()) {
            seedUsers();
        }
        if (properties.getDemo().isSeedClaims()) {
            seedClaims();
        }
    }

    // ------------------------------------------------------------------- users

    private void seedUsers() {
        if (users.count() > 0) {
            return;
        }
        String password = properties.getDemo().getPassword();

        create("admin", "Administrateur Systeme", "admin@cdg.ma", UserRole.ADMIN, "IT", password);
        create("supervisor", "Nadia El Amrani", "supervisor@cdg.ma", UserRole.SUPERVISOR,
                "Direction Qualite", password);
        create("qualif1", "Salma Bennani", "qualif1@cdg.ma", UserRole.QUALIFICATION,
                "Service Reclamations", password);
        create("qualif2", "Hamza Idrissi", "qualif2@cdg.ma", UserRole.QUALIFICATION,
                "Service Reclamations", password);
        create("fo1", "Youssef Alaoui", "fo1@cdg.ma", UserRole.FO, "Front Office", password);
        create("fo2", "Imane Tazi", "fo2@cdg.ma", UserRole.FO, "Front Office", password);
        create("mo1", "Karim Ouazzani", "mo1@cdg.ma", UserRole.MO, "Middle Office", password);
        create("bo1", "Fatima Zahra Cherkaoui", "bo1@cdg.ma", UserRole.BO, "Back Office", password);
        create("valid1", "Mehdi Berrada", "valid1@cdg.ma", UserRole.VALIDATION,
                "Direction Qualite", password);

        log.info("""

                ------------------------------------------------------------------
                 Demo accounts created. Password for all of them: {}
                   admin / supervisor / qualif1 / qualif2 / fo1 / fo2 / mo1 / bo1 / valid1
                 Change cdg.demo.password, or set cdg.demo.seed-users=false, \
                before any real deployment.
                ------------------------------------------------------------------""", password);
    }

    private void create(String username, String fullName, String email, UserRole role,
                        String department, String password) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setFullName(fullName);
        user.setEmail(email);
        user.setRole(role);
        user.setDepartment(department);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setActive(true);
        users.save(user);
    }

    // ----------------------------------------------------------------- claims

    /** Sample complaints, each pushed a different distance into the process. */
    private void seedClaims() {
        if (claims.count() > 0) {
            return;
        }
        AppUser qualif = require("qualif1");
        AppUser fo = require("fo1");
        AppUser mo = require("mo1");
        AppUser bo = require("bo1");
        AppUser validator = require("valid1");

        try {
            // 1. Waiting in the qualification queue.
            register("Amina Ouali", "amina.ouali@example.ma", ClaimChannel.EMAIL,
                    "Attestation de consignation non recue",
                    "J'ai demande une attestation de consignation il y a trois semaines et je n'ai "
                            + "toujours rien recu malgre deux relances telephoniques.",
                    ClaimType.DOCUMENT_REQUEST, ClaimPriority.NORMAL, qualif);

            register("Rachid Benjelloun", "rachid.b@example.ma", ClaimChannel.PHONE,
                    "Frais preleves a tort sur mon compte",
                    "Des frais de tenue de compte ont ete preleves deux fois ce trimestre. "
                            + "Je demande le remboursement du double prelevement.",
                    ClaimType.FEES_CHARGES, ClaimPriority.HIGH, qualif);

            // 2. Qualified and now with the Front Office.
            Claim withFo = register("Latifa Mansouri", "latifa.m@example.ma", ClaimChannel.BRANCH,
                    "Erreur sur le montant de ma pension",
                    "Le montant verse ce mois-ci est inferieur de 800 MAD au montant habituel, "
                            + "sans aucune notification prealable.",
                    ClaimType.PENSION_RETIREMENT, ClaimPriority.URGENT, qualif);
            advance(withFo, qualif, TaskDecision.VALIDATE, "Reclamation recevable, dossier complet",
                    null, null);

            // 3. Front Office could not answer: escalated to the Middle Office.
            Claim withMo = register("Omar Sqalli", "omar.sqalli@example.ma", ClaimChannel.WEB_PORTAL,
                    "Virement non credite depuis 10 jours",
                    "Un virement emis le 3 du mois n'apparait toujours pas sur mon releve.",
                    ClaimType.PAYMENT_TRANSFER, ClaimPriority.HIGH, qualif);
            advance(withMo, qualif, TaskDecision.VALIDATE, "Dossier recevable", null, null);
            advance(withMo, fo, TaskDecision.ESCALATE,
                    "Verification impossible au niveau FO, transaction interbancaire", null, null);

            // 4. Escalated once more, now with the Back Office.
            Claim withBo = register("Khadija Naciri", "khadija.n@example.ma", ClaimChannel.POSTAL_MAIL,
                    "Restitution de consignation bloquee",
                    "La restitution de ma consignation est bloquee depuis deux mois sans explication.",
                    ClaimType.DEPOSIT_CONSIGNATION, ClaimPriority.NORMAL, qualif);
            advance(withBo, qualif, TaskDecision.VALIDATE, "Dossier recevable", null, null);
            advance(withBo, fo, TaskDecision.ESCALATE, "Necessite une analyse du dossier juridique",
                    null, null);
            advance(withBo, mo, TaskDecision.ESCALATE, "Dossier a instruire par le Back Office",
                    null, null);

            // 5. Answer proposed by the Back Office, waiting for validation.
            Claim inValidation = register("Said Bourkia", "said.bourkia@example.ma",
                    ClaimChannel.EMAIL, "Compte cloture sans mon accord",
                    "Mon compte a ete cloture alors que je n'ai signe aucune demande de cloture.",
                    ClaimType.ACCOUNT_MANAGEMENT, ClaimPriority.URGENT, qualif);
            advance(inValidation, qualif, TaskDecision.VALIDATE, "Cas serieux, traitement prioritaire",
                    null, null);
            advance(inValidation, fo, TaskDecision.ESCALATE, "Hors perimetre FO", null, null);
            advance(inValidation, mo, TaskDecision.ESCALATE, "Verification des mandats requise",
                    null, null);
            advance(inValidation, bo, TaskDecision.ANSWER, "Cloture annulee, compte reactive",
                    "Le compte a ete reactive le jour meme. La cloture provenait d'une erreur de "
                            + "saisie interne, corrigee et tracee. Nos excuses vous sont presentees.",
                    null);

            // 6. Resolved and closed.
            Claim resolved = register("Nawal Fassi", "nawal.fassi@example.ma", ClaimChannel.PHONE,
                    "Delai de traitement trop long",
                    "Ma demande de duplicata est en attente depuis plus d'un mois.",
                    ClaimType.DELAY, ClaimPriority.NORMAL, qualif);
            advance(resolved, qualif, TaskDecision.VALIDATE, "Recevable", null, null);
            advance(resolved, fo, TaskDecision.ANSWER, "Duplicata edite et envoye",
                    "Le duplicata a ete edite et vous a ete envoye par courrier recommande. "
                            + "Le delai constate provenait d'une rupture de stock de formulaires.",
                    null);
            advance(resolved, validator, TaskDecision.APPROVE, "Reponse conforme", null, null);

            // 7. Rejected at qualification.
            Claim rejected = register("Anonyme", null, ClaimChannel.SOCIAL_MEDIA,
                    "Message sans objet identifiable",
                    "Message recu via les reseaux sociaux, sans identification du client ni objet "
                            + "de reclamation exploitable.",
                    ClaimType.OTHER, ClaimPriority.LOW, qualif);
            advance(rejected, qualif, TaskDecision.REJECT, "Reclamation non exploitable", null,
                    "Le message ne permet pas d'identifier le client ni l'objet de la reclamation. "
                            + "Une nouvelle demande avec les references du dossier est necessaire.");

            // 8. Sent back by validation, so it appears again in the qualification queue.
            Claim returned = register("Hicham Radi", "hicham.radi@example.ma", ClaimChannel.EMAIL,
                    "Contestation d'agios",
                    "Des agios ont ete appliques alors que mon compte etait provisionne.",
                    ClaimType.FEES_CHARGES, ClaimPriority.NORMAL, qualif);
            advance(returned, qualif, TaskDecision.VALIDATE, "Recevable", null, null);
            advance(returned, fo, TaskDecision.ANSWER, "Proposition de geste commercial",
                    "Un remboursement partiel des agios vous sera credite.", null);
            advance(returned, validator, TaskDecision.RETURN,
                    "Justificatifs insuffisants, reprendre la qualification du dossier", null, null);

            log.info("Seeded {} sample complaints across the process", claims.count());
        } catch (RuntimeException e) {
            log.warn("Sample complaints could not be seeded completely: {}", e.getMessage());
        }
    }

    private Claim register(String customer, String email, ClaimChannel channel, String subject,
                           String description, ClaimType type, ClaimPriority priority, AppUser actor) {
        return claimService.create(new CreateClaimCommand(customer, email, "+212 6 00 00 00 00",
                null, channel, "CDG - Siege Rabat", subject, description, type, priority), actor);
    }

    /** Completes whatever task is currently open on a complaint, as the given user. */
    private void advance(Claim claim, AppUser actor, TaskDecision decision, String comment,
                         String resolution, String rejectionReason) {
        Optional<TaskService.TaskItem> open = taskService
                .forProcessInstance(actor, claim.getProcessInstanceKey())
                .stream()
                .findFirst();

        if (open.isEmpty()) {
            log.warn("No open task on {} to apply {}", claim.getReference(), decision);
            return;
        }
        taskService.complete(actor, open.get().task().taskKey(),
                new CompleteTaskCommand(decision, comment, resolution, rejectionReason, null, null));
    }

    private AppUser require(String username) {
        return users.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalStateException("Demo user " + username + " is missing"));
    }
}
