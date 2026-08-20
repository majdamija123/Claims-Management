package ma.cdg.claims.service;

import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import ma.cdg.claims.domain.Claim;
import ma.cdg.claims.domain.ClaimEvent;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

/** Produces the Excel, CSV and PDF deliverables offered by the reporting screens. */
@Service
public class ExportService {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault());

    private static final String[] COLUMNS = {
            "Reference", "Registered on", "Customer", "E-mail", "Channel", "Entity",
            "Subject", "Category", "Priority", "Status", "Current step", "Assignee",
            "Deadline", "Overdue", "Closed on", "Resolution"
    };

    // ------------------------------------------------------------------- Excel

    public byte[] toExcel(List<Claim> claims) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("Complaints");
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle overdueStyle = overdueStyle(workbook);

            Row header = sheet.createRow(0);
            for (int i = 0; i < COLUMNS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(COLUMNS[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIndex = 1;
            for (Claim claim : claims) {
                Row row = sheet.createRow(rowIndex++);
                String[] values = rowOf(claim);
                for (int i = 0; i < values.length; i++) {
                    Cell cell = row.createCell(i);
                    cell.setCellValue(values[i]);
                    if (claim.isOverdue()) {
                        cell.setCellStyle(overdueStyle);
                    }
                }
            }

            for (int i = 0; i < COLUMNS.length; i++) {
                sheet.autoSizeColumn(i);
            }
            sheet.createFreezePane(0, 1);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the Excel export", e);
        }
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        org.apache.poi.ss.usermodel.Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private CellStyle overdueStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    // --------------------------------------------------------------------- CSV

    public byte[] toCsv(List<Claim> claims) {
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(";", COLUMNS)).append('\n');
        for (Claim claim : claims) {
            String[] values = rowOf(claim);
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    csv.append(';');
                }
                csv.append(escapeCsv(values[i]));
            }
            csv.append('\n');
        }
        // The BOM makes Excel open the file as UTF-8 without an import dialog.
        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] result = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, result, 0, bom.length);
        System.arraycopy(body, 0, result, bom.length, body.length);
        return result;
    }

    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace("\r", " ").replace("\n", " ");
        if (cleaned.contains(";") || cleaned.contains("\"")) {
            return '"' + cleaned.replace("\"", "\"\"") + '"';
        }
        return cleaned;
    }

    // --------------------------------------------------------------------- PDF

    /** A one-page dossier for a single complaint, suitable for a paper file. */
    public byte[] toPdf(Claim claim, List<ClaimEvent> history) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 40, 40, 50, 40);
            PdfWriter.getInstance(document, out);
            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, new Color(15, 45, 90));
            Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
            Font valueFont = FontFactory.getFont(FontFactory.HELVETICA, 10);
            Font smallFont = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.DARK_GRAY);

            Paragraph title = new Paragraph("Complaint dossier " + claim.getReference(), titleFont);
            title.setSpacingAfter(4f);
            document.add(title);

            Paragraph subtitle = new Paragraph(
                    "CDG - Customer complaint management - issued on " + TIMESTAMP.format(Instant.now()),
                    smallFont);
            subtitle.setSpacingAfter(16f);
            document.add(subtitle);

            document.add(sectionTitle("Customer", labelFont));
            document.add(detailTable(labelFont, valueFont,
                    "Name", claim.getCustomerName(),
                    "E-mail", claim.getCustomerEmail(),
                    "Telephone", claim.getCustomerPhone(),
                    "Customer reference", claim.getCustomerReference(),
                    "Channel", claim.getChannel().getLabel(),
                    "Entity concerned", claim.getEntity()));

            document.add(sectionTitle("Complaint", labelFont));
            document.add(detailTable(labelFont, valueFont,
                    "Subject", claim.getSubject(),
                    "Category", claim.getType().getLabel(),
                    "Priority", claim.getPriority().getLabel(),
                    "Registered on", format(claim.getCreatedAt()),
                    "Status", claim.getStatus().getLabel(),
                    "Closed on", format(claim.getClosedAt())));

            document.add(sectionTitle("Description", labelFont));
            Paragraph description = new Paragraph(nullSafe(claim.getDescription()), valueFont);
            description.setSpacingAfter(14f);
            document.add(description);

            if (claim.getResolution() != null && !claim.getResolution().isBlank()) {
                document.add(sectionTitle("Answer given to the customer", labelFont));
                Paragraph resolution = new Paragraph(claim.getResolution(), valueFont);
                resolution.setSpacingAfter(14f);
                document.add(resolution);
            }
            if (claim.getRejectionReason() != null && !claim.getRejectionReason().isBlank()) {
                document.add(sectionTitle("Reason for rejection", labelFont));
                Paragraph reason = new Paragraph(claim.getRejectionReason(), valueFont);
                reason.setSpacingAfter(14f);
                document.add(reason);
            }

            document.add(sectionTitle("Processing history", labelFont));
            document.add(historyTable(history, labelFont, valueFont));

            document.close();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the PDF dossier", e);
        }
    }

    private Paragraph sectionTitle(String text, Font labelFont) {
        Font font = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, new Color(15, 45, 90));
        Paragraph paragraph = new Paragraph(text, font);
        paragraph.setSpacingBefore(6f);
        paragraph.setSpacingAfter(6f);
        return paragraph;
    }

    private PdfPTable detailTable(Font labelFont, Font valueFont, String... pairs) {
        PdfPTable table = new PdfPTable(new float[]{1f, 2f});
        table.setWidthPercentage(100);
        table.setSpacingAfter(12f);
        for (int i = 0; i < pairs.length; i += 2) {
            table.addCell(borderlessCell(pairs[i], labelFont));
            table.addCell(borderlessCell(nullSafe(pairs[i + 1]), valueFont));
        }
        return table;
    }

    private PdfPTable historyTable(List<ClaimEvent> history, Font labelFont, Font valueFont) {
        PdfPTable table = new PdfPTable(new float[]{1.2f, 1.4f, 1f, 2.4f});
        table.setWidthPercentage(100);
        for (String header : new String[]{"Date", "Event", "By", "Detail"}) {
            PdfPCell cell = new PdfPCell(new Paragraph(header, labelFont));
            cell.setBackgroundColor(new Color(235, 239, 245));
            cell.setPadding(5f);
            table.addCell(cell);
        }
        for (ClaimEvent event : history) {
            table.addCell(cell(format(event.getOccurredAt()), valueFont));
            table.addCell(cell(event.getType().getLabel(), valueFont));
            table.addCell(cell(nullSafe(event.getActor()), valueFont));
            table.addCell(cell(nullSafe(event.getComment()), valueFont));
        }
        return table;
    }

    private PdfPCell cell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setPadding(5f);
        return cell;
    }

    private PdfPCell borderlessCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setBorder(org.openpdf.text.Rectangle.NO_BORDER);
        cell.setPadding(3f);
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        return cell;
    }

    // ------------------------------------------------------------------ shared

    private String[] rowOf(Claim claim) {
        return new String[]{
                claim.getReference(),
                format(claim.getCreatedAt()),
                nullSafe(claim.getCustomerName()),
                nullSafe(claim.getCustomerEmail()),
                claim.getChannel().getLabel(),
                nullSafe(claim.getEntity()),
                nullSafe(claim.getSubject()),
                claim.getType().getLabel(),
                claim.getPriority().getLabel(),
                claim.getStatus().getLabel(),
                claim.getCurrentStep() == null ? "" : claim.getCurrentStep().getLabel(),
                nullSafe(claim.getCurrentAssignee()),
                claim.getSlaDueAt() == null ? "" : format(claim.getSlaDueAt()),
                claim.isOverdue() ? "Yes" : "No",
                claim.getClosedAt() == null ? "" : format(claim.getClosedAt()),
                nullSafe(claim.getResolution())
        };
    }

    private static String format(Instant instant) {
        return instant == null ? "" : TIMESTAMP.format(instant);
    }

    /** File name suffix used by the download endpoints. */
    public String todayStamp() {
        return DAY.format(Instant.now()).replace('/', '-');
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
