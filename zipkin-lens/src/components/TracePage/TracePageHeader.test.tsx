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

import render from '../../test/util/render-with-default-settings';

import TracePageHeader from './TracePageHeader';

describe('<TraceSummaryHeader />', () => {
  const traceSummary = {
    traceId: '1',
    spans: [],
    serviceNameAndSpanCounts: [],
    duration: 1,
    durationStr: '1μs',
    rootSpan: {
      serviceName: 'service-A',
      spanName: 'span-A',
    },
    depth: 0,
  };

  it('renders download JSON button', () => {
    const { getByTestId } = render(
      <TracePageHeader traceSummary={traceSummary} />,
    );
    const downloadJson = getByTestId('download-json-link');
    expect(downloadJson).toBeInTheDocument();
    expect(downloadJson).toHaveAttribute('href', '/zipkin/api/v2/trace/1');
    expect(downloadJson).toHaveAttribute('download', '1.json');
  });

  it('does not render View Logs link with default config', () => {
    const { queryByTestId } = render(
      <TracePageHeader traceSummary={traceSummary} />,
    );
    expect(queryByTestId('view-logs-link')).not.toBeInTheDocument();
  });

  it('does render View Logs link when logs URL in config', () => {
    const { queryByTestId } = render(
      <TracePageHeader traceSummary={traceSummary} />,
      { uiConfig: { logsUrl: 'http://zipkin.io/logs={traceId}' } },
    );
    const logsLink = queryByTestId('view-logs-link');
    expect(logsLink).toBeInTheDocument();
    expect(logsLink).toHaveAttribute('href', 'http://zipkin.io/logs=1');
    // Make sure the link opens in a new tab
    expect(logsLink).toHaveAttribute('target', '_blank');
    expect(logsLink).toHaveAttribute('rel', 'noopener');
  });

  it('does replace multiple instances of {traceId} in logsUrl', () => {
    const { queryByTestId } = render(
      <TracePageHeader traceSummary={traceSummary} />,
      {
        uiConfig: {
          logsUrl: 'http://zipkin.io/logs={traceId}&moreLogs={traceId}',
        },
      },
    );
    const logsLink = queryByTestId('view-logs-link');
    expect(logsLink).toBeInTheDocument();
    expect(logsLink).toHaveAttribute(
      'href',
      'http://zipkin.io/logs=1&moreLogs=1',
    );
  });

  it('does not render Archive Trace link with default config', () => {
    const { queryByTestId } = render(
      <TracePageHeader traceSummary={traceSummary} />,
    );
    expect(queryByTestId('archive-trace-link')).not.toBeInTheDocument();
  });

  it('does render Archive Trace link when logs URL in config', () => {
    const { queryByTestId } = render(
      <TracePageHeader traceSummary={traceSummary} />,
      { uiConfig: { archivePostUrl: 'http://localhost:9411/api/v2/spans' } },
    );
    const logsLink = queryByTestId('archive-trace-link');
    expect(logsLink).toBeInTheDocument();
  });
});
