/*
 * Copyright 2015-2019 The OpenZipkin Authors
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
import React, { useMemo, useCallback } from 'react';
import classnames from 'classnames';
import { makeStyles } from '@material-ui/styles';

import {
  spanBarRowOffsetY,
  spanBarOffsetY,
  spanBarRowHeight,
  spanBarWidthPercent,
  spanBarOffsetXPercent,
  spanTreeWidthPercent,
  serviceNameWidthPercent,
  spanBarLinePosY,
  spanBarHeight,
} from '../sizing';
import { detailedSpanPropTypes } from '../../../prop-types';
import { selectColorByErrorType } from '../../../colors';

const minWidth = 0.25;

const propTypes = {
  span: detailedSpanPropTypes.isRequired,
  index: PropTypes.number.isRequired,
  onRowClick: PropTypes.func.isRequired,
  isFocused: PropTypes.bool.isRequired,
  startTs: PropTypes.number.isRequired,
  endTs: PropTypes.number.isRequired,
};

const useStyles = makeStyles(theme => ({
  line: {
    stroke: theme.palette.grey[400],
    strokeWidth: '1px',
  },
  bar: {
    opacity: 0.8,
  },
  row: {
    opacity: 0,
    fill: theme.palette.grey[500],
    cursor: 'pointer',
    '&:hover': {
      opacity: 0.2,
    },
  },
  'row--focused': {
    opacity: 0.3,
  },
  text: {
    fontSize: '1.03rem',
  },
}));

const calculateLeftAndWidth = (startTs, endTs, spanDuration, spanTimestamp) => {
  const duration = endTs - startTs;
  if (spanDuration) {
    return {
      width: Math.max(spanDuration / duration * 100, minWidth),
      // If the span's timestamp is not set, it is impossible to determine
      // where the span should be placed.
      // So set 0 value as a dummy value.
      left: spanTimestamp ? (spanTimestamp - startTs) / duration * 100 : 0,
    };
  }
  // If duration is 0, it can be considered that the trace graph contains
  // only single span.
  // In that case, width should be 100% and left should be 0%.
  if (duration === 0) {
    return {
      width: 100,
      left: 0,
    };
  }
  // Even if the span doesn't have duration, minWidth should be given to the span
  // as its width to display the span bar in UI.
  return {
    width: minWidth,
    // If the span's timestamp is not set, it is impossible to determine
    // where the span should be placed.
    // So set 0 value as a dummy value.
    left: spanTimestamp ? (spanTimestamp - startTs) / duration * 100 : 0,
  };
};

const TraceTimelineRow = React.memo(({
  span,
  index,
  onRowClick,
  isFocused,
  startTs,
  endTs,
}) => {
  const classes = useStyles();
  const { left, width } = useMemo(
    () => calculateLeftAndWidth(startTs, endTs, span.duration, span.timestamp),
    [startTs, endTs, span.duration, span.timestamp],
  );
  const handleClick = useCallback(() => onRowClick(span.spanId), [onRowClick, span.spanId]);
  const durationStr = span.durationStr ? `[${span.durationStr}]` : '';
  const isTextLeft = useMemo(() => {
    if (span.timestamp) {
      return endTs - span.timestamp > (endTs - startTs) / 2;
    }
    return true;
  }, [startTs, endTs, span.timestamp]);

  return (
    <g>
      <line
        x1={`${spanTreeWidthPercent + serviceNameWidthPercent}%`}
        x2="100%"
        y1={spanBarLinePosY(index)}
        y2={spanBarLinePosY(index)}
        className={classes.line}
      />
      <rect
        width={`${spanBarWidthPercent(width)}%`}
        height={spanBarHeight}
        x={`${spanBarOffsetXPercent(left)}%`}
        y={spanBarOffsetY(index)}
        rx={4}
        ry={4}
        className={classes.bar}
        style={{
          fill: selectColorByErrorType(span.errorType),
        }}
      />
      {
        isTextLeft ? (
          <text
            x={`${spanBarOffsetXPercent(left) + 1}%`}
            y={spanBarOffsetY(index) + spanBarRowHeight / 2}
            className={classes.text}
          >
            {`${span.spanName} ${durationStr}`}
          </text>
        ) : (
          <text
            x={`${spanBarOffsetXPercent(left) + spanBarWidthPercent(width) - 1}%`}
            y={spanBarOffsetY(index) + spanBarRowHeight / 2}
            textAnchor="end"
            className={classes.text}
          >
            {`${span.spanName} ${durationStr}`}
          </text>
        )
      }
      <rect
        className={classnames(classes.row, { [classes['row--focused']]: isFocused })}
        x={0}
        y={spanBarRowOffsetY(index)}
        width="100%"
        height={spanBarRowHeight}
        onClick={handleClick}
      />
    </g>
  );
});

TraceTimelineRow.propTypes = propTypes;

export default TraceTimelineRow;
