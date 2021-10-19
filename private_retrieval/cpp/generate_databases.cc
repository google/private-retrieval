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

#include "private_retrieval/cpp/generate_databases.h"

#include <cstdlib>
#include <vector>

namespace private_retrieval {
namespace pir {

std::vector<std::vector<uint8_t>> GenerateRandomRawDatabase(
    uint64_t num_entries, uint64_t entry_size) {
  unsigned int seed = 0;
  std::vector<std::vector<uint8_t>> database(num_entries);
  for (std::vector<uint8_t>& item : database) {
    item.reserve(entry_size);
    for (int i = 0; i < entry_size; i++) {
      item.push_back(rand_r(&seed));
    }
  }
  return database;
}

std::vector<std::vector<uint8_t>> GenerateCyclicDatabase(uint64_t num_entries,
                                                         uint64_t entry_size) {
  std::vector<std::vector<uint8_t>> database(num_entries);
  for (int iDatabaseEntry = 0; iDatabaseEntry < num_entries; iDatabaseEntry++) {
    std::vector<uint8_t>& item = database[iDatabaseEntry];
    item.reserve(entry_size);
    for (int iEntryByte = 0; iEntryByte < entry_size; iEntryByte++) {
      item.push_back((iDatabaseEntry + iEntryByte) % 256);
    }
  }
  return database;
}

}  // namespace pir
}  // namespace private_retrieval
