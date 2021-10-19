load("@rules_cc//cc:defs.bzl", "cc_library")
load("@rules_proto//proto:defs.bzl", "proto_library")

package(default_visibility = ["//:__subpackages__"])

# PIR protocol buffers for protocol parameters, requests, and responses.
proto_library(
    name = "pir_proto",
    srcs = [
        "pir_parameters.proto",
    ],
    deps = [
        "//private_retrieval/cpp/internal:pir_proto",
        "@com_github_google_shell//shell_encryption:serialization_proto",
    ],
)

cc_proto_library(
    name = "pir_cc_proto",
    deps = [":pir_proto"],
)

java_proto_library(
    name = "pir_java_proto",
    deps = [":pir_proto"],
)

java_lite_proto_library(
    name = "pir_java_proto_lite",
    deps = [":pir_proto"],
)

# Configuration parameter utils.
cc_library(
    name = "configuration_parameters",
    srcs = ["configuration_parameters.cc"],
    hdrs = ["configuration_parameters.h"],
    deps = [
        ":pir_cc_proto",
        "//private_retrieval/cpp/internal:pir_cc_proto",
        "//private_retrieval/cpp/internal:pir_utils",
        "//private_retrieval/cpp/internal:runtime_params",
        "//private_retrieval/cpp/internal/status:status_macros",
        "@com_github_google_shell//shell_encryption:context",
        "@com_github_google_shell//shell_encryption:integral_types",
        "@com_github_google_shell//shell_encryption:montgomery",
        "@com_google_absl//absl/status",
        "@com_google_absl//absl/status:statusor",
    ],
)

cc_test(
    name = "configuration_parameters_test",
    srcs = ["configuration_parameters_test.cc"],
    deps = [
        ":client",
        ":configuration_parameters",
        ":default_databases",
        ":generate_databases",
        ":pir_cc_proto",
        ":testing_utils",
        "//private_retrieval/cpp/internal:pir_cc_proto",
        "//private_retrieval/cpp/internal/status:status_macros",
        "//private_retrieval/cpp/internal/status:status_matchers",
        "@com_github_google_googletest//:gtest_main",
        "@com_google_absl//absl/status",
        "@com_google_absl//absl/strings",
    ],
)

cc_library(
    name = "testing_utils",
    testonly = 1,
    hdrs = ["testing_utils.h"],
    deps = [
        ":configuration_parameters",
        ":generate_databases",
        ":pir_cc_proto",
        "//private_retrieval/cpp/internal:pir_utils",
        "//private_retrieval/cpp/internal/status:status_macros",
        "@com_github_google_shell//shell_encryption:constants",
        "@com_github_google_shell//shell_encryption:montgomery",
        "@com_github_google_shell//shell_encryption:ntt_parameters",
        "@com_github_google_shell//shell_encryption:polynomial",
        "@com_github_google_shell//shell_encryption/testing:testing_utils",
        "@com_google_absl//absl/status",
        "@com_google_absl//absl/status:statusor",
    ],
)

cc_library(
    name = "default_databases",
    testonly = 1,
    srcs = ["default_databases.cc"],
    hdrs = ["default_databases.h"],
    deps = [
        ":configuration_parameters",
        ":generate_databases",
        ":pir_cc_proto",
        "@com_github_google_shell//shell_encryption:polynomial",
        "@com_google_absl//absl/status",
        "@com_google_absl//absl/status:statusor",
    ],
)

cc_library(
    name = "generate_databases",
    testonly = 1,
    srcs = ["generate_databases.cc"],
    hdrs = ["generate_databases.h"],
)

# PIR client API.
cc_library(
    name = "client",
    srcs = ["client.cc"],
    hdrs = ["client.h"],
    deps = [
        ":configuration_parameters",
        ":pir_cc_proto",
        "//private_retrieval/cpp/internal:pir_client",
        "//private_retrieval/cpp/internal:pir_utils",
        "//private_retrieval/cpp/internal/status:status_macros",
        "@com_github_google_shell//shell_encryption:constants",
        "@com_github_google_shell//shell_encryption:montgomery",
        "@com_github_google_shell//shell_encryption:symmetric_encryption",
        "@com_github_google_shell//shell_encryption/prng:single_thread_hkdf_prng",
        "@com_google_absl//absl/memory",
        "@com_google_absl//absl/status",
        "@com_google_absl//absl/status:statusor",
        "@com_google_absl//absl/types:optional",
    ],
)


