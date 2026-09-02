package ma.cdg.claims.service;

/**
 * Builds the letters sent to the customer, in the CDG colours.
 *
 * <p>Every letter is produced twice from the same wording: an HTML part for the usual
 * mail client, and a plain-text part for anything that will not render it. The answer the
 * agent wrote is the point of the message, so it is set apart in its own block rather than
 * buried in the paragraph flow.
 *
 * <p>Styling is inline and the layout is a table: mail clients strip stylesheets and have
 * uneven support for modern CSS, so this is deliberately old-fashioned HTML.
 */
final class CustomerLetter {

    private static final String SLATE = "#34474f";
    private static final String GREEN = "#7ba928";
    private static final String INK = "#4a5b63";
    private static final String LINE = "#e5ecef";

    private CustomerLetter() {
    }

    /** The letter closing a resolved complaint, carrying the answer the unit wrote. */
    static String resolvedHtml(String customerName, String reference, String subject,
                               String answer, String signature) {
        return page(reference,
                "Your complaint has been resolved",
                """
                <p style="margin:0 0 16px">Dear %s,</p>
                <p style="margin:0 0 16px">
                  Your complaint <strong style="color:%s">%s</strong> has been reviewed and resolved.
                </p>
                %s
                %s
                <p style="margin:24px 0 0">Thank you for your trust.</p>
                """.formatted(escape(customerName), SLATE, escape(reference),
                        subjectBlock(subject), answerBlock("Our answer", answer)),
                signature);
    }

    /** The letter telling the customer the complaint was not admissible. */
    static String rejectedHtml(String customerName, String reference, String subject,
                               String reason, String signature) {
        return page(reference,
                "About your complaint",
                """
                <p style="margin:0 0 16px">Dear %s,</p>
                <p style="margin:0 0 16px">
                  After review, your complaint <strong style="color:%s">%s</strong> could not be admitted.
                </p>
                %s
                %s
                <p style="margin:24px 0 0">You may contact us again with additional information.</p>
                """.formatted(escape(customerName), SLATE, escape(reference),
                        subjectBlock(subject), answerBlock("Reason", reason)),
                signature);
    }

    // ------------------------------------------------------------------ building blocks

    private static String page(String reference, String heading, String content, String signature) {
        return """
                <!doctype html>
                <html><body style="margin:0;padding:0;background:#f3f7f8">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"
                         style="background:#f3f7f8;padding:24px 12px">
                    <tr><td align="center">
                      <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"
                             style="max-width:580px;background:#ffffff;border:1px solid %s;border-radius:12px;overflow:hidden">

                        <tr><td style="background:%s;padding:20px 28px">
                          <span style="font:600 21px/1 Helvetica,Arial,sans-serif;color:#ffffff;letter-spacing:.02em">CDG</span>
                          <span style="display:inline-block;width:26px;height:3px;background:%s;margin-left:8px;vertical-align:middle"></span>
                          <div style="font:400 12px/1.5 Helvetica,Arial,sans-serif;color:#c9d5da;margin-top:6px">
                            Caisse de Depot et de Gestion &middot; Service Reclamations Clients
                          </div>
                        </td></tr>

                        <tr><td style="padding:28px">
                          <h1 style="margin:0 0 20px;font:600 18px/1.3 Helvetica,Arial,sans-serif;color:%s">%s</h1>
                          <div style="font:400 14px/1.65 Helvetica,Arial,sans-serif;color:%s">
                            %s
                          </div>
                        </td></tr>

                        <tr><td style="border-top:1px solid %s;padding:18px 28px;
                                       font:400 12px/1.6 Helvetica,Arial,sans-serif;color:#8397a0">
                          %s<br>
                          <span style="color:#a8b8bf">Reference %s &middot; please keep it for any follow-up.</span>
                        </td></tr>

                      </table>
                    </td></tr>
                  </table>
                </body></html>
                """.formatted(LINE, SLATE, GREEN, SLATE, escape(heading), INK, content,
                LINE, escape(signature).replace("\n", "<br>"), escape(reference));
    }

    private static String subjectBlock(String subject) {
        if (isBlank(subject)) {
            return "";
        }
        return """
                <p style="margin:0 0 16px;color:#8397a0;font-size:13px">
                  Subject: <span style="color:%s">%s</span>
                </p>
                """.formatted(INK, escape(subject));
    }

    /** The agent's own words, set apart behind a green rule so they read as the answer. */
    private static String answerBlock(String title, String body) {
        if (isBlank(body)) {
            return "";
        }
        return """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"
                       style="margin:4px 0 8px">
                  <tr>
                    <td style="border-left:3px solid %s;background:#f7faf1;padding:14px 16px;border-radius:0 6px 6px 0">
                      <div style="font:600 11px/1 Helvetica,Arial,sans-serif;color:#5b8420;
                                  text-transform:uppercase;letter-spacing:.06em;margin-bottom:8px">%s</div>
                      <div style="font:400 14px/1.65 Helvetica,Arial,sans-serif;color:%s;white-space:pre-line">%s</div>
                    </td>
                  </tr>
                </table>
                """.formatted(GREEN, escape(title), INK, escape(body));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** The wording is customer- and agent-supplied, so it is never trusted as markup. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
