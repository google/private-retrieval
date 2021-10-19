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

#include "private_retrieval/cpp/configuration_parameters.h"

#include <cmath>
#include <cstdlib>

#include "private_retrieval/cpp/internal/status/status_macros.h"
#include "private_retrieval/cpp/internal/pir_utils.h"
#include "private_retrieval/cpp/internal/runtime_params.h"
#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "shell_encryption/integral_types.h"

namespace private_retrieval {
namespace pir {

namespace {
// Default parameters for PIR.
static rlwe::Uint64 kModulusPIR128{4611686018427322369};
static rlwe::Uint64 kModulusSwitchingPIR128{18320723969};
static int kLogNPIR128 = 11;
static int kVariancePIR128 = 8;
static int kLogTPIR128 = 18;
static int kLevelRecursionPIR128 = 1;
static rlwe::Uint64 kModulusPIR256{2305843009213616129};
static rlwe::Uint64 kModulusSwitchingPIR256{9797890049};
static int kLogNPIR256 = 11;
static int kVariancePIR256 = 8;
static int kLogTPIR256 = 17;
static int kLevelRecursionPIR256 = 1;

}  // namespace

namespace {
// Given the plaintext modulus, dimension, standard deviation of the error,
// modulus,and the database size, verifies that decryption can occur correctly
// at the end of the PIR circuit. The circuit does not include modulus switching
// for error management or noise due to Oblivious Expand.
bool PirResultDecrypts(int log_t, int log_degree, int variance,
                       uint64_t modulus, int levels_of_recursion,
                       int database_size) {
  // The first level of PIR is an absorb between a plaintext database element
  // and a fresh encryption from the request vector. Let d be the levels of
  // recursion, n be the database size, E_p be a bound on the size (error) per
  // coefficient of a randomly distributed plaintext, and E_c be a bound on the
  // size per coefficient of a fresh encryption of a randomly distributed
  // plaintext. At the first level of recursion, we add n ^ (1 / d) absorbed
  // ciphertexts together, resulting in (n ^ (1 / d) * E_p * E_c) error, since
  // the error grows multiplicatively after the absorb. At the next level, two
  // of these ciphertexts are homomorphically multiplied resulting in (n ^ (2 /
  // d) * (E_p * E_c)^2), and (n ^ (1 / d)) of them are again added; the total
  // error is (n ^ (3 / d) * (E_p * E_c) ^ 2). Continuing like this, the total
  // error after all d levels of recursion is (n ^ ((2 ^ d - 1)/ d) * (E_p
  // * E_c) ^ d). For proper decryption to occur, this quantity must be less
  // than q / 2, where q is the modulus.
  uint64_t t = (static_cast<uint64_t>(1) << log_t) + 1;
  double standard_deviation = std::pow(variance, 0.5);
  double sqrt_dimension = std::pow(1 << log_degree, 0.5);

  // Calculate the size of a randomly distributed plaintext element and the size
  // of a fresh query.
  double plaintext_error = t * std::pow(3.0, 0.5) * sqrt_dimension;
  double ciphertext_error =
      t * sqrt_dimension * (std::pow(3.0, 0.5) + 6 * standard_deviation);

  // Compute the total accumulated error during PIR.
  double log_error =
      levels_of_recursion *
          (std::log2(plaintext_error) + std::log2(ciphertext_error)) +
      (((static_cast<uint64_t>(1) << levels_of_recursion) - 1.0) /
       levels_of_recursion) *
          std::log2(database_size);

  // Check that the accumlated error is less than q / 2 for correctness.
  return log_error < (std::log2(modulus) - 1);
}

}  // namespace

absl::Status VerifyXpirParams(
    const private_retrieval::XpirParams& xpir_params) {
  if (!xpir_params.has_pir_params() || !xpir_params.has_ring_lwe_params()) {
    return absl::InvalidArgumentError(
        "XpirParams is missing required sub-messages.");
  }
  // Expect that there will be either one or two moduli. All computation will be
  // performed using the first modulus and the result will be reduced to the
  // second modulus (if given) before being returned.
  int num_moduli;
  switch (xpir_params.ring_lwe_params().modulus_kind_case()) {
    case private_retrieval::RingLweParams::ModulusKindCase::kInt32Modulus:
      return absl::Status(absl::StatusCode::kInternal, "Unimplemented");
    case private_retrieval::RingLweParams::ModulusKindCase::kInt64Modulus:
      num_moduli =
          xpir_params.ring_lwe_params().int_64_modulus().modulus_size();
      if (num_moduli <= 0 || num_moduli > 2) {
        return absl::InvalidArgumentError("Expect either one or two moduli.");
      }
      break;
    case private_retrieval::RingLweParams::ModulusKindCase::kBigIntModulus:
      return absl::Status(absl::StatusCode::kInternal, "Unimplemented");
    default:
      return absl::Status(absl::StatusCode::kInvalidArgument, "Unrecognized");
  }
  return absl::OkStatus();
}

absl::Status VerifyConfigurationParams(
    const ConfigurationParameters& configuration_params) {
  // Check all sub-protos are present.
  if (!configuration_params.has_database_params() ||
      !configuration_params.has_xpir_params()) {
    return absl::InvalidArgumentError(
        "ConfigurationParameters is missing required sub-messages.");
  }

  // Verify XPIR params.
  RETURN_IF_ERROR(VerifyXpirParams(configuration_params.xpir_params()));

  // Important XPIR params.
  int log_degree =
      configuration_params.xpir_params().ring_lwe_params().log_degree();
  int log_t = configuration_params.xpir_params().ring_lwe_params().log_t();
  int variance =
      configuration_params.xpir_params().ring_lwe_params().variance();
  int num_moduli = configuration_params.xpir_params()
                       .ring_lwe_params()
                       .int_64_modulus()
                       .modulus_size();
  uint64_t modulus = modulus = configuration_params.xpir_params()
                                   .ring_lwe_params()
                                   .int_64_modulus()
                                   .modulus(0);
  uint64_t reduce_modulus;
  if (num_moduli == 2) {
    reduce_modulus = configuration_params.xpir_params()
                         .ring_lwe_params()
                         .int_64_modulus()
                         .modulus(1);
  }

  // Database shape.
  int database_size = configuration_params.database_params().database_size();
  int entry_size = configuration_params.database_params().entry_size();

  // PIR params.
  int levels_of_recursion =
      configuration_params.xpir_params().pir_params().levels_of_recursion();

  // Runtime params.
  ASSIGN_OR_RETURN(RuntimeParameters runtime_params,
                   ComputeRuntimeParameters(configuration_params));
  int num_slices = runtime_params.xpir_runtime_params().number_of_slices();
  int num_chunks = runtime_params.xpir_runtime_params().number_of_chunks();

  // Check consistency between pir params and database parameters.
  int bytes_per_chunk =
      private_retrieval::BytesPerPirChunk(1 << log_degree, log_t);

  if (entry_size > bytes_per_chunk * num_chunks) {
    return absl::InvalidArgumentError(
        "Not enough chunks to accommodate database entries.");
  }

  if (num_slices != ceil(std::pow(database_size, 1.0 / levels_of_recursion)) *
                        levels_of_recursion) {
    return absl::InvalidArgumentError(
        "Number of slices does not match database shape.");
  }

  // Check that log_t and the modulus can accommodate the noise growth
  // associated with homomorphic operations.
  if (!PirResultDecrypts(log_t, log_degree, variance, modulus,
                         levels_of_recursion, database_size)) {
    return absl::InvalidArgumentError(
        "Noise growth too large for PIR circuit.");
  }

  return absl::OkStatus();
}

absl::StatusOr<RuntimeParameters> ComputeRuntimeParameters(
    const ConfigurationParameters& config_params) {
  RuntimeParameters runtime_params;

  ASSIGN_OR_RETURN(
      private_retrieval::XpirRuntimeParams xpir_runtime_params,
      private_retrieval::ComputeXpirRuntimeParams(
          config_params.database_params(), config_params.xpir_params()));

  *runtime_params.mutable_xpir_runtime_params() = xpir_runtime_params;
  return runtime_params;
}

ConfigurationParameters ConfigurationParameters_PIR128(
    const uint64_t database_size, const uint64_t entry_size,
    const uint64_t levels_of_recursion) {
  // Set Ring-LWE parameters.
  private_retrieval::RingLweParams ring_lwe_params;
  ring_lwe_params.mutable_int_64_modulus()->add_modulus(kModulusPIR128);
  ring_lwe_params.mutable_int_64_modulus()->add_modulus(
      kModulusSwitchingPIR128);
  ring_lwe_params.set_log_degree(kLogNPIR128);
  ring_lwe_params.set_log_t(kLogTPIR128);
  ring_lwe_params.set_variance(kVariancePIR128);

  private_retrieval::PirParams pir_params;
  pir_params.set_levels_of_recursion(kLevelRecursionPIR128);

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

ConfigurationParameters ConfigurationParameters_PIR256(
    const uint64_t database_size, const uint64_t entry_size,
    const uint64_t levels_of_recursion) {
  // Set Ring-LWE parameters.
  private_retrieval::RingLweParams ring_lwe_params;
  ring_lwe_params.mutable_int_64_modulus()->add_modulus(kModulusPIR256);
  ring_lwe_params.mutable_int_64_modulus()->add_modulus(
      kModulusSwitchingPIR256);
  ring_lwe_params.set_log_degree(kLogNPIR256);
  ring_lwe_params.set_log_t(kLogTPIR256);
  ring_lwe_params.set_variance(kVariancePIR256);

  private_retrieval::PirParams pir_params;
  pir_params.set_levels_of_recursion(kLevelRecursionPIR256);

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

absl::StatusOr<
    std::unique_ptr<const rlwe::RlweContext<rlwe::MontgomeryInt<uint64_t>>>>
GetContext(const ConfigurationParameters& params) {
  if (!params.has_xpir_params() ||
      !params.xpir_params().has_ring_lwe_params() ||
      !params.xpir_params().ring_lwe_params().has_log_degree() ||
      !params.xpir_params().ring_lwe_params().has_log_t() ||
      !params.xpir_params().ring_lwe_params().has_variance() ||
      params.xpir_params().ring_lwe_params().modulus_kind_case() !=
          private_retrieval::RingLweParams::kInt64Modulus ||
      !params.xpir_params().ring_lwe_params().has_int_64_modulus() ||
      params.xpir_params().ring_lwe_params().int_64_modulus().modulus_size() <=
          0) {
    return absl::InvalidArgumentError(
        "Cannot construct Context due to invalid configuration "
        "parameters:\n" +
        params.DebugString());
  }
  uint64_t modulus =
      params.xpir_params().ring_lwe_params().int_64_modulus().modulus(0);
  size_t log_n = params.xpir_params().ring_lwe_params().log_degree();
  size_t log_t = params.xpir_params().ring_lwe_params().log_t();
  size_t variance = params.xpir_params().ring_lwe_params().variance();

  return rlwe::RlweContext<rlwe::MontgomeryInt<uint64_t>>::Create(
      {.modulus = modulus,
       .log_n = log_n,
       .log_t = log_t,
       .variance = variance});
}

absl::StatusOr<std::unique_ptr<const rlwe::MontgomeryInt<uint64_t>::Params>>
GetModulusParams(const ConfigurationParameters& params) {
  if (!params.has_xpir_params() ||
      !params.xpir_params().has_ring_lwe_params() ||
      params.xpir_params().ring_lwe_params().modulus_kind_case() !=
          private_retrieval::RingLweParams::kInt64Modulus ||
      !params.xpir_params().ring_lwe_params().has_int_64_modulus() ||
      params.xpir_params().ring_lwe_params().int_64_modulus().modulus_size() <=
          0) {
    return absl::InvalidArgumentError(
        "Cannot construct ModulusParams due to invalid configuration "
        "parameters:\n" +
        params.DebugString());
  }
  int64_t modulus =
      params.xpir_params().ring_lwe_params().int_64_modulus().modulus(0);
  return rlwe::MontgomeryInt<uint64_t>::Params::Create(modulus);
}

absl::StatusOr<rlwe::NttParameters<rlwe::MontgomeryInt<uint64_t>>> GetNttParams(
    const ConfigurationParameters& params,
    const rlwe::MontgomeryInt<uint64_t>::Params* modulus_params) {
  if (!params.has_xpir_params() ||
      !params.xpir_params().has_ring_lwe_params() ||
      !params.xpir_params().ring_lwe_params().has_log_degree()) {
    return absl::InvalidArgumentError(
        "Cannot construct NttParameters due to invalid configuration "
        "parameters.");
  }
  return rlwe::InitializeNttParameters<rlwe::MontgomeryInt<uint64_t>>(
      params.xpir_params().ring_lwe_params().log_degree(), modulus_params);
}

}  // namespace pir
}  // namespace private_retrieval
