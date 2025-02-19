// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.model.tld.label;

import static com.google.common.truth.Truth.assertWithMessage;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.flogger.FluentLogger;
import com.google.common.io.Resources;
import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ResourceInfo;
import google.registry.testing.AppEngineExtension;
import java.net.URL;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Presubmit tests for {@link ReservedList} configuration files. */
class GenruleReservedListTest {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String LISTS_DIRECTORY = "google/registry/config/files/reserved/";

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @Test
  void testParse_allReservedLists() throws Exception {
    ClassPath classpath = ClassPath.from(getClass().getClassLoader());
    int numParsed = 0;
    for (ResourceInfo resource : classpath.getResources()) {
      if (resource.getResourceName().startsWith(LISTS_DIRECTORY)
          && resource.getResourceName().endsWith(".txt")) {
        testParseOfReservedListFile(resource.getResourceName());
        numParsed++;
      }
    }
    assertWithMessage("No reserved lists found").that(numParsed).isAtLeast(1);
  }

  private static void testParseOfReservedListFile(String path) {
    try {
      URL url = Resources.getResource(path);
      List<String> lines = Resources.readLines(url, UTF_8);
      new ReservedList.Builder()
          .setName("reserved-list")
          .setReservedListMapFromLines(lines)
          .build();
    } catch (Exception e) {
      throw new AssertionError("Error in reserved list " + path, e);
    }
    logger.atInfo().log("Reserved list '%s' parsed successfully.", path);
  }
}
