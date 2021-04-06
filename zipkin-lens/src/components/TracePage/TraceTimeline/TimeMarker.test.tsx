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
import React from 'react';

import TimeMarker from './TimeMarker';
import render from '../../../test/util/render-with-default-settings';

describe('<TimeMarker />', () => {
  // |-----------|-----------|-----------|
  // 10μs        40μs        70μs       100μs
  it('should render time markers', () => {
    const { queryAllByTestId } = render(
      <TimeMarker startTs={10} endTs={100} treeWidthPercent={8} />,
    );
    const markers = queryAllByTestId('TimeMarker-marker');
    expect(markers.length).toBe(4);
    expect(markers[0]).toHaveStyle('left: 0%');
    expect(markers[1]).toHaveStyle(`left: ${(1 / 3) * 100}%`);
    expect(markers[2]).toHaveStyle(`left: ${(2 / 3) * 100}%`);
    expect(markers[3]).toHaveStyle('left: 100%');
  });
  it('should render labels correctly', () => {
    const { queryAllByTestId } = render(
      <TimeMarker startTs={10} endTs={100} treeWidthPercent={8} />,
    );
    const labels = queryAllByTestId('TimeMarker-label');
    expect(labels.length).toBe(4);
    expect(labels[0]).toHaveTextContent('10μs');
    expect(labels[1]).toHaveTextContent('40μs');
    expect(labels[2]).toHaveTextContent('70μs');
    expect(labels[3]).toHaveTextContent('100μs');
  });
});
