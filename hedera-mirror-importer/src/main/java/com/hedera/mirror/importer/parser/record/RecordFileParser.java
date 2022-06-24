package com.hedera.mirror.importer.parser.record;

/*-
 * ‌
 * Hedera Mirror Node
 * ​
 * Copyright (C) 2019 - 2022 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import static com.hedera.mirror.importer.config.MirrorDateRangePropertiesProcessor.DateRangeFilter;

import com.google.common.collect.ImmutableMap;

import com.hedera.mirror.common.domain.entity.EntityId;
import com.hedera.mirror.common.domain.transaction.Transaction;
import com.hedera.mirror.common.util.DomainUtils;
import com.hedera.mirror.importer.parser.record.entity.EntityRecordItemListener;

import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.TokenAssociateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.api.proto.java.TransferList;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.inject.Named;
import org.apache.logging.log4j.Level;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import com.hedera.mirror.common.domain.transaction.RecordFile;
import com.hedera.mirror.common.domain.transaction.RecordItem;
import com.hedera.mirror.common.domain.transaction.TransactionType;
import com.hedera.mirror.importer.config.MirrorDateRangePropertiesProcessor;
import com.hedera.mirror.importer.leader.Leader;
import com.hedera.mirror.importer.parser.AbstractStreamFileParser;
import com.hedera.mirror.importer.repository.StreamFileRepository;
import com.hedera.mirror.importer.util.Utility;

@Named
public class RecordFileParser extends AbstractStreamFileParser<RecordFile> {

    private final RecordItemListener recordItemListener;
    private final RecordStreamFileListener recordStreamFileListener;
    private final MirrorDateRangePropertiesProcessor mirrorDateRangePropertiesProcessor;

    // Metrics
    private final Map<Integer, Timer> latencyMetrics;
    private final Map<Integer, DistributionSummary> sizeMetrics;
    private final Timer unknownLatencyMetric;
    private final DistributionSummary unknownSizeMetric;

    // StringBuilder used to put together / dump out a Json Array.
    private final StringBuilder jsonArray = new StringBuilder();

    public RecordFileParser(MeterRegistry meterRegistry, RecordParserProperties parserProperties,
                            StreamFileRepository<RecordFile, Long> streamFileRepository,
                            RecordItemListener recordItemListener,
                            RecordStreamFileListener recordStreamFileListener,
                            MirrorDateRangePropertiesProcessor mirrorDateRangePropertiesProcessor) {
        super(meterRegistry, parserProperties, streamFileRepository);
        this.recordItemListener = recordItemListener;
        this.recordStreamFileListener = recordStreamFileListener;
        this.mirrorDateRangePropertiesProcessor = mirrorDateRangePropertiesProcessor;

        // build transaction latency metrics
        ImmutableMap.Builder<Integer, Timer> latencyMetricsBuilder = ImmutableMap.builder();
        ImmutableMap.Builder<Integer, DistributionSummary> sizeMetricsBuilder = ImmutableMap.builder();

        for (TransactionType type : TransactionType.values()) {
            Timer timer = Timer.builder("hedera.mirror.transaction.latency")
                    .description("The difference in ms between the time consensus was achieved and the mirror node " +
                            "processed the transaction")
                    .tag("type", type.toString())
                    .register(meterRegistry);
            latencyMetricsBuilder.put(type.getProtoId(), timer);

            DistributionSummary distributionSummary = DistributionSummary.builder("hedera.mirror.transaction.size")
                    .description("The size of the transaction in bytes")
                    .baseUnit("bytes")
                    .tag("type", type.toString())
                    .register(meterRegistry);
            sizeMetricsBuilder.put(type.getProtoId(), distributionSummary);
        }

        latencyMetrics = latencyMetricsBuilder.build();
        sizeMetrics = sizeMetricsBuilder.build();
        unknownLatencyMetric = latencyMetrics.get(TransactionType.UNKNOWN.getProtoId());
        unknownSizeMetric = sizeMetrics.get(TransactionType.UNKNOWN.getProtoId());
    }

    /**
     * Given a stream file data representing an rcd file from the service parse record items and persist changes
     *
     * @param recordFile containing information about file to be processed
     */
    @Override
    @Leader
    @Retryable(backoff = @Backoff(
            delayExpression = "#{@recordParserProperties.getRetry().getMinBackoff().toMillis()}",
            maxDelayExpression = "#{@recordParserProperties.getRetry().getMaxBackoff().toMillis()}",
            multiplierExpression = "#{@recordParserProperties.getRetry().getMultiplier()}"),
            maxAttemptsExpression = "#{@recordParserProperties.getRetry().getMaxAttempts()}")
    @Transactional(timeoutString = "#{@recordParserProperties.getTransactionTimeout().toSeconds()}")
    public void parse(RecordFile recordFile) {
        super.parse(recordFile);
    }

    @Override
    protected void doParse(RecordFile recordFile) {
        DateRangeFilter dateRangeFilter = mirrorDateRangePropertiesProcessor
                .getDateRangeFilter(parserProperties.getStreamType());

        try {
            Flux<RecordItem> recordItems = recordFile.getItems();
            log.warn("MYK: Starting to process recordFile {}", recordFile.getName());

            if (log.getLevel().isInRange(Level.DEBUG, Level.TRACE)) {
                recordItems = recordItems.doOnNext(this::logItem);
            }

            // MYK: temporary code to dump out arrays of json transaction protos
            jsonArray.setLength(0);
            // MYK: We *don't* want the square open bracket here
            log.info("MYK: Starting with empty array for recordFile {}", recordFile.getName());

            recordStreamFileListener.onStart();

            long count = recordItems.doOnNext(recordFile::processItem)
                    .filter(r -> dateRangeFilter.filter(r.getConsensusTimestamp()))
                    .doOnNext(recordItemListener::onItem)
                    .doOnNext(this::appendTransactionDetailsToJsonArray)
                    .doOnNext(this::recordMetrics)
                    .count()
                    .block();

            // MYK: We *don't* want the square close bracket here
            String contents = jsonArray.toString();

            String filename = System.getProperty("user.dir") + "/tempDir/" +
                    recordFile.getName().replace(".rcd",".json");
            File destFile = new File(filename);
            int counter = 1;
            while (destFile.exists()) {
                counter++;
                destFile = new File(filename + "-" + counter);
            }
            try {
                destFile.getParentFile().mkdirs(); // create parent directories if they don't already exist
                destFile.createNewFile();
            } catch (Exception e) {
                log.error("Error creating file {}", destFile.toString(), e);
            }

            try (
                FileWriter fw = new FileWriter(destFile, true);
                BufferedWriter bw = new BufferedWriter(fw);
            ) {
                bw.write(contents);
                log.info("Archived file to {}", destFile.toString());
                log.warn("MYK: Wrote {} bytes to {}", contents.length(), destFile.toString());
            } catch (Exception e) {
                log.error("Error archiving file to {}", destFile.toString(), e);
            }

            recordFile.finishLoad(count);

            recordStreamFileListener.onEnd(recordFile);
        } catch (Exception ex) {
            recordStreamFileListener.onError();
            throw ex;
        }
    }

    private void appendJsonToJsonArray(String prefix, String fieldName, String value, boolean comma) {
        jsonArray.append(prefix);
        jsonArray.append("\"" + fieldName + "\":");
        jsonArray.append(value);
        if (comma) {
            jsonArray.append(",");
        }
        jsonArray.append("\n");
    }

    private void appendStringToJsonArray(String prefix, String fieldName, String value, boolean comma) {
        appendJsonToJsonArray(prefix, fieldName, ("\"" + value + "\""), comma);
    }

    private void appendLongToJsonArray(String prefix, String fieldName, Long value, boolean comma) {
        appendJsonToJsonArray(prefix, fieldName, ("\"" + value + "\""), comma);
    }

    private void appendIntegerToJsonArray(String prefix, String fieldName, Integer value, boolean comma) {
        appendLongToJsonArray(prefix, fieldName, new Long(value), comma);
    }

    private void appendTransactionDetailsToJsonArray(RecordItem recordItem) {
        TransactionRecord tr = recordItem.getRecord();
        long consensusTimestamp = DomainUtils.timeStampInNanos(tr.getConsensusTimestamp());
        // DO NOT USE Transaction t = recordItem.getTransaction(); // wrong Transacton class!
        Transaction t = EntityRecordItemListener.buildTransaction(consensusTimestamp, recordItem);
        if (t == null) {
            jsonArray.append("{}\n");
            return;
        }
        jsonArray.append("{\n");
        appendStringToJsonArray("    ", "entityId", t.toJsonPartial("entityId"), true);
        appendStringToJsonArray("    ", "type", t.toJsonPartial("transactionType"), true);
        appendIntegerToJsonArray("    ", "index", t.toJsonPartialInteger("index"), true);
        appendJsonToJsonArray("    ", "fields", t.toJsonPartial("fields"), true);
        appendLongToJsonArray("    ", "consensus_timestamp", t.getId(), true);
        String transactionsArray = buildTransactionsJsonArray(recordItem, tr, t);
        if (transactionsArray.length() > 1) {
            appendJsonToJsonArray("    ", "transactions", transactionsArray, true);
        }
        jsonArray.append("}\n");  // no commas between records, and no final "]"
    }

    private String buildTransactionsJsonArray(RecordItem recordItem, TransactionRecord tr, Transaction t) {
        TransactionBody body = recordItem.getTransactionBody();
        boolean isTokenDissociate = body.hasTokenDissociate();
        long consensusTimestamp = DomainUtils.timeStampInNanos(tr.getConsensusTimestamp());
        StringBuilder output = new StringBuilder();

        tr.getTokenTransferListsList().forEach(tokenTransferList -> {
            output.append("      {\n");
            // to.do: assessed_custom_fees arrays
            // to.do: bytes?
            // to.do: charged_tx_fee
            output.append("        \"consensus_timestamp\":\"");
            output.append(consensusTimestamp);
            output.append("\",\n");
            // to.do: entity_id
            // to.do: max_fee
            // to.do: memo_base64
            // to.do: name
            // to.do: nft_transfers array // NFTs
            // to.do: node
            // to.do: nonce
            // to.do: parent_consensus_timestamp
            // to.do: result
            // to.do: scheduled
            // to.do: transaction_hash
            // to.do: transaction_id
            // to.do SECOND: transfers // hbar tokens
            // to.do: valid_duration_seconds
            // to.do: valid_start_timestamp
            // to.do FIRST: token_transfers // fungable tokens
            TokenID tokenId = tokenTransferList.getToken();
            EntityId entityTokenId = EntityId.of(tokenId);
            EntityId payerAccountId = recordItem.getPayerAccountId();
            List<AccountAmount> tokenTransfers = tokenTransferList.getTransfersList();
            int tokenTransferCount = tokenTransfers.size();
            StringBuilder tokenTransfersJson = new StringBuilder();
            for (int tokenTransferCounter = 0; tokenTransferCounter < tokenTransferCount; tokenTransferCounter++) {
                AccountAmount accountAmount = tokenTransfers.get(tokenTransferCounter);
                EntityId accountId = EntityId.of(accountAmount.getAccountID());
                long amount = accountAmount.getAmount();
                boolean isApproval = accountAmount.getIsApproval();
                tokenTransfersJson.append("          {\n");
                tokenTransfersJson.append("            \"account\":\"" + accountId.toString() + "\",\n");
                tokenTransfersJson.append("            \"account_shard\":\"" + accountId.getShardNum() + "\",\n");
                tokenTransfersJson.append("            \"account_realm\":\"" + accountId.getRealmNum() + "\",\n");
                tokenTransfersJson.append("            \"account_number\":\"" + accountId.getEntityNum() + "\",\n");
                tokenTransfersJson.append("            \"amount\":" + amount + ",\n");
                tokenTransfersJson.append("            \"is_approval\":" + isApproval + "\n");
                tokenTransfersJson.append("          }");
                if (tokenTransferCounter < tokenTransferCount - 1) {
                    tokenTransfersJson.append(",");
                }
                tokenTransfersJson.append("\n");
            }
            if (tokenTransfersJson.length() > 1) {
                output.append("        \"token_transfers\":[\n");
                output.append(tokenTransfersJson.toString());
                output.append("        ],\n");
            }
        });
        TransferList transferList = tr.getTransferList();
        int transferCount = transferList.getAccountAmountsCount();
        if (transferCount > 0) {
                output.append("      {\n");
                output.append("        \"transfers\":[\n");
                for (int i = 0; i < transferCount; ++i) {
                    var aa = transferList.getAccountAmounts(i);
                    EntityId account = EntityId.of(aa.getAccountID());
                    boolean isApproval = aa.getIsApproval();
                    output.append("          {\n");
                    output.append("            \"account\":\"" + account.toString() + "\",\n");
                    output.append("            \"account_shard\":\"" + account.getShardNum() + "\",\n");
                    output.append("            \"account_realm\":\"" + account.getRealmNum() + "\",\n");
                    output.append("            \"account_number\":\"" + account.getEntityNum() + "\",\n");
                    output.append("            \"amount\":" + aa.getAmount() + ",\n");
                    output.append("            \"is_approval\":" + isApproval + "\n");
                    output.append("          }");
                    if (i < transferCount - 1) {
                        output.append(",");
                    }
                    output.append("\n");
                }
                output.append("        ],\n");
                output.append("      }\n");
        }
        return output.toString();
    }

    private void logItem(RecordItem recordItem) {
        if (log.isTraceEnabled()) {
            log.trace("Transaction = {}, Record = {}",
                    Utility.printProtoMessage(recordItem.getTransaction()),
                    Utility.printProtoMessage(recordItem.getRecord()));
        } else if (log.isDebugEnabled()) {
            log.debug("Parsing transaction with consensus timestamp {}", recordItem.getConsensusTimestamp());
        }
    }

    private void recordMetrics(RecordItem recordItem) {
        sizeMetrics.getOrDefault(recordItem.getTransactionType(), unknownSizeMetric)
                .record(recordItem.getTransactionBytes().length);

        Instant consensusTimestamp = Utility.convertToInstant(recordItem.getRecord().getConsensusTimestamp());
        latencyMetrics.getOrDefault(recordItem.getTransactionType(), unknownLatencyMetric)
                .record(Duration.between(consensusTimestamp, Instant.now()));
    }
}
