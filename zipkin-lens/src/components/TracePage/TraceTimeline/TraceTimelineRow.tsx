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

import { Box, createStyles, makeStyles, Theme } from '@material-ui/core';
import ErrorOutlineIcon from '@material-ui/icons/ErrorOutline';
import classNames from 'classnames';
import React, { useCallback } from 'react';

import { TreeElementType } from './buildTimelineTree';
import { selectServiceColor } from '../../../constants/color';
import { AdjustedSpan } from '../../../models/AdjustedTrace';

const buttonSizePx = 18;

const useStyles = makeStyles((theme: Theme) =>
  createStyles({
    root: {
      cursor: 'pointer',
      '&:hover': {
        backgroundColor: theme.palette.grey[200],
      },
    },
    'root--isSelected': {
      backgroundColor: theme.palette.grey[200],
    },
    treeBegin: {
      borderTop: `1px solid ${theme.palette.grey[300]}`,
      borderLeft: `1px solid ${theme.palette.grey[300]}`,
    },
    treeMiddle: {
      borderLeft: `1px solid ${theme.palette.grey[300]}`,
    },
    treeMiddleWithBranch: {
      borderTop: `1px solid ${theme.palette.grey[300]}`,
      borderLeft: `1px solid ${theme.palette.grey[300]}`,
    },
    treeEnd: {
      borderTop: `1px solid ${theme.palette.grey[300]}`,
    },
  }),
);

interface TraceTimelineRowProps {
  endTs: number;
  // When undefined, the row does not have children.
  isClosed?: boolean;
  isSelected: boolean;
  rowHeight: number;
  setCurrentSpanId: (spanId: string) => void;
  span: AdjustedSpan;
  startTs: number;
  style?: React.CSSProperties;
  toggleChildren: (spanId: string) => void;
  toggleOpenSpanDetail: (b?: boolean) => void;
  treeData: (TreeElementType | undefined)[];
  treeWidthPercent: number;
}

