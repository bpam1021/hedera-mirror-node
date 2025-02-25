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

'use strict';

const _ = require('lodash');
const {
  network: {unreleasedSupplyAccounts: defaultUnreleasedSupplyAccounts},
} = require('../config');
const constants = require('../constants');
const utils = require('../utils');

const BaseController = require('./baseController');

const {AddressBookEntry, FileData} = require('../model');
const {FileDataService, NetworkNodeService} = require('../service');
const {
  ExchangeRateSetViewModel,
  NetworkNodeViewModel,
  NetworkSupplyViewModel,
  FeeScheduleViewModel,
} = require('../viewmodel');

// errors
const {InvalidArgumentError} = require('../errors/invalidArgumentError');

const entityId = require('../entityId');
const {NotFoundError} = require('../errors/notFoundError');

const networkNodesDefaultSize = 10;
const networkNodesMaxSize = 25;

class NetworkController extends BaseController {
  static totalSupply = 5000000000000000000n;
  static unreleasedSupplyAccounts = defaultUnreleasedSupplyAccounts.map((a) => entityId.parse(a).getEncodedId());

  /**
   * Extracts SQL where conditions, params, order, and limit
   *
   * @param {[]} filters parsed and validated filters
   */
  extractNetworkNodesQuery = (filters) => {
    let limit = networkNodesDefaultSize;
    let order = constants.orderFilterValues.ASC;
    let fileId = '102'; // default fileId for mirror node
    const startPosition = 2; // 1st index is reserved for fileId
    const conditions = [];
    const params = [];
    const nodeInValues = [];
    let fileIdSpecified = false;

    for (const filter of filters) {
      if (_.isNil(filter)) {
        continue;
      }

      switch (filter.key) {
        case constants.filterKeys.FILE_ID:
          if (fileIdSpecified) {
            throw new InvalidArgumentError(`Only a single instance is supported for ${constants.filterKeys.FILE_ID}`);
          }
          if (utils.opsMap.eq !== filter.operator) {
            throw new InvalidArgumentError(
              `Only equals (eq) comparison operator is supported for ${constants.filterKeys.FILE_ID}`
            );
          }
          fileId = filter.value;
          fileIdSpecified = true;
          break;
        case constants.filterKeys.NODE_ID:
          this.updateConditionsAndParamsWithInValues(
            filter,
            nodeInValues,
            params,
            conditions,
            AddressBookEntry.getFullName(AddressBookEntry.NODE_ID),
            startPosition + conditions.length
          );
          break;
        case constants.filterKeys.LIMIT:
          // response per address book node can be large so a reduced limit is enforced
          if (filter.value > networkNodesMaxSize) {
            throw new InvalidArgumentError(
              `Max value of ${networkNodesMaxSize} is supported for ${constants.filterKeys.LIMIT}`
            );
          }
          limit = filter.value;
          break;
        case constants.filterKeys.ORDER:
          order = filter.value;
          break;
        default:
          break;
      }
    }

    this.updateQueryFiltersWithInValues(
      params,
      conditions,
      nodeInValues,
      AddressBookEntry.getFullName(AddressBookEntry.NODE_ID),
      params.length + startPosition
    );

    return {
      conditions,
      params: [fileId].concat(params),
      order,
      limit,
    };
  };

  extractFileDataQuery = (filters) => {
    // get the latest rate only. Since logic pulls most recent items order and limit are omitted in filterQuery
    const filterQuery = {
      whereQuery: [],
    };
    let order = constants.orderFilterValues.ASC;

    for (const filter of filters) {
      if (_.isNil(filter)) {
        continue;
      }

      if (filter.key === constants.filterKeys.TIMESTAMP) {
        if (utils.opsMap.ne === filter.operator) {
          throw new InvalidArgumentError(
            `Not equals (ne) operator is not supported for ${constants.filterKeys.TIMESTAMP}`
          );
        }

        // to ensure most recent occurrence is found convert eq to lte
        if (utils.opsMap.eq === filter.operator) {
          filter.operator = utils.opsMap.lte;
        }

        filterQuery.whereQuery.push(FileDataService.getFilterWhereCondition(FileData.CONSENSUS_TIMESTAMP, filter));
      }

      if (filter.key === constants.filterKeys.ORDER) {
        order = filter.value;
      }
    }

    return {filterQuery, order};
  };

