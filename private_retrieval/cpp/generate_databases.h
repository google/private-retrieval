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

#ifndef PRIVATE_RETRIEVAL_CPP_GENERATE_DATABASES_H_
#define PRIVATE_RETRIEVAL_CPP_GENERATE_DATABASES_H_

#include <stdint.h>

#include <utility>
#include <vector>

namespace private_retrieval {
namespace pir {

// Generates a plaintext database of the given shape filled with random data.
std::vector<std::vector<uint8_t>> GenerateRandomRawDatabase(
    uint64_t num_entries, uint64_t entry_size);

// Generates a plaintext database of the given shape filled with deterministic
// data.
//
// The first byte of each entry is the entry number mod 256. Each subsequent
// byte in the entry counts up by 1 (mod 256).
std::vector<std::vector<uint8_t>> GenerateCyclicDatabase(uint64_t num_entries,
                                                         uint64_t entry_size);

}  // namespace pir
}  // namespace private_retrieval

#endif  // PRIVATE_RETRIEVAL_CPP_GENERATE_DATABASES_H_
