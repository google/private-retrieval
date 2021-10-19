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

#ifndef PRIVATE_RETRIEVAL_CPP_CLIENT_H_
#define PRIVATE_RETRIEVAL_CPP_CLIENT_H_

#include "private_retrieval/cpp/pir_parameters.pb.h"
#include "absl/status/status.h"
#include "absl/status/statusor.h"

namespace private_retrieval {
namespace pir {

class PirClient {
 public:
  virtual ~PirClient() = default;

  // Creates a Client object, which can be used to create a PIR request, and
  // process responses. This object will hold some setup state related to the
  // Ring-LWE scheme. It will also store state related to the PIR request
  // session once an entry has been selected.
  //
  // Returns INVALID_ARGUMENT if configuration_params is ill-formed.
  static absl::StatusOr<std::unique_ptr<PirClient> > Create(
      const private_retrieval::pir::ConfigurationParameters& configuration_params);

  // Begins a session, setting the database entry desired by this client, and
  // performing associated setup. A subsequent call to CreateRequest will create
  // a PIR request for this session.
  //
  // If a session is already underway when BeginSession is called, that session
  // will be abandoned and replaced with a new session.
  //
  // Returns INVALID_ARGUMENT if the index is not in the range of the database,
  // as specified by this client's ConfigurationParameters.
  virtual absl::Status BeginSession(uint32_t entry_index) = 0;

  // Serializes the client session, together with any state related to the
  // selected entry. A client session can be restored from the serialized state.
  //
  // Returns FAILED_PRECONDITION if BeginSession or RestoreSession has not been
  // called before calling this.
  virtual absl::StatusOr<std::unique_ptr<private_retrieval::pir::SerializedClientSession> >
  SerializeSession() = 0;

  // Restores the serialized client session, including the selected entry.
  //
  // If a session is already underway when RestoreSession is called, that
  // session will be abandoned and replaced with the deserialized session.
  //
  // Returns INVALID_ARGUMENT if the supplied SerializedClientSession is not
  // consistent with this client's ConfigurationParameters.
  virtual absl::Status RestoreSession(
      const private_retrieval::pir::SerializedClientSession& serialized) = 0;

  // Creates a query vector for the desired index, stored in the returned
  // PirRequest.
  //
  // Returns FAILED_PRECONDITION if BeginSession or RestoreSession has not been
  // called before calling this.
  virtual absl::StatusOr<std::unique_ptr<PirRequest> > CreateRequest() = 0;

  // Decrypts and processes a single PirResponseChunk. Expects the chunk to be
  // consistent with ConfigurationParameters, and also consistent with any
  // session state this client has related to its selected index.
  //
  // Returns FAILED_PRECONDITION if BeginSession or RestoreSession has not been
  // called before calling this.
  // Returns INVALID_ARGUMENT if the response chunk is inconsistent with
  // ConfigurationParameters or with client's session state.
  virtual absl::StatusOr<std::string> ProcessResponseChunk(
      const private_retrieval::pir::PirResponseChunk& response_chunk) = 0;

 protected:
  PirClient() = default;
};

}  // namespace pir
}  // namespace private_retrieval

#endif  // PRIVATE_RETRIEVAL_CPP_CLIENT_H_
