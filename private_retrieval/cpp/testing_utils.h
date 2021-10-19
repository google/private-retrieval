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

#ifndef PRIVATE_RETRIEVAL_CPP_TESTING_UTILS_H_
#define PRIVATE_RETRIEVAL_CPP_TESTING_UTILS_H_

#include <cstdint>

#include "private_retrieval/cpp/configuration_parameters.h"
#include "private_retrieval/cpp/generate_databases.h"
#include "private_retrieval/cpp/pir_parameters.pb.h"
#include "private_retrieval/cpp/internal/status/status_macros.h"
#include "private_retrieval/cpp/internal/pir_utils.h"
#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "shell_encryption/constants.h"
#include "shell_encryption/montgomery.h"
#include "shell_encryption/ntt_parameters.h"
#include "shell_encryption/polynomial.h"
#include "shell_encryption/testing/testing_utils.h"

namespace private_retrieval {
namespace pir {

using CreateConfig = ConfigurationParameters(
    const uint64_t database_size, const uint64_t entry_size,
    const uint64_t levels_of_recursion);

// Testing parameters.
inline ConfigurationParameters ConfigurationParameters_59_1024(
    const uint64_t database_size, const uint64_t entry_size,
    const uint64_t levels_of_recursion) {
  // Set Ring-LWE parameters.
  int variance = 8;
  uint64_t log_t = 2;

  private_retrieval::RingLweParams ring_lwe_params;
  ring_lwe_params.mutable_int_64_modulus()->add_modulus(rlwe::kModulus59);
  ring_lwe_params.set_log_degree(rlwe::kLogDegreeBound59);
  ring_lwe_params.set_log_t(log_t);
  ring_lwe_params.set_variance(variance);

  private_retrieval::PirParams pir_params;
  pir_params.set_levels_of_recursion(levels_of_recursion);

  private_retrieval::XpirParams xpir_params;
  *xpir_params.mutable_pir_params() = pir_params;
  *xpir_params.mutable_ring_lwe_params() = ring_lwe_params;

  private_retrieval::DatabaseParams database_params;
  database_params.set_entry_size(entry_size);
  database_params.set_database_size(database_size);

  ConfigurationParameters config_params;
  *config_params.mutable_xpir_params() = xpir_params;
  *config_params.mutable_database_params() = database_params;

  return config_params;
}

// Returns an unserialized version of a random RLWE-encoded database with the
// given shape, modulus, log n, and log t values.
inline absl::StatusOr<
    std::vector<std::vector<rlwe::Polynomial<rlwe::MontgomeryInt<uint64_t>>>>>
GenerateEncodedDatabase(uint64_t num_entries, uint64_t entry_size,
                        uint64_t modulus, uint32_t log_n, uint32_t log_t) {
  ASSIGN_OR_RETURN(auto params,
                   rlwe::MontgomeryInt<uint64_t>::Params::Create(modulus));
  ASSIGN_OR_RETURN(auto ntt_params,
                   rlwe::InitializeNttParameters<rlwe::MontgomeryInt<uint64_t>>(
                       log_n, params.get()));

  auto raw_database = GenerateRandomRawDatabase(num_entries, entry_size);

  ASSIGN_OR_RETURN(
      auto transposed_encoded_database,
      private_retrieval::EncodeDatabase(raw_database, log_t, 1 << log_n,
                                        params.get(), &ntt_params));
  if (transposed_encoded_database.empty()) {
    return transposed_encoded_database;
  }
  if (transposed_encoded_database[0].size() != num_entries) {
    return absl::InternalError("Invalid encoding of generated database.");
  }
  std::vector<std::vector<rlwe::Polynomial<rlwe::MontgomeryInt<uint64_t>>>>
      encoded_database(num_entries);
  for (int i = 0; i < num_entries; ++i) {
    encoded_database[i].resize(transposed_encoded_database.size());
    for (int j = 0; j < transposed_encoded_database.size(); ++j) {
      encoded_database[i][j] = transposed_encoded_database[j][i];
    }
  }
  return encoded_database;
}

// Returns a serialized version of the provided RLWE-encoded database.
inline absl::StatusOr<std::vector<std::vector<rlwe::SerializedNttPolynomial>>>
SerializeEncodedDatabase(
    std::vector<std::vector<rlwe::Polynomial<rlwe::MontgomeryInt<uint64_t>>>>
        database,
    uint64_t modulus) {
  if (database.empty()) {
    return absl::Status(absl::StatusCode::kInvalidArgument,
                        "Database must not be empty.");
  }

  ASSIGN_OR_RETURN(auto params,
                   rlwe::MontgomeryInt<uint64_t>::Params::Create(modulus));

  uint64_t num_entries = database.size();
  uint64_t entry_size = database[0].size();
  std::vector<std::vector<rlwe::SerializedNttPolynomial>> serialized_database;
  serialized_database.resize(num_entries);

  for (int i = 0; i < num_entries; i++) {
    auto& item = serialized_database[i];
    item.reserve(entry_size);
    for (int j = 0; j < entry_size; j++) {
      ASSIGN_OR_RETURN(auto serialized_p,
                       database[i][j].Serialize(params.get()));
      item.push_back(serialized_p);
    }
  }

  return serialized_database;
}

// Returns a serialized version of a random RLWE-encoded database with the given
// configuration parameters and shape.
inline std::vector<std::vector<rlwe::SerializedNttPolynomial>>
GenerateSerializedEncodedDatabase(
    const ConfigurationParameters& configuration_parameters,
    uint64_t num_entries, uint64_t entry_size) {
  auto ring_lwe_params =
      configuration_parameters.xpir_params().ring_lwe_params();
  if (!ring_lwe_params.has_int_64_modulus()) {
    LOG(FATAL) << "Serialized encoded database generation is not implemented "
                  "for non-64-bit moduli.";
  }

  uint64_t modulus = ring_lwe_params.int_64_modulus().modulus(0);
  auto generate_encoded_database = GenerateEncodedDatabase(
      num_entries, entry_size, modulus, ring_lwe_params.log_degree(),
      ring_lwe_params.log_t());
  if (!generate_encoded_database.ok()) {
    LOG(FATAL) << "Failed to generate encoded database: "
               << generate_encoded_database.status();
  }
  auto encoded_database = *generate_encoded_database;

  auto serialized_encoded_database =
      SerializeEncodedDatabase(encoded_database, modulus);
  if (!serialized_encoded_database.ok()) {
    LOG(FATAL) << "Failed to serialize encoded database: "
               << serialized_encoded_database.status();
  }

  return *serialized_encoded_database;
}

}  // namespace pir
}  // namespace private_retrieval

#endif  // PRIVATE_RETRIEVAL_CPP_TESTING_UTILS_H_
