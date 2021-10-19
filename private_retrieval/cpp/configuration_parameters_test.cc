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

#include "private_retrieval/cpp/client.h"
#include "private_retrieval/cpp/default_databases.h"
#include "private_retrieval/cpp/generate_databases.h"
#include "private_retrieval/cpp/pir_parameters.pb.h"
#include "private_retrieval/cpp/testing_utils.h"
#include "private_retrieval/cpp/internal/status/status_macros.h"
#include "private_retrieval/cpp/internal/status/status_matchers.h"
#include "private_retrieval/cpp/internal/pir.pb.h"
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include "absl/status/status.h"
#include "absl/strings/str_cat.h"

namespace private_retrieval {
namespace pir {
namespace {

using ::testing::HasSubstr;
using ::testing::TestWithParam;
using ::testing::Values;

const uint64_t kLogTTooLarge = 63;
const uint64_t kRecursionLevels = 1;
const uint64_t kDatabaseSize = 37;
const uint64_t kEntrySize = 256;
const uint64_t kTestIndex = 2;

TEST(FailedConfigurationParametersTest, MissingSubmessages) {
  ConfigurationParameters configuration_params;
  auto result = VerifyConfigurationParams(configuration_params);
  EXPECT_EQ(result.code(), absl::StatusCode::kInvalidArgument);
  EXPECT_THAT(result.message(), HasSubstr("missing required sub-messages"));
}

TEST(FailedConfigurationParametersTest, LogTBoundaryWithSmallerModulus) {
  // Set Ring-LWE params with a 49-bit prime and a 29-bit prime.
  uint64_t modulus49 = 387102083209217;
  ConfigurationParameters configuration_params =
      ConfigurationParameters_59_1024(kDatabaseSize, kEntrySize,
                                      kRecursionLevels);  // log_t = 2
  configuration_params.mutable_xpir_params()
      ->mutable_ring_lwe_params()
      ->mutable_int_64_modulus()
      ->set_modulus(0, modulus49);

  // Expect log_t = 13 decrypts properly.
  int log_t = 13;
  configuration_params.mutable_xpir_params()
      ->mutable_ring_lwe_params()
      ->set_log_t(log_t);
  ASSERT_OK(VerifyConfigurationParams(configuration_params));

  // Increase log_t by one and expect failure.
  configuration_params.mutable_xpir_params()
      ->mutable_ring_lwe_params()
      ->set_log_t(log_t + 1);
  auto result = VerifyConfigurationParams(configuration_params);
  EXPECT_EQ(result.code(), absl::StatusCode::kInvalidArgument);
  EXPECT_THAT(result.message(), HasSubstr("Noise growth too large"));
}

class ConfigurationParametersTest : public TestWithParam<CreateConfig*> {
 protected:
  ConfigurationParametersTest()
      : configuration_params_(
            (*GetParam())(kDatabaseSize, kEntrySize, kRecursionLevels)),
        runtime_params_(*ComputeRuntimeParameters(configuration_params_)) {}

