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

const os = require('os');

let jobs = Math.max(os.cpus().length / 2, 1);
if (process.env.CI) {
  jobs = 2;
}

module.exports = {
  color: true,
  'fail-zero': true,
  'forbid-only': true,
  jobs,
  // 'node-option': ['unhandled-rejections=strict'], // without leading "--", also V8 flags
  package: './package.json',
  parallel: true,
  recursive: true,
  reporter: 'spec',
  // 'reporter-option': ['foo=bar', 'baz=quux'], // array, not object
  require: ['ts-node/register', '__tests__/mocha.setup.js'],
  // require: ['chai/register-expect.js'],
  slow: '75',
  // spec: ['__tests__/**/*.test.js'], // the positional arguments!
  timeout: '2000', // same as "timeout: '2s'"
  // timeout: false, // same as "timeout: 0"
  // 'trace-warnings': true, // node flags ok
  ui: 'bdd',
  // 'v8-stack-trace-limit': 100, // V8 flags are prepended with "v8-"
  // watch: false,
};
