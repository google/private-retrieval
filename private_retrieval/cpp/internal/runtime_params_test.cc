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

#include "private_retrieval/cpp/internal/runtime_params.h"

#include "private_retrieval/cpp/internal/status/status_macros.h"
#include "private_retrieval/cpp/internal/status/status_matchers.h"
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include "absl/status/status.h"
#include "shell_encryption/constants.h"

namespace private_retrieval {

namespace {

using ::testing::StartsWith;

TEST(RuntimeParamsTest, ZeroLevelsOfRecursion) {
  DatabaseParams db_params;
  XpirParams xpir_params;
  xpir_params.mutable_pir_params()->set_levels_of_recursion(0);

  auto result = ComputeXpirRuntimeParams(db_params, xpir_params);
  EXPECT_EQ(result.status().code(), absl::StatusCode::kInvalidArgument);
  EXPECT_THAT(result.status().message(),
              StartsWith("Levels of recursion must be positive."));
}

TEST(RuntimeParamsTest, NegativeLevelsOfRecursion) {
  DatabaseParams db_params;
  XpirParams xpir_params;
  xpir_params.mutable_pir_params()->set_levels_of_recursion(-1);

  auto result = ComputeXpirRuntimeParams(db_params, xpir_params);
  EXPECT_EQ(result.status().code(), absl::StatusCode::kInvalidArgument);
  EXPECT_THAT(result.status().message(),
              StartsWith("Levels of recursion must be positive."));
}

TEST(RuntimeParamsTest, PositiveLevelsOfRecursion) {
  RingLweParams ring_lwe_params;
  ring_lwe_params.mutable_int_64_modulus()->add_modulus(rlwe::kModulus59);
  ring_lwe_params.set_log_degree(rlwe::kLogDegreeBound59);
  ring_lwe_params.set_log_t(2);
  ring_lwe_params.set_variance(8);

  PirParams pir_params;
  pir_params.set_levels_of_recursion(1);

  XpirParams xpir_params;
  *xpir_params.mutable_pir_params() = pir_params;
  *xpir_params.mutable_ring_lwe_params() = ring_lwe_params;

  DatabaseParams db_params;
  db_params.set_database_size(1);
  db_params.set_entry_size(1);

  EXPECT_OK(ComputeXpirRuntimeParams(db_params, xpir_params));
}

}  // namespace

}  // namespace private_retrieval
