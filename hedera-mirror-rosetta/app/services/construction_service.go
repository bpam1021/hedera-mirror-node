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

package services

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"math/big"
	"time"

	"github.com/coinbase/rosetta-sdk-go/server"
	rTypes "github.com/coinbase/rosetta-sdk-go/types"
	"github.com/hashgraph/hedera-mirror-node/hedera-mirror-rosetta/app/config"
	"github.com/hashgraph/hedera-mirror-node/hedera-mirror-rosetta/app/domain/types"
	"github.com/hashgraph/hedera-mirror-node/hedera-mirror-rosetta/app/errors"
	"github.com/hashgraph/hedera-mirror-node/hedera-mirror-rosetta/app/interfaces"
	"github.com/hashgraph/hedera-mirror-node/hedera-mirror-rosetta/app/services/construction"
	"github.com/hashgraph/hedera-mirror-node/hedera-mirror-rosetta/app/tools"
	"github.com/hashgraph/hedera-protobufs-go/services"
	"github.com/hashgraph/hedera-sdk-go/v2"
	log "github.com/sirupsen/logrus"
	"google.golang.org/protobuf/encoding/prototext"
)

const (
	defaultValidDurationSeconds     = 180
	metadataKeyValidDurationSeconds = "valid_duration"
	metadataKeyValidStartNanos      = "valid_start_nanos"
	optionKeyOperationType          = "operation_type"
)

// constructionAPIService implements the server.ConstructionAPIServicer interface.
type constructionAPIService struct {
	BaseService
	defaultMaxTransactionFee map[string]hedera.Hbar
	hederaClient             *hedera.Client
	nodeAccountIds           []hedera.AccountID
	nodeAccountIdsLen        *big.Int
	systemShard              int64
	systemRealm              int64
	transactionHandler       construction.TransactionConstructor
}

// ConstructionCombine implements the /construction/combine endpoint.
func (c *constructionAPIService) ConstructionCombine(
	_ context.Context,
	request *rTypes.ConstructionCombineRequest,
) (*rTypes.ConstructionCombineResponse, *rTypes.Error) {
	if len(request.Signatures) == 0 {
		return nil, errors.ErrNoSignature
	}

	transaction, rErr := unmarshallTransactionFromHexString(request.UnsignedTransaction)
	if rErr != nil {
		return nil, rErr
	}

	frozenBodyBytes, rErr := getFrozenTransactionBodyBytes(transaction)
	if rErr != nil {
		return nil, rErr
	}

	for _, signature := range request.Signatures {
		if signature.SignatureType != rTypes.Ed25519 {
			return nil, errors.ErrInvalidSignatureType
		}

		pubKey, err := hedera.PublicKeyFromBytes(signature.PublicKey.Bytes)
		if err != nil {
			return nil, errors.ErrInvalidPublicKey
		}

		if !ed25519.Verify(pubKey.Bytes(), frozenBodyBytes, signature.Bytes) {
			return nil, errors.ErrInvalidSignatureVerification
		}

		if rErr := addSignature(transaction, pubKey, signature.Bytes); rErr != nil {
			return nil, rErr
		}
	}

	transactionBytes, err := transaction.ToBytes()
	if err != nil {
		return nil, errors.ErrTransactionMarshallingFailed
	}

	return &rTypes.ConstructionCombineResponse{
		SignedTransaction: tools.SafeAddHexPrefix(hex.EncodeToString(transactionBytes)),
	}, nil
}

// ConstructionDerive implements the /construction/derive endpoint.
func (c *constructionAPIService) ConstructionDerive(
	_ context.Context,
	request *rTypes.ConstructionDeriveRequest,
) (*rTypes.ConstructionDeriveResponse, *rTypes.Error) {
	publicKey := request.PublicKey
	if publicKey.CurveType != rTypes.Edwards25519 {
		return nil, errors.ErrInvalidPublicKey
	}
	accountId, err := types.NewAccountIdFromAlias(request.PublicKey.Bytes, c.systemShard, c.systemRealm)
	if err != nil || accountId.GetCurveType() != rTypes.Edwards25519 {
		return nil, errors.ErrInvalidPublicKey
	}
	return &rTypes.ConstructionDeriveResponse{AccountIdentifier: accountId.ToRosetta()}, nil
}

