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

import {
  Box,
  Button,
  Grid,
  Table as MuiTable,
  TableBody,
  TableCell as MuiTableCell,
  TableContainer,
  TableRow,
} from '@material-ui/core';
import ReactEcharts from 'echarts-for-react';
import React, { useMemo } from 'react';
import styled from 'styled-components';

import { selectServiceTheme } from '../../constants/color';
import { ServiceNameAndSpanCount } from '../../models/TraceSummary';

interface SimpleLineProps {
  color: string;
}

const SimpleLine: React.FC<SimpleLineProps> = ({ color }) => {
  return (
    <svg
      width="16"
      height="16"
      xmlns="http://www.w3.org/2000/svg"
      version="1.1"
    >
      <line x1="0" x2="16" y1="8" y2="8" strokeWidth="4" stroke={color} />
    </svg>
  );
};

interface SpanCountChartProps {
  serviceSummaries: ServiceNameAndSpanCount[];
  toggleFilter: (serviceName: string) => void;
}

const SpanCountChart: React.FC<SpanCountChartProps> = ({
  serviceSummaries,
  toggleFilter,
}) => {
  const sortedServiceSummaries = [...serviceSummaries].sort(
    (a, b) => b.spanCount - a.spanCount,
  );

  const data = useMemo(
    () =>
      sortedServiceSummaries.map((serviceSummary) => ({
        name: serviceSummary.serviceName,
        value: serviceSummary.spanCount,
      })),
    [sortedServiceSummaries],
  );

  const colorPalette = useMemo(
    () =>
      sortedServiceSummaries.map(
        (serviceSummary) =>
          selectServiceTheme(serviceSummary.serviceName).palette.primary.main,
      ),
    [sortedServiceSummaries],
  );

  const option = useMemo(
    () => ({
      series: [
        {
          radius: ['50%', '70%'],
          type: 'pie',
          data,
          color: colorPalette,
          height: 300,
          label: {
            normal: {
              show: true,
            },
          },
        },
      ],
    }),
    [colorPalette, data],
  );

  return (
    <Grid container>
      <Grid item xs={6}>
        <ReactEcharts option={option} notMerge lazyUpdate />
      </Grid>
      <TableGrid item xs={6}>
        <TableContainer>
          <Table size="small">
            <TableBody>
              {sortedServiceSummaries.map((serviceSummary) => (
                <TableRow key={serviceSummary.serviceName}>
                  <TableCell component="th" scope="row">
                    <Box display="flex" alignItems="center">
                      <Box flexShrink={0}>
                        <SimpleLine
                          color={
                            selectServiceTheme(serviceSummary.serviceName)
                              .palette.primary.main
                          }
                        />
                      </Box>
                      <ServiceName ml={1}>
                        {serviceSummary.serviceName}
                      </ServiceName>
                    </Box>
                  </TableCell>
                  <TableCell align="right">
                    {serviceSummary.spanCount}
                    <FilterButton
                      size="small"
                      variant="outlined"
                      onClick={() => toggleFilter(serviceSummary.serviceName)}
                    >
                      Filter
                    </FilterButton>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </TableGrid>
    </Grid>
  );
};

export default SpanCountChart;

const TableGrid = styled(Grid)`
  max-height: 300px;
  overflow: auto;
`;

const Table = styled(MuiTable)`
  table-layout: fixed;
`;

const TableCell = styled(MuiTableCell)`
  font-size: 0.75rem;
`;

const FilterButton = styled(Button)`
  font-size: 0.6rem;
  margin-left: ${({ theme }) => theme.spacing(2)}px;
  padding: 2px 9px;
`;

const ServiceName = styled(Box)`
  flex-grow: 1;
  width: 100%;
  overflow-wrap: break-word;
`;
