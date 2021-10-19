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

#include "private_retrieval/cpp/client.h"

#include "private_retrieval/cpp/configuration_parameters.h"
#include "private_retrieval/cpp/internal/status/status_macros.h"
#include "private_retrieval/cpp/internal/pir_client.h"
#include "private_retrieval/cpp/internal/pir_utils.h"
#include "absl/memory/memory.h"
#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/types/optional.h"
#include "shell_encryption/constants.h"
#include "shell_encryption/montgomery.h"
#include "shell_encryption/prng/single_thread_hkdf_prng.h"
#include "shell_encryption/symmetric_encryption.h"

namespace private_retrieval {
namespace pir {

namespace {
using uint_m = ::rlwe::MontgomeryInt<uint64_t>;
using ::rlwe::NttParameters;

// Implements a Client for an XPIR-style protocol using underlying arithmetic
// (uint64_t, uint128_t, BigNum) as specified by the template parameter
// ModularInt.
template <typename ModularInt>
class XpirClientImpl : public PirClient {
 public:
  explicit XpirClientImpl(
      const ConfigurationParameters& configuration_params,
      const RuntimeParameters& runtime_params,
      std::unique_ptr<const uint_m::Params> encrypt_modulus_params,
      std::unique_ptr<const NttParameters<uint_m>> encrypt_ntt_params,
      std::unique_ptr<const rlwe::ErrorParams<uint_m>> encrypt_error_params,
      std::unique_ptr<const uint_m::Params> decrypt_modulus_params,
      std::unique_ptr<const NttParameters<uint_m>> decrypt_ntt_params,
      std::unique_ptr<const rlwe::ErrorParams<uint_m>> decrypt_error_params);

  absl::Status BeginSession(uint32_t index) override {
    if (index >= database_params_.database_size()) {
      return absl::InvalidArgumentError(
          absl::StrCat("Index ", index, " must be >= 0 and < database_size (",
                       database_params_.database_size(), ")"));
    }

    // Note: implicitly overrides previous session, if one existed.

    entry_has_been_selected_ = true;
    ASSIGN_OR_RETURN(std::string prng_seed,
                     rlwe::SingleThreadHkdfPrng::GenerateSeed());
    ASSIGN_OR_RETURN(auto prng, rlwe::SingleThreadHkdfPrng::Create(prng_seed));
    ASSIGN_OR_RETURN(
        encrypt_key_,
        rlwe::SymmetricRlweKey<ModularInt>::Sample(
            ring_lwe_params_.log_degree(), ring_lwe_params_.variance(),
            ring_lwe_params_.log_t(), encrypt_modulus_params_.get(),
            encrypt_ntt_params_.get(), prng.get()));
    if (decrypt_modulus_params_ && decrypt_ntt_params_) {
      ASSIGN_OR_RETURN(decrypt_key_, encrypt_key_->SwitchModulus(
                                         decrypt_modulus_params_.get(),
                                         decrypt_ntt_params_.get()));
    } else {
      decrypt_key_ = encrypt_key_.value();
    }
    index_ = index;

    return absl::OkStatus();
  }

  absl::StatusOr<std::unique_ptr<SerializedClientSession>> SerializeSession()
      override {
    if (!entry_has_been_selected_) {
      return absl::FailedPreconditionError(
          "No session underway. Call BeginSession or "
          "RestoreSession before calling SerializeClient");
    }

    auto serialized_session = absl::make_unique<SerializedClientSession>();

    ASSIGN_OR_RETURN(*(serialized_session->mutable_encrypt_key()),
                     encrypt_key_.value().Serialize());
    if (decrypt_modulus_params_ && decrypt_ntt_params_) {
      ASSIGN_OR_RETURN(*(serialized_session->mutable_decrypt_key()),
                       decrypt_key_.value().Serialize());
    }
    serialized_session->set_index(index_);
    serialized_session->set_fingerprint(fingerprint_);

    return std::move(serialized_session);
  }

  absl::Status RestoreSession(
      const SerializedClientSession& serialized) override {
    // Verify consistency of the serialized client with this client's
    // ConfigurationParameters.
    if (serialized.index() >= database_params_.database_size()) {
      return absl::InvalidArgumentError(absl::StrCat(
          "Index ", serialized.index(),
          " in the serialized session must be >= 0 and < database_size (",
          database_params_.database_size(), ")"));
    }

    // Set state from the serialized client.
    // Note: implicitly overrides previous session, if one existed.
    entry_has_been_selected_ = true;
    index_ = serialized.index();
    ASSIGN_OR_RETURN(
        encrypt_key_,
        rlwe::SymmetricRlweKey<ModularInt>::Deserialize(
            ring_lwe_params_.variance(), ring_lwe_params_.log_t(),
            serialized.encrypt_key(), encrypt_modulus_params_.get(),
            encrypt_ntt_params_.get()));
    if (decrypt_modulus_params_ && decrypt_ntt_params_) {
      ASSIGN_OR_RETURN(
          decrypt_key_,
          rlwe::SymmetricRlweKey<ModularInt>::Deserialize(
              ring_lwe_params_.variance(), ring_lwe_params_.log_t(),
              serialized.decrypt_key(), decrypt_modulus_params_.get(),
              encrypt_modulus_params_.get(), decrypt_ntt_params_.get()));
    } else {
      decrypt_key_ = encrypt_key_.value();
    }
    fingerprint_ = serialized.fingerprint();

    return absl::OkStatus();
  }

