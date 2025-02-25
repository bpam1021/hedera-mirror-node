package com.hedera.mirror.importer.parser.record.entity.sql;

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

import static com.hedera.mirror.importer.config.MirrorImporterConfiguration.TOKEN_DISSOCIATE_BATCH_PERSISTER;

import com.google.common.base.Stopwatch;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.inject.Named;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.BeanCreationNotAllowedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;

import com.hedera.mirror.common.domain.addressbook.NodeStake;
import com.hedera.mirror.common.domain.contract.Contract;
import com.hedera.mirror.common.domain.contract.ContractLog;
import com.hedera.mirror.common.domain.contract.ContractResult;
import com.hedera.mirror.common.domain.contract.ContractStateChange;
import com.hedera.mirror.common.domain.entity.AbstractCryptoAllowance;
import com.hedera.mirror.common.domain.entity.AbstractEntity;
import com.hedera.mirror.common.domain.entity.AbstractNftAllowance;
import com.hedera.mirror.common.domain.entity.AbstractTokenAllowance;
import com.hedera.mirror.common.domain.entity.CryptoAllowance;
import com.hedera.mirror.common.domain.entity.Entity;
import com.hedera.mirror.common.domain.entity.EntityId;
import com.hedera.mirror.common.domain.entity.NftAllowance;
import com.hedera.mirror.common.domain.entity.TokenAllowance;
import com.hedera.mirror.common.domain.file.FileData;
import com.hedera.mirror.common.domain.schedule.Schedule;
import com.hedera.mirror.common.domain.token.Nft;
import com.hedera.mirror.common.domain.token.NftId;
import com.hedera.mirror.common.domain.token.NftTransfer;
import com.hedera.mirror.common.domain.token.NftTransferId;
import com.hedera.mirror.common.domain.token.Token;
import com.hedera.mirror.common.domain.token.TokenAccount;
import com.hedera.mirror.common.domain.token.TokenAccountId;
import com.hedera.mirror.common.domain.token.TokenAccountKey;
import com.hedera.mirror.common.domain.token.TokenTransfer;
import com.hedera.mirror.common.domain.topic.TopicMessage;
import com.hedera.mirror.common.domain.transaction.AssessedCustomFee;
import com.hedera.mirror.common.domain.transaction.CryptoTransfer;
import com.hedera.mirror.common.domain.transaction.CustomFee;
import com.hedera.mirror.common.domain.transaction.EthereumTransaction;
import com.hedera.mirror.common.domain.transaction.LiveHash;
import com.hedera.mirror.common.domain.transaction.NonFeeTransfer;
import com.hedera.mirror.common.domain.transaction.Prng;
import com.hedera.mirror.common.domain.transaction.RecordFile;
import com.hedera.mirror.common.domain.transaction.StakingRewardTransfer;
import com.hedera.mirror.common.domain.transaction.Transaction;
import com.hedera.mirror.common.domain.transaction.TransactionSignature;
import com.hedera.mirror.importer.domain.EntityIdService;
import com.hedera.mirror.importer.exception.ImporterException;
import com.hedera.mirror.importer.exception.ParserException;
import com.hedera.mirror.importer.parser.batch.BatchPersister;
import com.hedera.mirror.importer.parser.record.RecordStreamFileListener;
import com.hedera.mirror.importer.parser.record.entity.ConditionOnEntityRecordParser;
import com.hedera.mirror.importer.parser.record.entity.EntityBatchCleanupEvent;
import com.hedera.mirror.importer.parser.record.entity.EntityBatchSaveEvent;
import com.hedera.mirror.importer.parser.record.entity.EntityListener;
import com.hedera.mirror.importer.repository.RecordFileRepository;

@Log4j2
@Named
@Order(0)
@ConditionOnEntityRecordParser
public class SqlEntityListener implements EntityListener, RecordStreamFileListener {