// ConstructionHash implements the /construction/hash endpoint.
func (c *constructionAPIService) ConstructionHash(
	_ context.Context,
	request *rTypes.ConstructionHashRequest,
) (*rTypes.TransactionIdentifierResponse, *rTypes.Error) {
	signedTransaction, rErr := unmarshallTransactionFromHexString(request.SignedTransaction)
	if rErr != nil {
		return nil, rErr
	}

	hash, err := signedTransaction.GetTransactionHash()
	if err != nil {
		return nil, errors.ErrTransactionHashFailed
	}

	return &rTypes.TransactionIdentifierResponse{
		TransactionIdentifier: &rTypes.TransactionIdentifier{Hash: tools.SafeAddHexPrefix(hex.EncodeToString(hash[:]))},
	}, nil
}

// ConstructionMetadata implements the /construction/metadata endpoint.
func (c *constructionAPIService) ConstructionMetadata(
	_ context.Context,
	request *rTypes.ConstructionMetadataRequest,
) (*rTypes.ConstructionMetadataResponse, *rTypes.Error) {
	options := request.Options
	if options == nil || options[optionKeyOperationType] == nil {
		return nil, errors.ErrInvalidOptions
	}
	operationType, ok := options[optionKeyOperationType].(string)
	if !ok {
		return nil, errors.ErrInvalidOptions
	}

	maxFee, err := c.transactionHandler.GetDefaultMaxTransactionFee(operationType)
	if err != nil {
		return nil, err
	}

	return &rTypes.ConstructionMetadataResponse{
		Metadata:     make(map[string]interface{}),
		SuggestedFee: []*rTypes.Amount{maxFee.ToRosetta()},
	}, nil
}

// ConstructionParse implements the /construction/parse endpoint.
func (c *constructionAPIService) ConstructionParse(
	ctx context.Context,
	request *rTypes.ConstructionParseRequest,
) (*rTypes.ConstructionParseResponse, *rTypes.Error) {
	transaction, err := unmarshallTransactionFromHexString(request.Transaction)
	if err != nil {
		return nil, err
	}

	operations, accounts, err := c.transactionHandler.Parse(ctx, transaction)
	if err != nil {
		return nil, err
	}

	signers := make([]*rTypes.AccountIdentifier, 0, len(accounts))
	if request.Signed {
		for _, account := range accounts {
			signers = append(signers, account.ToRosetta())
		}
	}

	return &rTypes.ConstructionParseResponse{
		Operations:               operations.ToRosetta(),
		AccountIdentifierSigners: signers,
	}, nil
}

// ConstructionPayloads implements the /construction/payloads endpoint.
func (c *constructionAPIService) ConstructionPayloads(
	ctx context.Context,
	request *rTypes.ConstructionPayloadsRequest,
) (*rTypes.ConstructionPayloadsResponse, *rTypes.Error) {
	validDurationSeconds, rErr := c.getIntMetadataValue(request.Metadata, metadataKeyValidDurationSeconds)
	if rErr != nil || !isValidTransactionValidDuration(validDurationSeconds) {
		return nil, errors.ErrInvalidArgument
	}

	validStartNanos, rErr := c.getIntMetadataValue(request.Metadata, metadataKeyValidStartNanos)
	if rErr != nil {
		return nil, rErr
	}

	operations, rErr := c.getOperationSlice(request.Operations)
	if rErr != nil {
		return nil, rErr
	}

	transaction, signers, rErr := c.transactionHandler.Construct(ctx, operations)
	if rErr != nil {
		return nil, rErr
	}

	payer := signers[0].ToSdkAccountId()
	if rErr = updateTransaction(
		transaction,
		transactionSetNodeAccountId(c.getRandomNodeAccountId()),
		transactionSetTransactionId(payer, validStartNanos),
		transactionSetValidDuration(validDurationSeconds),
		transactionFreeze,
	); rErr != nil {
		return nil, rErr
	}

	bytes, err := transaction.ToBytes()
	if err != nil {
		return nil, errors.ErrTransactionMarshallingFailed
	}

	frozenBodyBytes, rErr := getFrozenTransactionBodyBytes(transaction)
	if rErr != nil {
		return nil, rErr
	}

	signingPayloads := make([]*rTypes.SigningPayload, 0, len(signers))
	for _, signer := range signers {
		signingPayloads = append(signingPayloads, &rTypes.SigningPayload{
			AccountIdentifier: signer.ToRosetta(),
			Bytes:             frozenBodyBytes,
			SignatureType:     rTypes.Ed25519,
		})
	}

	return &rTypes.ConstructionPayloadsResponse{
		UnsignedTransaction: tools.SafeAddHexPrefix(hex.EncodeToString(bytes)),
		Payloads:            signingPayloads,
	}, nil
}

