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

const DbErrorMessage = 'Unable to connect to database. Please retry later';

class DbError extends Error {
  constructor(errorMessage) {
    super();
    this.message = DbErrorMessage;
    this.dbErrorMessage = errorMessage;
    this.isConnectionError = this.isDbConnectionError(errorMessage);
  }

  /**
   * Match known db error connection messages
   * @param errorMessage
   * @returns {boolean}
   */
  isDbConnectionError(errorMessage) {
    return (
      /ECONNREFUSED/.test(errorMessage) ||
      /Connection terminated unexpectedly/.test(errorMessage) ||
      /unable to read data from DB/.test(errorMessage)
    );
  }
}

module.exports = {
  DbError,
};