    private final BatchPersister batchPersister;
    private final EntityIdService entityIdService;
    private final ApplicationEventPublisher eventPublisher;
    private final RecordFileRepository recordFileRepository;
    private final SqlProperties sqlProperties;
    private final BatchPersister tokenDissociateTransferBatchPersister;

    // lists of insert only domains
    private final Collection<AssessedCustomFee> assessedCustomFees;
    private final Collection<Contract> contracts;
    private final Collection<ContractLog> contractLogs;
    private final Collection<ContractResult> contractResults;
    private final Collection<ContractStateChange> contractStateChanges;
    private final Collection<CryptoAllowance> cryptoAllowances;
    private final Collection<CryptoTransfer> cryptoTransfers;
    private final Collection<CustomFee> customFees;
    private final Collection<Entity> entities;
    private final Collection<EthereumTransaction> ethereumTransactions;
    private final Collection<FileData> fileData;
    private final Collection<LiveHash> liveHashes;
    private final Collection<NftAllowance> nftAllowances;
    private final Collection<NodeStake> nodeStakes;
    private final Collection<NonFeeTransfer> nonFeeTransfers;
    private final Collection<Prng> prngs;
    private final Collection<StakingRewardTransfer> stakingRewardTransfers;
    private final Map<TokenAccountId, TokenAccount> tokenAccounts;
    private final Collection<TokenAllowance> tokenAllowances;
    private final Collection<TokenTransfer> tokenDissociateTransfers;
    private final Collection<TokenTransfer> tokenTransfers;
    private final Collection<TopicMessage> topicMessages;
    private final Collection<Transaction> transactions;
    private final Collection<TransactionSignature> transactionSignatures;

    // maps of upgradable domains
    private final Map<Long, Contract> contractState;
    private final Map<AbstractCryptoAllowance.Id, CryptoAllowance> cryptoAllowanceState;
    private final Map<Long, Entity> entityState;
    private final Map<NftId, Nft> nfts;
    private final Map<AbstractNftAllowance.Id, NftAllowance> nftAllowanceState;
    private final Map<NftTransferId, NftTransfer> nftTransferState;
    private final Map<Long, Schedule> schedules;
    private final Map<Long, Token> tokens;
    private final Map<AbstractTokenAllowance.Id, TokenAllowance> tokenAllowanceState;

    // tracks the state of <token, account> relationships in a batch, the initial state before the batch is in db.
    // for each <token, account> update, merge the state and the update, save the merged state to the batch.
    // during batch upsert, the merged state at time T is again merged with the initial state before the batch to
    // get the full state at time T
    private final Map<TokenAccountKey, TokenAccount> tokenAccountState;

    public SqlEntityListener(BatchPersister batchPersister,
                             EntityIdService entityIdService,
                             ApplicationEventPublisher eventPublisher,
                             RecordFileRepository recordFileRepository,
                             SqlProperties sqlProperties,
                             @Qualifier(TOKEN_DISSOCIATE_BATCH_PERSISTER) BatchPersister tokenDissociateTransferBatchPersister) {
        this.batchPersister = batchPersister;
        this.entityIdService = entityIdService;
        this.eventPublisher = eventPublisher;
        this.recordFileRepository = recordFileRepository;
        this.sqlProperties = sqlProperties;
        this.tokenDissociateTransferBatchPersister = tokenDissociateTransferBatchPersister;

        assessedCustomFees = new ArrayList<>();
        contracts = new ArrayList<>();
        contractLogs = new ArrayList<>();
        contractResults = new ArrayList<>();
        contractStateChanges = new ArrayList<>();
        cryptoAllowances = new ArrayList<>();
        cryptoTransfers = new ArrayList<>();
        customFees = new ArrayList<>();
        entities = new ArrayList<>();
        ethereumTransactions = new ArrayList<>();
        fileData = new ArrayList<>();
        liveHashes = new ArrayList<>();
        nftAllowances = new ArrayList<>();
        nodeStakes = new ArrayList<>();
        nonFeeTransfers = new ArrayList<>();
        prngs = new ArrayList<>();
        stakingRewardTransfers = new ArrayList<>();
        tokenAccounts = new LinkedHashMap<>();
        tokenAllowances = new ArrayList<>();
        tokenDissociateTransfers = new ArrayList<>();
        tokenTransfers = new ArrayList<>();
        topicMessages = new ArrayList<>();
        transactions = new ArrayList<>();
        transactionSignatures = new ArrayList<>();

        contractState = new HashMap<>();
        cryptoAllowanceState = new HashMap<>();
        entityState = new HashMap<>();
        nfts = new HashMap<>();
        nftAllowanceState = new HashMap<>();
        nftTransferState = new HashMap<>();
        schedules = new HashMap<>();
        tokens = new HashMap<>();
        tokenAccountState = new HashMap<>();
        tokenAllowanceState = new HashMap<>();
    }

