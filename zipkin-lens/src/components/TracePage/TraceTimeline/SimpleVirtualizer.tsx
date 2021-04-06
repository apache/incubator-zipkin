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
import React, { useCallback, useMemo, useState } from 'react';

export interface SimpleVirtualizerProps<T> {
  height: number;
  items: T[];
  itemHeight: number;
  renderItem: (
    item: T,
    index: number,
    style: React.CSSProperties,
  ) => React.ReactNode;
  width: number;
}

export const SimpleVirtualizer = <T extends object | string>({
  height,
  items,
  itemHeight,
  renderItem,
  width,
}: SimpleVirtualizerProps<T>) => {
  console.log(height);
  const [scrollTop, setScrollTop] = useState(0);

  const startIndex = Math.floor(scrollTop / itemHeight);
  const endIndex = Math.min(
    items.length - 1,
    Math.floor((scrollTop + height) / itemHeight),
  );

  const handleScroll = useCallback((event: React.UIEvent<HTMLDivElement>) => {
    setScrollTop(event.currentTarget.scrollTop);
  }, []);

  const renderedItems = useMemo(() => items.slice(startIndex, endIndex + 1), [
    endIndex,
    items,
    startIndex,
  ]);

  return (
    <Box overflow="auto" height={height} onScroll={handleScroll} width={width}>
      <Box height={items.length * itemHeight} position="relative">
        {renderedItems.map((item, index) =>
          renderItem(item, index + startIndex, {
            position: 'absolute',
            top: `${(index + startIndex) * itemHeight}px`,
            height: itemHeight,
            width,
          }),
        )}
      </Box>
    </Box>
  );
};
