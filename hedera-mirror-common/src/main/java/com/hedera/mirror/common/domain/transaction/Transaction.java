package com.hedera.mirror.common.domain.transaction;

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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.vladmihalcea.hibernate.type.basic.PostgreSQLEnumType;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.apache.commons.codec.binary.Base64;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;
import org.springframework.data.domain.Persistable;

import com.hedera.mirror.common.converter.AccountIdConverter;
import com.hedera.mirror.common.converter.EntityIdSerializer;
import com.hedera.mirror.common.converter.UnknownIdConverter;
import com.hedera.mirror.common.domain.entity.EntityId;

@AllArgsConstructor(access = AccessLevel.PRIVATE) // For builder
@Builder
@Data
@Entity
@NoArgsConstructor
@TypeDef(
        name = "pgsql_enum",
        typeClass = PostgreSQLEnumType.class
)
public class Transaction implements Persistable<Long> {

    @Id
    private Long consensusTimestamp;

    private Long chargedTxFee;

    @Convert(converter = UnknownIdConverter.class)
    @JsonSerialize(using = EntityIdSerializer.class)
    private EntityId entityId;

    @Enumerated(EnumType.STRING)
    @Type(type = "pgsql_enum")
    private ErrataType errata;

    private Integer index;

    private Long initialBalance;

    @ToString.Exclude
    private byte[] memo;

    private Long maxFee;

    @Convert(converter = AccountIdConverter.class)
    @JsonSerialize(using = EntityIdSerializer.class)
    private EntityId nodeAccountId;

    private Integer nonce;

    private Long parentConsensusTimestamp;

    @Convert(converter = AccountIdConverter.class)
    @JsonSerialize(using = EntityIdSerializer.class)
    private EntityId payerAccountId;

    private Integer result;

    private boolean scheduled;

    @ToString.Exclude
    private byte[] transactionBytes;

    @ToString.Exclude
    private byte[] transactionHash;

    private Integer type;

    private Long validDurationSeconds;

    private Long validStartNs;

    @JsonIgnore
    @Override
    public Long getId() {
        return consensusTimestamp;
    }

    @JsonIgnore
    @Override
    public boolean isNew() {
        return true; // Since we never update and use a natural ID, avoid Hibernate querying before insert
    }

    @JsonIgnore
    public String toJsonPartial(String designator) {
        if (designator.equalsIgnoreCase("entityId")) {
            return "" + entityId.getEntityNum();
        } else if (designator.equalsIgnoreCase("scheduled")) {
            return Boolean.toString(scheduled);
        } else if (designator.equalsIgnoreCase("transactionType")) {
            return TransactionType.of(type).name();
        } else if (designator.equalsIgnoreCase("transactionId")) {
            long microsToSecond = 1000000L;
            long seconds = validStartNs / microsToSecond;
            long nanos = (validStartNs % microsToSecond) * 1000L;
            // MYK -- to be determined -- append scheduled and nonce to this?
            return payerAccountId.toString() + "-" + seconds + "-" + nanos;
        } else if (designator.equalsIgnoreCase("fields")) {
            final String quote = "\"";
            final String equals = "\":";
            final String equalsString = "\\\":\\\"";
            final String comma = ",";
            final String commaString = "\\\",";
            StringBuilder sb = new StringBuilder()
                .append("\"{")
                .append(quote)
                .append("payer_account_id")
                .append(equalsString)
                .append(payerAccountId.toString())
                .append(commaString)
                .append(quote)
                .append("node")
                .append(equalsString)
                .append(nodeAccountId.toString())
                .append(commaString)
                .append(quote)
                .append("valid_start_ns")
                .append(equalsString)
                .append(validStartNs)
                .append(commaString)
                .append(quote)
                .append("valid_duration_seconds")
                .append(equalsString)
                .append(validDurationSeconds)
                .append(commaString)
                .append(quote)
                .append("initial_balance")
                .append(equalsString)
                .append(initialBalance)
                .append(commaString)
                .append(quote)
                .append("max_fee")
                .append(equalsString)
                .append(maxFee)
                .append(commaString)
                .append(quote)
                .append("charged_tx_fee")
                .append(equalsString)
                .append(chargedTxFee)
                .append(commaString)
                .append(quote)
                .append("memo")
                .append(equalsString)
                .append(Base64.encodeBase64String(memo))
                .append(commaString)
                .append(quote)
                .append("transaction_hash")
                .append(equalsString)
                .append(Base64.encodeBase64String(transactionHash))
                .append(commaString)
                .append(quote)
                .append("transaction_bytes")
                .append(equalsString)
                .append(Base64.encodeBase64String(transactionBytes))
                .append(commaString)
                .append(quote)
                .append("parent_consensus_timestamp")
                .append(equalsString)
                .append(parentConsensusTimestamp)
                .append(commaString)
                .append(quote)
                .append("errata")
                .append(equalsString)
                .append(errata == null ? "" : errata.name())
                .append("\"}\"");
            return sb.toString();
        } else {
            return "\"Unknown designator\":\"" + designator + "\"";
        }
    }

    @JsonIgnore
    public Integer toJsonPartialInteger(String designator) {
        if (designator.equalsIgnoreCase("index")) {
            return index;
        } else if (designator.equalsIgnoreCase("nonce")) {
            return nonce;
        } else if (designator.equalsIgnoreCase("result")) {
            return result;
        } else {
            // log an exception?
            return -1;
        }
    }

}