    @Override
    public boolean isEnabled() {
        return sqlProperties.isEnabled();
    }

    @Override
    public void onStart() {
        cleanup();
    }

    @Override
    public void onEnd(RecordFile recordFile) {
        executeBatches();
        if (recordFile != null) {
            recordFileRepository.save(recordFile);
        }
    }

    @Override
    public void onError() {
        cleanup();
    }

    private void cleanup() {
        try {
            assessedCustomFees.clear();
            contracts.clear();
            contractState.clear();
            contractLogs.clear();
            contractResults.clear();
            contractStateChanges.clear();
            cryptoAllowances.clear();
            cryptoAllowanceState.clear();
            cryptoTransfers.clear();
            customFees.clear();
            entities.clear();
            entityState.clear();
            ethereumTransactions.clear();
            fileData.clear();
            liveHashes.clear();
            nonFeeTransfers.clear();
            stakingRewardTransfers.clear();
            nfts.clear();
            nftAllowances.clear();
            nftAllowanceState.clear();
            nftTransferState.clear();
            nodeStakes.clear();
            prngs.clear();
            schedules.clear();
            topicMessages.clear();
            tokenAccounts.clear();
            tokenAccountState.clear();
            tokenAllowances.clear();
            tokenAllowanceState.clear();
            tokens.clear();
            tokenDissociateTransfers.clear();
            tokenTransfers.clear();
            transactions.clear();
            transactionSignatures.clear();
            eventPublisher.publishEvent(new EntityBatchCleanupEvent(this));
        } catch (BeanCreationNotAllowedException e) {
            // This error can occur during shutdown
        }
    }

    private void executeBatches() {
        try {
            // batch save action may run asynchronously, triggering it before other operations can reduce latency
            eventPublisher.publishEvent(new EntityBatchSaveEvent(this));

            Stopwatch stopwatch = Stopwatch.createStarted();

            // insert only operations
            batchPersister.persist(assessedCustomFees);
            batchPersister.persist(contractLogs);
            batchPersister.persist(contractResults);
            batchPersister.persist(contractStateChanges);
            batchPersister.persist(cryptoTransfers);
            batchPersister.persist(customFees);
            batchPersister.persist(ethereumTransactions);
            batchPersister.persist(fileData);
            batchPersister.persist(liveHashes);
            batchPersister.persist(nodeStakes);
            batchPersister.persist(prngs);
            batchPersister.persist(topicMessages);
            batchPersister.persist(transactions);
            batchPersister.persist(transactionSignatures);

            // insert operations with conflict management
            batchPersister.persist(contracts);
            batchPersister.persist(cryptoAllowances);
            batchPersister.persist(entities);
            batchPersister.persist(nftAllowances);
            batchPersister.persist(tokens.values());
            // ingest tokenAccounts after tokens since some fields of token accounts depends on the associated token
            batchPersister.persist(tokenAccounts.values());
            batchPersister.persist(tokenAllowances);
            batchPersister.persist(nfts.values()); // persist nft after token entity
            batchPersister.persist(schedules.values());

            // transfers operations should be last to ensure insert logic completeness, entities should already exist
            batchPersister.persist(nonFeeTransfers);
            batchPersister.persist(nftTransferState.values());
            batchPersister.persist(stakingRewardTransfers);
            batchPersister.persist(tokenTransfers);

            // handle the transfers from token dissociate transactions after nft is processed
            tokenDissociateTransferBatchPersister.persist(tokenDissociateTransfers);

            log.info("Completed batch inserts in {}", stopwatch);
        } catch (ParserException e) {
            throw e;
        } catch (Exception e) {
            throw new ParserException(e);
        } finally {
            cleanup();
        }
    }