  /**
   * Handler function for /network/exchangerate API
   * @param {Request} req HTTP request object
   * @param {Response} res HTTP response object
   * @returns {Promise<void>}
   */
  getExchangeRate = async (req, res) => {
    // extract filters from query param
    const filters = utils.buildAndValidateFilters(req.query);

    const {filterQuery} = this.extractFileDataQuery(filters);

    const exchangeRate = await FileDataService.getExchangeRate(filterQuery);

    if (_.isNil(exchangeRate)) {
      throw new NotFoundError('Not found');
    }

    res.locals[constants.responseDataLabel] = new ExchangeRateSetViewModel(exchangeRate);
  };

  /**
   * Handler function for /network/nodes API
   * @param {Request} req HTTP request object
   * @param {Response} res HTTP response object
   * @returns {Promise<void>}
   */
  getNetworkNodes = async (req, res) => {
    // extract filters from query param
    const filters = utils.buildAndValidateFilters(req.query);

    const {conditions, params, order, limit} = this.extractNetworkNodesQuery(filters);
    const nodes = await NetworkNodeService.getNetworkNodes(conditions, params, order, limit);

    const response = {
      nodes: nodes.map((node) => new NetworkNodeViewModel(node)),
      links: {
        next: null,
      },
    };

    if (response.nodes.length === limit) {
      const lastRow = _.last(response.nodes);
      const last = {
        [constants.filterKeys.NODE_ID]: lastRow.node_id,
      };
      response.links.next = utils.getPaginationLink(req, false, last, order);
    }

    res.locals[constants.responseDataLabel] = response;
  };

  /**
   * Handler function for /network/supply API.
   * @param {Request} req HTTP request object
   * @param {Response} res HTTP response object
   * @return {Promise<void>}
   */
  getSupply = async (req, res) => {
    utils.validateReq(req);
    const [tsQuery, tsParams] = utils.parseTimestampQueryParam(req.query, 'abf.consensus_timestamp', {
      [utils.opsMap.eq]: utils.opsMap.lte,
    });

    const sqlQuery = `
      select sum(balance) as unreleased_supply, max(consensus_timestamp) as consensus_timestamp
      from account_balance
      where consensus_timestamp = (
        select max(consensus_timestamp)
        from account_balance_file abf
        where ${tsQuery !== '' ? tsQuery : '1=1'}
      )
        and account_id in (${NetworkController.unreleasedSupplyAccounts});`;

    const query = utils.convertMySqlStyleQueryToPostgres(sqlQuery);

    if (logger.isTraceEnabled()) {
      logger.trace(`getSupply query: ${query} ${utils.JSONStringify(tsParams)}`);
    }

    const {rows} = await pool.queryQuietly(query, tsParams);
    if (rows.length !== 1 || !rows[0].consensus_timestamp) {
      throw new NotFoundError('Not found');
    }

    res.locals[constants.responseDataLabel] = new NetworkSupplyViewModel(rows[0], NetworkController.totalSupply);
    logger.debug(`getSupply returning ${rows.length} entries`);
  };

  /**
   * Handler function for /network/fees API.
   * @param {Request} req HTTP request object
   * @param {Response} res HTTP response object
   * @return {Promise<void>}
   */
  getFees = async (req, res) => {
    const filters = utils.buildAndValidateFilters(req.query);
    const {filterQuery, order} = this.extractFileDataQuery(filters);

    const [exchangeRate, feeSchedule] = await Promise.all([
      FileDataService.getExchangeRate(filterQuery),
      FileDataService.getFeeSchedule(filterQuery),
    ]);

    if (_.isNil(exchangeRate) || _.isNil(feeSchedule)) {
      throw new NotFoundError('Not found');
    }

    res.locals[constants.responseDataLabel] = new FeeScheduleViewModel(feeSchedule, exchangeRate, order);
  };
}

module.exports = new NetworkController();
