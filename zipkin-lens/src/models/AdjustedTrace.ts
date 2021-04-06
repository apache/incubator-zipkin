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

import { ServiceNameAndSpanCount } from './TraceSummary';

export type AdjustedAnnotation = {
  value: string;
  timestamp: number;
  endpoint: string; // Ex. 'fooo' or 'unknown' on null span.localEndpoint
  relativeTime?: string;
};

export type AdjustedSpan = {
  spanId: string;
  spanName: string; // span.name or 'unknown' on null
  serviceName: string; // span.localEndpoint.serviceName or 'unknown' on null
  parentId?: string;
  childIds: string[];
  serviceNames: string[];
  timestamp?: number;
  duration: number;
  durationStr: string;
  tags: {
    key: string;
    value: string;
  }[];
  annotations: AdjustedAnnotation[];
  errorType: string;
  depth: number;
  width: number;
  left: number;
};

type AdjustedTrace = {
  traceId: string;
  serviceNameAndSpanCounts: ServiceNameAndSpanCount[];
  duration: number;
  durationStr: string;
  // the root-most span, when the root is missing
  rootSpan: {
    serviceName: string; // span.localEndpoint.serviceName or 'unknown' on null
    spanName: string; // span.name or 'unknown' on null
  };
  spans: AdjustedSpan[];
};

export default AdjustedTrace;
