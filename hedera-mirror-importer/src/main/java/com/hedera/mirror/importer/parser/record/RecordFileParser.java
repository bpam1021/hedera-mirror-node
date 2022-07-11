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

import com.hedera.mirror.common.domain.addressbook.AddressBook;
import com.hedera.mirror.common.domain.entity.EntityId;
import com.hedera.mirror.common.domain.transaction.Transaction;
import com.hedera.mirror.common.util.DomainUtils;
import com.hedera.mirror.importer.parser.record.entity.EntityRecordItemListener;

import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AssessedCustomFee;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.ContractLoginfo;
import com.hederahashgraph.api.proto.java.ContractStateChange;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.StorageChange;
import com.hederahashgraph.api.proto.java.TokenAssociateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.api.proto.java.TransferList;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Named;
import org.apache.commons.codec.binary.Base64;
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

            //For record_file table
            final StringBuilder recordFileContents = new StringBuilder();
            recordFileContents.append("{");
            recordFileJsonAppender("\"" + recordFile.getConsensusStart().toString() + "\"", recordFileContents,
                    "consensus_start_timestamp", true);
            recordFileJsonAppender("\"" + recordFile.getConsensusEnd().toString() + "\"", recordFileContents,
                    "consensus_end_timestamp", true);
            String dataHash = Base64.encodeBase64String(recordFile.getMetadataHash().getBytes(StandardCharsets.UTF_8));
            recordFileJsonAppender("\"" + dataHash + "\"", recordFileContents, "data_hash", true);
            String prevHash = Base64.encodeBase64String(recordFile.getPreviousHash().getBytes(StandardCharsets.UTF_8));
            recordFileJsonAppender("\"" + prevHash + "\"", recordFileContents, "prev_hash", true);
            recordFileJsonAppender("" + recordFile.getIndex(), recordFileContents, "number", true);
            String addressBookAsString = (recordFile.getAddressBook() == null) ? " "
                    : " \\\"" + recordFile.getAddressBook().toString() + "\\\" ";
            recordFileJsonAppender("\"[" + addressBookAsString + "]\"", recordFileContents, "address_books", true);
            StringBuilder signatureFiles = new StringBuilder();
            signatureFiles.append("\"[");
            Map<String, String> signatures = recordFile.getSignatureFiles();
            boolean firstSignature = true;
            for (Map.Entry<String, String> entry : signatures.entrySet()) {
                if (firstSignature) {
                    firstSignature = false;
                } else {
                    signatureFiles.append(",");
                }
                signatureFiles.append(" {\\\"account_number\\\":\\\"" + entry.getKey() + "\\\",");
                signatureFiles.append(" \\\"signature_file_hash\\\":\\\"" + entry.getValue() + "\\\"}");
            }
            signatureFiles.append(" ]\"");
            recordFileJsonAppender(signatureFiles.toString(), recordFileContents, "signature_files", true);
  
            StringBuilder fields = new StringBuilder();
            fields.append("\"{");
            fields.append(" \\\"count\\\":\\\"" + recordFile.getCount() + "\\\",");
            fields.append(" \\\"gas_used\\\":\\\"" + recordFile.getGasUsed() + "\\\",");
            fields.append(" \\\"hapi_version\\\":\\\"" + recordFile.getHapiVersion() + "\\\",");
            fields.append(" \\\"logs_bloom\\\":\\\"" + Base64.encodeBase64String(recordFile.getLogsBloom()) + "\\\",");
            fields.append(" \\\"name\\\":\\\"" + recordFile.getName() + "\\\",");
            fields.append(" \\\"size\\\":\\\"" + recordFile.getSize() + "\\\"}\"");
            recordFileJsonAppender(fields.toString(), recordFileContents, "fields", false);

            recordFileContents.append(" }\n");

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

            String filenameRecordFile = System.getProperty("user.dir") + "/tempRecord/" +
                    recordFile.getName().replace(".rcd",".json");
            writeFile(filename, contents);
            writeFile(filenameRecordFile, recordFileContents.toString());
            recordFile.finishLoad(count);
            recordStreamFileListener.onEnd(recordFile);
        } catch (Exception ex) {
            recordStreamFileListener.onError();
            throw ex;
        }
    }

    private void recordFileJsonAppender(String value, StringBuilder recordFileContents, String fieldName,
            boolean comma) {
        recordFileContents.append("\"" + fieldName + "\":");
        recordFileContents.append(value);
        if (comma) {
           recordFileContents.append(",");
        }
    }

    private void writeFile(String filename, String contents) {
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
    }

    private void appendJsonToJsonArray(String prefix, String fieldName, String value, boolean comma) {
        jsonArray.append(prefix);
        jsonArray.append("\"" + fieldName + "\":");
        jsonArray.append(value);
        if (comma) {
            jsonArray.append(",");
        }
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
        jsonArray.append("{");
        appendStringToJsonArray(" ", "entityId", t.toJsonPartial("entityId"), true);
        appendStringToJsonArray(" ", "type", t.toJsonPartial("transactionType"), true);
        appendIntegerToJsonArray(" ", "index", t.toJsonPartialInteger("index"), true);
        appendIntegerToJsonArray(" ", "result", t.toJsonPartialInteger("result"), true);
        appendStringToJsonArray(" ", "scheduled", t.toJsonPartial("scheduled"), true);
        appendIntegerToJsonArray(" ", "nonce", t.toJsonPartialInteger("nonce"), true);
        appendStringToJsonArray(" ", "transaction_id", t.toJsonPartial("transactionId"), true);
        appendJsonToJsonArray(" ", "fields", t.toJsonPartial("fields"), true);
        appendLongToJsonArray(" ", "consensus_timestamp", t.getId(), true);
        String assessedCustomFees = buildAssessedCustomFeesJsonArray(recordItem, tr, t);
        if (assessedCustomFees.length() > 1) {
            appendJsonToJsonArray(" ", "assessed_custom_fees", assessedCustomFees, true);
        }
        String tokenTransfers = buildTokenTransfersJsonArray(recordItem, tr, t);
        if (tokenTransfers.length() > 1) {
            appendJsonToJsonArray(" ", "transfers_tokens", tokenTransfers, true);
        }
        String hbarTransfers = buildHbarTransfersJsonArray(recordItem, tr, t);
        if (hbarTransfers.length() > 1) {
            appendJsonToJsonArray(" ", "transfers_hbar", hbarTransfers, true);
        }
        String nftTransfers = buildNftTransfersJsonArray(recordItem, tr, t);
        if (nftTransfers.length() > 1) {
            appendJsonToJsonArray(" ", "transfers_nft", nftTransfers, true);
        }

        ContractFunctionResult contractResult = getContractFunctionResult(tr);
        EntityId payerAccountId = recordItem.getPayerAccountId();
        if (contractResult != null) {
            String contractLogs = buildContractLogs(contractResult, payerAccountId);
            if (contractLogs.length() > 1) {
                appendJsonToJsonArray(" ", "contract_logs", contractLogs, true);
            }
            String contractResults = buildContractResults(contractResult, payerAccountId);
            if (contractResults.length() > 1) {
                appendJsonToJsonArray(" ", "contract_results", contractResults, true);
            }
            String contractStateChanges = buildContractStateChanges(contractResult, payerAccountId);
            if (contractStateChanges.length() > 1) {
                appendJsonToJsonArray(" ", "contract_state_changes", contractStateChanges, true);
            }
        }

        // remove the trailing comma from this record
        jsonArray.setLength(jsonArray.length() - 1);
        jsonArray.append(" }\n");  // no commas between records, and no final "]"
    }

    private String buildAssessedCustomFeesJsonArray(RecordItem recordItem, TransactionRecord tr, Transaction t) {
        int count = tr.getAssessedCustomFeesCount();
        if (count == 0) {
            return "";
        }
        EntityId payerAccountId = recordItem.getPayerAccountId();
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < count; i++) {
            AssessedCustomFee assessedCustomFee = tr.getAssessedCustomFees(i);
            if (i == 0) {
                output.append("\"[");
            } else {
                output.append(",");
            }
            EntityId collectorAccountId = EntityId.of(assessedCustomFee.getFeeCollectorAccountId());
            List<String> effectivePayerEntityIds = assessedCustomFee.getEffectivePayerAccountIdList().stream()
                    .map(EntityId::of)
                    .map(EntityId::toString)
                    .collect(Collectors.toList());
            String effectivePayersList = String.join(", ", effectivePayerEntityIds);
            EntityId tokenId = EntityId.of(assessedCustomFee.getTokenId());
            output.append(" {");
            output.append(" \\\"amount\":\\\"" + assessedCustomFee.getAmount() + "\\\",");
            output.append(" \\\"collector_account_id\":\\\"" + collectorAccountId.toString() + "\\\",");
            output.append(" \\\"effective_payer_account_ids\":\\[ " + effectivePayersList + " ],");
            output.append(" \\\"payer_account_id\":\\\"" + payerAccountId.toString() + "\\\",");
            output.append(" \\\"token_id\":\\\"" + tokenId.toString() + "\\\"");
            output.append("}");
        }
        output.append(" ]\"");
        return output.toString();
    }

    private String buildTokenTransfersJsonArray(RecordItem recordItem, TransactionRecord tr, Transaction t) {
        StringBuilder output = new StringBuilder();
        boolean atLeastOneTokenFound = false;

        for (TokenTransferList tokenTransferList : tr.getTokenTransferListsList()) {
            TokenID tokenId = tokenTransferList.getToken();
            EntityId entityTokenId = EntityId.of(tokenId);
            EntityId payerAccountId = recordItem.getPayerAccountId();
            List<AccountAmount> tokenTransfers = tokenTransferList.getTransfersList();
            int tokenTransferCount = tokenTransfers.size();
            for (int tokenTransferCounter = 0; tokenTransferCounter < tokenTransferCount; tokenTransferCounter++) {
                if (atLeastOneTokenFound) {
                    output.append(",");
                } else {
                    output.append("\"[");
                    atLeastOneTokenFound = true;
                }
                AccountAmount accountAmount = tokenTransfers.get(tokenTransferCounter);
                EntityId accountId = EntityId.of(accountAmount.getAccountID());
                long amount = accountAmount.getAmount();
                boolean isApproval = accountAmount.getIsApproval();
                output.append(" \"{");
                output.append(" \\\"account\\\":\\\"" + accountId.toString() + "\\\",");
                output.append(" \\\"account_shard\\\":\\\"" + accountId.getShardNum() + "\\\",");
                output.append(" \\\"account_realm\\\":\\\"" + accountId.getRealmNum() + "\\\",");
                output.append(" \\\"account_number\\\":\\\"" + accountId.getEntityNum() + "\\\",");
                output.append(" \\\"amount\\\":\\" + amount + "\\\",");
                output.append(" \\\"is_approval\\\":" + isApproval);
                output.append("}");
            }
        }
        if (output.length() > 0) {
            output.append(" ]\"");
        }
        return output.toString();
    }

    private String buildHbarTransfersJsonArray(RecordItem recordItem, TransactionRecord tr, Transaction t) {
        StringBuilder output = new StringBuilder();

        TransferList transferList = tr.getTransferList();
        int transferCount = transferList.getAccountAmountsCount();
        if (transferCount > 0) {
            output.append(" \"[");
            for (int i = 0; i < transferCount; ++i) {
                var aa = transferList.getAccountAmounts(i);
                EntityId account = EntityId.of(aa.getAccountID());
                boolean isApproval = aa.getIsApproval();
                output.append(" {");
                output.append(" \\\"account\\\":\\\"" + account.toString() + "\\\",");
                output.append(" \\\"account_shard\\\":\\\"" + account.getShardNum() + "\\\",");
                output.append(" \\\"account_realm\\\":\\\"" + account.getRealmNum() + "\\\",");
                output.append(" \\\"account_number\\\":\\\"" + account.getEntityNum() + "\\\",");
                output.append(" \\\"amount\\\":" + aa.getAmount() + ",");
                output.append(" \\\"is_approval\\\":" + isApproval + " }");
                if (i < transferCount - 1) {
                    output.append(",");
                }
            }
            output.append(" ]\"");
        }
        return output.toString();
    }

    private String buildNftTransfersJsonArray(RecordItem recordItem, TransactionRecord tr, Transaction t) {
        StringBuilder output = new StringBuilder();
        boolean atLeastOneTokenFound = false;
        for (TokenTransferList tokenTransferList : tr.getTokenTransferListsList()) {
            TokenID tokenId = tokenTransferList.getToken();
            EntityId entityTokenId = EntityId.of(tokenId);
            EntityId payerAccountId = recordItem.getPayerAccountId();
            List<NftTransfer> nftTransfers = tokenTransferList.getNftTransfersList();
            int nftTransferCount = nftTransfers.size();
            for (int nftTransferCounter = 0; nftTransferCounter < nftTransferCount; nftTransferCounter++) {
                if (atLeastOneTokenFound) {
                    output.append(",");
                } else {
                    output.append("[");
                    atLeastOneTokenFound = true;
                }
                NftTransfer nftTransfer = nftTransfers.get(nftTransferCounter);
                EntityId receiverId = EntityId.of(nftTransfer.getReceiverAccountID());
                EntityId senderId = EntityId.of(nftTransfer.getSenderAccountID());
                long serialNumber = nftTransfer.getSerialNumber();
                boolean isApproval = nftTransfer.getIsApproval();
                output.append(" {");
                output.append(" \\\"payer_account\\\":\\\"" + payerAccountId.toString() + "\",");
                output.append(" \\\"payer_account_shard\\\":\\\"" + payerAccountId.getShardNum() + "\",");
                output.append(" \\\"payer_account_realm\\\":\\\"" + payerAccountId.getRealmNum() + "\",");
                output.append(" \\\"payer_account_number\\\":\\\"" + payerAccountId.getEntityNum() + "\",");
                output.append(" \\\"sender_account\\\":\\\"" + senderId.toString() + "\",");
                output.append(" \\\"sender_account_shard\\\":\\\"" + senderId.getShardNum() + "\",");
                output.append(" \\\"sender_account_realm\\\":\\\"" + senderId.getRealmNum() + "\",");
                output.append(" \\\"sender_account_number\\\":\\\"" + senderId.getEntityNum() + "\",");
		output.append(" \\\"receiver_account\\\":\\\"" + receiverId.toString() + "\",");
                output.append(" \\\"receiver_account_shard\\\":\\\"" + receiverId.getShardNum() + "\",");
                output.append(" \\\"receiver_account_realm\\\":\\\"" + receiverId.getRealmNum() + "\",");
                output.append(" \\\"receiver_account_number\\\":\\\"" + receiverId.getEntityNum() + "\",");
                output.append(" \\\"serial_number\\\":" + serialNumber + ",");
                output.append(" \\\"is_approval\":" + isApproval + " }");
            }
        };
        if (output.length() > 0) {
            output.append(" ]");
        }
        return output.toString();
    }

    private String buildContractLogs(ContractFunctionResult contractResult, EntityId payerAccountId) {
        StringBuilder output = new StringBuilder();
        boolean atLeastOneLogFound = false;
        for (ContractLoginfo contractLoginfo : contractResult.getLogInfoList()) {
            if (atLeastOneLogFound) {
                output.append(",");
            } else {
                output.append("\\\"[");
                atLeastOneLogFound = true;
            }
            output.append(" {");
            output.append(" \\\"bloom\\\":\\\"");
            output.append(Base64.encodeBase64String(contractLoginfo.getBloom().toByteArray()) + "\\\",");
            output.append(" \\\"data\\\":\\\"");
            output.append(Base64.encodeBase64String(contractLoginfo.getData().toByteArray()) + "\\\",");
            output.append(" \\\"index\\\":\\\"" + contractLoginfo.getTopicCount() + "\\\",");
            if (contractLoginfo.getTopicCount() > 0) {
                output.append(" \\\"topic0\\\":\\\"");
                output.append(Base64.encodeBase64String(contractLoginfo.getTopic(0).toByteArray()) + "\\\",");
            }
            if (contractLoginfo.getTopicCount() > 1) {
                output.append(" \\\"topic1\\\":\\\"");
                output.append(Base64.encodeBase64String(contractLoginfo.getTopic(1).toByteArray()) + "\\\",");
            }
            if (contractLoginfo.getTopicCount() > 2) {
                output.append(" \\\"topic2\\\":\\\"");
                output.append(Base64.encodeBase64String(contractLoginfo.getTopic(2).toByteArray()) + "\\\",");
            }
            if (contractLoginfo.getTopicCount() > 3) {
                output.append(" \\\"topic3\\\":\\\"");
                output.append(Base64.encodeBase64String(contractLoginfo.getTopic(3).toByteArray()) + "\\\",");
            }
            output.append(" \\\"payer_account_id\\\":\\\"" + payerAccountId.toString() + "\\\",");
            output.append(" \\\"payer_account_shard\\\":\\\"" + payerAccountId.getShardNum() + "\\\",");
            output.append(" \\\"payer_account_realm\\\":\\\"" + payerAccountId.getRealmNum() + "\\\",");
            output.append(" \\\"payer_account_number\\\":\\\"" + payerAccountId.getEntityNum() + "\\\"");
            output.append(" }");
        };
        if (output.length() > 0) {
            output.append(" ]\\\"");
        }
        return output.toString();
    }

    // there is only a single ContractFunctionResult, not a List of them.  But, for consistency, if any of
    // the "contract_results" data items are found, we return an array of the one contract_result.
    private String buildContractResults(ContractFunctionResult contractResult, EntityId payerAccountId) {
        StringBuilder output = new StringBuilder();
        output.append("\"[");
        output.append(" {");
        output.append(" \\\"function_parameters\\\":\\\"");
        output.append(Base64.encodeBase64String(contractResult.getFunctionParameters().toByteArray()) + "\\\",");
        output.append(" \\\"gas_limit\\\":\\\"" + contractResult.getGas() + "\\\",");
        output.append(" \\\"function_result\\\":\\\"");
        output.append(Base64.encodeBase64String(contractResult.toByteArray()) + "\\\",");
        output.append(" \\\"gas_used\\\":\\\"" + contractResult.getGasUsed() + "\\\",");
        output.append(" \\\"amount\\\":\\\"" + contractResult.getAmount() + "\\\",");
        output.append(" \\\"call_result\\\":\\\"");
        output.append(Base64.encodeBase64String(contractResult.getContractCallResult().toByteArray()) + "\\\",");
        output.append(" \\\"created_contract_ids\\\":\\\"[");
        boolean firstContract = true;
        for (ContractID contractId : contractResult.getCreatedContractIDsList()) {
            if (firstContract) {
                firstContract = false;
            } else {
                output.append(",");
            }
            EntityId contractEntity = EntityId.of(contractId);
            output.append(" \\\"" + contractEntity.toString() + "\\\"");
        }
        output.append(" ]\\\",");
        output.append(" \\\"error_message\\\":\\\"" + contractResult.getErrorMessage() + "\\\",");
        EntityId senderAccountId = EntityId.of(contractResult.getSenderId());
        output.append(" \\\"sender_account_id\\\":\\\"" + senderAccountId.toString() + "\\\",");
        output.append(" \\\"sender_account_shard\\\":\\\"" + senderAccountId.getShardNum() + "\\\",");
        output.append(" \\\"sender_account_realm\\\":\\\"" + senderAccountId.getRealmNum() + "\\\",");
        output.append(" \\\"sender_account_number\\\":\\\"" + senderAccountId.getEntityNum() + "\\\",");
        output.append(" \\\"payer_account_id\\\":\\\"" + payerAccountId.toString() + "\\\",");
        output.append(" \\\"payer_account_shard\\\":\\\"" + payerAccountId.getShardNum() + "\\\",");
        output.append(" \\\"payer_account_realm\\\":\\\"" + payerAccountId.getRealmNum() + "\\\",");
        output.append(" \\\"payer_account_number\\\":\\\"" + payerAccountId.getEntityNum() + "\\\",");
        output.append(" \\\"bloom\\\":\\\"");
        output.append(Base64.encodeBase64String(contractResult.getBloom().toByteArray()) + "\\\"");
        output.append("} ]\\\"");
        return output.toString();
    }

    private String buildContractStateChanges(ContractFunctionResult contractResult, EntityId payerAccountId) {
        StringBuilder output = new StringBuilder();
        boolean atLeastOneStateChangeFound = false;
        for (ContractStateChange contractStateChange : contractResult.getStateChangesList()) {
            EntityId contractId = EntityId.of(contractStateChange.getContractID());
            for (StorageChange storageChange : contractStateChange.getStorageChangesList()) {
                if (atLeastOneStateChangeFound) {
                    output.append(",");
                } else {
                    output.append("\"[");
                    atLeastOneStateChangeFound = true;
                }

                output.append(" {");
                output.append(" \\\"contract_id\\\":\\\"" + contractId.toString() + "\\\",");
                output.append(" \\\"value_written\\\":\\\"");
                output.append(Base64.encodeBase64String(storageChange.getValueWritten().toByteArray()) + "\\\",");
                output.append(" \\\"value_read\":\\\"");
                output.append(Base64.encodeBase64String(storageChange.getValueRead().toByteArray()) + "\\\",");
                output.append(" \\\"slot\":\\\"");
                output.append(Base64.encodeBase64String(storageChange.getSlot().toByteArray()) + "\\\",");
                output.append(" \\\"payer_account_id\\\":\\\"" + payerAccountId.toString() + "\\\",");
                output.append(" \\\"payer_account_shard\\\":\\\"" + payerAccountId.getShardNum() + "\\\",");
                output.append(" \\\"payer_account_realm\\\":\\\"" + payerAccountId.getRealmNum() + "\\\",");
                output.append(" \\\"payer_account_number\\\":\\\"" + payerAccountId.getEntityNum() + "\\\"");
                output.append(" }");
            }
        }
        if (output.length() > 0) {
            output.append("]\"");
        }
        return output.toString();
    }

    private ContractFunctionResult getContractFunctionResult(TransactionRecord record) {
        if (record.hasContractCreateResult()) {
            return record.getContractCreateResult();
        }
        if (record.hasContractCallResult()) {
            return record.getContractCallResult();
        }
        return null;
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
