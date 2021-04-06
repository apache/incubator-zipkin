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
import { t, Trans } from '@lingui/macro';
import { useLingui } from '@lingui/react';
import React, { useCallback } from 'react';
import { useDispatch } from 'react-redux';
import { faDownload, faFileAlt } from '@fortawesome/free-solid-svg-icons';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import { makeStyles } from '@material-ui/styles';
import Box from '@material-ui/core/Box';
import Grid from '@material-ui/core/Grid';
import Button from '@material-ui/core/Button';
import Typography from '@material-ui/core/Typography';

import { setAlert } from '../App/slice';

import { useUiConfig } from '../UiConfig';

import {
  detailedSpanPropTypes,
  detailedTraceSummaryPropTypes,
} from '../../prop-types';
import * as api from '../../constants/api';

const propTypes = {
  traceSummary: detailedTraceSummaryPropTypes,
  rootSpan: detailedSpanPropTypes,
};

const defaultProps = {
  traceSummary: null,
  rootSpan: null,
};

const useStyles = makeStyles((theme) => ({
  root: {
    paddingLeft: theme.spacing(3),
    paddingRight: theme.spacing(3),
  },
  upperBox: {
    width: '100%',
    display: 'flex',
    borderBottom: `1px solid ${theme.palette.grey[300]}`,
    paddingTop: theme.spacing(1),
  },
  serviceNameAndSpanName: {
    display: 'flex',
    alignItems: 'center',
  },
  serviceName: {
    textTransform: 'uppercase',
  },
  spanName: {
    color: theme.palette.text.secondary,
  },
  lowerBox: {
    marginTop: theme.spacing(0.5),
    marginBottom: theme.spacing(0.5),
  },
  traceInfo: {
    display: 'flex',
    alignItems: 'center',
  },
  traceInfoEntry: {
    marginRight: theme.spacing(1),
    display: 'flex',
  },
  traceInfoLabel: {
    fontWeight: 'bold',
    color: theme.palette.grey[600],
  },
  traceInfoValue: {
    fontWeight: 'bold',
    marginLeft: theme.spacing(0.8),
  },
  actionButton: {
    fontSize: '0.7rem',
    lineHeight: 1.0,
  },
  actionButtonIcon: {
    marginRight: theme.spacing(1),
  },
}));

const TraceSummaryHeader = React.memo(({ traceSummary, rootSpan }) => {
  const dispatch = useDispatch();
  const classes = useStyles();
  const { i18n } = useLingui();
  const config = useUiConfig();

  const logsUrl =
    config.logsUrl && traceSummary
      ? config.logsUrl.replace(/{traceId}/g, traceSummary.traceId)
      : undefined;

  const traceJsonUrl = traceSummary
    ? `${api.TRACE}/${traceSummary.traceId}`
    : undefined;

  const archivePostUrl =
    config.archivePostUrl && traceSummary ? config.archivePostUrl : undefined;

  const archiveUrl =
    config.archiveUrl && traceSummary
      ? config.archiveUrl.replace('{traceId}', traceSummary.traceId)
      : undefined;

  const archiveClick = useCallback(() => {
    // We don't store the raw json in the browser yet, so we need to make an
    // HTTP call to retrieve it again.
    fetch(`${api.TRACE}/${traceSummary.traceId}`)
      .then((response) => {
        if (!response.ok) {
          throw new Error('Failed to fetch trace from backend');
        }
        return response.json();
      })
      .then((json) => {
        // Add zipkin.archived tag to root span
        /* eslint-disable-next-line no-restricted-syntax */
        for (const span of json) {
          if ('parentId' in span === false) {
            const tags = span.tags || {};
            tags['zipkin.archived'] = 'true';
            span.tags = tags;
            break;
          }
        }
        return json;
      })
      .then((json) => {
        return fetch(archivePostUrl, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify(json),
        });
      })
      .then((response) => {
        if (
          !response.ok ||
          (response.status !== 202 && response.status === 200)
        ) {
          throw new Error('Failed to archive the trace');
        }
        if (archiveUrl) {
          dispatch(
            setAlert({
              message: `Archive successful! This trace is now accessible at ${archiveUrl}`,
              severity: 'success',
            }),
          );
        } else {
          dispatch(
            setAlert({
              message: `Archive successful!`,
              severity: 'success',
            }),
          );
        }
      })
      .catch(() => {
        dispatch(
          setAlert({
            message: 'Failed to archive the trace',
            severity: 'error',
          }),
        );
      });
  }, [archivePostUrl, archiveUrl, dispatch, traceSummary]);

  const traceInfo = traceSummary ? (
    <Box className={classes.traceInfo}>
      {[
        { label: i18n._(t`Duration`), value: traceSummary.durationStr },
        {
          label: i18n._(t`Services`),
          value: traceSummary.serviceNameAndSpanCounts.length,
        },
        { label: i18n._(t`Depth`), value: traceSummary.depth },
        { label: i18n._(t`Total Spans`), value: traceSummary.spans.length },
        {
          label: i18n._(t`Trace ID`),
          value: !rootSpan
            ? traceSummary.traceId
            : `${traceSummary.traceId} - ${rootSpan.spanId}`,
        },
      ].map((entry) => (
        <Box key={entry.label} className={classes.traceInfoEntry}>
          <Box className={classes.traceInfoLabel}>{`${entry.label}:`}</Box>
          <Box className={classes.traceInfoValue}>{entry.value}</Box>
        </Box>
      ))}
    </Box>
  ) : (
    <div />
  );

  return (
    <Box className={classes.root}>
      <Box className={classes.upperBox}>
        {traceSummary ? (
          <>
            <Typography variant="h5" className={classes.serviceName}>
              {traceSummary.rootSpan.serviceName}
            </Typography>
            <Typography variant="h5" className={classes.spanName}>
              {` : ${traceSummary.rootSpan.spanName}`}
            </Typography>
          </>
        ) : null}
      </Box>
      <Grid container className={classes.lowerBox} justify="space-between">
        <Grid item xs={8}>
          {traceInfo}
        </Grid>
        <Grid container item xs={4} justify="flex-end" spacing={1}>
          <Grid item>
            <Button
              variant="outlined"
              className={classes.actionButton}
              href={traceJsonUrl}
              download={traceSummary && `${traceSummary.traceId}.json`}
              data-testid="download-json-link"
            >
              <FontAwesomeIcon
                icon={faDownload}
                className={classes.actionButtonIcon}
              />
              <Trans>Download JSON</Trans>
            </Button>
          </Grid>
          {logsUrl && (
            <Grid item>
              <Button
                variant="outlined"
                className={classes.actionButton}
                href={logsUrl}
                target="_blank"
                rel="noopener"
                data-testid="view-logs-link"
              >
                <FontAwesomeIcon
                  icon={faFileAlt}
                  className={classes.actionButtonIcon}
                />
                <Trans>View Logs</Trans>
              </Button>
            </Grid>
          )}
          {archivePostUrl && (
            <Grid item>
              <Button
                variant="outlined"
                className={classes.actionButton}
                target="_blank"
                rel="noopener"
                data-testid="archive-trace-link"
                onClick={archiveClick}
              >
                <FontAwesomeIcon
                  icon={faFileAlt}
                  className={classes.actionButtonIcon}
                />
                <Trans>Archive Trace</Trans>
              </Button>
            </Grid>
          )}
        </Grid>
      </Grid>
    </Box>
  );
});

TraceSummaryHeader.propTypes = propTypes;
TraceSummaryHeader.defaultProps = defaultProps;

export default TraceSummaryHeader;
