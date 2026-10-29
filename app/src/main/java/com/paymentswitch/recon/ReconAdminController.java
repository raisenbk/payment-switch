package com.paymentswitch.recon;

import com.paymentswitch.common.error.ApiException;
import com.paymentswitch.recon.ReconRepository.FileRow;
import com.paymentswitch.recon.ReconRepository.ResultRow;
import com.paymentswitch.recon.SettlementImportService.SettlementReport;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator API (X-Admin-Token): import supplier settlement files and read the reconciliation. */
@RestController
@RequestMapping("/admin/recon/settlements")
class ReconAdminController {

    private static final String CSV_HEADER = "match_status,transaction_id,line_no,reference,supplier_ref,"
            + "internal_status,internal_amount,supplier_amount,diff_amount,currency";

    private final SettlementImportService service;

    ReconAdminController(SettlementImportService service) {
        this.service = service;
    }

    /** 201 for a new file, 200 when exactly this content was imported before (nothing re-run). */
    @PostMapping(consumes = {"text/csv", MediaType.TEXT_PLAIN_VALUE})
    ResponseEntity<SettlementReport> importFile(@RequestParam String supplier,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate businessDate,
            @RequestParam(required = false) String fileName, @RequestBody String content) {
        String name = fileName != null ? fileName : supplier.toUpperCase() + "_" + businessDate + ".csv";
        if (!name.matches("[A-Za-z0-9._-]{1,255}")) {
            // It ends up in a Content-Disposition header of the CSV export.
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE_NAME",
                    "fileName may only contain letters, digits, '.', '_' and '-'");
        }
        SettlementReport report = service.importFile(supplier, businessDate, name, content);
        return ResponseEntity.status(report.alreadyImported() ? HttpStatus.OK : HttpStatus.CREATED).body(report);
    }

    @GetMapping
    List<FileRow> list(@RequestParam(defaultValue = "50") int limit) {
        return service.list(Math.clamp(limit, 1, 200));
    }

    @GetMapping("/{id}")
    SettlementReport get(@PathVariable long id) {
        return service.report(id);
    }

    /** Every non-matching result, for spreadsheets and the supplier dispute process. */
    @GetMapping(value = "/{id}/discrepancies.csv", produces = "text/csv")
    ResponseEntity<String> discrepanciesCsv(@PathVariable long id) {
        SettlementReport report = service.report(id);
        String body = Stream.concat(Stream.of(CSV_HEADER), report.discrepancies().stream().map(ReconAdminController::csv))
                .collect(Collectors.joining("\n", "", "\n"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"discrepancies_" + report.file().fileName() + "\"")
                .body(body);
    }

    private static String csv(ResultRow r) {
        return Stream.of(r.matchStatus(), r.transactionId(), r.lineNo(), r.reference(), r.supplierRef(),
                        r.internalStatus(), r.internalAmount(), r.supplierAmount(), r.diffAmount(), r.currency())
                .map(value -> escape(Objects.toString(value, "")))
                .collect(Collectors.joining(","));
    }

    /** RFC 4180 quoting; also defuses spreadsheet formulas in values that came from the supplier's file. */
    private static String escape(String value) {
        String safe = !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 && !value.matches("-?\\d+")
                ? "'" + value : value;
        return safe.contains(",") || safe.contains("\"") || safe.contains("\n")
                ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }
}
