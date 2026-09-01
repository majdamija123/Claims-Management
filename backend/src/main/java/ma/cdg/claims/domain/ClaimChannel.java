package ma.cdg.claims.domain;

/** How the complaint reached CDG. */
public enum ClaimChannel {

    EMAIL("E-mail"),
    PHONE("Telephone"),
    BRANCH("Branch visit"),
    WEB_PORTAL("Web portal"),
    POSTAL_MAIL("Postal mail"),
    SOCIAL_MEDIA("Social media");

    private final String label;

    ClaimChannel(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