    @Override
    public void onAssessedCustomFee(AssessedCustomFee assessedCustomFee) throws ImporterException {
        assessedCustomFees.add(assessedCustomFee);
    }

    @Override
    public void onContract(Contract contract) {
        entityIdService.notify(contract);
        Contract merged = contractState.merge(contract.getId(), contract, this::mergeContract);
        if (merged == contract) {
            // only add the merged object to the collection if the state is replaced with the new contract object, i.e.,
            // attributes only in the previous state are merged into the new contract object
            contracts.add(merged);
        }
    }

    @Override
    public void onContractLog(ContractLog contractLog) {
        contractLogs.add(contractLog);
    }

    @Override
    public void onContractResult(ContractResult contractResult) throws ImporterException {
        contractResults.add(contractResult);
    }

    @Override
    public void onContractStateChange(ContractStateChange contractStateChange) {
        contractStateChanges.add(contractStateChange);
    }

    @Override
    public void onCryptoAllowance(CryptoAllowance cryptoAllowance) {
        var merged = cryptoAllowanceState.merge(cryptoAllowance.getId(), cryptoAllowance, this::mergeCryptoAllowance);
        cryptoAllowances.add(merged);
    }

    @Override
    public void onCryptoTransfer(CryptoTransfer cryptoTransfer) throws ImporterException {
        cryptoTransfers.add(cryptoTransfer);
    }

    @Override
    public void onCustomFee(CustomFee customFee) throws ImporterException {
        customFees.add(customFee);
    }

    @Override
    public void onEntity(Entity entity) throws ImporterException {
        long id = entity.getId();
        if (id == EntityId.EMPTY.getId()) {
            return;
        }

        Entity merged = entityState.merge(entity.getId(), entity, this::mergeEntity);
        if (merged == entity) {
            // only add the merged object to the collection if the state is replaced with the new entity object, i.e.,
            // attributes only in the previous state are merged into the new entity object
            entities.add(entity);
        }
    }

    @Override
    public void onEthereumTransaction(EthereumTransaction ethereumTransaction) throws ImporterException {
        ethereumTransactions.add(ethereumTransaction);
    }

    @Override
    public void onFileData(FileData fd) {
        fileData.add(fd);
    }

    @Override
    public void onLiveHash(LiveHash liveHash) throws ImporterException {
        liveHashes.add(liveHash);
    }

    @Override
    public void onNft(Nft nft) throws ImporterException {
        nfts.merge(nft.getId(), nft, this::mergeNft);
    }

    @Override
    public void onNftAllowance(NftAllowance nftAllowance) {
        var merged = nftAllowanceState.merge(nftAllowance.getId(), nftAllowance, this::mergeNftAllowance);
        nftAllowances.add(merged);
    }

    @Override
    public void onNftTransfer(NftTransfer nftTransfer) throws ImporterException {
        nftTransferState.merge(nftTransfer.getId(), nftTransfer, this::mergeNftTransfer);
    }

    @Override
    public void onNodeStake(NodeStake nodeStake) {
        nodeStakes.add(nodeStake);
    }

    @Override
    public void onNonFeeTransfer(NonFeeTransfer nonFeeTransfer) throws ImporterException {
        nonFeeTransfers.add(nonFeeTransfer);
    }

    @Override
    public void onPrng(Prng prng) {
        prngs.add(prng);
    }

