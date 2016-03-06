import _ from 'lodash';
import {
  traceSummary,
  getGroupedTimestamps,
  getServiceDurations,
  getServiceNames,
  getServiceName,
  mkDurationStr,
  traceDuration,
} from './traceSummary';
import {Constants, ConstantNames} from './traceConstants';

export function getRootSpans(spans) {
  const ids = spans.map((s) => s.id);
  return spans.filter((s) => ids.indexOf(s.parentId) === -1);
}

function compareSpan(s1, s2) {
  return (s1.timestamp || 0) - (s2.timestamp || 0);
}

function childrenToList(entry) {
  const deepChildren = _(entry.children || []).sort((e1, e2) => compareSpan(e1.span, e2.span)).flatMap(childrenToList).value();
  return [entry.span, ...deepChildren];
}

function createSpanTreeEntry(span, trace, indexByParentId = null) {
  if (indexByParentId == null) {
    indexByParentId = _(trace).filter((span) => span.parentId != null).groupBy((span) => span.parentId).value();
  }
  return {
    span,
    children: (indexByParentId[span.id] || []).map((s) => createSpanTreeEntry(s, trace, indexByParentId))
  };
}

function getRootMostSpan(spans) {
  const firstWithoutParent = _(spans).find((s) => !s.parentId);
  if (firstWithoutParent) {
    return firstWithoutParent;
  } else {
    const idToSpanMap = _(spans).groupBy((s) => s.id).mapValues(([s]) => s);
    return recursiveGetRootMostSpan(idToSpanMap, spans[0]);
  }
}

function recursiveGetRootMostSpan(idSpan, prevSpan) {
  if (prevSpan.parentId && idSpan[prevSpan.parentId]) {
    return recursiveGetRootMostSpan(idSpan, idSpan[prevSpan.parentId]);
  } else {
    return prevSpan;
  }
}

function treeDepths(entry, startDepth) {
  const initial = {};
  initial[entry.span.id] = startDepth;
  if (entry.children.length == 0) {
    return initial;
  }
  return _(entry.children || []).reduce((prevMap, child) => {
    const childDepths = treeDepths(child, startDepth + 1);
    const newCombined = {
      ...prevMap,
      ...childDepths
    };
    return newCombined;
  }, initial);
}


function toSpanDepths(spans) {
  const rootMost = getRootMostSpan(spans);
  const entry = createSpanTreeEntry(rootMost, spans);
  return treeDepths(entry, 1);
}

export function formatEndpoint({ipv4, port = 0}) {
  return ipv4 + ':' + port;
}

export default function traceToMustache(trace) {
  const summary = traceSummary(trace);
  const duration = mkDurationStr(summary.duration);
  const groupedTimestamps = getGroupedTimestamps(summary);
  const serviceDurations = getServiceDurations(groupedTimestamps);
  const services = serviceDurations.length || 0;
  const serviceCounts = _(serviceDurations).sortBy('name').value();
  const groupByParentId = _(trace).groupBy((s) => s.parentId).value();

  const traceTimestamp = trace[0].timestamp || 0;
  const spanDepths = toSpanDepths(trace);

  const depth = Math.max(..._.values(spanDepths));

  const spans = _(getRootSpans(trace)).flatMap(
    (rootSpan) => childrenToList(createSpanTreeEntry(rootSpan, trace))).map((span) => {
    const spanStartTs = span.timestamp || traceTimestamp;
    const depth = spanDepths[span.id] || 1;
    const width = (span.duration || 0) / summary.duration * 100;

    const binaryAnnotations = span.binaryAnnotations.map((a) => {
      if (Constants.CORE_ADDRESS.indexOf(a.key) !== -1) {
        return {
          ...a,
          key: ConstantNames[a.key],
          value: formatEndpoint(a.endpoint)
        };
      } else if (ConstantNames[a.key]) {
        return {
          ...a,
          key: ConstantNames[a.key]
        };
      } else {
        return a;
      }
    });

    const localComponentAnnotation = _(span.binaryAnnotations).find((s) => s.key === Constants.LOCAL_COMPONENT);
    if (localComponentAnnotation && localComponentAnnotation.endpoint) {
      binaryAnnotations.push({
        ...localComponentAnnotation,
        key: "Local Address",
        value: formatEndpoint(localComponentAnnotation.endpoint)
      });
    }

    return {
      spanId: span.id,
      parentId: span.parentId || null,
      spanName: span.name,
      serviceNames: getServiceNames(span).join(','),
      serviceName: getServiceName(span) || '',
      duration: span.duration,
      durationStr: mkDurationStr(span.duration),
      left: parseFloat(spanStartTs - traceTimestamp) / parseFloat(summary.duration) * 100,
      width: width < 0.1 ? 0.1 : width,
      depth: (depth + 1) * 5,
      depthClass: (depth - 1) % 6,
      children: (groupByParentId[span.id] || []).map((s) => s.id).join(','),
      annotations: span.annotations.map((a) => ({
        isCore: Constants.CORE_ANNOTATIONS.indexOf(a.value) !== -1,
        left: (a.timestamp - spanStartTs) / span.duration * 100,
        endpoint: a.endpoint ? formatEndpoint(a.endpoint) : null,
        value: ConstantNames[a.value] || a.value,
        timestamp: a.timestamp,
        relativeTime: mkDurationStr(a.timestamp - traceTimestamp),
        serviceName: a.endpoint && a.endpoint.serviceName? a.endpoint.serviceName : null,
        width: 8
      })),
      binaryAnnotations
    };
  }).value();

  const totalSpans = spans.length;
  const timeMarkers = [0.0, 0.2, 0.4, 0.6, 0.8, 1.0].map((p, index) => ({index, time: mkDurationStr(summary.duration * p)}));
  const timeMarkersBackup = timeMarkers;
  const spansBackup = spans;

  return {
    duration,
    services,
    depth,
    totalSpans,
    serviceCounts,
    timeMarkers,
    timeMarkersBackup,
    spans,
    spansBackup
  };
};
