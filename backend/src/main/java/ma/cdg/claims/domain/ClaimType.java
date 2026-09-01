package ma.cdg.claims.domain;

/**
 * Complaint categories. These mirror the labels produced by the classification model
 * built during the data-cleaning phase; keep them in sync with the ML service.
 */
public enum ClaimType {

    DEPOSIT_CONSIGNATION("Deposits and consignments"),
    PENSION_RETIREMENT("Pensions and retirement"),
    ACCOUNT_MANAGEMENT("Account management"),
    PAYMENT_TRANSFER("Payments and transfers"),
    FEES_CHARGES("Fees and charges"),
    DOCUMENT_REQUEST("Document request"),
    DELAY("Processing delay"),
    SERVICE_QUALITY("Quality of service"),
    TECHNICAL_ISSUE("Technical issue"),
    OTHER("Other");

    private final String label;

    ClaimType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