const TraceTimelineRow = React.memo<TraceTimelineRowProps>(
  ({
    endTs,
    isClosed,
    isSelected,
    rowHeight,
    setCurrentSpanId,
    span,
    startTs,
    style,
    toggleChildren,
    toggleOpenSpanDetail,
    treeData,
    treeWidthPercent,
  }) => {
    const classes = useStyles();

    const handleButtonClick = useCallback(
      (event: React.MouseEvent<HTMLButtonElement>) => {
        event.stopPropagation();
        toggleChildren(span.spanId);
      },
      [span.spanId, toggleChildren],
    );

    let button: React.ReactNode = null;
    if (typeof isClosed !== 'undefined') {
      if (!treeData.find((d) => typeof d !== 'undefined')) {
        button = (
          <button
            type="button"
            onClick={handleButtonClick}
            style={{
              cursor: 'pointer',
              position: 'absolute',
              left: `calc(${100 / treeData.length}% - ${buttonSizePx / 2}px)`,
              top: `${(rowHeight / 4) * 3 - buttonSizePx / 2}px`,
              width: `${buttonSizePx}px`,
              height: `${buttonSizePx}px`,
              display: 'flex',
              justifyContent: 'center',
              alignItems: 'center',
              zIndex: 101,
            }}
          >
            {isClosed ? '+' : '-'}
          </button>
        );
      } else {
        for (let i = treeData.length - 1; i >= 0; i -= 1) {
          const elemType = treeData[i];
          if (elemType) {
            button = (
              <button
                type="button"
                onClick={handleButtonClick}
                style={{
                  cursor: 'pointer',
                  position: 'absolute',
                  left: `calc(${
                    (100 / (treeData.length + 1)) * (isClosed ? i + 1 : i)
                  }% - ${buttonSizePx / 2}px)`,
                  top: `${(rowHeight / 4) * 3 - buttonSizePx / 2}px`,
                  width: `${buttonSizePx}px`,
                  height: `${buttonSizePx}px`,
                  display: 'flex',
                  justifyContent: 'center',
                  alignItems: 'center',
                  zIndex: 101,
                }}
              >
                {isClosed ? '+' : '-'}
              </button>
            );
            break;
          }
        }
      }
    }

    let isBranch = true;
    const tree: JSX.Element[] = [];
    const commonProps = {
      height: rowHeight,
      width: `${100 / (treeData.length + 1)}%`,
      style: {
        transform: `translateY(${(rowHeight / 4) * 3}px)`,
        zIndex: 100,
      },
    };
    tree.push(<Box {...commonProps} className={classes.treeEnd} />);
    for (let i = treeData.length - 1; i >= 0; i -= 1) {
      const tp = treeData[i];
      const props = { ...commonProps, key: i };
      switch (tp) {
        case 'MIDDLE':
          if (isBranch) {
            tree.push(
              <Box {...props} className={classes.treeMiddleWithBranch} />,
            );
          } else {
            tree.push(<Box {...props} className={classes.treeMiddle} />);
          }
          isBranch = false;
          break;
        case 'END':
          isBranch = false;
          tree.push(<Box {...props} className={classes.treeEnd} />);
          break;
        case 'BEGIN':
          tree.push(<Box {...props} className={classes.treeBegin} />);
          break;
        default:
          if (isBranch) {
            tree.push(<Box {...props} className={classes.treeEnd} />);
          } else {
            tree.push(<Box {...props} />);
          }
      }
    }
    tree.reverse();

    let left = 0;
    if (span.timestamp) {
      left = ((span.timestamp - startTs) / (endTs - startTs)) * 100;
    }
    const width = Math.max((span.duration / (endTs - startTs)) * 100, 1);

    const handleClick = useCallback(() => {
      setCurrentSpanId(span.spanId);
      toggleOpenSpanDetail(true);
    }, [setCurrentSpanId, span.spanId, toggleOpenSpanDetail]);

    return (
      <Box
        pl={2}
        pr={2}
        display="flex"
        className={classNames(classes.root, {
          [classes['root--isSelected']]: isSelected,
        })}
        style={style}
        onClick={handleClick}
      >
        <Box
          width={`${treeWidthPercent}%`}
          display="flex"
          flexShrink={0}
          position="relative"
        >
          {tree}
          {button}
        </Box>
        <Box flexGrow={1} position="relative">
          <Box
            height={rowHeight}
            style={{
              transform: `translateY(${(rowHeight / 4) * 3}px)`,
            }}
            className={classes.treeEnd}
          />
          <Box
            position="absolute"
            left={`${left}%`}
            width={`${width}%`}
            top={`${(rowHeight / 4) * 3 - 5}px`}
            zIndex={100}
          >
            <Box
              style={{
                opacity: 0.8,
              }}
              width="100%"
              height={10}
              bgcolor={selectServiceColor(span.serviceName)}
              borderRadius={3}
            />
          </Box>
          <Box
            position="absolute"
            width="100%"
            pl={2}
            pr={2}
            display="flex"
            justifyContent="space-between"
            style={{
              opacity: 0.8,
              transform: `translateY(${-rowHeight}px)`,
            }}
          >
            <Box display="flex" alignItems="center">
              {span.errorType && span.errorType !== 'none' && (
                <Box mr={0.5} color="red" display="flex" alignItems="end">
                  <ErrorOutlineIcon fontSize="small" />
                </Box>
              )}
              <Box mr={0.5}>{span.serviceName}:</Box>
              <Box color="text.secondary">{span.spanName}</Box>
            </Box>
            <Box>{span.durationStr}</Box>
          </Box>
          {span.annotations.map((annotation) => {
            return (
              <Box
                position="absolute"
                left={`${
                  ((annotation.timestamp - startTs) / (endTs - startTs)) * 100
                }%`}
                width="1px"
                height="6px"
                top={`${(rowHeight / 4) * 3 - 3}px`}
                zIndex={1000}
                bgcolor="background.paper"
                borderRadius={100}
              />
            );
          })}
        </Box>
      </Box>
    );
  },
);

export default TraceTimelineRow;
