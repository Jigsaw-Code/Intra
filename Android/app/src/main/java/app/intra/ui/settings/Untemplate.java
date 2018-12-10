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
package app.intra.ui.settings;

/**
 * Utility wrapper for trivial resolution of URI templates to URLs.
 */

public class Untemplate {
  /**
   * Performs variable expansion on a URI Template (RFC 6570) in the special case where all
   * variables are undefined.  This is the only case of URI templates that is needed for DOH in POST
   * mode.
   * @param template A URI template (or just a URI)
   * @return A URI produced by this template when all variables are undefined
   */
  public static String strip(String template) {
    return template.replaceAll("\\{[^}]*\\}", "");
  }
}
