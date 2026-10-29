package com.paymentswitch.recon;

import com.paymentswitch.recon.ReconClassifier.LineToMatch;
import com.paymentswitch.recon.ReconClassifier.Result;
import com.paymentswitch.recon.ReconRepository.FileRow;
import com.paymentswitch.recon.SettlementLine.InvalidSettlementFileException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcCursorItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.infrastructure.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The settlement reconciliation job. One run per imported file (job parameter {@code fileId}):
 * <ol>
 *   <li><b>importLines</b> (chunk): parse the CSV into {@code settlement_lines}; any invalid line
 *       fails the whole job, and the importer discards the file.</li>
 *   <li><b>matchLines</b> (chunk): join each line with our transaction, classify it with
 *       {@link ReconClassifier}, write {@code recon_results}.</li>
 *   <li><b>findMissingSupplier</b> (tasklet): our successes with this supplier on that business day
 *       that the file does not contain.</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(ReconProperties.class)
class SettlementReconJobConfig {

    static final String JOB_NAME = "settlementReconJob";
    private static final int CHUNK_SIZE = 500;

    @Bean
    Job settlementReconJob(JobRepository jobRepository, Step importLines, Step matchLines, Step findMissingSupplier) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(importLines)
                .next(matchLines)
                .next(findMissingSupplier)
                .build();
    }

    @Bean
    Step importLines(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            FlatFileItemReader<SettlementLine> settlementLineReader, ItemWriter<SettlementLine> settlementLineWriter) {
        return new StepBuilder("importLines", jobRepository)
                .<SettlementLine, SettlementLine>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(settlementLineReader)
                .writer(settlementLineWriter)
                .build();
    }

    @Bean
    Step matchLines(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            JdbcCursorItemReader<LineToMatch> lineToMatchReader, ItemProcessor<LineToMatch, Result> lineClassifier,
            ItemWriter<Result> reconResultWriter) {
        return new StepBuilder("matchLines", jobRepository)
                .<LineToMatch, Result>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(lineToMatchReader)
                .processor(lineClassifier)
                .writer(reconResultWriter)
                .build();
    }

    @Bean
    Step findMissingSupplier(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            ReconRepository repository, ReconProperties properties) {
        return new StepBuilder("findMissingSupplier", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    long fileId = (Long) chunkContext.getStepContext().getJobParameters().get("fileId");
                    FileRow file = repository.findFile(fileId).orElseThrow();
                    OffsetDateTime from = file.businessDate().atStartOfDay(properties.zone()).toOffsetDateTime();
                    OffsetDateTime to = file.businessDate().plusDays(1).atStartOfDay(properties.zone()).toOffsetDateTime();
                    contribution.incrementWriteCount(
                            repository.insertMissingSupplier(fileId, file.supplierId(), from, to));
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    FlatFileItemReader<SettlementLine> settlementLineReader(@Value("#{jobParameters['fileId']}") Long fileId,
            ReconRepository repository) {
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setNames(SettlementLine.COLUMNS.toArray(String[]::new));
        tokenizer.setStrict(true);
        return new FlatFileItemReaderBuilder<SettlementLine>()
                .name("settlementLineReader")
                .resource(new ByteArrayResource(repository.content(fileId).getBytes(StandardCharsets.UTF_8)))
                .linesToSkip(1)
                .skippedLinesCallback(header -> {
                    if (!SettlementLine.HEADER.equals(header.strip())) {
                        throw new InvalidSettlementFileException("line 1: header must be " + SettlementLine.HEADER);
                    }
                })
                .lineMapper((line, lineNo) -> {
                    try {
                        return SettlementLine.parse(tokenizer.tokenize(line), lineNo);
                    } catch (InvalidSettlementFileException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        throw new InvalidSettlementFileException("line " + lineNo + ": " + e.getMessage());
                    }
                })
                .build();
    }

    @Bean
    @StepScope
    ItemWriter<SettlementLine> settlementLineWriter(@Value("#{jobParameters['fileId']}") Long fileId,
            ReconRepository repository) {
        return chunk -> chunk.forEach(line -> repository.insertLine(fileId, line));
    }

    @Bean
    @StepScope
    JdbcCursorItemReader<LineToMatch> lineToMatchReader(@Value("#{jobParameters['fileId']}") Long fileId,
            DataSource dataSource) {
        return new JdbcCursorItemReaderBuilder<LineToMatch>()
                .name("lineToMatchReader")
                .dataSource(dataSource)
                .sql("""
                        SELECT l.line_no, l.reference, l.supplier_ref, l.amount, l.currency,
                               row_number() OVER (PARTITION BY l.reference ORDER BY l.line_no) AS occurrence,
                               t.id AS transaction_id, t.status, t.supplier_id, t.supplier_cost,
                               t.currency AS internal_currency
                          FROM settlement_lines l
                          LEFT JOIN transactions t ON t.id = l.transaction_id
                         WHERE l.settlement_file_id = ?
                         ORDER BY l.line_no""")
                .queryArguments(fileId)
                .rowMapper((rs, row) -> new LineToMatch(rs.getInt("line_no"), rs.getString("reference"),
                        rs.getString("supplier_ref"), rs.getLong("amount"), rs.getString("currency"),
                        rs.getInt("occurrence"), rs.getObject("transaction_id", UUID.class), rs.getString("status"),
                        rs.getObject("supplier_id", Long.class), rs.getObject("supplier_cost", Long.class),
                        rs.getString("internal_currency")))
                .build();
    }

    @Bean
    @StepScope
    ItemProcessor<LineToMatch, Result> lineClassifier(@Value("#{jobParameters['fileId']}") Long fileId,
            ReconRepository repository) {
        long supplierId = repository.findFile(fileId).orElseThrow().supplierId();
        return line -> ReconClassifier.classify(line, supplierId);
    }

    @Bean
    @StepScope
    ItemWriter<Result> reconResultWriter(@Value("#{jobParameters['fileId']}") Long fileId,
            ReconRepository repository) {
        return chunk -> chunk.forEach(result -> repository.insertResult(fileId, result));
    }
}
