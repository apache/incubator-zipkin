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
import {
  Box,
  Collapse,
  Divider,
  IconButton,
  Menu,
  MenuItem,
  Typography,
} from '@material-ui/core';
import ArchiveIcon from '@material-ui/icons/Archive';
import ArrowDropDownIcon from '@material-ui/icons/ArrowDropDown';
import ArrowDropUpIcon from '@material-ui/icons/ArrowDropUp';
import ArrowRightIcon from '@material-ui/icons/ArrowRight';
import GetAppIcon from '@material-ui/icons/GetApp';
import ListIcon from '@material-ui/icons/List';
import MenuIcon from '@material-ui/icons/Menu';
import React, { useCallback } from 'react';
import { useDispatch } from 'react-redux';
import { useToggle } from 'react-use';

import { useUiConfig } from '../UiConfig';
import * as api from '../../constants/api';
import AdjustedTrace from '../../models/AdjustedTrace';
import Span from '../../models/Span';
import { setAlert } from '../App/slice';

interface TracePageHeaderProps {
  traceSummary?: AdjustedTrace;
  rootSpanIndex?: number;
}

const TracePageHeader = React.memo<TracePageHeaderProps>(
  ({ traceSummary, rootSpanIndex = 0 }) => {
    const { i18n } = useLingui();
    const config = useUiConfig();
    const dispatch = useDispatch();
    const [openInfo, toggleOpenInfo] = useToggle(true);
    const [menuAnchorEl, setMenuAnchorEl] = React.useState<HTMLButtonElement>();

    const handleMenuButtonClick = useCallback(
      (event: React.MouseEvent<HTMLButtonElement>) => {
        setMenuAnchorEl(event.currentTarget);
      },
      [],
    );

    const handleMenuClose = useCallback(() => {
      setMenuAnchorEl(undefined);
    }, []);

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

    const handleArchiveButtonClick = useCallback(async () => {
      if (!traceSummary) {
        return;
      }
      try {
        // We don't store the raw json in the browser yet, so we need to make an
        // HTTP call to retrieve it again.
        const resp = await fetch(`${api.TRACE}/${traceSummary.traceId}`);
        if (!resp.ok) {
          throw new Error('Failed to fetch trace from backend');
        }
        const spans: Span[] = await resp.json();
        // Add zipkin.archived tag to root span
        /* eslint-disable-next-line no-restricted-syntax */
        for (const span of spans) {
          if ('parentId' in span === false) {
            const tags = span.tags || {};
            tags['zipkin.archived'] = 'true';
            span.tags = tags;
            break;
          }
        }
        const postResp = await fetch(archivePostUrl, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify(spans),
        });
        if (
          !postResp.ok ||
          (postResp.status !== 202 && postResp.status === 200)
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
      } catch (error) {
        dispatch(
          setAlert({
            message: 'Failed to archive the trace',
            severity: 'error',
          }),
        );
      }
    }, [archivePostUrl, archiveUrl, dispatch, traceSummary]);

    const traceInfo = traceSummary ? (
      <Box display="flex" alignItems="center" pl={3} pr={3} pt={0.5} pb={0.5}>
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
            value:
              rootSpanIndex === 0
                ? traceSummary.traceId
                : `${traceSummary.traceId} - ${traceSummary.spans[rootSpanIndex].spanId}`,
          },
        ].map((entry) => (
          <Box key={entry.label} display="flex" mr={0.75}>
            <ArrowRightIcon fontSize="small" />
            <Box color="text.secondary" mr={0.5}>{`${entry.label}:`}</Box>
            <Box>{entry.value}</Box>
          </Box>
        ))}
      </Box>
    ) : (
      <div />
    );

    return (
      <Box bgcolor="background.paper" boxShadow={3}>
        <Box
          display="flex"
          alignItems="center"
          justifyContent="space-between"
          pl={3}
          pr={3}
          pt={1}
          pb={1}
        >
          <Box display="flex" alignItems="center">
            {traceSummary && (
              <>
                <Typography variant="h6">
                  {traceSummary.rootSpan.serviceName}
                </Typography>
                <Typography variant="h6" color="textSecondary">
                  {` : ${traceSummary.rootSpan.spanName}`}
                </Typography>
              </>
            )}
            <Box ml={1}>
              <IconButton size="small" onClick={toggleOpenInfo}>
                {openInfo ? <ArrowDropUpIcon /> : <ArrowDropDownIcon />}
              </IconButton>
            </Box>
          </Box>
          <IconButton size="small" onClick={handleMenuButtonClick}>
            <MenuIcon />
          </IconButton>
          <Menu
            anchorEl={menuAnchorEl}
            keepMounted
            open={Boolean(menuAnchorEl)}
            onClose={handleMenuClose}
          >
            <MenuItem
              onClick={handleMenuClose}
              component="a"
              href={traceJsonUrl}
              download={traceSummary && `${traceSummary.traceId}.json`}
              data-testid="download-json-link"
            >
              <GetAppIcon />
              <Box ml={1}>
                <Trans>Download JSON</Trans>
              </Box>
            </MenuItem>
            {logsUrl && (
              <MenuItem
                onClick={handleMenuClose}
                component="a"
                href={logsUrl}
                target="_blank"
                rel="noopener"
                data-testid="view-logs-link"
              >
                <ListIcon />
                <Box ml={1}>
                  <Trans>View Logs</Trans>
                </Box>
              </MenuItem>
            )}
            {archivePostUrl && (
              <MenuItem
                onClick={handleArchiveButtonClick}
                data-testid="archive-trace-link"
              >
                <ArchiveIcon />
                <Box ml={1}>
                  <Trans>Archive Trace</Trans>
                </Box>
              </MenuItem>
            )}
          </Menu>
        </Box>
        <Collapse in={openInfo}>
          <Divider />
          {traceInfo}
        </Collapse>
      </Box>
    );
  },
);

export default TracePageHeader;
