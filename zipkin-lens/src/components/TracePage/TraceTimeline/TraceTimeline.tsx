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

import { Box } from '@material-ui/core';
import React, { useCallback, useMemo } from 'react';
import { AutoSizer } from 'react-virtualized';

import buildTimelineTree from './buildTimelineTree';
import { SimpleVirtualizer } from './SimpleVirtualizer';
import TraceTimelineHeader from './TraceTimelineHeader';
import TraceTimelineRow from './TraceTimelineRow';
import { AdjustedSpan } from '../../../models/AdjustedTrace';

const treeWidthPercent = 10;
const virtualizeThreshold = 100;

const extractStartTsAndEndTs = (spans: AdjustedSpan[]) => {
  let startTs = Number.POSITIVE_INFINITY;
  let endTs = Number.NEGATIVE_INFINITY;
  spans.forEach((span) => {
    if (typeof span.timestamp !== 'undefined') {
      startTs = Math.min(startTs, span.timestamp);
      endTs = Math.max(endTs, span.timestamp + span.duration);
    }
  });
  return [startTs, endTs];
};

interface TraceTimelineProps {
  closedSpanIds: { [key: string]: boolean };
  collapseAll: () => void;
  currentSpanId: string;
  expandAll: () => void;
  openSpanDetail: boolean;
  reroot: (span?: string) => void;
  rootSpanId?: string;
  rowHeight: number;
  setCurrentSpanId: (spanId: string) => void;
  spans: AdjustedSpan[];
  toggleChildren: (spanId: string) => void;
  toggleOpenSpanDetail: (b?: boolean) => void;
}

const TraceTimeline = React.memo<TraceTimelineProps>(
  ({
    closedSpanIds,
    collapseAll,
    currentSpanId,
    expandAll,
    openSpanDetail,
    reroot,
    rootSpanId,
    rowHeight,
    setCurrentSpanId,
    spans,
    toggleChildren,
    toggleOpenSpanDetail,
  }) => {
    const [startTs, endTs] = useMemo(() => extractStartTsAndEndTs(spans), [
      spans,
    ]);
    const tree = useMemo(() => buildTimelineTree(spans), [spans]);

    const renderSpan = useCallback(
      (span: AdjustedSpan, index: number, style: React.CSSProperties) => (
        <TraceTimelineRow
          key={span.spanId}
          endTs={endTs}
          isClosed={closedSpanIds[span.spanId]}
          isSelected={span.spanId === currentSpanId}
          rowHeight={rowHeight}
          span={span}
          startTs={startTs}
          toggleChildren={toggleChildren}
          toggleOpenSpanDetail={toggleOpenSpanDetail}
          treeData={tree[index]}
          treeWidthPercent={treeWidthPercent}
          style={style}
          setCurrentSpanId={setCurrentSpanId}
        />
      ),
      [
        closedSpanIds,
        currentSpanId,
        endTs,
        rowHeight,
        setCurrentSpanId,
        startTs,
        toggleChildren,
        toggleOpenSpanDetail,
        tree,
      ],
    );

    return (
      <Box display="flex" flexDirection="column" height="100%">
        <TraceTimelineHeader
          collapseAll={collapseAll}
          endTs={endTs}
          expandAll={expandAll}
          openSpanDetail={openSpanDetail}
          reroot={reroot}
          rootSpanId={rootSpanId}
          startTs={startTs}
          toggleOpenSpanDetail={toggleOpenSpanDetail}
          treeWidthPercent={treeWidthPercent}
        />
        <Box mt={2} flexGrow={1} overflow="auto">
          {spans.length > virtualizeThreshold ? (
            <AutoSizer>
              {({ width, height }) => (
                <SimpleVirtualizer
                  height={height}
                  items={spans}
                  itemHeight={rowHeight}
                  renderItem={renderSpan}
                  width={width}
                />
              )}
            </AutoSizer>
          ) : (
            spans.map((span, index) => (
              <TraceTimelineRow
                key={span.spanId}
                endTs={endTs}
                isClosed={closedSpanIds[span.spanId]}
                isSelected={span.spanId === currentSpanId}
                rowHeight={rowHeight}
                span={span}
                startTs={startTs}
                toggleChildren={toggleChildren}
                toggleOpenSpanDetail={toggleOpenSpanDetail}
                treeData={tree[index]}
                treeWidthPercent={treeWidthPercent}
                setCurrentSpanId={setCurrentSpanId}
              />
            ))
          )}
        </Box>
      </Box>
    );
  },
);

export default TraceTimeline;
