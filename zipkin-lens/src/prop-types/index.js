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
import PropTypes from 'prop-types';

export const spanTagPropTypes = PropTypes.shape({
  key: PropTypes.string.isRequired,
  value: PropTypes.string.isRequired,
});

export const spanTagsPropTypes = PropTypes.arrayOf(
  spanTagPropTypes,
);

export const spanAnnotationPropTypes = PropTypes.shape({
  value: PropTypes.string.isRequired,
  timestamp: PropTypes.number.isRequired,
  endpoint: PropTypes.string.isRequired,
  relativeTime: PropTypes.string.isRequired,
});

export const spanAnnotationsPropTypes = PropTypes.arrayOf(
  spanAnnotationPropTypes,
);

// TODO: Verify which fields we should enforce here, as some are optional per
// https://github.com/openzipkin/zipkin-api/blob/master/zipkin2-api.yaml
export const detailedSpanPropTypes = PropTypes.shape({
  spanId: PropTypes.string.isRequired,
  spanName: PropTypes.string.isRequired,
  parentId: PropTypes.string,
  childIds: PropTypes.arrayOf(PropTypes.string).isRequired,
  serviceName: PropTypes.string.isRequired,
  serviceNames: PropTypes.arrayOf(PropTypes.string).isRequired,
  timestamp: PropTypes.number,
  duration: PropTypes.number,
  durationStr: PropTypes.string,
  tags: spanTagsPropTypes.isRequired,
  annotations: spanAnnotationsPropTypes.isRequired,
  errorType: PropTypes.string.isRequired,
  // Data for Trace Timeline
  depth: PropTypes.number.isRequired,
  width: PropTypes.number.isRequired,
  left: PropTypes.number.isRequired,
});

export const detailedSpansPropTypes = PropTypes.arrayOf(
  detailedSpanPropTypes,
);

export const spanServiceNameSummary = PropTypes.shape({
  serviceName: PropTypes.string.isRequired,
  spanCount: PropTypes.number.isRequired,
});

export const spanServiceNameSummaries = PropTypes.arrayOf(
  spanServiceNameSummary,
);

export const traceSummaryPropTypes = PropTypes.shape({
  traceId: PropTypes.string.isRequired,
  timestamp: PropTypes.number.isRequired,
  duration: PropTypes.number.isRequired,
  durationStr: PropTypes.string.isRequired,
  servicePercentage: PropTypes.number,
  serviceSummaries: spanServiceNameSummaries.isRequired,
  infoClass: PropTypes.string,
  spanCount: PropTypes.number.isRequired,
  width: PropTypes.number.isRequired,
});

export const traceSummariesPropTypes = PropTypes.arrayOf(
  traceSummaryPropTypes,
);

export const detailedTraceSummaryPropTypes = PropTypes.shape({
  traceId: PropTypes.string.isRequired,
  spans: detailedSpansPropTypes.isRequired,
  serviceNameAndSpanCounts: spanServiceNameSummaries.isRequired,
  duration: PropTypes.number.isRequired,
  durationStr: PropTypes.string.isRequired,
  rootSpan: PropTypes.shape({
    serviceName: PropTypes.string.isRequired,
    spanName: PropTypes.string.isRequired,
  }).isRequired,
});

export const globalSearchConditionsPropTypes = PropTypes.arrayOf(PropTypes.shape({
  key: PropTypes.string,
  value: PropTypes.oneOfType([
    PropTypes.string,
    PropTypes.number,
    PropTypes.shape({}),
  ]),
}));

export const globalSearchLookbackConditionPropTypes = PropTypes.shape({
  value: PropTypes.oneOfType([PropTypes.number, PropTypes.string]),
  endTs: PropTypes.number,
  startTs: PropTypes.number,
});
