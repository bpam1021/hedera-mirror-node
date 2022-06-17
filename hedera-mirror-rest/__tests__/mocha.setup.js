import chai from 'chai';
import {jestSnapshotPlugin} from 'mocha-chai-jest-snapshot';

global.expect = chai.expect;

export const mochaHooks = {
  beforeAll() {
    chai.use(jestSnapshotPlugin());
    console.log('Installed jest snapshot plugin');
  },
};
