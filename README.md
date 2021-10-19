# Private (Information) Retrieval

## Problem Statement

Private information retrieval (PIR) enables retrieving an element from a
server-held database without revealing the desired index.

## Dependencies

This library requires the following external dependencies:

*   [Abseil](https://github.com/abseil/abseil-cpp) for C++ common libraries.

*   [Bazel](https://github.com/bazelbuild/bazel) for building the library.

*   [BoringSSL](https://github.com/google/boringssl) for underlying
    cryptographic operations.

*   [GFlag](https://github.com/gflags/gflags) for flags. Needed to use glog.

*   [GLog](https://github.com/google/glog) for logging.

*   [Google Test](https://github.com/google/googletest) for unit testing the
    library.

*   [Protocol Buffers](https://github.com/google/protobuf) for data
    serialization.

*   [Shell](https://github.com/google/shell-encryption) for fully homomorphic
    encryption.

*   [Tink](https://github.com/google/tink) for cryptographic PRNGs.

## How to Build

In order to run the SHELL library, you need to install Bazel, if you don't have
it already.
[Follow the instructions for your platform on the Bazel website.](https://docs.bazel.build/versions/master/install.html)

You also need to install Git, if you don't have it already.
[Follow the instructions for your platform on the Git website.](https://git-scm.com/book/en/v2/Getting-Started-Installing-Git)

Once you've installed Bazel and Git, open a Terminal and clone the SHELL
repository into a local folder:

```shell
git clone https://github.com/google/private-retrieval.git
```

Navigate into the `private-retrieval` folder you just created, and build the
SHELL library and dependencies using Bazel. Note, the library must be built
using C++17.

```bash
cd private-retrieval
bazel build :all --cxxopt='-std=c++17'
```

You may also run all tests (recursively) using the following command:

```bash
bazel test ... --cxxopt='-std=c++17'
```

## Disclaimers

This is not an officially supported Google product. The software is provided
as-is without any guarantees or warranties, express or implied.
