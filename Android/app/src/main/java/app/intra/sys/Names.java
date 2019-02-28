/*
Copyright 2018 Jigsaw Operations LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package app.intra.sys;

// Names for intents and events that are used by multiple components and must agree.
// Each name is used to generate a string.
public enum Names {
  BOOTSTRAP,
  BOOTSTRAP_FAILED,
  BYTES,
  CHUNKS,
  DNS_STATUS,
  DOWNLOAD,
  DURATION,
  EARLY_RESET,
  FALLBACK_HOSTNAME,
  FIRST_BYTE_MS,
  LATENCY,
  PORT,
  RESULT,
  RETRY,
  SPLIT,
  TIMEOUT,
  TCP_HANDSHAKE_MS,
  TRANSACTION,
  UDP,
  UPLOAD,
}