// ConstructionPreprocess implements the /construction/preprocess endpoint.
func (c *constructionAPIService) ConstructionPreprocess(
	ctx context.Context,
	request *rTypes.ConstructionPreprocessRequest,
) (*rTypes.ConstructionPreprocessResponse, *rTypes.Error) {
	operations, rErr := c.getOperationSlice(request.Operations)
	if rErr != nil {
		return nil, rErr
	}

	signers, err := c.transactionHandler.Preprocess(ctx, operations)
	if err != nil {
		return nil, err
	}

	requiredPublicKeys := make([]*rTypes.AccountIdentifier, 0, len(signers))
	for _, signer := range signers {
		requiredPublicKeys = append(requiredPublicKeys, &rTypes.AccountIdentifier{Address: signer.String()})
	}

	return &rTypes.ConstructionPreprocessResponse{
		Options:            map[string]interface{}{optionKeyOperationType: operations[0].Type},
		RequiredPublicKeys: requiredPublicKeys,
	}, nil
}

// ConstructionSubmit implements the /construction/submit endpoint.
func (c *constructionAPIService) ConstructionSubmit(
	_ context.Context,
	request *rTypes.ConstructionSubmitRequest,
) (*rTypes.TransactionIdentifierResponse, *rTypes.Error) {
	if !c.IsOnline() {
		return nil, errors.ErrEndpointNotSupportedInOfflineMode
	}

	transaction, rErr := unmarshallTransactionFromHexString(request.SignedTransaction)
	if rErr != nil {
		return nil, rErr
	}

	hashBytes, err := transaction.GetTransactionHash()
	if err != nil {
		return nil, errors.ErrTransactionHashFailed
	}

	hash := tools.SafeAddHexPrefix(hex.EncodeToString(hashBytes))
	log.Infof("Submitting transaction %s (hash %s) to node %s", transaction.GetTransactionID(),
		hash, transaction.GetNodeAccountIDs()[0])

	_, err = transaction.Execute(c.hederaClient)
	if err != nil {
		log.Errorf("Failed to execute transaction %s: %s", transaction.GetTransactionID(), err)
		return nil, errors.AddErrorDetails(
			errors.ErrTransactionSubmissionFailed,
			"reason",
			fmt.Sprintf("%s", err),
		)
	}

	return &rTypes.TransactionIdentifierResponse{
		TransactionIdentifier: &rTypes.TransactionIdentifier{Hash: hash},
	}, nil
}

func (c *constructionAPIService) getOperationSlice(operations []*rTypes.Operation) (
	types.OperationSlice,
	*rTypes.Error,
) {
	operationSlice := make(types.OperationSlice, 0, len(operations))
	for _, operation := range operations {
		var accountId types.AccountId
		if operation.Account != nil {
			var err error
			accountId, err = types.NewAccountIdFromString(operation.Account.Address, c.systemShard, c.systemRealm)
			if err != nil || accountId.IsZero() {
				return nil, errors.ErrInvalidAccount
			}
		}

		var amount types.Amount
		if operation.Amount != nil {
			var rErr *rTypes.Error
			if amount, rErr = types.NewAmount(operation.Amount); rErr != nil {
				return nil, rErr
			}
		}

		operationSlice = append(operationSlice, types.Operation{
			AccountId: accountId,
			Amount:    amount,
			Index:     operation.OperationIdentifier.Index,
			Metadata:  operation.Metadata,
			Type:      operation.Type,
		})
	}

	return operationSlice, nil
}

func (c *constructionAPIService) getRandomNodeAccountId() hedera.AccountID {
	index, err := rand.Int(rand.Reader, c.nodeAccountIdsLen)
	if err != nil {
		log.Errorf("Failed to get a random number, use 0 instead: %s", err)
		return c.nodeAccountIds[0]
	}

	return c.nodeAccountIds[index.Int64()]
}

func (c *constructionAPIService) getIntMetadataValue(metadata map[string]interface{}, metadataKey string) (int64, *rTypes.Error) {
	var metadataValue int64
	if metadata != nil && metadata[metadataKey] != nil {
		value, ok := metadata[metadataKey].(string)
		if !ok {
			return metadataValue, errors.ErrInvalidArgument
		}

		var err error
		if metadataValue, err = tools.ToInt64(value); err != nil || metadataValue < 0 {
			return metadataValue, errors.ErrInvalidArgument
		}
	}

	return metadataValue, nil
}

