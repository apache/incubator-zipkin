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

import { Box, createStyles, makeStyles } from '@material-ui/core';
import classNames from 'classnames';
import React from 'react';

import { formatDuration } from '../../../util/timestamp';

const useStyles = makeStyles(() =>
  createStyles({
    label: {
      fontSize: '0.8rem',
      left: '4px',
      position: 'absolute',
    },
    'label--last': {
      left: 'initial',
      right: '4px',
    },
  }),
);

interface TimeMarkerProps {
  endTs: number;
  startTs: number;
  treeWidthPercent: number;
}

const numTimeMarkers = 4;

const TimeMarker = React.memo<TimeMarkerProps>(
  ({ endTs, startTs, treeWidthPercent }) => {
    const classes = useStyles();
    const timeMarkers = [];

    for (let i = 0; i < numTimeMarkers; i += 1) {
      const label = (i / (numTimeMarkers - 1)) * (endTs - startTs);
      const portion = i / (numTimeMarkers - 1);

      timeMarkers.push(
        <Box
          key={portion}
          position="absolute"
          height="100%"
          width="1px"
          bgcolor="grey.300"
          style={{ left: `${portion * 100}%` }}
          data-testid="TimeMarker-marker"
        >
          <Box
            component="span"
            position="absolute"
            className={classNames(classes.label, {
              [classes['label--last']]: portion >= 1,
            })}
            data-testid="TimeMarker-label"
          >
            {formatDuration(label)}
          </Box>
        </Box>,
      );
    }
    return (
      <Box display="flex" pl={2} pr={2}>
        <Box
          mt={1}
          height={15}
          position="relative"
          marginLeft={`${treeWidthPercent}%`}
          width={`${100 - treeWidthPercent}%`}
        >
          {timeMarkers}
        </Box>
      </Box>
    );
  },
);

export default TimeMarker;
