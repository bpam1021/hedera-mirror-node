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

const RecordFile = require('../model/recordFile');
const BaseController = require('./baseController');
const {RecordFileService} = require('../service');
const {BlockViewModel} = require('../viewmodel');
const utils = require('../utils');
const constants = require('../constants');
const {NotFoundError} = require('../errors/notFoundError');
const {InvalidArgumentError} = require('../errors/invalidArgumentError');
const {
  response: {
    limit: {default: defaultLimit, max: maxLimit},
  },
} = require('../config');

const validateHashOrNumber = (hashOrNumber) => {
  if (utils.isValidBlockHash(hashOrNumber)) {
    return {hash: hashOrNumber.replace('0x', ''), number: null};
  }

  if (utils.isPositiveLong(hashOrNumber, true)) {
    return {hash: null, number: hashOrNumber};
  }

  throw InvalidArgumentError.forParams(constants.filterKeys.HASH_OR_NUMBER);
};

class BlockController extends BaseController {
  extractOrderFromFilters = (filters) => {
    const order = _.findLast(filters, {key: constants.filterKeys.ORDER});

    return order ? constants.orderFilterValues[order.value.toUpperCase()] : constants.orderFilterValues.DESC;
  };

  extractLimitFromFilters = (filters) => {
    const limit = _.findLast(filters, {key: constants.filterKeys.LIMIT});

    return limit ? (limit.value > maxLimit ? defaultLimit : limit.value) : defaultLimit;
  };

  getFilterWhereCondition = (key, filter) => {
    return {
      query: `${key} ${filter.operator}`,
      param: filter.value,
    };
  };

  extractSqlFromBlockFilters = (filters) => {
    const filterQuery = {
      order: this.extractOrderFromFilters(filters),
      limit: this.extractLimitFromFilters(filters),
      whereQuery: [],
    };

    if (filters && filters.length === 0) {
      return filterQuery;
    }

    filterQuery.whereQuery = filters
      .filter((f) => [constants.filterKeys.BLOCK_NUMBER, constants.filterKeys.TIMESTAMP].includes(f.key))
      .map((f) => {
        switch (f.key) {
          case constants.filterKeys.BLOCK_NUMBER:
            return this.getFilterWhereCondition(RecordFile.INDEX, f);

          case constants.filterKeys.TIMESTAMP:
            return this.getFilterWhereCondition(RecordFile.CONSENSUS_END, f);
        }
      });

    return filterQuery;
  };

  generateNextLink = (req, blocks, filters) => {
    return blocks.length
      ? utils.getPaginationLink(
          req,
          blocks.length !== filters.limit,
          {[constants.filterKeys.BLOCK_NUMBER]: _.last(blocks).index},
          filters.order
        )
      : null;
  };

  getBlocks = async (req, res) => {
    const filters = utils.buildAndValidateFilters(req.query);
    const formattedFilters = this.extractSqlFromBlockFilters(filters);
    const blocks = await RecordFileService.getBlocks(formattedFilters);

    res.locals[constants.responseDataLabel] = {
      blocks: blocks.map((model) => new BlockViewModel(model)),
      links: {
        next: this.generateNextLink(req, blocks, formattedFilters),
      },
    };
  };

  getByHashOrNumber = async (req, res) => {
    const {hash, number} = validateHashOrNumber(req.params.hashOrNumber);
    const block = await RecordFileService.getByHashOrNumber(hash, number);

    if (!block) {
      throw new NotFoundError();
    }

    res.locals[constants.responseDataLabel] = new BlockViewModel(block);
  };
}

module.exports = new BlockController();
