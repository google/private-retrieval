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

#include "private_retrieval/cpp/internal/pir_utils.h"

#include <iterator>

namespace private_retrieval {

int BytesPerPirChunk(int num_coeffs, int log_t) {
  return (num_coeffs * log_t) / 8;
}

int NumberOfPirChunks(int bytes_per_chunk, int bytes_per_entry) {
  return (bytes_per_entry + (bytes_per_chunk - 1)) / bytes_per_chunk;
}

std::vector<uint8_t> Pad(std::vector<uint8_t> input,
                         unsigned int input_bit_length,
                         unsigned int output_bit_length) {
  // Error checking. Ensure input is shorter than length with room to pad.
  if (output_bit_length < input_bit_length + 1) {
    return std::vector<uint8_t>();
  }

  // Create the output vector.
  unsigned int output_byte_length = (output_bit_length + 7) / 8;
  std::vector<uint8_t> output = std::move(input);
  output.resize(output_byte_length, 0);

  // Get the index of the last partial byte.
  unsigned int input_byte_length = input_bit_length / 8;

  // Truncate the last partial byte (if necessary).
  if (input_bit_length % 8 != 0 && output_byte_length > input_byte_length) {
    uint8_t mask = (1 << (input_bit_length % 8)) - 1;
    output[input_byte_length] &= mask;
  }

  // Pad.
  output[input_byte_length] |= 1 << (input_bit_length % 8);

  return output;
}

std::vector<uint8_t> Unpad(std::vector<uint8_t> input) {
  // Determine where the highest-index "1" byte is.
  int end = input.size() - 1;
  while (end > 0 && input[end] == 0) {
    end--;
  }

  // Failure case: no "1" bit detected.
  if (end == 0 && input[end] == 0) {
    return std::vector<uint8_t>();
  }

  // Copy the appropriate stretch of the input.
  std::vector<uint8_t> output(std::make_move_iterator(input.begin()),
                              std::make_move_iterator(input.begin() + end + 1));

  // Find the padding bit.
  uint8_t mask = 1 << 7;
  while (mask != 0 && (output[end] & mask) == 0) {
    mask >>= 1;
  }

  // Wipe out the padding bit.
  output[end] &= ~mask;

  // If the padding bit was in a byte by itself, eliminate that byte.
  if (mask == 1) {
    output.pop_back();
  }

  return output;
}

}  // namespace private_retrieval