func isValidTransactionValidDuration(validDuration int64) bool {
	// A value of 0 indicates validDuration is unset
	return validDuration >= 0 && validDuration <= 180
}

// NewConstructionAPIService creates a new instance of a constructionAPIService.
func NewConstructionAPIService(
	baseService BaseService,
	network string,
	nodes config.NodeMap,
	systemShard int64,
	systemRealm int64,
	transactionConstructor construction.TransactionConstructor,
) (server.ConstructionAPIServicer, error) {
	var err error
	var hederaClient *hedera.Client

	// there is no live demo network, it's only used to run rosetta test, so replace it with testnet
	if network == "demo" {
		log.Info("Use testnet instead of demo")
		network = "testnet"
	}

	if len(nodes) > 0 {
		hederaClient = hedera.ClientForNetwork(nodes)
	} else if hederaClient, err = hedera.ClientForName(network); err != nil {
		return nil, err
	}

	// disable SDK auto retry
	hederaClient.SetMaxAttempts(1)

	networkMap := hederaClient.GetNetwork()
	nodeAccountIds := make([]hedera.AccountID, 0, len(networkMap))
	for _, nodeAccountId := range networkMap {
		nodeAccountIds = append(nodeAccountIds, nodeAccountId)
	}

	return &constructionAPIService{
		BaseService:        baseService,
		hederaClient:       hederaClient,
		nodeAccountIds:     nodeAccountIds,
		nodeAccountIdsLen:  big.NewInt(int64(len(nodeAccountIds))),
		systemShard:        systemShard,
		systemRealm:        systemRealm,
		transactionHandler: transactionConstructor,
	}, nil
}

func addSignature(transaction interfaces.Transaction, pubKey hedera.PublicKey, signature []byte) *rTypes.Error {
	switch tx := transaction.(type) {
	// these transaction types are what the construction service supports
	case *hedera.AccountCreateTransaction:
		tx.AddSignature(pubKey, signature)
	case *hedera.TokenAssociateTransaction:
		tx.AddSignature(pubKey, signature)
	case *hedera.TokenBurnTransaction:
		tx.AddSignature(pubKey, signature)
	case *hedera.TokenCreateTransaction:
		tx.AddSignature(pubKey, signature)
	case *hedera.TokenDeleteTransaction:
		tx.AddSignature(pubKey, signature)
	case *hedera.TokenDissociateTransaction:
		tx.AddSignature(pubKey, signature)
	case *hedera.TokenFreezeTransaction:
		tx.AddSignature(pubKey, signature)
	case *hedera.TokenGrantKycTransaction:
		tx.AddSignature(pubKey, signature)
	case *hedera.TokenMintTransaction:
		tx.AddSignature(pubKey, signature)
	case *hedera.TokenRevokeKycTransaction:
		tx.AddSignature(pubKey, signature)
	case *hedera.TokenUnfreezeTransaction:
		tx.AddSignature(pubKey, signature)
	case *hedera.TokenUpdateTransaction:
		tx.AddSignature(pubKey, signature)
	case *hedera.TokenWipeTransaction:
		tx.AddSignature(pubKey, signature)
	case *hedera.TransferTransaction:
		tx.AddSignature(pubKey, signature)
	default:
		return errors.ErrTransactionInvalidType
	}

	return nil
}

func getFrozenTransactionBodyBytes(transaction interfaces.Transaction) ([]byte, *rTypes.Error) {
	signedTransaction := services.SignedTransaction{}
	if err := prototext.Unmarshal([]byte(transaction.String()), &signedTransaction); err != nil {
		return nil, errors.ErrTransactionUnmarshallingFailed
	}

	return signedTransaction.BodyBytes, nil
}