    @Override
    public void onSchedule(Schedule schedule) throws ImporterException {
        // schedules could experience multiple updates in a single record file, handle updates in memory for this case
        schedules.merge(schedule.getScheduleId(), schedule, this::mergeSchedule);
    }

    @Override
    public void onStakingRewardTransfer(StakingRewardTransfer stakingRewardTransfer) {
        stakingRewardTransfers.add(stakingRewardTransfer);
    }

    @Override
    public void onToken(Token token) throws ImporterException {
        // tokens could experience multiple updates in a single record file, handle updates in memory for this case
        tokens.merge(token.getTokenId().getTokenId().getId(), token, this::mergeToken);
    }

    @Override
    public void onTokenAccount(TokenAccount tokenAccount) throws ImporterException {
        if (tokenAccounts.containsKey(tokenAccount.getId())) {
            log.warn("Skipping duplicate token account association: {}", tokenAccount);
            return;
        }

        var key = new TokenAccountKey(tokenAccount.getId().getTokenId(), tokenAccount.getId().getAccountId());
        TokenAccount merged = tokenAccountState.merge(key, tokenAccount, this::mergeTokenAccount);
        tokenAccounts.put(merged.getId(), merged);
    }

    @Override
    public void onTokenAllowance(TokenAllowance tokenAllowance) {
        TokenAllowance merged = tokenAllowanceState.merge(tokenAllowance.getId(), tokenAllowance,
                this::mergeTokenAllowance);
        tokenAllowances.add(merged);
    }

    @Override
    public void onTokenTransfer(TokenTransfer tokenTransfer) throws ImporterException {
        if (tokenTransfer.isTokenDissociate()) {
            tokenDissociateTransfers.add(tokenTransfer);
            return;
        }

        tokenTransfers.add(tokenTransfer);
    }

    @Override
    public void onTopicMessage(TopicMessage topicMessage) throws ImporterException {
        topicMessages.add(topicMessage);
    }

    @Override
    public void onTransaction(Transaction transaction) throws ImporterException {
        transactions.add(transaction);
        if (transactions.size() == sqlProperties.getBatchSize()) {
            executeBatches();
        }
    }

    @Override
    public void onTransactionSignature(TransactionSignature transactionSignature) throws ImporterException {
        transactionSignatures.add(transactionSignature);
    }

    private <T extends AbstractEntity> T mergeAbstractEntity(T previous, T current) {
        // This entity should not trigger a history record, so just copy common non-history fields, if set, to previous
        if (!current.isHistory()) {
            if (current.getStakePeriodStart() != null) {
                previous.setStakePeriodStart(current.getStakePeriodStart());
            }
            return previous;
        }

        // If previous doesn't have history, merge reversely from current to previous
        var src = previous.isHistory() ? previous : current;
        var dest = previous.isHistory() ? current : previous;

        // Copy non-updatable fields from src
        dest.setCreatedTimestamp(src.getCreatedTimestamp());
        dest.setEvmAddress(src.getEvmAddress());

        if (dest.getAutoRenewPeriod() == null) {
            dest.setAutoRenewPeriod(src.getAutoRenewPeriod());
        }

        if (dest.getAutoRenewAccountId() == null) {
            dest.setAutoRenewAccountId(src.getAutoRenewAccountId());
        }

        if (dest.getDeclineReward() == null) {
            dest.setDeclineReward(src.getDeclineReward());
        }

        if (dest.getDeleted() == null) {
            dest.setDeleted(src.getDeleted());
        }

        if (dest.getExpirationTimestamp() == null) {
            dest.setExpirationTimestamp(src.getExpirationTimestamp());
        }

        if (dest.getKey() == null) {
            dest.setKey(src.getKey());
        }

        if (dest.getMemo() == null) {
            dest.setMemo(src.getMemo());
        }

        if (dest.getProxyAccountId() == null) {
            dest.setProxyAccountId(src.getProxyAccountId());
        }

        if (dest.getStakedAccountId() == null) {
            dest.setStakedAccountId(src.getStakedAccountId());
        }

        if (dest.getStakedNodeId() == null) {
            dest.setStakedNodeId(src.getStakedNodeId());
        }

        if (dest.getStakePeriodStart() == null) {
            dest.setStakePeriodStart(src.getStakePeriodStart());
        }

        // There is at least one entity with history. If there is one without history, it must be dest and just copy the
        // timestamp range from src to dest. Otherwise, both have history, and it's a normal merge from previous to
        // current, so close the src entity's timestamp range
        if (!dest.isHistory()) {
            dest.setTimestampRange(src.getTimestampRange());
        } else {
            src.setTimestampUpper(dest.getTimestampLower());
        }

        return dest;
    }

