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
import { ActionTypes, DependenciesAction } from '../types/action-types';
import { DependenciesState } from '../types/state-types';

const initialState: DependenciesState = {
  dependencies: [],
  isLoading: false,
  error: null,
};

const dependencies = (
  state = initialState,
  action: DependenciesAction,
): DependenciesState => {
  switch (action.type) {
    case ActionTypes.LOAD_DEPENDENCIES_REQUEST:
      return {
        dependencies: [],
        isLoading: true,
        error: null,
      };
    case ActionTypes.LOAD_DEPENDENCIES_SUCCESS:
      return {
        dependencies: action.payload.dependencies,
        isLoading: false,
        error: null,
      };
    case ActionTypes.LOAD_DEPENDENCIES_FAILURE:
      return {
        dependencies: [],
        isLoading: false,
        error: action.payload.error,
      };
    case ActionTypes.CLEAR_DEPENDENCIES:
      return {
        dependencies: [],
        isLoading: false,
        error: null,
      };
    default:
      return state;
  }
};

export default dependencies;
