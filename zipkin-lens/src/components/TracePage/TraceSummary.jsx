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
import React, { useState, useCallback, useMemo } from 'react';
import Box from '@material-ui/core/Box';
import { AutoSizer } from 'react-virtualized';
import minBy from 'lodash/minBy';

import TraceSummaryHeader from './TraceSummaryHeader';
import TraceTimeline from './TraceTimeline';
import TraceTimelineHeader from './TraceTimelineHeader';
import SpanDetail from './SpanDetail';
import { detailedTraceSummaryPropTypes } from '../../prop-types';
import { hasRootSpan } from '../../util/trace';

const findSpanIndex = (spans, spanId) =>
  spans.findIndex((span) => span.spanId === spanId);

const propTypes = {
  traceSummary: detailedTraceSummaryPropTypes.isRequired,
};

const TraceSummary = React.memo(({ traceSummary }) => {
  const isRootedTrace = hasRootSpan(traceSummary.spans);
  const [rootSpanIndex, setRootSpanIndex] = useState(0);
  const isRerooted = rootSpanIndex !== 0;
  const [currentSpanIndex, setCurrentSpanIndex] = useState(0);
  const [childrenHiddenSpanIndices, setChildrenHiddenSpanIndices] = useState(
    {},
  );
  const [isSpanDetailOpened, setIsSpanDetailOpened] = useState(true);
  const traceTimelineWidthPercent = isSpanDetailOpened ? 60 : 100;

  const handleChildrenToggle = useCallback(
    (spanId) => {
      const spanIndex = findSpanIndex(traceSummary.spans, spanId);
      setChildrenHiddenSpanIndices((prev) => ({
        ...prev,
        [spanIndex]: !prev[spanIndex],
      }));
    },
    [traceSummary.spans],
  );

  const handleResetRerootButtonClick = useCallback(() => {
    setRootSpanIndex(0);
  }, []);

  const handleTimelineRowClick = useCallback(
    (spanId) => {
      const idx = traceSummary.spans.findIndex(
        (span) => span.spanId === spanId,
      );
      if (isRootedTrace && currentSpanIndex === idx) {
        if (rootSpanIndex === idx) {
          setRootSpanIndex(0);
        } else {
          setRootSpanIndex(idx);
        }
      }
      setCurrentSpanIndex(idx);
      setIsSpanDetailOpened(true);
    },
    [currentSpanIndex, isRootedTrace, traceSummary.spans, rootSpanIndex],
  );

  const rerootedTree = useMemo(() => {
    // If the trace does not have a root span, the trace is not filtered anymore
    // and the entire trace should be displayed.
    if (!isRootedTrace) {
      return traceSummary.spans;
    }

    const rootSpan = traceSummary.spans[rootSpanIndex];
    const spans = [rootSpan];
    for (let i = rootSpanIndex + 1; i < traceSummary.spans.length; i += 1) {
      const span = traceSummary.spans[i];
      if (span.depth <= rootSpan.depth) {
        break;
      }
      spans.push(span);
    }
    return spans;
  }, [isRootedTrace, rootSpanIndex, traceSummary.spans]);

  // shownTree is a list of spans excluding hidden spans from rerootedTree.
  const shownTree = useMemo(() => {
    let childrenHiddenSpanDepth;
    return rerootedTree.reduce((acc, span) => {
      if (!!childrenHiddenSpanDepth && span.depth > childrenHiddenSpanDepth) {
        return acc;
      }
      childrenHiddenSpanDepth = null;
      acc.push(span);
      const spanIndex = findSpanIndex(traceSummary.spans, span.spanId);
      if (childrenHiddenSpanIndices[spanIndex]) {
        childrenHiddenSpanDepth = span.depth;
      }
      return acc;
    }, []);
  }, [rerootedTree, childrenHiddenSpanIndices, traceSummary.spans]);

  const childrenHiddenSpanIds = React.useMemo(
    () =>
      Object.keys(childrenHiddenSpanIndices)
        .filter((spanIndex) => !!childrenHiddenSpanIndices[spanIndex])
        .reduce((acc, spanIndex) => {
          acc[traceSummary.spans[spanIndex].spanId] = true;
          return acc;
        }, {}),
    [traceSummary.spans, childrenHiddenSpanIndices],
  );

  // Find the minumum and maximum timestamps in the shown spans.
  const startTs = useMemo(() => minBy(rerootedTree, 'timestamp').timestamp, [
    rerootedTree,
  ]);
  const endTs = useMemo(
    () =>
      rerootedTree
        .map((span) => {
          let ts = span.timestamp;
          if (span.duration) {
            ts += span.duration;
          }
          return ts;
        })
        .reduce((a, b) => Math.max(a, b)),
    [rerootedTree],
  );

  const handleSpanDetailToggle = useCallback(() => {
    setIsSpanDetailOpened((prev) => !prev);
  }, []);

  const handleExpandButtonClick = useCallback(() => {
    const expandedSpanIndices = shownTree
      .filter((span) => !!childrenHiddenSpanIds[span.spanId])
      .reduce((acc, span) => {
        const spanIndex = findSpanIndex(traceSummary.spans, span.spanId);
        acc[spanIndex] = false;
        return acc;
      }, {});
    setChildrenHiddenSpanIndices((prev) => ({
      ...prev,
      ...expandedSpanIndices,
    }));
  }, [shownTree, traceSummary.spans, childrenHiddenSpanIds]);

  const handleCollapseButtonClick = useCallback(() => {
    const spanIndex = findSpanIndex(traceSummary.spans, shownTree[0].spanId);
    setChildrenHiddenSpanIndices((prev) => ({
      ...prev,
      [spanIndex]: true,
    }));
  }, [shownTree, traceSummary.spans]);

  return (
    <Box height="calc(100vh - 64px)" display="flex" flexDirection="column">
      <Box boxShadow={3} zIndex={1}>
        <TraceSummaryHeader
          traceSummary={traceSummary}
          rootSpanIndex={rootSpanIndex}
        />
      </Box>
      <Box height="100%" display="flex">
        <Box
          width={`${traceTimelineWidthPercent}%`}
          display="flex"
          flexDirection="column"
        >
          <TraceTimelineHeader
            startTs={startTs - traceSummary.spans[0].timestamp}
            endTs={endTs - traceSummary.spans[0].timestamp}
            isRerooted={isRerooted}
            isRootedTrace={isRootedTrace}
            onResetRerootButtonClick={handleResetRerootButtonClick}
            isSpanDetailOpened={isSpanDetailOpened}
            onSpanDetailToggle={handleSpanDetailToggle}
            onCollapseButtonClick={handleCollapseButtonClick}
            onExpandButtonClick={handleExpandButtonClick}
          />
          <Box flexGrow={1} width="100%">
            <AutoSizer>
              {({ height, width }) => (
                <Box height={height} width={width} overflow="auto">
                  <TraceTimeline
                    currentSpanId={traceSummary.spans[currentSpanIndex].spanId}
                    spans={shownTree}
                    depth={traceSummary.depth}
                    childrenHiddenSpanIds={childrenHiddenSpanIds}
                    isRootedTrace={isRootedTrace}
                    onRowClick={handleTimelineRowClick}
                    onChildrenToggle={handleChildrenToggle}
                    startTs={startTs}
                    endTs={endTs}
                  />
                </Box>
              )}
            </AutoSizer>
          </Box>
        </Box>
        <Box width={`${100 - traceTimelineWidthPercent}%`}>
          <AutoSizer>
            {({ height, width }) => (
              <Box height={height} width={width} overflow="auto" boxShadow={2}>
                <SpanDetail
                  span={traceSummary.spans[currentSpanIndex]}
                  minHeight={height}
                />
              </Box>
            )}
          </AutoSizer>
        </Box>
      </Box>
    </Box>
  );
});

TraceSummary.propTypes = propTypes;

export default TraceSummary;
