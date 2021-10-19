load("@rules_cc//cc:defs.bzl", "cc_library")
load("@rules_proto//proto:defs.bzl", "proto_library")

package(default_visibility = ["//:__subpackages__"])

# Proto for PIR.
proto_library(
    name = "pir_proto",
    srcs = ["pir.proto"],
    deps = ["@com_github_google_shell//shell_encryption:serialization_proto"],
)

java_lite_proto_library(
    name = "pir_java_proto_lite",
    deps = [":pir_proto"],
)

cc_proto_library(
    name = "pir_cc_proto",
    deps = [":pir_proto"],
)

java_proto_library(
    name = "pir_java_proto",
    deps = [":pir_proto"],
)

# Utility files.

cc_library(
    name = "pir_utils",
    srcs = ["pir_utils.cc"],
    hdrs = ["pir_utils.h"],
    deps = [
        "//private_retrieval/cpp/internal/status:status_macros",
        "@com_github_google_shell//shell_encryption:polynomial",
        "@com_github_google_shell//shell_encryption:transcription",
        "@com_github_google_shell//shell_encryption/prng",
        "@com_google_absl//absl/status",
        "@com_google_absl//absl/status:statusor",
    ],
)

cc_test(
    name = "pir_utils_test",
    srcs = [
        "pir_utils_test.cc",
    ],
    deps = [
        ":pir_utils",
        "//private_retrieval/cpp/internal/status:status_macros",
        "//private_retrieval/cpp/internal/status:status_matchers",
        "@com_github_google_googletest//:gtest_main",
        "@com_github_google_shell//shell_encryption:constants",
        "@com_github_google_shell//shell_encryption:montgomery",
        "@com_github_google_shell//shell_encryption:ntt_parameters",
        "@com_github_google_shell//shell_encryption/testing:testing_prng",
        "@com_google_absl//absl/numeric:int128",
    ],
)

# Library to construct runtime parameters.
cc_library(
    name = "runtime_params",
    srcs = ["runtime_params.cc"],
    hdrs = ["runtime_params.h"],
    deps = [
        ":pir_cc_proto",
        ":pir_utils",
        "@com_google_absl//absl/status",
        "@com_google_absl//absl/status:statusor",
    ],
)

cc_test(
    name = "runtime_params_test",
    srcs = ["runtime_params_test.cc"],
    deps = [
        ":runtime_params",
        "//private_retrieval/cpp/internal/status:status_macros",
        "//private_retrieval/cpp/internal/status:status_matchers",
        "@com_github_google_googletest//:gtest_main",
        "@com_github_google_shell//shell_encryption:constants",
        "@com_google_absl//absl/status",
    ],
)

# The PIR client.

cc_library(
    name = "pir_client",
    hdrs = ["pir_client.h"],
    deps = [
        ":pir_cc_proto",
        ":pir_utils",
        "//private_retrieval/cpp/internal/status:status_macros",
        "@com_github_google_shell//shell_encryption:galois_key",
        "@com_github_google_shell//shell_encryption:oblivious_expand",
        "@com_github_google_shell//shell_encryption:serialization_cc_proto",
        "@com_github_google_shell//shell_encryption:symmetric_encryption",
        "@com_github_google_shell//shell_encryption:symmetric_encryption_with_prng",
        "@com_github_google_shell//shell_encryption/prng:single_thread_hkdf_prng",
        "@com_google_absl//absl/status",
        "@com_google_absl//absl/status:statusor",
    ],
)
