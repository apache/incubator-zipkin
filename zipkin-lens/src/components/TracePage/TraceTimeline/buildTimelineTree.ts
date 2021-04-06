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

/*
  In the timeline graph, a tree will be drawn.
  For example, a tree like the following.

    0 +---------- SERVICE_A
      |
    1 |--+------- SERVICE_B
      |  |
    2 |  |--+---- SERVICE_C
      |  |  |
    3 |  |  |---- SERVICE_D
      |  |
    4 |  |--+---- SERVICE_E
      |     |
    5 |     |---- SERVICE_F
      |
    6 |---------- SERVICE_G

  Since it is difficult to draw this tree directly
  from a span list, we first need to convert the span list
  to an intermediate state.
  `buildTimelineTree` function performs the conversion.
  The conversion is done as follows.

  First, index vertically like the following.

      0  1  2 <==== Index vertically.
      v  v  v
    0 +---------- SERVICE_A
      |
    1 |--+------- SERVICE_B
      |  |
    2 |  |--+---- SERVICE_C
      |  |  |
    3 |  |  |---- SERVICE_D
      |  |
    4 |  |--+---- SERVICE_E
      |     |
    5 |     |---- SERVICE_F
      |
    6 |---------- SERVICE_G  

  And consider this as a two-dimensional array.
  Each element contains the type of the line (BEGIN, MIDDLE, END).

  * "BEGIN" type is where the tree branch begins.
  * "END" type is where the tree branch ends.
  * "MIDDLE" type is a branch that is neither BEGIN and END.
  * If the branch does not exist, the element will be undefined.

  The above example will be converted to the following
  intermediate state.

    [
      [BEGIN,  undefined, undefined],  // SERVICE_A
      [MIDDLE, BEGIN,     undefined],  // SERVICE_B
      [MIDDLE, MIDDLE,    BEGIN    ],  // SERVICE_C
      [MIDDLE, MIDDLE,    END      ],  // SERVICE_D
      [MIDDLE, END,       BEGIN    ],  // SERVICE_E
      [MIDDLE, undefined, END      ],  // SERVICE_F
      [END,    undefined, undefined],  // SERVICE_G
    ]

  This is the return value of `buildTimelineTree` function.
*/

export type TreeElementType = 'BEGIN' | 'END' | 'MIDDLE';

export const getMinMaxDepth = (spans: { depth: number }[]) => {
  let minDepth = Number.POSITIVE_INFINITY;
  let maxDepth = Number.NEGATIVE_INFINITY;
  spans.forEach((span) => {
    minDepth = Math.min(span.depth, minDepth);
    maxDepth = Math.max(span.depth, maxDepth);
  });
  return [minDepth, maxDepth];
};

// To avoid allocation multiple times, create fixed size
// two dimensional array.
const buildMatrix = (height: number, width: number) => {
  const ret = new Array<(TreeElementType | undefined)[]>(height);
  for (let i = 0; i < height; i += 1) {
    ret[i] = new Array<TreeElementType | undefined>(width);
  }
  return ret;
};

const buildTimelineTree = (spans: { depth: number }[]) => {
  const [minDepth, maxDepth] = getMinMaxDepth(spans);
  const ret = buildMatrix(spans.length, maxDepth - minDepth);
  const _spans = spans.map(({ depth }) => ({ depth: depth - minDepth }));

  const stack: { index: number; depth: number }[] = [];
  for (let i = 0; i < _spans.length; i += 1) {
    const currentSpan = _spans[i];

    if (
      stack.length === 0 ||
      stack[stack.length - 1].depth < currentSpan.depth
    ) {
      stack.push({ index: i, depth: currentSpan.depth });
      continue;
    }

    if (stack[stack.length - 1].depth === currentSpan.depth) {
      stack.pop();
      stack.push({ index: i, depth: currentSpan.depth });
      continue;
    }

    //
    // A               parent
    // |
    // +-- B
    // |   |
    // |   +-- C
    // |   |    |
    //    ...
    // |        |
    // |        +-- D
    // |
    // |-- E           currentSpan
    //

    // Pop to parent (= A)
    const popped = [];
    for (let j = stack.length - 1; j >= 0; j -= 1) {
      if (stack[j].depth < currentSpan.depth) {
        break;
      }
      const t = stack[stack.length - 1];
      popped.push(t);
      stack.pop();
    }
    stack.push({ index: i, depth: currentSpan.depth });

    // Mark popped.
    for (let j = 0; j < popped.length - 1; j += 1) {
      const i1 = popped[j + 1].index;
      const i2 = popped[j].index;
      const d = popped[j + 1].depth;
      ret[i1][d] = 'BEGIN';
      ret[i2][d] = 'END';
      for (let k = i1 + 1; k < i2; k += 1) {
        ret[k][d] = 'MIDDLE';
      }
    }
  }

  //
  // A          Root span
  // |
  // +-- B
  //     |
  //    ...
  //     |
  //     |-- C
  //
  for (let j = 0; j < stack.length - 1; j += 1) {
    const i1 = stack[j].index;
    const i2 = stack[j + 1].index;
    const d = stack[j].depth;
    ret[i1][d] = 'BEGIN';
    ret[i2][d] = 'END';
    for (let k = i1 + 1; k < i2; k += 1) {
      ret[k][d] = 'MIDDLE';
    }
  }

  return ret;
};

export default buildTimelineTree;
