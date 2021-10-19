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

#ifndef PRIVATE_RETRIEVAL_CPP_INTERNAL_RUNTIME_PARAMS_H_
#define PRIVATE_RETRIEVAL_CPP_INTERNAL_RUNTIME_PARAMS_H_

#include "private_retrieval/cpp/internal/pir.pb.h"
#include "absl/status/statusor.h"

namespace private_retrieval {

// Computes additional configuration parameters that can be derived from the
// shape of the database and the Ring LWE configuration. For example, the
// number of "chunks" each database entry should be broken so that each chunk
// fits in a RLWE ciphertext, and the total number of ciphertexts the server
// would expect to receive from a client (the number of "slices").
absl::StatusOr<XpirRuntimeParams> ComputeXpirRuntimeParams(
    const DatabaseParams& database_params, const XpirParams& xpir_params);

// Computes the number of slices given the number of database items and the
// levels of recursions of the PIR protocol.
absl::StatusOr<int> ComputeNumberOfSlices(int database_size,
                                          int levels_of_recursion);

}  // namespace private_retrieval

#endif  // PRIVATE_RETRIEVAL_CPP_INTERNAL_RUNTIME_PARAMS_H_
