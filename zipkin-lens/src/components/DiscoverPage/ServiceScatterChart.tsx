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

import moment from 'moment';
import React, { useMemo, useCallback } from 'react';
import { useHistory } from 'react-router-dom';
import {
  CartesianGrid,
  ResponsiveContainer,
  Scatter,
  ScatterChart,
  Tooltip,
  XAxis,
  YAxis,
  ZAxis,
} from 'recharts';

import { selectColorByInfoClass } from '../../constants/color';
import TraceSummary from '../../models/TraceSummary';
import { formatDuration } from '../../util/timestamp';

interface ServiceScatterChartProps {
  traceSummaries: TraceSummary[];
}

const ServiceScatterChart: React.FC<ServiceScatterChartProps> = ({
  traceSummaries,
}) => {
  const traceSummariesMap = useMemo(
    () =>
      traceSummaries.reduce((acc, cur) => {
        const infoClass = !cur.infoClass ? 'normal' : cur.infoClass;
        if (!acc[infoClass]) {
          acc[infoClass] = [] as TraceSummary[];
        }
        acc[infoClass].push(cur);
        return acc;
      }, {} as { [key: string]: TraceSummary[] }),
    [traceSummaries],
  );

  const history = useHistory();

  const handleScatterClick = useCallback(
    (traceSummary: TraceSummary) => {
      history.push(`traces/${traceSummary.traceId}`);
    },
    [history],
  );

  return (
    <ResponsiveContainer>
      <ScatterChart margin={{ top: 20, right: 30, bottom: 10, left: 30 }}>
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis
          dataKey="timestamp"
          name="Start Time"
          domain={['auto', 'auto']}
          type="number"
          tickFormatter={(ts) => moment(ts).format('HH:mm:ss:SSS')}
        />
        <YAxis
          dataKey="duration"
          name="Duration"
          domain={['auto', 'auto']}
          type="number"
          tickFormatter={(duration) => formatDuration(duration)}
        />
        <ZAxis dataKey="spanCount" name="Span Count" range={[200, 700]} />
        <Tooltip
          cursor={{ strokeDasharray: '3 3' }}
          formatter={(value, name) => {
            switch (name) {
              case 'Start Time':
                return moment(value).format('M/DD HH:mm:ss:SSS');
              case 'Duration':
                return formatDuration(value);
              case 'Span Count':
                return value;
              default:
                return value;
            }
          }}
        />
        {Object.keys(traceSummariesMap).map((infoClass) => (
          <Scatter
            data={traceSummariesMap[infoClass]}
            fill={selectColorByInfoClass(infoClass)}
            opacity={0.7}
            onClick={handleScatterClick}
          />
        ))}
      </ScatterChart>
    </ResponsiveContainer>
  );
};

export default ServiceScatterChart;
