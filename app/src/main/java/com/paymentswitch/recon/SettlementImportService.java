package com.paymentswitch.recon;

import com.paymentswitch.common.error.ApiException;
import com.paymentswitch.recon.ReconRepository.FileRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Imports a supplier settlement file and reconciles it. Idempotent: the SHA-256 checksum of the
 * content is unique, so sending the same file again returns the earlier result without
 * re-running anything. A file that fails validation is discarded completely.
 */
@Service
public class SettlementImportService {

    private static final Logger log = LoggerFactory.getLogger(SettlementImportService.class);

    private final ReconRepository repository;
    private final JobOperator jobOperator;
    private final Job settlementReconJob;

    SettlementImportService(ReconRepository repository, JobOperator jobOperator, Job settlementReconJob) {
        this.repository = repository;
        this.jobOperator = jobOperator;
        this.settlementReconJob = settlementReconJob;
    }

    public SettlementReport importFile(String supplierCode, LocalDate businessDate, String fileName, String content) {
        long supplierId = repository.supplierId(supplierCode).orElseThrow(() ->
                new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_SUPPLIER", "Unknown supplier " + supplierCode));
        String checksum = sha256(content);
        var created = repository.insertFile(supplierId, businessDate, fileName, checksum, content);
        if (created.isEmpty()) {
            FileRow existing = repository.findFileByChecksum(checksum).orElseThrow();
            log.info("Settlement file {} already imported as {} (checksum {})", fileName, existing.id(), checksum);
            return report(existing, true);
        }
        long fileId = created.get();
        JobExecution execution = run(fileId);
        if (execution.getStatus() != BatchStatus.COMPLETED) {
            repository.deleteFile(fileId);
            String reason = execution.getAllFailureExceptions().stream()
                    .map(SettlementImportService::rootMessage)
                    .findFirst()
                    .orElse("reconciliation job ended with " + execution.getStatus());
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_SETTLEMENT_FILE", reason);
        }
        repository.markCompleted(fileId);
        FileRow file = repository.findFile(fileId).orElseThrow();
        log.info("Settlement file {} for {} {} reconciled: {}", fileId, supplierCode, businessDate,
                repository.summary(fileId));
        return report(file, false);
    }

    public SettlementReport report(long fileId) {
        return repository.findFile(fileId)
                .map(file -> report(file, false))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SETTLEMENT_FILE_NOT_FOUND",
                        "Settlement file not found"));
    }

    public List<FileRow> list(int limit) {
        return repository.listFiles(limit);
    }

    private JobExecution run(long fileId) {
        try {
            return jobOperator.start(settlementReconJob, new JobParametersBuilder()
                    .addLong("fileId", fileId)
                    .toJobParameters());
        } catch (Exception e) {
            repository.deleteFile(fileId);
            throw new IllegalStateException("Could not start the reconciliation job for file " + fileId, e);
        }
    }

    private SettlementReport report(FileRow file, boolean alreadyImported) {
        return new SettlementReport(file, alreadyImported, repository.summary(file.id()),
                repository.discrepancies(file.id()));
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.toString();
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record SettlementReport(FileRow file, boolean alreadyImported, Map<String, Long> summary,
            List<ReconRepository.ResultRow> discrepancies) {
    }
}
