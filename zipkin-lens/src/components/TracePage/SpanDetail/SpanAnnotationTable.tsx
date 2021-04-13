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

import { t } from '@lingui/macro';
import { useLingui } from '@lingui/react';
import { DataGrid, GridColDef } from '@material-ui/data-grid';
import React, { useMemo } from 'react';

import { AdjustedAnnotation } from '../../../models/AdjustedTrace';

const { formatTimestampMicros } = require('../../../util/timestamp');

const convertAnnotations = (annotations: AdjustedAnnotation[]) => {
  return annotations.map((annotation) => ({
    id: annotation.value,
    value: annotation.value,
    timestamp: formatTimestampMicros(annotation.timestamp),
    relativeTime: annotation.relativeTime,
    endpoint: annotation.endpoint,
  }));
};

interface SpanAnnotationTableProps {
  annotations: AdjustedAnnotation[];
}

const SpanAnnotationTable = React.memo<SpanAnnotationTableProps>(
  ({ annotations }) => {
    const { i18n } = useLingui();

    const columns: GridColDef[] = useMemo(
      () => [
        { field: 'value', headerName: i18n._(t`Value`), width: 120 },
        { field: 'timestamp', headerName: i18n._(t`Start Time`), width: 200 },
        {
          field: 'relativeTime',
          headerName: i18n._(t`Relative Time`),
          width: 140,
        },
        { field: 'endpoint', headerName: i18n._(t`Address`), width: 300 },
      ],
      [i18n],
    );

    const converted = useMemo(() => convertAnnotations(annotations), [
      annotations,
    ]);
    return (
      <DataGrid
        rows={converted}
        columns={columns}
        density="compact"
        disableSelectionOnClick
        disableColumnSelector
        disableColumnMenu
        hideFooter
      />
    );
  },
);

export default SpanAnnotationTable;
