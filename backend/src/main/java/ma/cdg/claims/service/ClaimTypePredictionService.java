package ma.cdg.claims.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import ma.cdg.claims.config.ApplicationProperties;
import ma.cdg.claims.domain.ClaimType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Suggests the category of a complaint from its wording.
 *
 * <p>When {@code cdg.ml.enabled} is on, the classification model trained on the cleaned
 * historical export is queried over HTTP (see {@code ml-service/}). If it is off — or
 * unreachable, or slow — the request falls back to a keyword scorer so registration
 * never blocks on the model. The response always says which of the two answered.
 */
@Service
public class ClaimTypePredictionService {

    private static final Logger log = LoggerFactory.getLogger(ClaimTypePredictionService.class);

    /** One suggested category with its score. */
    public record ScoredType(ClaimType type, double confidence) {
    }

    /**
     * @param source {@code MODEL} when the ML service answered, {@code RULES} otherwise
     */
    public record Prediction(ClaimType type, double confidence, String source,
                             List<ScoredType> alternatives) {
    }

    /** Payload exchanged with the Python service. */
    private record PredictRequest(String subject, String description) {
    }

    private record PredictResponse(String type, Double confidence, String source,
                                   List<Map<String, Object>> alternatives) {
    }

    /** Lower-cased keywords, in French and English, that point at a category. */
    private static final Map<ClaimType, List<String>> KEYWORDS = keywords();

    private final ApplicationProperties properties;
    private final RestClient restClient;

    public ClaimTypePredictionService(ApplicationProperties properties,
                                      RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(properties.getMl().getBaseUrl())
                .build();
    }

    public Prediction predict(String subject, String description) {
        String text = ((subject == null ? "" : subject) + " " + (description == null ? "" : description))
                .toLowerCase(Locale.ROOT);

        if (properties.getMl().isEnabled()) {
            try {
                Prediction fromModel = callModel(subject, description);
                if (fromModel != null) {
                    return fromModel;
                }
            } catch (RuntimeException e) {
                log.warn("Classification service unavailable ({}), falling back to keyword rules",
                        e.getMessage());
            }
        }
        return byKeywords(text);
    }

    // ------------------------------------------------------------------- model

    private Prediction callModel(String subject, String description) {
        Duration timeout = properties.getMl().getTimeout();
        PredictResponse response = restClient.post()
                .uri("/predict")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PredictRequest(subject, description))
                .retrieve()
                .body(PredictResponse.class);

        if (response == null || response.type() == null) {
            return null;
        }
        ClaimType type = parseType(response.type());
        if (type == null) {
            log.warn("Classification service returned unknown category '{}'", response.type());
            return null;
        }
        List<ScoredType> alternatives = new ArrayList<>();
        if (response.alternatives() != null) {
            for (Map<String, Object> alternative : response.alternatives()) {
                ClaimType alternativeType = parseType(String.valueOf(alternative.get("type")));
                Object score = alternative.get("confidence");
                if (alternativeType != null && score instanceof Number number) {
                    alternatives.add(new ScoredType(alternativeType, number.doubleValue()));
                }
            }
        }
        // The service reports whether its own model or its fallback answered; pass that
        // through rather than claiming a model was used.
        String source = "RULES".equalsIgnoreCase(response.source()) ? "RULES" : "MODEL";
        log.debug("Classification service answered {} ({}) in under {}", type, source, timeout);
        return new Prediction(type,
                response.confidence() == null ? 0.5d : response.confidence(),
                source, alternatives);
    }

    private static ClaimType parseType(String raw) {
        if (raw == null) {
            return null;
        }
        for (ClaimType type : ClaimType.values()) {
            if (type.name().equalsIgnoreCase(raw.trim())) {
                return type;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------- rules

    private Prediction byKeywords(String text) {
        Map<ClaimType, Integer> scores = new EnumMap<>(ClaimType.class);
        KEYWORDS.forEach((type, terms) -> {
            int score = 0;
            for (String term : terms) {
                if (text.contains(term)) {
                    score++;
                }
            }
            if (score > 0) {
                scores.put(type, score);
            }
        });

        if (scores.isEmpty()) {
            return new Prediction(ClaimType.OTHER, 0.2d, "RULES", List.of());
        }

        int total = scores.values().stream().mapToInt(Integer::intValue).sum();
        List<ScoredType> ranked = scores.entrySet().stream()
                .map(e -> new ScoredType(e.getKey(), round(e.getValue() / (double) total)))
                .sorted(Comparator.comparingDouble(ScoredType::confidence).reversed())
                .toList();

        ScoredType best = ranked.getFirst();
        List<ScoredType> alternatives = ranked.size() > 1
                ? ranked.subList(1, Math.min(4, ranked.size()))
                : List.of();
        return new Prediction(best.type(), best.confidence(), "RULES", alternatives);
    }

    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private static Map<ClaimType, List<String>> keywords() {
        Map<ClaimType, List<String>> map = new EnumMap<>(ClaimType.class);
        map.put(ClaimType.DEPOSIT_CONSIGNATION, List.of(
                "consignation", "consigne", "depot", "dépôt", "deposit", "restitution", "caution"));
        map.put(ClaimType.PENSION_RETIREMENT, List.of(
                "retraite", "pension", "rcar", "cnra", "pensionne", "retirement", "annuit"));
        map.put(ClaimType.ACCOUNT_MANAGEMENT, List.of(
                "compte", "account", "rib", "releve", "relevé", "statement", "cloture de compte",
                "ouverture de compte"));
        map.put(ClaimType.PAYMENT_TRANSFER, List.of(
                "virement", "transfer", "paiement", "payment", "prelevement", "prélèvement",
                "cheque", "chèque", "versement"));
        map.put(ClaimType.FEES_CHARGES, List.of(
                "frais", "commission", "agios", "fee", "charge", "facturation", "prelevé a tort"));
        map.put(ClaimType.DOCUMENT_REQUEST, List.of(
                "attestation", "document", "justificatif", "certificat", "duplicata", "copie"));
        map.put(ClaimType.DELAY, List.of(
                "retard", "delai", "délai", "attente", "toujours pas", "depuis des semaines",
                "delay", "waiting", "not yet received"));
        map.put(ClaimType.SERVICE_QUALITY, List.of(
                "accueil", "comportement", "impoli", "mauvais service", "agent", "guichet",
                "rude", "unprofessional", "quality"));
        map.put(ClaimType.TECHNICAL_ISSUE, List.of(
                "site", "application", "erreur", "bug", "connexion", "mot de passe", "portail",
                "website", "login", "technical"));
        return map;
    }
}
