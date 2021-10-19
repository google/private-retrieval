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

#ifndef PRIVATE_RETRIEVAL_CPP_INTERNAL_PIR_CLIENT_H_
#define PRIVATE_RETRIEVAL_CPP_INTERNAL_PIR_CLIENT_H_

#include <cmath>
#include <cstdint>
#include <vector>

#include "private_retrieval/cpp/internal/status/status_macros.h"
#include "private_retrieval/cpp/internal/pir.pb.h"
#include "private_retrieval/cpp/internal/pir_utils.h"
#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "shell_encryption/galois_key.h"
#include "shell_encryption/oblivious_expand.h"
#include "shell_encryption/prng/single_thread_hkdf_prng.h"
#include "shell_encryption/serialization.pb.h"
#include "shell_encryption/symmetric_encryption.h"
#include "shell_encryption/symmetric_encryption_with_prng.h"

namespace private_retrieval {
namespace client {
namespace internal {

inline absl::Status CheckErrorConditions(ssize_t size_of_database,
                                         int levels_of_recursion, int index,
                                         int log_compression_factor,
                                         int log_decomposition_modulus,
                                         int num_coeffs, int log_modulus) {
  // Error conditions.
  if (size_of_database <= 0) {
    return absl::Status(absl::StatusCode::kInvalidArgument,
                        "Invalid database size.");
  } else if (index < 0 || index >= size_of_database) {
    return absl::Status(absl::StatusCode::kInvalidArgument,
                        "Index out of range for database.");
  } else if (levels_of_recursion <= 0) {
    return absl::Status(absl::StatusCode::kInvalidArgument,
                        "Must have at least one level of recursion.");
  } else if (log_compression_factor < 0 ||
             num_coeffs < (1 << log_compression_factor)) {
    return absl::Status(
        absl::StatusCode::kInvalidArgument,
        "Compression depth out of range for number of coefficients.");
  } else if (log_decomposition_modulus < 1 ||
             log_modulus < log_decomposition_modulus) {
    return absl::Status(absl::StatusCode::kInvalidArgument,
                        "Decomposition modulus out of range for modulus size.");
  }
  return absl::OkStatus();
}

// Returns a PirRequest.
template <typename ModularInt>
absl::StatusOr<private_retrieval::PirRequest> BuildRequestInternal(
    const rlwe::SymmetricRlweKey<ModularInt>& key, int size_of_database,
    int levels_of_recursion, int index, int log_compression_factor,
    int log_decomposition_modulus, bool send_galois_generator) {
  private_retrieval::PirRequest req;
  // Set the number of levels of recursion in the request proto.
  req.set_levels_of_recursion(levels_of_recursion);

  // Convert the "0" and "1" messages to NTT form. This does not require to
  // call ConvertToNtt, because:
  // - NTT(0, ..., 0) = (0, ..., 0)
  // - NTT(1, 0, ..., 0) = (1, ..., 1)
  std::vector<ModularInt> zero_vec(key.Len(),
                                   ModularInt::ImportZero(key.ModulusParams()));
  std::vector<ModularInt> one_vec(key.Len(),
                                  ModularInt::ImportOne(key.ModulusParams()));

  auto zero = rlwe::Polynomial<ModularInt>(std::move(zero_vec));
  auto one = rlwe::Polynomial<ModularInt>(std::move(one_vec));

  // The number of virtual entries per level of recursion = the
  // (levels_of_recursion)th root of the number of items in the database.
  double exact_entries_per_level =
      pow(size_of_database, 1.0 / levels_of_recursion);
  // Round this number up to the nearest whole integer.
  unsigned int branching_factor =
      static_cast<unsigned int>(ceil(exact_entries_per_level));

  // Create the ciphertexts for each level of recursion. This two-dimensional
  // table is flattened when it is put into the proto.

  // Determine the number of actual database items stored in each virtual
  // database block at this level. This is the number of items remaining
  // divided by the branching factor, rounded up.
  unsigned int items_in_block =
      (size_of_database + branching_factor - 1) / branching_factor;

  // The index of the item we want to request at the current level of recursion.
  unsigned int index_remaining = index;

  std::vector<rlwe::Polynomial<ModularInt>> plaintexts;
  // Create the messages for each level of recursion.
  for (int level = 0; level < levels_of_recursion; level++) {
    // Determine which block contains the item we wish to request.
    const int index_at_level = index_remaining / items_in_block;

    // Determine the index of the desired item within that block. This is
    // the index within the items that remain after this level of recursion.
    index_remaining = index_remaining % items_in_block;

    // Create the ciphertexts for each level. If we are compressing and using
    // ObliviousExpand, there will be (branching_factor / compression_factor)
    // ciphertexts at each level, with the (index_at_level / compression_factor)
    // one being compression_factor}} and the rest {0}. If we are not using
    // ObliviousExpand (i.e. compression_factor = 1), there will be using
    // ObliviousExpand (i.e. compression_factor = 1), this means that there will
    // be branching_factor ciphertexts at each level of recursion, with the
    // index_at_level being {1}, and the rest {0}.
    ASSIGN_OR_RETURN(
        std::vector<rlwe::Polynomial<ModularInt>> query_at_level,
        rlwe::MakeCompressedVector(branching_factor, {index_at_level},
                                   log_compression_factor, key.ModulusParams(),
                                   key.NttParams()));

    plaintexts.insert(plaintexts.end(),
                      std::make_move_iterator(query_at_level.begin()),
                      std::make_move_iterator(query_at_level.end()));

    // Update the block size for the next level of recursion.
    items_in_block = (items_in_block + branching_factor - 1) / branching_factor;
  }

  // When expanding the vector of ciphertexts, the coefficients will be
  // multiplied by 2^log_compression_factor. Since the plaintext space cannot be
  // a power of two in the ObliviousExpand protocol, we pre-multiply the
  // plaintexts by 2^-log_compression_factor modulo the plaintext space.
  auto normalizer = rlwe::ObliviousExpander<ModularInt>::ComputeNormalizer(
      log_compression_factor, key.LogT());
  ASSIGN_OR_RETURN(auto m_normalizer,
                   ModularInt::ImportInt(normalizer, key.ModulusParams()));
  for (auto& plaintext : plaintexts) {
    RETURN_IF_ERROR(plaintext.MulInPlace(m_normalizer, key.ModulusParams()));
  }

  ASSIGN_OR_RETURN(auto prng_seed, rlwe::SingleThreadHkdfPrng::GenerateSeed());
  req.set_prng_seed(prng_seed);
  ASSIGN_OR_RETURN(auto prng, rlwe::SingleThreadHkdfPrng::Create(prng_seed));
  ASSIGN_OR_RETURN(std::string prng_encryption_seed,
                   rlwe::SingleThreadHkdfPrng::GenerateSeed());
  ASSIGN_OR_RETURN(auto prng_encryption,
                   rlwe::SingleThreadHkdfPrng::Create(prng_encryption_seed));
  ASSIGN_OR_RETURN(std::vector<rlwe::Polynomial<ModularInt>> ciphertexts,
                   rlwe::EncryptWithPrng(key, plaintexts, prng.get(),
                                         prng_encryption.get()));
  for (int i = 0; i < ciphertexts.size(); ++i) {
    ASSIGN_OR_RETURN(*req.add_request(),
                     ciphertexts[i].Serialize(key.ModulusParams()));
  }

  // Set Galois keys, either with a generator or by sending all keys.
  if (send_galois_generator && log_compression_factor > 0) {
    req.mutable_key_generator()->set_levels_of_expand(log_compression_factor);
    ASSIGN_OR_RETURN(auto galois_key,
                     rlwe::GaloisKey<ModularInt>::Create(
                         key, rlwe::PRNG_TYPE_HKDF,
                         rlwe::GaloisGeneratorObliviousExpander<
                             ModularInt>::GetSubstitutionGenerator(),
                         log_decomposition_modulus));
    ASSIGN_OR_RETURN(*req.mutable_key_generator()->mutable_generator(),
                     galois_key.Serialize());
  } else {
    for (int i = 0; i < log_compression_factor; ++i) {
      ASSIGN_OR_RETURN(auto galois_key,
                       rlwe::GaloisKey<ModularInt>::Create(
                           key, rlwe::PRNG_TYPE_HKDF, (key.Len() >> i) + 1,
                           log_decomposition_modulus));
      ASSIGN_OR_RETURN(*req.mutable_all_keys()->add_keys(),
                       galois_key.Serialize());
    }
  }

  return req;
}

}  //  namespace internal

// Build a request to query for item "index" from a PIR database.
// levels_of_recursion is set to 1 if no recursion is to occur (i.e., only one
// level of recursion).
// log_compression_factor is set to 0 when no query compression is to occur.
// (i.e. we send N ciphertexts for a size N database).
//
// In non-recursive and non-compressed queries PIR, the database contains N
// items. If the client wants the item at index k, the client will send a query
// with an encrypted 0 (written as {0}) for each index other than k and an
// encrypted 1 (written as {1}) for index k. Suppose the database has six items
// and the client wants item D:
//
//   Database:    A    B    C    D    E    F
//   Client:     {0}  {0}  {0}  {1}  {0}  {0}
//               ----------------------------
//   Multiplied: {0}  {0}  {0}  {D}  {0}  {0} --> Added: {D}
//
// The database thereby sends the requested item, encrypted, back to the client.
//
// In recursive PIR, the database is broken into virtual blocks. For example,
// consider a database with eight items and three levels of recursion. We first
// split that database into two virtual blocks, containing four elements each:
//
//   Database:   [A    B    C    D]  [E    F    G    H]
//
// The client, who still wants item D, sends two messages to determine which
// of these virtual blocks it wants:
//
//   Database:   [A    B    C    D]  [E    F    G    H]
//   Client              {1}                 {0}
//
// Every item in the first block is separately multiplied by the first client
// message, and every item in the second block is separately multiplied by the
// second client message. The above diagram can equivalently be written as:
//
//   Database:    A    B    C    D    E    F    G    H
//   Client      {1}  {1}  {1}  {1}  {0}  {0}  {0}  {0}
//   Multiply:   ---------------------------------------
//               {A}  {B}  {C}  {D}  {0}  {0}  {0}  {0}
//   Add          |    |    |    |    |    |    |    |
//   Pairwise:    -----|----|----|-----    |    |    |
//                     -----|----|----------    |    |
//                          -----|---------------    |
//                               ---------------------
//   Equivalently: A*{1}+E*{0}    B*{1}+F*{0}    C*{1}+G*{0}    D*{1}+G*{0}
//   Result:       {A}    {B}    {C}    {D}
//
// Essentially, we have run PIR over the two large blocks that the database
// was originally divided into. We then do the same thing at this level.
//
//   Database:     [{A}    {B}]  [{C}    {D}]
//   Client:            {0}          {1}
//
//   Equivalently:  {A}    {B}    {C}    {D}
//                  {0}    {0}    {1}    {1}
//   Multiply:      -------------------------
//                  {0}    {0}    {C}    {D}
//   Add:            |      |      |      |
//                   -------|-------      |
//                          ---------------
//   Result:              {C}    {D}
//
// At this point, one more round of standard PIR will retrieve the correct
// element.
//
//  Database:  {C}    {D}
//  Client:    {0}    {1}
//          -------------
//  Multiply:  {0}    {D} --> Added: {D}
//
// In this recursive variant, only six messages needed to be sent from the
// client, not eight as in single-level PIR. In general, in a database with
// N items and L levels of recursion, a client needs to send:
//
//    L * (N ^ (1/L))
//
// messages. When L = 1, this is N messages. The benefits of recursion can be
// substantial. A database with 1,000,000 items and three levels of recursion
// requies a client to send 300 messages rather than 1,000,000.
//
// In the compressed query variant, the client packs the query into each
// coefficient slot of the plaintext and the server expands these ciphertexts
// into the typical query vector. The compression factor is some power of two,
// which can be at most the number of coefficients in the polynomial. In the
// simplest case, if there are 1024 elements in the database, and the
// compression factor is 1024, then in order to build a level 1 query for index
// j, the client will create a single ciphertext Enc(x^j). If the database had
// size 2048, and the client wants to request item 1034, the first ciphertext
// would be Enc(0), and the second would be Enc(x^10). In general, in a database
// with D items, L levels of recursion, and a compression factor of K, a client
// needs to send:
//
//    L * (D ^ (1/L) / K)
//
// messages. When L = 1, this is D/K messages.
// For each log_compression_factor, the client needs to send one GaloisKey.
// Their size is inversely proportional to log_decomposition_modulus, but error
// also grows linearly with the decomposition_modulus.
//
// When expanding the vector of ciphertexts, the coefficients will be
// multiplied by 2^log_compression_factor; we therefore normalize the plaintext
// vectors when building the request. Note that this requires
// 2^log_compression_factor to be invertible modulo the plaintext space.
//
// The database size must be a non-negative integer, and the index must be an
// integer between 0 and the database size. There must be at least one level of
// recursion. The compression factor is specified by log_compression_factor, and
// must have a value between 0 and log_2(num_coeffs). The decomposition modulus
// is specified by log_decomposition_modulus, and ranges from 1 to log modulus.
// Otherwise, an invalid argument will be returned.
template <typename ModularInt>
absl::StatusOr<private_retrieval::PirRequest> BuildRequest(
    const rlwe::SymmetricRlweKey<ModularInt>& key, ssize_t size_of_database,
    int levels_of_recursion, int index, int log_compression_factor = 0,
    int log_decomposition_modulus = 1, bool send_galois_generator = false) {
  RETURN_IF_ERROR(internal::CheckErrorConditions(
      size_of_database, levels_of_recursion, index, log_compression_factor,
      log_decomposition_modulus, key.Len(), key.ModulusParams()->log_modulus));

  return internal::BuildRequestInternal(
      key, size_of_database, levels_of_recursion, index, log_compression_factor,
      log_decomposition_modulus, send_galois_generator);
}

// Extract the payload of a response chunk from a PIR server.
template <typename ModularInt>
absl::StatusOr<std::vector<uint8_t>> ProcessResponseChunk(
    const rlwe::SymmetricRlweKey<ModularInt>& key,
    const rlwe::SerializedSymmetricRlweCiphertext& response_chunk,
    const rlwe::ErrorParams<ModularInt>* error_params) {
  ASSIGN_OR_RETURN(auto ciphertext,
                   rlwe::SymmetricRlweCiphertext<ModularInt>::Deserialize(
                       response_chunk, key.ModulusParams(), error_params));
  ASSIGN_OR_RETURN(std::vector<typename ModularInt::Int> plaintext,
                   Decrypt(key, ciphertext));
  ASSIGN_OR_RETURN(
      std::vector<uint8_t> bytes,
      (rlwe::TranscribeBits<typename ModularInt::Int, uint8_t>(
          plaintext, key.Len() * key.BitsPerCoeff(), key.BitsPerCoeff(), 8)));
  return bytes;
}

// Extract the payload of a response from a PIR server.
template <typename ModularInt>
absl::StatusOr<std::vector<uint8_t>> ProcessResponse(
    const rlwe::SymmetricRlweKey<ModularInt>& key, const int entry_size,
    const private_retrieval::PirResponse& response,
    const rlwe::ErrorParams<ModularInt>* error_params) {
  // Ensure the response was well-formed.
  if (!response.has_status()) {
    return absl::Status(absl::StatusCode::kInvalidArgument,
                        "Required field 'status' missing from response.");
  } else if (response.status() == private_retrieval::PIR_RESPONSE_FAILURE) {
    return absl::Status(absl::StatusCode::kInvalidArgument,
                        "Request to PIR server failed.");
  } else if (response.response_size() == 0) {
    return absl::Status(absl::StatusCode::kInvalidArgument,
                        "Server response is empty.");
  }

  // Decrypt and coalesce the response chunks.
  std::vector<uint8_t> combined_chunks;

  for (int i = 0; i < response.response_size(); i++) {
    ASSIGN_OR_RETURN(
        std::vector<uint8_t> chunk,
        ProcessResponseChunk(key, response.response(i), error_params));

    combined_chunks.insert(combined_chunks.end(),
                           std::make_move_iterator(chunk.begin()),
                           std::make_move_iterator(chunk.end()));
  }
  combined_chunks.resize(entry_size);

  return combined_chunks;
}

}  // namespace client
}  // namespace private_retrieval

#endif  // PRIVATE_RETRIEVAL_CPP_INTERNAL_PIR_CLIENT_H_
