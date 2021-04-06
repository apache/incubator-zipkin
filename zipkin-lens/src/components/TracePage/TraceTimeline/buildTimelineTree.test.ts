/*
 * Copyright 2015-2021 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

import buildTimelineTree from './buildTimelineTree';

describe('buildTimelineTree', () => {
  it('should build a timeline tree (case 1)', () => {
    // 0 +---------- SERVICE_A
    //   |
    // 1 |--+------- SERVICE_B
    //   |  |
    // 2 |  |--+---- SERVICE_C
    //   |  |  |
    // 3 |  |  |---- SERVICE_D
    //   |  |
    // 4 |  |--+---- SERVICE_E
    //   |     |
    // 5 |     |---- SERVICE_F
    //   |
    // 6 |---------- SERVICE_G
    expect(
      buildTimelineTree([
        { depth: 0 }, // SERVICE_A
        { depth: 1 }, // SERVICE_B
        { depth: 2 }, // SERVICE_C
        { depth: 3 }, // SERVICE_D
        { depth: 2 }, // SERVICE_E
        { depth: 3 }, // SERVICE_F
        { depth: 1 }, // SERVICE_G
      ]),
    ).toEqual([
      ['BEGIN', undefined, undefined], // SERVICE_A
      ['MIDDLE', 'BEGIN', undefined], // SERVICE_B
      ['MIDDLE', 'MIDDLE', 'BEGIN'], // SERVICE_C
      ['MIDDLE', 'MIDDLE', 'END'], // SERVICE_D
      ['MIDDLE', 'END', 'BEGIN'], // SERVICE_E
      ['MIDDLE', undefined, 'END'], // SERVICE_F
      ['END', undefined, undefined], // SERVICE_G
    ]);
  });

  // Sometimes, users have multiple root traces..
  it('should build a timeline tree (case 2)', () => {
    // 0 +---------- SERVICE_A
    //   |
    // 1 |---------- SERVICE_B
    //
    // 2 +---------- SERVICE_C
    //   |
    // 3 |---------- SERVICE_D
    expect(
      buildTimelineTree([
        { depth: 0 }, // SERVICE_A
        { depth: 1 }, // SERVICE_B
        { depth: 0 }, // SERVICE_C
        { depth: 1 }, // SERVICE_D
      ]),
    ).toEqual([
      ['BEGIN'], // SERVICE_A
      ['END'], // SERVICE_B
      ['BEGIN'], // SERVICE_C
      ['END'], // SERVICE_D
    ]);
  });

  it('should build a timeline tree (case 3)', () => {
    // 0 +---------- SERVICE_A
    //   |
    // 1 |---------- SERVICE_B
    //   |
    // 2 +---------- SERVICE_C
    //   |
    // 3 |---------- SERVICE_D
    expect(
      buildTimelineTree([
        { depth: 0 }, // SERVICE_A
        { depth: 1 }, // SERVICE_B
        { depth: 1 }, // SERVICE_C
        { depth: 1 }, // SERVICE_D
      ]),
    ).toEqual([
      ['BEGIN'], // SERVICE_A
      ['MIDDLE'], // SERVICE_B
      ['MIDDLE'], // SERVICE_C
      ['END'], // SERVICE_D
    ]);
  });

  it('should build a timeline tree (case 4)', () => {
    // 0 +---------- SERVICE_A
    //   |
    // 1 |--+------- SERVICE_B
    //   |  |
    // 2 |  |------- SERVICE_C
    //   |  |
    // 3 |  |------- SERVICE_D
    //   |  |
    // 4 |  |------- SERVICE_E
    //   |
    // 5 |---------- SERVICE_F
    expect(
      buildTimelineTree([
        { depth: 0 }, // SERVICE_A
        { depth: 1 }, // SERVICE_B
        { depth: 2 }, // SERVICE_C
        { depth: 2 }, // SERVICE_D
        { depth: 2 }, // SERVICE_E
        { depth: 1 }, // SERVICE_F
      ]),
    ).toEqual([
      ['BEGIN', undefined], // SERVICE_A
      ['MIDDLE', 'BEGIN'], // SERVICE_B
      ['MIDDLE', 'MIDDLE'], // SERVICE_C
      ['MIDDLE', 'MIDDLE'], // SERVICE_D
      ['MIDDLE', 'END'], // SERVICE_E
      ['END', undefined], // SERVICE_F
    ]);
  });

  it('should build a timeline tree (case 5)', () => {
    // 0 +---------- SERVICE_A
    expect(
      buildTimelineTree([
        { depth: 0 }, // SERVICE_A
      ]),
    ).toEqual([
      [], // SERVICE_A
    ]);
  });

  // Sometimes, users have multiple root traces..
  it('should build a timeline tree (case 6)', () => {
    // 0 +---------- SERVICE_A
    // 0 +---------- SERVICE_B
    // 1 +---------- SERVICE_C
    expect(
      buildTimelineTree([
        { depth: 0 }, // SERVICE_A
        { depth: 0 }, // SERVICE_B
        { depth: 1 }, // SERVICE_C
      ]),
    ).toEqual([
      [undefined], // SERVICE_A
      ['BEGIN'], // SERVICE_B
      ['END'], // SERVICE_C
    ]);
  });
});
