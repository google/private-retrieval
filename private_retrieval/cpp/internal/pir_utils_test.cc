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

#include <cstdint>
#include <random>
#include <vector>

#include "private_retrieval/cpp/internal/status/status_macros.h"
#include "private_retrieval/cpp/internal/status/status_matchers.h"
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include "absl/numeric/int128.h"
#include "shell_encryption/constants.h"
#include "shell_encryption/montgomery.h"
#include "shell_encryption/ntt_parameters.h"
#include "shell_encryption/testing/testing_prng.h"

namespace private_retrieval {
namespace {

using uint_m = rlwe::MontgomeryInt<uint64_t>;

const uint64_t kLogT = 3;

TEST(PirUtilsTest, Pad) {
  unsigned int seed = 104;
  for (int trial = 0; trial < 50; trial++) {
    for (int bit_len = 1; bit_len < 100; bit_len++) {
      int byte_len = (bit_len + 7) / 8;

      // Create a bit string of length bit_len.
      std::vector<uint8_t> before(byte_len, 0);
      for (int i = 0; i < byte_len; i++) {
        before[i] = rand_r(&seed);
      }

      // Ensure the last byte has the correct number of bits set.
      before[byte_len - 1] &= (1 << (bit_len % 8)) - 1;

      // Pad to various bit lengths.
      for (int pad_to = bit_len + 1; pad_to < 2 * bit_len; pad_to++) {
        std::vector<uint8_t> padded = Pad(before, bit_len, pad_to);
        EXPECT_EQ(padded.size(), (pad_to + 7) / 8);

        std::vector<uint8_t> unpadded = Unpad(padded);
        EXPECT_EQ(before, unpadded);
      }
    }
  }
}

TEST(PirUtilsTest, UnpadOnZerosReturnsEmptyVector) {
  // Verify that an empty vector is returned if no "1" is detected.
  for (int bit_len = 1; bit_len < 50; bit_len++) {
    std::vector<uint8_t> zeros(bit_len, 0);
    std::vector<uint8_t> result = Unpad(zeros);
    EXPECT_EQ(result.size(), 0);
  }
}

class DatabaseRowTest : public ::testing::Test {
 protected:
  DatabaseRowTest()
      : bytes_per_chunk_((1 << rlwe::kLogDegreeBound59) * kLogT / 8) {}

  void SetUp() override {
    ASSERT_OK_AND_ASSIGN(modulus_params_,
                         uint_m::Params::Create(rlwe::kModulus59));
    ASSERT_OK_AND_ASSIGN(auto ntt_params,
                         rlwe::InitializeNttParameters<uint_m>(
                             rlwe::kLogDegreeBound59, modulus_params_.get()));
    ntt_params_ =
        absl::make_unique<rlwe::NttParameters<uint_m>>(std::move(ntt_params));
  }

  std::unique_ptr<const uint_m::Params> modulus_params_;
  std::unique_ptr<const rlwe::NttParameters<uint_m>> ntt_params_;
  const uint64_t bytes_per_chunk_;
};

TEST_F(DatabaseRowTest, SerializeAndDeserializeWithPadding) {
  std::vector<uint8_t> input = {5, 37, 1, 2};
  ASSERT_OK_AND_ASSIGN(
      auto serialized,
      DatabaseRowToChunks(input, bytes_per_chunk_, kLogT, modulus_params_.get(),
                          ntt_params_.get()));
  ASSERT_OK_AND_ASSIGN(
      auto deserialized,
      ChunksToDatabaseRow(serialized, kLogT, modulus_params_.get(),
                          ntt_params_.get()));
  EXPECT_EQ(deserialized.size() % bytes_per_chunk_, 0);
  for (int i = 0; i < input.size(); ++i) {
    EXPECT_EQ(deserialized[i], input[i]);
  }
}

TEST_F(DatabaseRowTest, SerializeAndDeserializeWithoutPadding) {
  std::vector<uint8_t> input(bytes_per_chunk_ * 3);
  ASSERT_OK_AND_ASSIGN(
      auto serialized,
      DatabaseRowToChunks(input, bytes_per_chunk_, kLogT, modulus_params_.get(),
                          ntt_params_.get()));
  ASSERT_OK_AND_ASSIGN(
      auto deserialized,
      ChunksToDatabaseRow(serialized, kLogT, modulus_params_.get(),
                          ntt_params_.get()));
  // Ensure no padding has occurred.
  EXPECT_EQ(deserialized, input);
}

TEST_F(DatabaseRowTest, SerializeAndDeserializeEmptyInput) {
  ASSERT_OK_AND_ASSIGN(
      auto serialized,
      DatabaseRowToChunks({}, (1 << rlwe::kLogDegreeBound59) * kLogT / 8, kLogT,
                          modulus_params_.get(), ntt_params_.get()));
  ASSERT_OK_AND_ASSIGN(
      auto deserialized,
      ChunksToDatabaseRow(serialized, kLogT, modulus_params_.get(),
                          ntt_params_.get()));
  EXPECT_TRUE(deserialized.empty());
}

}  // namespace
}  // namespace private_retrieval
