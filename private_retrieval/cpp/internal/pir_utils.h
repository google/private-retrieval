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

#ifndef PRIVATE_RETRIEVAL_CPP_INTERNAL_PIR_UTILS_H_
#define PRIVATE_RETRIEVAL_CPP_INTERNAL_PIR_UTILS_H_

#include <cstdint>
#include <string>
#include <vector>

#include "private_retrieval/cpp/internal/status/status_macros.h"
#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "shell_encryption/polynomial.h"
#include "shell_encryption/prng/prng.h"
#include "shell_encryption/transcription.h"

namespace private_retrieval {
// Computes the capacity in bytes of the PIR protocol chunks.
int BytesPerPirChunk(int num_coeffs, int log_t);

// Computes the number of PIR protocol chunks.
int NumberOfPirChunks(int bytes_per_chunk, int bytes_per_entry);

// The input is a sequence of bits, which we wish to serialize into chunks that
// will be represented using Polynomials.
template <typename ModularInt>
absl::StatusOr<std::vector<rlwe::Polynomial<ModularInt>>> DatabaseRowToChunks(
    const std::vector<uint8_t>& input, int bytes_per_chunk, int log_t,
    const typename ModularInt::Params* modulus_params,
    const rlwe::NttParameters<ModularInt>* ntt_params) {
  int num_chunks = (input.size() + (bytes_per_chunk - 1)) / bytes_per_chunk;
  std::vector<rlwe::Polynomial<ModularInt>> output(num_chunks);
  for (int i = 0; i < num_chunks; ++i) {
    // Extract a chunk. Zero-pad the input to the size of a plaintext.
    std::vector<uint8_t> chunk(bytes_per_chunk, '\0');
    auto chunk_start = input.begin() + (i * bytes_per_chunk);
    auto chunk_end = input.end();
    if ((i + 1) * bytes_per_chunk < input.size()) {
      chunk_end = input.begin() + ((i + 1) * bytes_per_chunk);
    }
    chunk.insert(chunk.begin(), chunk_start, chunk_end);

    // Split the chunk into coefficients of log_t_ bits.
    ASSIGN_OR_RETURN(std::vector<typename ModularInt::Int> transcribed,
                     (rlwe::TranscribeBits<uint8_t, typename ModularInt::Int>(
                         chunk, bytes_per_chunk * 8, 8, log_t)));

    // Convert each coefficient of this chunk into a ModularInt.
    std::vector<ModularInt> coeffs;
    coeffs.reserve(transcribed.size());
    for (const typename ModularInt::Int& transcribed_elt : transcribed) {
      ASSIGN_OR_RETURN(auto m_elt,
                       ModularInt::ImportInt(transcribed_elt, modulus_params));
      coeffs.push_back(std::move(m_elt));
    }
    output[i] = rlwe::Polynomial<ModularInt>::ConvertToNtt(coeffs, ntt_params,
                                                           modulus_params);
  }
  return output;
}

// The input is a series of Polynomials, which we will deserialize back into
// bytes.
template <typename ModularInt>
absl::StatusOr<std::vector<uint8_t>> ChunksToDatabaseRow(
    const std::vector<rlwe::Polynomial<ModularInt>>& input, int log_t,
    const typename ModularInt::Params* modulus_params,
    const rlwe::NttParameters<ModularInt>* ntt_params) {
  std::vector<uint8_t> output;
  for (int i = 0; i < input.size(); ++i) {
    std::vector<ModularInt> inverse_ntt =
        input[i].InverseNtt(ntt_params, modulus_params);
    std::vector<typename ModularInt::Int> coeffs(inverse_ntt.size());
    for (int j = 0; j < inverse_ntt.size(); ++j) {
      coeffs[j] = inverse_ntt[j].ExportInt(modulus_params);
    }
    ASSIGN_OR_RETURN(std::vector<uint8_t> transcribed,
                     (rlwe::TranscribeBits<typename ModularInt::Int, uint8_t>(
                         coeffs, log_t * inverse_ntt.size(), log_t, 8)));
    output.insert(output.end(), std::make_move_iterator(transcribed.begin()),
                  std::make_move_iterator(transcribed.end()));
  }
  return output;
}

// Pad the input vector of bytes to "output_bit_length" bits using 10*
// padding. Places a single "1" bit after the last bit of the message followed
// by as many zeros as are necessary to fill up the space. output_bit_length
// must include at least enough room for the additional bit necessary for
// padding.
//
// Note that output will implicitly be padded to a byte boundary since it is
// a vector of bytes.
std::vector<uint8_t> Pad(std::vector<uint8_t> input,
                         unsigned int input_bit_length,
                         unsigned int output_bit_length);

// Invert the Pad function.
std::vector<uint8_t> Unpad(std::vector<uint8_t> input);

// RLWE-encode an entry of a plaintext database.
//
// Returns INVALID_ARGUMENT for malformed database input.
template <typename ModularInt>
absl::StatusOr<std::vector<rlwe::Polynomial<ModularInt>>> EncodeDatabaseEntry(
    const std::vector<uint8_t>& entry, int log_t, int num_coeffs,
    const typename ModularInt::Params* modulus_params,
    const rlwe::NttParameters<ModularInt>* ntt_params) {
  if (entry.empty()) {
    return absl::InvalidArgumentError("PIR entries must non-empty.");
  }

  // Each RLWE ciphertext can store only a limited number of plaintext
  // bits. We will need to break each database entry up into chunks
  // that will allow it to span across multiple ciphertexts.

  // Determine the number of whole bytes that can fit into a single plaintext.
  // Each coefficient of the Polynomial will be able to store log_t bits.
  // We will slice database items into chunks of this size, to ensure that
  // chunks are byte-aligned.
  int bytes_per_chunk = BytesPerPirChunk(num_coeffs, log_t);

  // Transcribe the entry.
  return DatabaseRowToChunks<ModularInt>(entry, bytes_per_chunk, log_t,
                                         modulus_params, ntt_params);
}

// RLWE-encode a plaintext database. This function requires that each entry
// of the database is the same size.
//
// Returns INVALID_ARGUMENT for malformed database input.
template <typename ModularInt>
absl::StatusOr<std::vector<std::vector<rlwe::Polynomial<ModularInt>>>>
EncodeDatabase(const std::vector<std::vector<uint8_t>>& database, int log_t,
               int num_coeffs,
               const typename ModularInt::Params* modulus_params,
               const rlwe::NttParameters<ModularInt>* ntt_params) {
  // Check that the database is nonempty
  int number_of_items = database.size();
  if (number_of_items <= 0) {
    return absl::InvalidArgumentError(
        "Cannot create a server with an empty database.");
  }

  // Check that all entries are the same size.
  int bytes_per_entry = database[0].size();

  for (const auto& entry : database) {
    if (entry.size() != bytes_per_entry)
      return absl::InvalidArgumentError(
          "All entries in database must be of the same size.");
  }

  // Each RLWE ciphertext can store only a limited number of plaintext
  // bits. We will need to break each database entry up into chunks
  // that will allow it to span across multiple ciphertexts.

  // Determine the number of whole bytes that can fit into a single plaintext.
  // Each coefficient of the Polynomial will be able to store log_t bits.
  // We will slice database items into chunks of this size, to ensure that
  // chunks are byte-aligned.
  int bytes_per_chunk = BytesPerPirChunk(num_coeffs, log_t);

  // How many chunks are needed for each database entry.
  int num_chunks = NumberOfPirChunks(bytes_per_chunk, bytes_per_entry);

  // Convert the input database into a database of Polynomials. The result
  // is a two-dimensional vector of Polynomials. The outer vector has one
  // element for each chunk. Index i of the outer vector stores a vector
  // containing chunk i of each item in the database.
  std::vector<std::vector<rlwe::Polynomial<ModularInt>>> encoded_database(
      num_chunks);
  for (auto& chunk_vector : encoded_database) {
    chunk_vector.reserve(database.size());
  }

  // Transcribe and add each entry.
  for (const std::vector<uint8_t>& entry : database) {
    ASSIGN_OR_RETURN(std::vector<rlwe::Polynomial<ModularInt>> encoded_entry,
                     EncodeDatabaseEntry<ModularInt>(
                         entry, log_t, num_coeffs, modulus_params, ntt_params));
    for (int j = 0; j < num_chunks; ++j) {
      encoded_database[j].push_back(std::move(encoded_entry[j]));
    }
  }
  return encoded_database;
}

// Deserialize an RLWE-encoded database. This function requires that each entry
// of the database is the same size.
//
// Returns INVALID_ARGUMENT for malformed database input.
template <typename ModularInt>
absl::StatusOr<std::vector<std::vector<rlwe::Polynomial<ModularInt>>>>
DeserializeDatabase(
    const std::vector<std::vector<rlwe::SerializedNttPolynomial>>& database,
    int log_t, int num_coeffs,
    const typename ModularInt::Params* modulus_params,
    const rlwe::NttParameters<ModularInt>* ntt_params) {
  // Check that the database is nonempty.
  int number_of_items = database.size();
  if (number_of_items <= 0) {
    return absl::InvalidArgumentError(
        "Cannot create a server with an empty database.");
  }

  // Check that all entries have the same number of chunks.
  int chunks_per_entry = database[0].size();
  if (chunks_per_entry <= 0) {
    return absl::InvalidArgumentError(
        "Cannot create a server with empty items.");
  }

  for (const auto& entry : database) {
    if (entry.size() != chunks_per_entry)
      return absl::InvalidArgumentError(
          "All entries in database must have the same number of chunks.");
  }

  std::vector<std::vector<rlwe::Polynomial<ModularInt>>> deserialized_database(
      chunks_per_entry);

  for (auto& chunk_vector : deserialized_database) {
    chunk_vector.reserve(number_of_items);
  }

  // Deserialize Polynomials.
  for (int i = 0; i < number_of_items; ++i) {
    for (int j = 0; j < chunks_per_entry; ++j) {
      ASSIGN_OR_RETURN(rlwe::Polynomial<ModularInt> deserialized,
                       rlwe::Polynomial<ModularInt>::Deserialize(
                           database[i][j], modulus_params));

      // Check that all polynomials have the same number of coefficients. This
      // is currently impossible to test since either deserializing or
      // creating polynomials of incorrect length crashes.
      if (deserialized.Len() != num_coeffs) {
        return absl::InvalidArgumentError(
            absl::StrCat("Cannot create a server with polynomials of length ",
                         deserialized.Len(), " != ", num_coeffs, "."));
      }

      deserialized_database[j].push_back(std::move(deserialized));
    }
  }
  return deserialized_database;
}

}  // namespace private_retrieval

#endif  // PRIVATE_RETRIEVAL_CPP_INTERNAL_PIR_UTILS_H_