    private Contract mergeContract(Contract previous, Contract current) {
        var merged = mergeAbstractEntity(previous, current);

        // Merge consistently
        var src = merged == current ? previous : current;
        var dest = merged == current ? current : previous;

        dest.setFileId(src.getFileId());
        dest.setInitcode(src.getInitcode());

        if (dest.getMaxAutomaticTokenAssociations() == null) {
            dest.setMaxAutomaticTokenAssociations(src.getMaxAutomaticTokenAssociations());
        }

        if (dest.getObtainerId() == null) {
            dest.setObtainerId(src.getObtainerId());
        }

        if (dest.getPermanentRemoval() == null) {
            dest.setPermanentRemoval(src.getPermanentRemoval());
        }

        return dest;
    }

    private CryptoAllowance mergeCryptoAllowance(CryptoAllowance previous, CryptoAllowance current) {
        previous.setTimestampUpper(current.getTimestampLower());
        return current;
    }

    private Entity mergeEntity(Entity previous, Entity current) {
        var merged = mergeAbstractEntity(previous, current);

        // This entity should not trigger a history record, so just copy non-history fields, if set, to previous
        if (!current.isHistory()) {
            if (current.getEthereumNonce() != null) {
                previous.setEthereumNonce(current.getEthereumNonce());
            }
            return previous;
        }

        // Merge consistently
        var src = merged == current ? previous : current;
        var dest = merged == current ? current : previous;

        if (dest.getEthereumNonce() == null) {
            dest.setEthereumNonce(src.getEthereumNonce());
        }

        if (dest.getMaxAutomaticTokenAssociations() == null) {
            dest.setMaxAutomaticTokenAssociations(src.getMaxAutomaticTokenAssociations());
        }

        if (dest.getReceiverSigRequired() == null) {
            dest.setReceiverSigRequired(src.getReceiverSigRequired());
        }

        if (dest.getSubmitKey() == null) {
            dest.setSubmitKey(src.getSubmitKey());
        }

        return dest;
    }

    private Nft mergeNft(Nft cachedNft, Nft newNft) {
        if (newNft.getAccountId() != null) { // only domains generated by NftTransfers should set account
            cachedNft.setAccountId(newNft.getAccountId());
        }

        if (cachedNft.getCreatedTimestamp() == null && newNft.getCreatedTimestamp() != null) {
            cachedNft.setCreatedTimestamp(newNft.getCreatedTimestamp());
        }

        if (newNft.getDeleted() != null) {
            cachedNft.setDeleted(newNft.getDeleted());
        }

        if (newNft.getMetadata() != null) {
            cachedNft.setMetadata(newNft.getMetadata());
        }

        cachedNft.setModifiedTimestamp(newNft.getModifiedTimestamp());

        // copy allowance related fields
        cachedNft.setDelegatingSpender(newNft.getDelegatingSpender());
        cachedNft.setSpender(newNft.getSpender());

        return cachedNft;
    }

    private NftTransfer mergeNftTransfer(NftTransfer cachedNftTransfer, NftTransfer newNftTransfer) {
        // flatten multi receiver transfers
        if (!Objects.equals(cachedNftTransfer.getReceiverAccountId(), newNftTransfer.getReceiverAccountId())) {
            cachedNftTransfer.setReceiverAccountId(newNftTransfer.getReceiverAccountId());
        }

        return cachedNftTransfer;
    }

