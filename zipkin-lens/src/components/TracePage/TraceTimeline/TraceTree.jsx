/*
 * Copyright 2015-2020 The OpenZipkin Authors
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
import PropTypes from 'prop-types';
import React, { useMemo } from 'react';
import { makeStyles } from '@material-ui/styles';

import {
  spanBarLinePosY,
  spanToggleButtonTranslate,
  spanTreeLineWidthPercentPerDepth,
  spanToggleButtonLengthOfSide,
  serviceNameWidthPercent,
  spanTreeWidthPercent,
  serviceNameBadgeTranslate,
  serviceNameBadgeWidth,
  serviceNameBadgeHeight,
} from '../sizing';
import { detailedSpansPropTypes } from '../../../prop-types';
import { selectServiceColor } from '../../../constants/color';

export const buildTraceTree = (spans, childrenHiddenSpanIds) => {
  const stack = [];

  const horizontalLineDataList = [];
  const verticalLineDataList = [];
  const buttonDataList = [];
  const serviceNameDataList = [];

  for (let i = 0; i < spans.length; i += 1) {
    const currentSpan = spans[i];

    // This is a root span.
    if (stack.length === 0) {
      stack.push({ index: i, depth: currentSpan.depth });
      continue;
    }

    const stackTop = stack[stack.length - 1];

    // X  stackTop (parent)
    // |
    // |---Y  currentSpan
    if (stackTop.depth < currentSpan.depth) {
      const parent = stackTop;
      stack.push({ index: i, depth: currentSpan.depth });
      // X
      // |
      // |+++Y  <- Write this horizontal line.
      horizontalLineDataList.push({ x: parent.depth, y: i });
      serviceNameDataList.push({
        x: currentSpan.depth,
        y: i,
        serviceName: currentSpan.serviceName,
      });
      if (childrenHiddenSpanIds[currentSpan.spanId]) {
        buttonDataList.push({
          x: currentSpan.depth,
          y: i,
          spanId: currentSpan.spanId,
          isClosed: true,
        });
      }
      continue;
    }

    // X  parent
    // |
    // |---Y  stackTop
    // |
    // |---Z  currentSpan
    if (stackTop.depth === currentSpan.depth) {
      stack.pop();
      const parent = stack[stack.length - 1];
      stack.push({ index: i, depth: currentSpan.depth });
      // X
      // |
      // |---Y
      // |
      // |+++Z  <- Write this horizontal line.
      horizontalLineDataList.push({ x: parent.depth, y: i });
      serviceNameDataList.push({
        x: currentSpan.depth,
        y: i,
        serviceName: currentSpan.serviceName,
      });
      if (childrenHiddenSpanIds[currentSpan.spanId]) {
        buttonDataList.push({
          x: currentSpan.depth,
          y: i,
          spanId: currentSpan.spanId,
          isClosed: true,
        });
      }
      continue;
    }

    // A  parent
    // |
    // |---B
    // |   |
    // |   |---C  stackTop
    // |
    // |---D  currentSpan
    if (stackTop.depth > currentSpan.depth) {
      const popped = [];
      for (let j = stack.length - 1; j >= 0; j -= 1) {
        if (stack[j].depth < currentSpan.depth) {
          break;
        }
        popped.push(stack.pop());
      }
      const parent = stack[stack.length - 1];
      stack.push({ index: i, depth: currentSpan.depth });
      // A
      // |
      // |---B
      // |   |
      // |   |---C
      // |
      // |+++D  <- Write this horizontal line.
      horizontalLineDataList.push({ x: parent.depth, y: i });
      serviceNameDataList.push({
        x: currentSpan.depth,
        y: i,
        serviceName: currentSpan.serviceName,
      });
      if (childrenHiddenSpanIds[currentSpan.spanId]) {
        buttonDataList.push({
          x: currentSpan.depth,
          y: i,
          spanId: currentSpan.spanId,
          isClosed: true,
        });
      }
      for (let j = 0; j < popped.length - 1; j += 1) {
        // A
        // |
        // |---B
        // |   +      <- Write these vertical lines.
        // |   +---C
        // |
        // |---D
        verticalLineDataList.push({
          x: popped[j + 1].depth,
          y1: popped[j].index,
          y2: popped[j + 1].index,
        });
        buttonDataList.push({
          x: popped[j + 1].depth,
          y: popped[j + 1].index,
          spanId: spans[popped[j + 1].index].spanId,
        });
      }
      continue;
    }
  }
  // A++++  Root span. Write this horizontal line.
  // |
  // |---B
  horizontalLineDataList.push({ x: spans[0].depth, y: 0 });
  serviceNameDataList.push({
    x: spans[0].depth,
    y: 0,
    serviceName: spans[0].serviceName,
  });
  if (childrenHiddenSpanIds[spans[0].spanId]) {
    buttonDataList.push({
      x: spans[0].depth,
      y: 0,
      spanId: spans[0].spanId,
      isClosed: true,
    });
  }

  for (let j = 0; j < stack.length - 1; j += 1) {
    // A  Root span
    // +         <- Write these vertical lines.
    // +---B
    //     +
    //     +---C
    verticalLineDataList.push({
      x: stack[j].depth,
      y1: stack[j].index,
      y2: stack[j + 1].index,
    });
    buttonDataList.push({
      x: stack[j].depth,
      y: stack[j].index,
      spanId: spans[stack[j].index].spanId,
    });
  }

  return {
    horizontalLineDataList,
    verticalLineDataList,
    buttonDataList,
    serviceNameDataList,
  };
};

const propTypes = {
  spans: detailedSpansPropTypes.isRequired,
  depth: PropTypes.number.isRequired,
  childrenHiddenSpanIds: PropTypes.shape({}).isRequired,
  onChildrenToggle: PropTypes.func.isRequired,
};

const useStyles = makeStyles((theme) => ({
  line: {
    stroke: theme.palette.grey[300],
    strokeWidth: '1px',
  },
  spanToggleButton: {
    cursor: 'pointer',
    opacity: 0,
    backgroundColor: theme.palette.grey[300],
    '&:hover': {
      opacity: 0.1,
    },
  },
  buttonIcon: {
    stroke: theme.palette.grey[800],
    strokeWidth: 2,
  },
  spanToggleButtonInternal: {
    stroke: theme.palette.grey[700],
    strokeWidth: 1,
    fill: theme.palette.common.white,
  },
  serviceBadgeText: {
    textTransform: 'uppercase',
  },
}));

const TraceTree = React.memo(
  ({ spans, depth, childrenHiddenSpanIds, onChildrenToggle }) => {
    const classes = useStyles();
    const {
      horizontalLineDataList,
      verticalLineDataList,
      buttonDataList,
      serviceNameDataList,
    } = useMemo(() => buildTraceTree(spans, childrenHiddenSpanIds), [
      spans,
      childrenHiddenSpanIds,
    ]);

    return (
      <g>
        {horizontalLineDataList.map(({ x, y }) => (
          <line
            key={`${x}-${y}`}
            x1={`${x * spanTreeLineWidthPercentPerDepth(depth)}%`}
            x2={`${spanTreeWidthPercent + serviceNameWidthPercent}%`}
            y1={spanBarLinePosY(y)}
            y2={spanBarLinePosY(y)}
            className={classes.line}
          />
        ))}
        {serviceNameDataList.map(({ x, y, serviceName }) => (
          <g transform={serviceNameBadgeTranslate} key={`${x}-${y}`}>
            <svg
              x={`${x * spanTreeLineWidthPercentPerDepth(depth)}%`}
              y={spanBarLinePosY(y)}
              width={`${serviceNameBadgeWidth}%`}
              height={serviceNameBadgeHeight}
            >
              <rect
                rx={3}
                ry={3}
                x={0}
                y={0}
                width="100%"
                height="100%"
                fill={selectServiceColor(serviceName)}
              />
              <text
                x="50%"
                y="50%"
                textAnchor="middle"
                dominantBaseline="central"
                fill="white"
                className={classes.serviceBadgeText}
              >
                {serviceName}
                <title>
                  {
                    // This adds a tooltip to service name. This is useful for viewing large service
                    // names. https://developer.mozilla.org/en-US/docs/Web/SVG/Element/title
                    serviceName
                  }
                </title>
              </text>
            </svg>
          </g>
        ))}
        {verticalLineDataList.map(({ x, y1, y2 }) => (
          <line
            key={`${x}-${y1}-${y2}`}
            x1={`${x * spanTreeLineWidthPercentPerDepth(depth)}%`}
            x2={`${x * spanTreeLineWidthPercentPerDepth(depth)}%`}
            y1={spanBarLinePosY(y1)}
            y2={spanBarLinePosY(y2)}
            className={classes.line}
          />
        ))}
        {buttonDataList.map(({ x, y, spanId, isClosed }) => (
          <g key={spanId} transform={spanToggleButtonTranslate}>
            <rect
              rx={2}
              ry={2}
              x={`${x * spanTreeLineWidthPercentPerDepth(depth)}%`}
              y={spanBarLinePosY(y)}
              width={spanToggleButtonLengthOfSide}
              height={spanToggleButtonLengthOfSide}
              className={classes.spanToggleButtonInternal}
            />
            <svg
              x={`${x * spanTreeLineWidthPercentPerDepth(depth)}%`}
              y={spanBarLinePosY(y)}
              className={classes.buttonIcon}
            >
              <line
                x1={0}
                x2={spanToggleButtonLengthOfSide}
                y1={spanToggleButtonLengthOfSide / 2}
                y2={spanToggleButtonLengthOfSide / 2}
              />
              {isClosed ? (
                <line
                  x1={spanToggleButtonLengthOfSide / 2}
                  x2={spanToggleButtonLengthOfSide / 2}
                  y1={0}
                  y2={spanToggleButtonLengthOfSide}
                />
              ) : null}
            </svg>
            <rect
              rx={2}
              ry={2}
              x={`${x * spanTreeLineWidthPercentPerDepth(depth)}%`}
              y={spanBarLinePosY(y)}
              width={spanToggleButtonLengthOfSide}
              height={spanToggleButtonLengthOfSide}
              onClick={() => onChildrenToggle(spanId)}
              className={classes.spanToggleButton}
            />
          </g>
        ))}
      </g>
    );
  },
);

TraceTree.propTypes = propTypes;

export default TraceTree;