  absl::StatusOr<std::unique_ptr<PirRequest>> CreateRequest() override {
    if (!entry_has_been_selected_) {
      return absl::FailedPreconditionError(
          "No session underway. Call BeginSession or "
          "RestoreSession before calling CreateRequest");
    }

    ASSIGN_OR_RETURN(private_retrieval::PirRequest intermediate_request,
                     private_retrieval::client::BuildRequest(
                         encrypt_key_.value(), database_params_.database_size(),
                         pir_params_.levels_of_recursion(), index_));

    auto request = absl::make_unique<PirRequest>();
    *(request->mutable_query()) = intermediate_request.request();
    request->set_prng_seed(intermediate_request.prng_seed());
    request->set_fingerprint(fingerprint_);

    return std::move(request);
  }

  absl::StatusOr<std::string> ProcessResponseChunk(
      const PirResponseChunk& response_chunk) override {
    if (!entry_has_been_selected_) {
      return absl::FailedPreconditionError(
          "No session underway. Call BeginSession or "
          "RestoreSession before calling ProcessResponseChunk");
    }

    if (response_chunk.status() != PIR_RESPONSE_SUCCESS) {
      return absl::InvalidArgumentError(
          "Response chunk has non-successful status.");
    }

    // Verify fingerprint.
    if (response_chunk.fingerprint() != fingerprint_) {
      return absl::InvalidArgumentError(
          "Fingerprint in the response chunk differs from this client's "
          "state.");
    }

    // Verify that the chunk number is in range.
    if (response_chunk.chunk_number() < 0 ||
        response_chunk.chunk_number() >= runtime_params_.number_of_chunks()) {
      return absl::InvalidArgumentError(absl::StrCat(
          "Invalid chunk number: ", response_chunk.chunk_number(),
          ", expected >=0 and < ", runtime_params_.number_of_chunks()));
    }

    ASSIGN_OR_RETURN(
        std::vector<uint8_t> response_vector,
        private_retrieval::client::ProcessResponseChunk<ModularInt>(
            decrypt_key_.value(), response_chunk.chunk(),
            decrypt_error_params_.get()));

    // Unpad zeros on the final chunk so that the size of the element is
    // entry_size.
    if ((response_chunk.chunk_number() + 1) ==
        runtime_params_.number_of_chunks()) {
      int bytes_per_chunk = response_vector.size();
      // Truncate only when the entry ends in the middle of a chunk.
      if (database_params_.entry_size() % bytes_per_chunk != 0) {
        response_vector.resize(database_params_.entry_size() % bytes_per_chunk);
      }
    }

    return std::string(std::make_move_iterator(response_vector.begin()),
                       std::make_move_iterator(response_vector.end()));
  }

 private:
  // Configuration parameters.
  const private_retrieval::RingLweParams ring_lwe_params_;
  const private_retrieval::PirParams pir_params_;
  const private_retrieval::DatabaseParams database_params_;
  const private_retrieval::XpirRuntimeParams runtime_params_;

  // Parameters for the Ring-LWE modulus.
  std::unique_ptr<const typename ModularInt::Params> encrypt_modulus_params_;
  std::unique_ptr<const typename ModularInt::Params> decrypt_modulus_params_;
  // Parameters to compute NTT.
  std::unique_ptr<const NttParameters<ModularInt>> encrypt_ntt_params_;
  std::unique_ptr<const NttParameters<ModularInt>> decrypt_ntt_params_;
  // Parameters that hold ring-specific error constants.
  std::unique_ptr<const rlwe::ErrorParams<ModularInt>> encrypt_error_params_;
  std::unique_ptr<const rlwe::ErrorParams<ModularInt>> decrypt_error_params_;