func unmarshallTransactionFromHexString(transactionString string) (interfaces.Transaction, *rTypes.Error) {
	transactionBytes, err := hex.DecodeString(tools.SafeRemoveHexPrefix(transactionString))
	if err != nil {
		return nil, errors.ErrTransactionDecodeFailed
	}

	transaction, err := hedera.TransactionFromBytes(transactionBytes)
	if err != nil {
		return nil, errors.ErrTransactionUnmarshallingFailed
	}

	switch tx := transaction.(type) {
	// these transaction types are what the construction service supports
	case hedera.AccountCreateTransaction:
		return &tx, nil
	case hedera.TokenAssociateTransaction:
		return &tx, nil
	case hedera.TokenBurnTransaction:
		return &tx, nil
	case hedera.TokenCreateTransaction:
		return &tx, nil
	case hedera.TokenDeleteTransaction:
		return &tx, nil
	case hedera.TokenDissociateTransaction:
		return &tx, nil
	case hedera.TokenFreezeTransaction:
		return &tx, nil
	case hedera.TokenGrantKycTransaction:
		return &tx, nil
	case hedera.TokenMintTransaction:
		return &tx, nil
	case hedera.TokenRevokeKycTransaction:
		return &tx, nil
	case hedera.TokenUnfreezeTransaction:
		return &tx, nil
	case hedera.TokenUpdateTransaction:
		return &tx, nil
	case hedera.TokenWipeTransaction:
		return &tx, nil
	case hedera.TransferTransaction:
		return &tx, nil
	default:
		return nil, errors.ErrTransactionInvalidType
	}
}

type updater func(transaction interfaces.Transaction) *rTypes.Error

func updateTransaction(transaction interfaces.Transaction, updaters ...updater) *rTypes.Error {
	for _, updater := range updaters {
		if err := updater(transaction); err != nil {
			return err
		}
	}
	return nil
}

func transactionSetNodeAccountId(nodeAccountId hedera.AccountID) updater {
	return func(transaction interfaces.Transaction) *rTypes.Error {
		if _, err := hedera.TransactionSetNodeAccountIDs(transaction, []hedera.AccountID{nodeAccountId}); err != nil {
			log.Errorf("Failed to set node account id for transaction: %s", err)
			return errors.ErrInternalServerError
		}
		return nil
	}
}

func transactionSetTransactionId(payer hedera.AccountID, validStartNanos int64) updater {
	return func(transaction interfaces.Transaction) *rTypes.Error {
		var transactionId hedera.TransactionID
		if validStartNanos == 0 {
			transactionId = hedera.TransactionIDGenerate(payer)
		} else {
			transactionId = hedera.NewTransactionIDWithValidStart(payer, time.Unix(0, validStartNanos))
		}
		if _, err := hedera.TransactionSetTransactionID(transaction, transactionId); err != nil {
			log.Errorf("Failed to set transaction id: %s", err)
			return errors.ErrInternalServerError
		}
		return nil
	}
}

func transactionSetValidDuration(validDurationSeconds int64) updater {
	return func(transaction interfaces.Transaction) *rTypes.Error {
		if validDurationSeconds == 0 {
			// Default to 180 seconds
			validDurationSeconds = defaultValidDurationSeconds
		}

		_, err := hedera.TransactionSetTransactionValidDuration(transaction, time.Second*time.Duration(validDurationSeconds))
		if err != nil {
			log.Errorf("Failed to set transaction valid duration: %s", err)
			return errors.ErrInternalServerError
		}
		return nil
	}
}

func transactionFreeze(transaction interfaces.Transaction) *rTypes.Error {
	var err error
	switch tx := transaction.(type) {
	// these transaction types are what the construction service supports
	case *hedera.AccountCreateTransaction:
		_, err = tx.Freeze()
	case *hedera.TokenAssociateTransaction:
		_, err = tx.Freeze()
	case *hedera.TokenBurnTransaction:
		_, err = tx.Freeze()
	case *hedera.TokenCreateTransaction:
		_, err = tx.Freeze()
	case *hedera.TokenDeleteTransaction:
		_, err = tx.Freeze()
	case *hedera.TokenDissociateTransaction:
		_, err = tx.Freeze()
	case *hedera.TokenFreezeTransaction:
		_, err = tx.Freeze()
	case *hedera.TokenGrantKycTransaction:
		_, err = tx.Freeze()
	case *hedera.TokenMintTransaction:
		_, err = tx.Freeze()
	case *hedera.TokenRevokeKycTransaction:
		_, err = tx.Freeze()
	case *hedera.TokenUnfreezeTransaction:
		_, err = tx.Freeze()
	case *hedera.TokenUpdateTransaction:
		_, err = tx.Freeze()
	case *hedera.TokenWipeTransaction:
		_, err = tx.Freeze()
	case *hedera.TransferTransaction:
		_, err = tx.Freeze()
	default:
		log.Error("Invalid transaction type")
		return errors.ErrTransactionInvalidType
	}

	if err != nil {
		log.Errorf("Failed to freeze transaction: %s", err)
		return errors.ErrTransactionFreezeFailed
	}
	return nil
}
