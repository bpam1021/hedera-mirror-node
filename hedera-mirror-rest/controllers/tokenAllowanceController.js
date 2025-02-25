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
  response: {
    limit: {default: defaultLimit},
  },
} = require('../config');
const constants = require('../constants');
const utils = require('../utils');

const BaseController = require('./baseController');
const Bound = require('./bound');

const {EntityService, TokenAllowanceService} = require('../service');
const {TokenAllowanceViewModel} = require('../viewmodel');

class TokenAllowanceController extends BaseController {
  /**
   * Extracts multiple queries to be combined in union.
   *
   * @param {[]} filters req filters
   * @param {BigInt} ownerAccountId Encoded owner entityId
   * @returns {{bounds: {string: Bound}, lower: *[], inner: *[], upper: *[],
   *  accountId: BigInt, order: 'asc'|'desc', limit: number}}
   */
  extractTokenMultiUnionQuery(filters, ownerAccountId) {
    const bounds = {
      primary: new Bound(constants.filterKeys.SPENDER_ID, 'spender'),
      secondary: new Bound(constants.filterKeys.TOKEN_ID, 'token_id'),
    };
    let limit = defaultLimit;
    let order = constants.orderFilterValues.ASC;

    for (const filter of filters) {
      switch (filter.key) {
        case constants.filterKeys.SPENDER_ID:
          bounds.primary.parse(filter);
          break;
        case constants.filterKeys.TOKEN_ID:
          bounds.secondary.parse(filter);
          break;
        case constants.filterKeys.LIMIT:
          limit = filter.value;
          break;
        case constants.filterKeys.ORDER:
          order = filter.value;
          break;
        default:
          break;
      }
    }

    this.validateBounds(bounds);

    return {
      bounds,
      lower: this.getLowerFilters(bounds),
      inner: this.getInnerFilters(bounds),
      upper: this.getUpperFilters(bounds),
      order,
      ownerAccountId,
      limit,
    };
  }

  /**
   * Handler function for /accounts/:idOrAliasOrEvmAddress/allowances/tokens API
   * @param {Request} req HTTP request object
   * @param {Response} res HTTP response object
   * @returns {Promise<void>}
   */
  getAccountTokenAllowances = async (req, res) => {
    const accountId = await EntityService.getEncodedId(req.params[constants.filterKeys.ID_OR_ALIAS_OR_EVM_ADDRESS]);
    const filters = utils.buildAndValidateFilters(req.query);
    const query = this.extractTokenMultiUnionQuery(filters, accountId);
    const tokenAllowances = await TokenAllowanceService.getAccountTokenAllowances(query);
    const allowances = tokenAllowances.map((model) => new TokenAllowanceViewModel(model));

    res.locals[constants.responseDataLabel] = {
      allowances,
      links: {
        next: this.getPaginationLink(req, allowances, query.bounds, query.limit, query.order),
      },
    };
  };
}

module.exports = new TokenAllowanceController();
