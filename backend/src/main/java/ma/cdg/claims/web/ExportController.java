package ma.cdg.claims.web;

import java.time.Instant;
import java.util.List;
import ma.cdg.claims.domain.Claim;
import ma.cdg.claims.domain.ClaimPriority;
import ma.cdg.claims.domain.ClaimStatus;
import ma.cdg.claims.domain.ClaimType;
import ma.cdg.claims.service.ClaimSearchCriteria;
import ma.cdg.claims.service.ClaimService;
import ma.cdg.claims.service.ExportService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Downloadable deliverables: the complaint register and the per-complaint dossier. */
@RestController
@RequestMapping("/api/exports")
public class ExportController {

    /** Exports are capped so a mistyped filter cannot pull the whole database into memory. */
    private static final int MAX_ROWS = 10_000;

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ClaimService claims;
    private final ExportService exports;

    public ExportController(ClaimService claims, ExportService exports) {
        this.claims = claims;
        this.exports = exports;
    }

    @GetMapping("/claims.xlsx")
    public ResponseEntity<byte[]> excel(@RequestParam(required = false) String search,
                                        @RequestParam(required = false) List<ClaimStatus> status,
                                        @RequestParam(required = false) List<ClaimType> type,
                                        @RequestParam(required = false) List<ClaimPriority> priority,
                                        @RequestParam(required = false) Boolean openOnly,
                                        @RequestParam(required = false) Instant createdFrom,
                                        @RequestParam(required = false) Instant createdTo) {

        List<Claim> rows = find(search, status, type, priority, openOnly, createdFrom, createdTo);
        return download(exports.toExcel(rows), XLSX,
                "complaints-%s.xlsx".formatted(exports.todayStamp()));
    }

    @GetMapping("/claims.csv")
    public ResponseEntity<byte[]> csv(@RequestParam(required = false) String search,
                                      @RequestParam(required = false) List<ClaimStatus> status,
                                      @RequestParam(required = false) List<ClaimType> type,
                                      @RequestParam(required = false) List<ClaimPriority> priority,
                                      @RequestParam(required = false) Boolean openOnly,
                                      @RequestParam(required = false) Instant createdFrom,
                                      @RequestParam(required = false) Instant createdTo) {

        List<Claim> rows = find(search, status, type, priority, openOnly, createdFrom, createdTo);
        return download(exports.toCsv(rows), MediaType.parseMediaType("text/csv; charset=UTF-8"),
                "complaints-%s.csv".formatted(exports.todayStamp()));
    }

    @GetMapping("/claims/{id}.pdf")
    public ResponseEntity<byte[]> dossier(@PathVariable Long id) {
        Claim claim = claims.require(id);
        byte[] pdf = exports.toPdf(claim, claims.history(claim.getId()));
        return download(pdf, MediaType.APPLICATION_PDF, "%s.pdf".formatted(claim.getReference()));
    }

    private List<Claim> find(String search, List<ClaimStatus> status, List<ClaimType> type,
                             List<ClaimPriority> priority, Boolean openOnly,
                             Instant createdFrom, Instant createdTo) {
        ClaimSearchCriteria criteria = new ClaimSearchCriteria(search,
                status == null ? List.of() : status,
                type == null ? List.of() : type,
                priority == null ? List.of() : priority,
                List.of(), null, null, null, openOnly, createdFrom, createdTo);

        return claims.search(criteria,
                        PageRequest.of(0, MAX_ROWS, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent();
    }

    private ResponseEntity<byte[]> download(byte[] body, MediaType contentType, String filename) {
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(body);
    }
}
