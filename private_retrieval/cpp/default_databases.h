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

#ifndef PRIVATE_RETRIEVAL_CPP_DEFAULT_DATABASES_H_
#define PRIVATE_RETRIEVAL_CPP_DEFAULT_DATABASES_H_

#include <cstdint>
#include <utility>

#include "private_retrieval/cpp/configuration_parameters.h"
#include "private_retrieval/cpp/pir_parameters.pb.h"
#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "shell_encryption/polynomial.h"

namespace private_retrieval {
namespace pir {
namespace server {

constexpr uint64_t kUnitTestDatabaseNumEntries = 128;
constexpr uint64_t kUnitTestDatabaseNumChunks =
    3;  // Needs to be >= 3 to pass the unit test in session_handler_test.cc
constexpr uint64_t kUnitTestDatabaseChunkSize =
    4608;  // Determined by the ConfigurationParameters
constexpr uint64_t kUnitTestDatabaseEntrySize =
    kUnitTestDatabaseNumChunks * kUnitTestDatabaseChunkSize -
    50;  // make last chunk short
constexpr uint64_t kUnitTestEncodedDatabaseEntrySize = 256;
constexpr uint64_t kUnitTestDatabaseRecursionLevels = 1;

// A "hard-coded" PIR database.
class PirDefaultDatabase {
 public:
  PirDefaultDatabase(
      bool is_encoded_database, uint64_t num_entries, uint64_t entry_size,
      ConfigurationParameters configuration_parameters,
      const std::function<absl::StatusOr<std::vector<
          std::vector<rlwe::Polynomial<rlwe::MontgomeryInt<uint64_t>>>>>(
          uint64_t num_entries, uint64_t entry_size, uint64_t modulus,
          uint32_t log_n, uint32_t log_t)>& encoded_database_generator,
      const std::function<std::vector<std::vector<uint8_t>>(
          uint64_t num_entries, uint64_t entry_size)>& raw_database_generator)
      : is_encoded_database_(is_encoded_database),
        num_entries_(num_entries),
        entry_size_(entry_size),
        configuration_parameters_(std::move(configuration_parameters)) {
    if (is_encoded_database_) {
      auto ring_lwe_params =
          configuration_parameters_.xpir_params().ring_lwe_params();
      encoded_database_ = *encoded_database_generator(
          num_entries_, entry_size_,
          ring_lwe_params.int_64_modulus().modulus(0),
          ring_lwe_params.log_degree(), ring_lwe_params.log_t());
    } else {
      raw_database_ = raw_database_generator(num_entries, entry_size);
    }
  }

  absl::StatusOr<
      std::vector<std::vector<rlwe::Polynomial<rlwe::MontgomeryInt<uint64_t>>>>>
  GetEncodedDatabase() const {
    if (!is_encoded_database_) {
      return absl::Status(absl::StatusCode::kUnavailable,
                          "Database is raw; call GetRawDatabase to retrieve.");
    }

    return encoded_database_;
  }

  absl::StatusOr<std::vector<std::vector<uint8_t>>> GetRawDatabase() const {
    if (is_encoded_database_) {
      return absl::Status(
          absl::StatusCode::kUnavailable,
          "Database is encoded; call GetEncodedDatabase to retrieve.");
    }

    return raw_database_;
  }

  ConfigurationParameters GetConfigurationParameters() const {
    return configuration_parameters_;
  }

 private:
  bool is_encoded_database_;
  uint64_t num_entries_;
  uint64_t entry_size_;
  ConfigurationParameters configuration_parameters_;
  std::vector<std::vector<rlwe::Polynomial<rlwe::MontgomeryInt<uint64_t>>>>
      encoded_database_;
  std::vector<std::vector<uint8_t>> raw_database_;
};

}  // namespace server
}  // namespace pir
}  // namespace private_retrieval

#endif  // PRIVATE_RETRIEVAL_CPP_DEFAULT_DATABASES_H_