  // Members below constitute the precomputed state related to the Pir Query for
  // a selected entry.
  bool entry_has_been_selected_ = false;
  absl::optional<rlwe::SymmetricRlweKey<ModularInt>> encrypt_key_ =
      absl::nullopt;
  absl::optional<rlwe::SymmetricRlweKey<ModularInt>> decrypt_key_ =
      absl::nullopt;
  uint32_t index_ = 0;
  int64_t fingerprint_ = 0;
};

// Template-specialized XpirClientImpl constructors for each underlying
// arithmetic (int64, int128, BigNum). Each specialization will pull client
// parameters from different components of the supplied ConfigurationParameters.
template <>
XpirClientImpl<uint_m>::XpirClientImpl(
    const ConfigurationParameters& configuration_params,
    const RuntimeParameters& runtime_params,
    std::unique_ptr<const uint_m::Params> encrypt_modulus_params,
    std::unique_ptr<const NttParameters<uint_m>> encrypt_ntt_params,
    std::unique_ptr<const rlwe::ErrorParams<uint_m>> encrypt_error_params,
    std::unique_ptr<const uint_m::Params> decrypt_modulus_params,
    std::unique_ptr<const NttParameters<uint_m>> decrypt_ntt_params,
    std::unique_ptr<const rlwe::ErrorParams<uint_m>> decrypt_error_params)
    : ring_lwe_params_(configuration_params.xpir_params().ring_lwe_params()),
      pir_params_(configuration_params.xpir_params().pir_params()),
      database_params_(configuration_params.database_params()),
      runtime_params_(runtime_params.xpir_runtime_params()),
      encrypt_modulus_params_(std::move(encrypt_modulus_params)),
      decrypt_modulus_params_(std::move(decrypt_modulus_params)),
      encrypt_ntt_params_(std::move(encrypt_ntt_params)),
      decrypt_ntt_params_(std::move(decrypt_ntt_params)),
      encrypt_error_params_(std::move(encrypt_error_params)),
      decrypt_error_params_(std::move(decrypt_error_params)) {}

}  // namespace

absl::StatusOr<std::unique_ptr<PirClient>> PirClient::Create(
    const ConfigurationParameters& configuration_params) {
  RETURN_IF_ERROR(VerifyConfigurationParams(configuration_params));

  std::unique_ptr<PirClient> result = nullptr;

  ASSIGN_OR_RETURN(auto runtime_params,
                   ComputeRuntimeParameters(configuration_params));
  auto ring_lwe_params = configuration_params.xpir_params().ring_lwe_params();

  switch (auto kind = configuration_params.xpir_params()
                          .ring_lwe_params()
                          .modulus_kind_case();
          kind) {
    case private_retrieval::RingLweParams::ModulusKindCase::kBigIntModulus:
      return absl::Status(absl::StatusCode::kInvalidArgument, "Unimplemented!");
    case private_retrieval::RingLweParams::ModulusKindCase::kInt32Modulus:
      return absl::Status(absl::StatusCode::kInvalidArgument, "Unimplemented!");
    case private_retrieval::RingLweParams::ModulusKindCase::kInt64Modulus: {
      ASSIGN_OR_RETURN(
          auto encrypt_modulus_params,
          uint_m::Params::Create(ring_lwe_params.int_64_modulus().modulus(0)));

      ASSIGN_OR_RETURN(
          auto temp_encrypt_ntt_params,
          rlwe::InitializeNttParameters<uint_m>(ring_lwe_params.log_degree(),
                                                encrypt_modulus_params.get()));
      auto encrypt_ntt_params = absl::make_unique<NttParameters<uint_m>>(
          std::move(temp_encrypt_ntt_params));

      ASSIGN_OR_RETURN(
          auto temp_encrypt_error_params,
          rlwe::ErrorParams<uint_m>::Create(
              ring_lwe_params.log_t(), ring_lwe_params.variance(),
              encrypt_modulus_params.get(), encrypt_ntt_params.get()));
      auto encrypt_error_params = absl::make_unique<rlwe::ErrorParams<uint_m>>(
          temp_encrypt_error_params);

      std::unique_ptr<const uint_m::Params> decrypt_modulus_params = nullptr;
      std::unique_ptr<const NttParameters<uint_m>> decrypt_ntt_params = nullptr;
      std::unique_ptr<const rlwe::ErrorParams<uint_m>> decrypt_error_params =
          nullptr;
      if (ring_lwe_params.int_64_modulus().modulus_size() > 1) {
        ASSIGN_OR_RETURN(decrypt_modulus_params,
                         uint_m::Params::Create(
                             ring_lwe_params.int_64_modulus().modulus(1)));

        ASSIGN_OR_RETURN(
            auto temp_decrypt_ntt_params,
            rlwe::InitializeNttParameters<uint_m>(
                ring_lwe_params.log_degree(), decrypt_modulus_params.get()));
        decrypt_ntt_params = absl::make_unique<const NttParameters<uint_m>>(
            std::move(temp_decrypt_ntt_params));

        ASSIGN_OR_RETURN(
            auto temp_decrypt_error_params,
            rlwe::ErrorParams<uint_m>::Create(
                ring_lwe_params.log_t(), ring_lwe_params.variance(),
                decrypt_modulus_params.get(), decrypt_ntt_params.get()));
        auto decrypt_error_params =
            absl::make_unique<const rlwe::ErrorParams<uint_m>>(
                temp_decrypt_error_params);
      }

      result = absl::make_unique<XpirClientImpl<uint_m>>(
          configuration_params, runtime_params,
          std::move(encrypt_modulus_params), std::move(encrypt_ntt_params),
          std::move(encrypt_error_params), std::move(decrypt_modulus_params),
          std::move(decrypt_ntt_params), std::move(decrypt_error_params));
      break;
    }
    case private_retrieval::RingLweParams::ModulusKindCase::
        MODULUS_KIND_NOT_SET:
      [[fallthrough]];
    default:
      return absl::Status(absl::StatusCode::kInvalidArgument,
                          "Unrecognized/missing modulus_kind!");
  }

  return std::move(result);
}

}  // namespace pir
}  // namespace private_retrieval
