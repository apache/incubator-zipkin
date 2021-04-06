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

import { Box, Button, ButtonGroup, IconButton } from '@material-ui/core';
import FullscreenExitIcon from '@material-ui/icons/FullscreenExit';
import FullscreenIcon from '@material-ui/icons/Fullscreen';
import React, { useCallback } from 'react';

import TimeMarker from './TimeMarker';

interface TraceTimelineHeaderProps {
  collapseAll: () => void;
  endTs: number;
  expandAll: () => void;
  openSpanDetail: boolean;
  reroot: (spanId?: string) => void;
  rootSpanId?: string;
  startTs: number;
  toggleOpenSpanDetail: () => void;
  treeWidthPercent: number;
}

const TraceTimelineHeader = React.memo<TraceTimelineHeaderProps>(
  ({
    collapseAll,
    endTs,
    expandAll,
    openSpanDetail,
    reroot,
    rootSpanId,
    startTs,
    toggleOpenSpanDetail,
    treeWidthPercent,
  }) => {
    const handleResetRootButtonClick = useCallback(() => {
      reroot(undefined);
    }, [reroot]);

    return (
      <Box bgcolor="background.paper" boxShadow={1} flexShrink={0}>
        <Box
          display="flex"
          justifyContent="space-between"
          pt={2}
          pl={2}
          pr={2}
          pb={1}
        >
          <ButtonGroup size="small" variant="outlined">
            <Button onClick={collapseAll}>Collapse</Button>
            <Button onClick={expandAll}>Expand</Button>
          </ButtonGroup>
          <Box display="flex">
            {rootSpanId && (
              <Box mr={1}>
                <Button
                  size="small"
                  variant="outlined"
                  onClick={handleResetRootButtonClick}
                >
                  Reset root
                </Button>
              </Box>
            )}
            <IconButton size="small" onClick={toggleOpenSpanDetail}>
              {openSpanDetail ? <FullscreenIcon /> : <FullscreenExitIcon />}{' '}
            </IconButton>
          </Box>
        </Box>
        <TimeMarker
          endTs={endTs}
          startTs={startTs}
          treeWidthPercent={treeWidthPercent}
        />
      </Box>
    );
  },
);

export default TraceTimelineHeader;
