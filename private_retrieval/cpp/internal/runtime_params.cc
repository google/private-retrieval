// Copyright 2021 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "private_retrieval/cpp/internal/runtime_params.h"

#include <cmath>

#include "private_retrieval/cpp/internal/pir_utils.h"
#include "absl/status/status.h"
#include "absl/status/statusor.h"

namespace private_retrieval {

absl::StatusOr<XpirRuntimeParams> ComputeXpirRuntimeParams(
    const DatabaseParams& database_params, const XpirParams& xpir_params) {
  XpirRuntimeParams xpir_runtime_params;

  int levels_of_recursion = xpir_params.pir_params().levels_of_recursion();
  ASSIGN_OR_RETURN(int number_of_slices,
                   ComputeNumberOfSlices(database_params.database_size(),
                                         levels_of_recursion));
  xpir_runtime_params.set_number_of_slices(number_of_slices);
  int chunk_size_bytes =
      BytesPerPirChunk(1 << xpir_params.ring_lwe_params().log_degree(),
                       xpir_params.ring_lwe_params().log_t());
  xpir_runtime_params.set_chunk_size_bytes(chunk_size_bytes);
  xpir_runtime_params.set_number_of_chunks(
      NumberOfPirChunks(chunk_size_bytes, database_params.entry_size()));

  return xpir_runtime_params;
}

absl::StatusOr<int> ComputeNumberOfSlices(int database_size,
                                          int levels_of_recursion) {
  if (levels_of_recursion <= 0) {
    return absl::InvalidArgumentError("Levels of recursion must be positive.");
  }
  int number_of_slices =
      ceil(std::pow(database_size, 1.0 / levels_of_recursion)) *
      levels_of_recursion;
  return number_of_slices;
}

}  // namespace private_retrieval