  ConfigurationParameters configuration_params_;
  RuntimeParameters runtime_params_;
};

TEST_P(ConfigurationParametersTest, NumSlicesMatchesWithRecursion) {
  EXPECT_OK(VerifyConfigurationParams(configuration_params_));
}

TEST_P(ConfigurationParametersTest, LogTTooLargeForCorrectness) {
  configuration_params_.mutable_xpir_params()
      ->mutable_ring_lwe_params()
      ->set_log_t(kLogTTooLarge);
  auto result = VerifyConfigurationParams(configuration_params_);
  EXPECT_EQ(result.code(), absl::StatusCode::kInvalidArgument);
  EXPECT_THAT(result.message(), HasSubstr("Noise growth too large"));
}

TEST_P(ConfigurationParametersTest,
       SuccessfulGetModulusParamsAndGetNttParamsTest) {
  ASSERT_OK_AND_ASSIGN(auto modulus_params,
                       GetModulusParams(configuration_params_));
  EXPECT_OK(GetNttParams(configuration_params_, modulus_params.get()));
}

TEST_P(ConfigurationParametersTest, FailedGetModulusParamsTest) {
  auto bad_configuration_params = configuration_params_;
  bad_configuration_params.mutable_xpir_params()
      ->mutable_ring_lwe_params()
      ->clear_int_64_modulus();
  EXPECT_EQ(GetModulusParams(bad_configuration_params).status().code(),
            absl::StatusCode::kInvalidArgument);
}

TEST_P(ConfigurationParametersTest, FailedGetNttParamsTest) {
  auto bad_configuration_params = configuration_params_;
  bad_configuration_params.mutable_xpir_params()
      ->mutable_ring_lwe_params()
      ->clear_log_degree();
  ASSERT_OK_AND_ASSIGN(auto modulus_params,
                       GetModulusParams(configuration_params_));
  EXPECT_EQ(GetNttParams(bad_configuration_params, modulus_params.get())
                .status()
                .code(),
            absl::StatusCode::kInvalidArgument);
}

TEST_P(ConfigurationParametersTest, SuccessfulGetContextTest) {
  ASSERT_OK_AND_ASSIGN(auto context, GetContext(configuration_params_));
}

TEST_P(ConfigurationParametersTest, FailedGetContextTestNoModulus) {
  auto bad_configuration_params = configuration_params_;
  bad_configuration_params.mutable_xpir_params()
      ->mutable_ring_lwe_params()
      ->clear_int_64_modulus();
  EXPECT_EQ(GetContext(bad_configuration_params).status().code(),
            absl::StatusCode::kInvalidArgument);
}

TEST_P(ConfigurationParametersTest, FailedGetContextTestNoDegree) {
  auto bad_configuration_params = configuration_params_;
  bad_configuration_params.mutable_xpir_params()
      ->mutable_ring_lwe_params()
      ->clear_log_degree();
  EXPECT_EQ(GetContext(bad_configuration_params).status().code(),
            absl::StatusCode::kInvalidArgument);
}

TEST_P(ConfigurationParametersTest, FailedGetContextTestNoPlaintext) {
  auto bad_configuration_params = configuration_params_;
  bad_configuration_params.mutable_xpir_params()
      ->mutable_ring_lwe_params()
      ->clear_log_t();
  EXPECT_EQ(GetContext(bad_configuration_params).status().code(),
            absl::StatusCode::kInvalidArgument);
}

TEST_P(ConfigurationParametersTest, FailedGetContextTestNoVariance) {
  auto bad_configuration_params = configuration_params_;
  bad_configuration_params.mutable_xpir_params()
      ->mutable_ring_lwe_params()
      ->clear_variance();
  EXPECT_EQ(GetContext(bad_configuration_params).status().code(),
            absl::StatusCode::kInvalidArgument);
}

TEST_P(ConfigurationParametersTest, MissingXpirParamsTest) {
  auto bad_xpir_params = configuration_params_.xpir_params();
  bad_xpir_params.clear_pir_params();
  EXPECT_EQ(VerifyXpirParams(bad_xpir_params).code(),
            absl::StatusCode::kInvalidArgument);

  bad_xpir_params = configuration_params_.xpir_params();
  bad_xpir_params.clear_ring_lwe_params();
  EXPECT_EQ(VerifyXpirParams(bad_xpir_params).code(),
            absl::StatusCode::kInvalidArgument);
}

INSTANTIATE_TEST_SUITE_P(ConfigurationParametersWithManyConfigsTest,
                         ConfigurationParametersTest,
                         Values(&ConfigurationParameters_59_1024,
                                &ConfigurationParameters_PIR128,
                                &ConfigurationParameters_PIR256));

TEST(ConfigurationParameters128, Correctness) {
  for (int database_size = 2; database_size <= 128; database_size <<= 1) {
    auto configuration_params = ConfigurationParameters_PIR128(
        database_size, kEntrySize, kRecursionLevels);
    EXPECT_OK(VerifyConfigurationParams(configuration_params));
  }
}

TEST(ConfigurationParameters256, Correctness) {
  for (int database_size = 2; database_size <= 256; database_size <<= 1) {
    auto configuration_params = ConfigurationParameters_PIR256(
        database_size, kEntrySize, kRecursionLevels);
    EXPECT_OK(VerifyConfigurationParams(configuration_params));
  }
}

}  // namespace
}  // namespace pir
}  // namespace private_retrieval
