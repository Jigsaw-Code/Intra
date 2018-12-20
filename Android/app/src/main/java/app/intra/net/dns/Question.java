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
package app.intra.net.dns;

import androidx.annotation.Nullable;

public class Question {
  public final String name;
  public final short qtype;
  public final short qclass;

  Question(String name, short qtype, short qclass) {
    this.name = name;
    this.qtype = qtype;
    this.qclass = qclass;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (obj instanceof Question) {
      Question other = (Question)obj;
      return other.name.equals(name) && other.qtype == qtype && other.qclass == qclass;
    }
    return false;
  }
}
