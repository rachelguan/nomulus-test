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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.CloudTasksHelper.TaskMatcher;
import google.registry.testing.InjectExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link GenerateEscrowDepositCommand}. */
@MockitoSettings(strictness = Strictness.LENIENT)
public class GenerateEscrowDepositCommandTest
    extends CommandTestCase<GenerateEscrowDepositCommand> {

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  CloudTasksHelper cloudTasksHelper = new CloudTasksHelper();

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    createTld("anothertld");
    command = new GenerateEscrowDepositCommand();
    command.cloudTasksUtils = cloudTasksHelper.getTestCloudTasksUtils();
  }

  @Test
  void testCommand_missingTld() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommand("--watermark=2017-01-01T00:00:00Z", "--mode=thin", "-r 42", "-o test"));
    assertThat(thrown).hasMessageThat().contains("The following option is required: -t, --tld");
  }

  @Test
  void testCommand_emptyTld() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommand(
                    "--tld=",
                    "--watermark=2017-01-01T00:00:00Z",
                    "--mode=thin",
                    "-r 42",
                    "-o test"));
    assertThat(thrown).hasMessageThat().contains("Null or empty TLD specified");
  }

  @Test
  void testCommand_invalidTld() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommand(
                    "--tld=invalid",
                    "--watermark=2017-01-01T00:00:00Z",
                    "--mode=thin",
                    "-r 42",
                    "-o test"));
    assertThat(thrown).hasMessageThat().contains("TLDs do not exist: invalid");
  }

  @Test
  void testCommand_missingWatermark() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () -> runCommand("--tld=tld", "--mode=full", "-r 42", "-o test"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("The following option is required: -w, --watermark");
  }

  @Test
  void testCommand_emptyWatermark() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommand("--tld=tld", "--watermark=", "--mode=full", "-r 42", "-o test"));
    assertThat(thrown).hasMessageThat().contains("Invalid format: \"\"");
  }

  @Test
  void testCommand_missingOutdir() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommand(
                    "--tld=tld", "--watermark=2017-01-01T00:00:00Z", "--mode=thin", "-r 42"));
    assertThat(thrown).hasMessageThat().contains("The following option is required: -o, --outdir");
  }

  @Test
  void testCommand_emptyOutdir() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommand(
                    "--tld=tld",
                    "--watermark=2017-01-01T00:00:00Z",
                    "--mode=thin",
                    "--outdir=",
                    "-r 42"));
    assertThat(thrown).hasMessageThat().contains("Output subdirectory must not be empty");
  }

  @Test
  void testCommand_invalidWatermark() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommand(
                    "--tld=tld",
                    "--watermark=2017-01-01T10:00:00Z,2017-01-02T00:00:00Z",
                    "--mode=thin",
                    "-r 42",
                    "-o test"));
    assertThat(thrown).hasMessageThat().contains("Each watermark date must be the start of a day");
  }

  @Test
  void testCommand_invalidMode() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommand(
                    "--tld=tld",
                    "--watermark=2017-01-01T00:00:00Z",
                    "--mode=thing",
                    "-r 42",
                    "-o test"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Invalid value for -m parameter. Allowed values:[FULL, THIN]");
  }

  @Test
  void testCommand_invalidRevision() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommand(
                    "--tld=tld",
                    "--watermark=2017-01-01T00:00:00Z",
                    "--mode=thin",
                    "-r -1",
                    "-o test"));
    assertThat(thrown).hasMessageThat().contains("Revision must be greater than or equal to zero");
  }

  @Test
  void testCommand_successWithLenientValidationMode() throws Exception {
    runCommand(
        "--tld=tld",
        "--watermark=2017-01-01T00:00:00Z",
        "--mode=thin",
        "--lenient",
        "-r 42",
        "-o test");

    cloudTasksHelper.assertTasksEnqueued(
        "rde-report",
        new TaskMatcher()
            .url("/_dr/task/rdeStaging")
            .param("mode", "THIN")
            .param("lenient", "true")
            .param("watermarks", "2017-01-01T00:00:00.000Z")
            .param("tlds", "tld")
            .param("directory", "test")
            .param("manual", "true")
            .param("revision", "42"));
  }

  @Test
  void testCommand_successWithBeam() throws Exception {
    runCommand(
        "--tld=tld",
        "--watermark=2017-01-01T00:00:00Z",
        "--mode=thin",
        "--beam",
        "-r 42",
        "-o test");

    cloudTasksHelper.assertTasksEnqueued(
        "rde-report",
        new TaskMatcher()
            .url("/_dr/task/rdeStaging")
            .param("mode", "THIN")
            .param("beam", "true")
            .param("watermarks", "2017-01-01T00:00:00.000Z")
            .param("tlds", "tld")
            .param("directory", "test")
            .param("manual", "true")
            .param("revision", "42"));
  }

  @Test
  void testCommand_successWithDefaultValidationMode() throws Exception {
    runCommand("--tld=tld", "--watermark=2017-01-01T00:00:00Z", "--mode=thin", "-r 42", "-o test");

    cloudTasksHelper.assertTasksEnqueued(
        "rde-report",
        new TaskMatcher()
            .url("/_dr/task/rdeStaging")
            .param("mode", "THIN")
            .param("lenient", "false")
            .param("watermarks", "2017-01-01T00:00:00.000Z")
            .param("tlds", "tld")
            .param("directory", "test")
            .param("manual", "true")
            .param("revision", "42"));
  }

  @Test
  void testCommand_successWithDefaultRevision() throws Exception {
    runCommand("--tld=tld", "--watermark=2017-01-01T00:00:00Z", "--mode=thin", "-o test");

    cloudTasksHelper.assertTasksEnqueued(
        "rde-report",
        new TaskMatcher()
            .url("/_dr/task/rdeStaging")
            .param("lenient", "false")
            .param("beam", "false")
            .param("mode", "THIN")
            .param("watermarks", "2017-01-01T00:00:00.000Z")
            .param("tlds", "tld")
            .param("directory", "test")
            .param("manual", "true"));
  }

  @Test
  void testCommand_successWithDefaultMode() throws Exception {
    runCommand("--tld=tld", "--watermark=2017-01-01T00:00:00Z", "-r=42", "-o test");

    cloudTasksHelper.assertTasksEnqueued(
        "rde-report",
        new TaskMatcher()
            .url("/_dr/task/rdeStaging")
            .param("mode", "FULL")
            .param("lenient", "false")
            .param("beam", "false")
            .param("watermarks", "2017-01-01T00:00:00.000Z")
            .param("tlds", "tld")
            .param("directory", "test")
            .param("manual", "true")
            .param("revision", "42"));
  }

  @Test
  void testCommand_successWithMultipleArgumentValues() throws Exception {
    runCommand(
        "--tld=tld,anothertld",
        "--watermark=2017-01-01T00:00:00Z,2017-01-02T00:00:00Z",
        "--mode=thin",
        "-r 42",
        "-o test");

    cloudTasksHelper.assertTasksEnqueued(
        "rde-report",
        new TaskMatcher()
            .url("/_dr/task/rdeStaging")
            .param("mode", "THIN")
            .param("lenient", "false")
            .param("beam", "false")
            .param("watermarks", "2017-01-01T00:00:00.000Z,2017-01-02T00:00:00.000Z")
            .param("tlds", "tld,anothertld")
            .param("directory", "test")
            .param("manual", "true")
            .param("revision", "42"));
  }
}