    private NftAllowance mergeNftAllowance(NftAllowance previous, NftAllowance current) {
        previous.setTimestampUpper(current.getTimestampLower());
        return current;
    }

    private Schedule mergeSchedule(Schedule cachedSchedule, Schedule schedule) {
        cachedSchedule.setExecutedTimestamp(schedule.getExecutedTimestamp());
        return cachedSchedule;
    }

    private Token mergeToken(Token cachedToken, Token newToken) {
        if (newToken.getFreezeKey() != null) {
            cachedToken.setFreezeKey(newToken.getFreezeKey());
        }

        if (newToken.getKycKey() != null) {
            cachedToken.setKycKey(newToken.getKycKey());
        }

        if (newToken.getName() != null) {
            cachedToken.setName(newToken.getName());
        }

        if (newToken.getPauseKey() != null) {
            cachedToken.setPauseKey(newToken.getPauseKey());
        }

        if (newToken.getPauseStatus() != null) {
            cachedToken.setPauseStatus(newToken.getPauseStatus());
        }

        if (newToken.getSupplyKey() != null) {
            cachedToken.setSupplyKey(newToken.getSupplyKey());
        }

        if (newToken.getSymbol() != null) {
            cachedToken.setSymbol(newToken.getSymbol());
        }

        if (newToken.getTotalSupply() != null) {
            Long newTotalSupply = newToken.getTotalSupply();
            if (cachedToken.getTotalSupply() != null && newTotalSupply < 0) {
                // if the cached token has total supply set, and the new total supply is negative because it's an update
                // from the token transfer of a token dissociate of a deleted token, aggregate the change
                cachedToken.setTotalSupply(cachedToken.getTotalSupply() + newTotalSupply);
            } else {
                // if the cached token doesn't have total supply or the new total supply is non-negative, set it to the
                // new token's total supply. Later step should apply the change on the current total supply in db if
                // the value is negative.
                cachedToken.setTotalSupply(newToken.getTotalSupply());
            }
        }

        if (newToken.getTreasuryAccountId() != null) {
            cachedToken.setTreasuryAccountId(newToken.getTreasuryAccountId());
        }

        if (newToken.getWipeKey() != null) {
            cachedToken.setWipeKey(newToken.getWipeKey());
        }

        cachedToken.setModifiedTimestamp(newToken.getModifiedTimestamp());
        return cachedToken;
    }

    private TokenAccount mergeTokenAccount(TokenAccount lastTokenAccount, TokenAccount newTokenAccount) {
        if (newTokenAccount.getCreatedTimestamp() != null) {
            return newTokenAccount;
        }

        // newTokenAccount is a partial update. It must have its id (tokenId, accountId, modifiedTimestamp) set.
        // copy the lifespan immutable fields createdTimestamp and automaticAssociation from the previous snapshot.
        // copy other fields from the previous snapshot if not set in newTokenAccount
        newTokenAccount.setCreatedTimestamp(lastTokenAccount.getCreatedTimestamp());
        newTokenAccount.setAutomaticAssociation(lastTokenAccount.getAutomaticAssociation());

        if (newTokenAccount.getAssociated() == null) {
            newTokenAccount.setAssociated(lastTokenAccount.getAssociated());
        }

        if (newTokenAccount.getFreezeStatus() == null) {
            newTokenAccount.setFreezeStatus(lastTokenAccount.getFreezeStatus());
        }

        if (newTokenAccount.getKycStatus() == null) {
            newTokenAccount.setKycStatus(lastTokenAccount.getKycStatus());
        }

        return newTokenAccount;
    }

    private TokenAllowance mergeTokenAllowance(TokenAllowance previous, TokenAllowance current) {
        previous.setTimestampUpper(current.getTimestampLower());
        return current;
    }
}
