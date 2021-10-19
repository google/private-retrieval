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

#ifndef PRIVATE_RETRIEVAL_CPP_CONFIGURATION_PARAMETERS_H_
#define PRIVATE_RETRIEVAL_CPP_CONFIGURATION_PARAMETERS_H_

#include "private_retrieval/cpp/pir_parameters.pb.h"
#include "private_retrieval/cpp/internal/pir.pb.h"
#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "shell_encryption/context.h"
#include "shell_encryption/montgomery.h"

namespace private_retrieval {
namespace pir {

// Validates the set of configuration parameters, making sure all required
// fields are present.
//
// Also makes sure the params are sufficient for correctness, that is, that the
// modulus and degree are large enough to hold a response chunk, and also
// accommodate the noise growth associated with homomorphic operations.
absl::Status VerifyConfigurationParams(
    const ConfigurationParameters& configuration_params);

// Validates the set of XPIR parameters.
absl::Status VerifyXpirParams(const private_retrieval::XpirParams& xpir_params);

// Computes runtime parameters containing information useful during the
// processing of the PIR protocol.
absl::StatusOr<RuntimeParameters> ComputeRuntimeParameters(
    const ConfigurationParameters& config_params);

// Generates Configuration Parameters with Ring-LWE parameters which allows to
// handle a database up to 128 elements.
ConfigurationParameters ConfigurationParameters_PIR128(
    const uint64_t database_size, const uint64_t entry_size,
    const uint64_t levels_of_recursion);

// Generates Configuration Parameters with Ring-LWE parameters which allows to
// handle a database up to 256 elements.
ConfigurationParameters ConfigurationParameters_PIR256(
    const uint64_t database_size, const uint64_t entry_size,
    const uint64_t levels_of_recursion);

// Construct context using configuration parameters.
//
// Fails if the configuration parameters do not specify the 64-bit modulus, the
// degree of the polynomial, the plaintext space, or the variance, correctly.
absl::StatusOr<
    std::unique_ptr<const rlwe::RlweContext<rlwe::MontgomeryInt<uint64_t>>>>
GetContext(const ConfigurationParameters& params);

// Construct modulus params using configuration parameters.
//
// Fails if the configuration parameter does not specify the 64 bit modulus
// correctly.
absl::StatusOr<std::unique_ptr<const rlwe::MontgomeryInt<uint64_t>::Params>>
GetModulusParams(const ConfigurationParameters& params);

// Construct NTT parameters using configuration parameters and Modulus params.
//
// Fails if the degree of the polynomial is not specified correctly.
absl::StatusOr<rlwe::NttParameters<rlwe::MontgomeryInt<uint64_t>>> GetNttParams(
    const ConfigurationParameters& params,
    const rlwe::MontgomeryInt<uint64_t>::Params* modulus_params);

}  // namespace pir
}  // namespace private_retrieval

#endif  // PRIVATE_RETRIEVAL_CPP_CONFIGURATION_PARAMETERS_H_
