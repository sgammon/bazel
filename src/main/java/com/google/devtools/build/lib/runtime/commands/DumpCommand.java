// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.runtime.commands;

import static java.util.stream.Collectors.toList;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.profiler.memory.AllocationTracker;
import com.google.devtools.build.lib.profiler.memory.AllocationTracker.RuleBytes;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.BlazeCommandUtils;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.BlazeWorkspace;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.DumpCommand.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor.RuleStat;
import com.google.devtools.build.lib.util.RegexFilter;
import com.google.devtools.build.lib.util.RegexFilter.RegexFilterConverter;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.common.options.EnumConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingResult;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Implementation of the dump command. */
@Command(
  mustRunInWorkspace = false,
  options = {DumpCommand.DumpOptions.class},
  help =
      "Usage: %{product} dump <options>\n"
          + "Dumps the internal state of the %{product} server process.  This command is provided "
          + "as an aid to debugging, not as a stable interface, so users should not try to "
          + "parse the output; instead, use 'query' or 'info' for this purpose.\n%{options}",
  name = "dump",
  shortDescription = "Dumps the internal state of the %{product} server process."
)
public class DumpCommand implements BlazeCommand {

  /**
   * NB! Any changes to this class must be kept in sync with anyOutput variable value in the {@link
   * DumpCommand#exec} method below.
   */
  public static class DumpOptions extends OptionsBase {

    @Option(
      name = "packages",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help = "Dump package cache content."
    )
    public boolean dumpPackages;

    @Option(
      name = "action_cache",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help = "Dump action cache content."
    )
    public boolean dumpActionCache;

    @Option(
      name = "rule_classes",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help = "Dump rule classes."
    )
    public boolean dumpRuleClasses;

    @Option(
      name = "rules",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help = "Dump rules, including counts and memory usage (if memory is tracked)."
    )
    public boolean dumpRules;

    @Option(
        name = "skylark_memory",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.BAZEL_MONITORING},
        help =
            "Dumps a pprof-compatible memory profile to the specified path. To learn more please"
                + " see https://github.com/google/pprof.")
    public String starlarkMemory;

    @Option(
        name = "skyframe",
        defaultValue = "off",
        converter = SkyframeDumpEnumConverter.class,
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.BAZEL_MONITORING},
        help = "Dump Skyframe graph: 'off', 'summary', 'count', 'value', 'deps', or 'rdeps'.")
    public SkyframeDumpOption dumpSkyframe;

    @Option(
        name = "skykey_filter",
        defaultValue = ".*",
        converter = RegexFilterConverter.class,
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.BAZEL_MONITORING},
        help = "Regex filter of SkyKey names to output. Only used with --skyframe=deps, rdeps.")
    public RegexFilter skyKeyFilter;
  }

  /**
   * Different ways to dump information about Skyframe.
   */
  public enum SkyframeDumpOption {
    OFF,
    SUMMARY,
    COUNT,
    VALUE,
    DEPS,
    RDEPS
  }

  /**
   * Enum converter for SkyframeDumpOption.
   */
  public static class SkyframeDumpEnumConverter extends EnumConverter<SkyframeDumpOption> {
    public SkyframeDumpEnumConverter() {
      super(SkyframeDumpOption.class, "Skyframe Dump option");
    }
  }

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    BlazeRuntime runtime = env.getRuntime();
    DumpOptions dumpOptions = options.getOptions(DumpOptions.class);

    boolean anyOutput =
        dumpOptions.dumpPackages
            || dumpOptions.dumpActionCache
            || dumpOptions.dumpRuleClasses
            || dumpOptions.dumpRules
            || dumpOptions.starlarkMemory != null
            || dumpOptions.dumpSkyframe != SkyframeDumpOption.OFF;
    if (!anyOutput) {
      Collection<Class<? extends OptionsBase>> optionList = new ArrayList<>();
      optionList.add(DumpOptions.class);

      env.getReporter()
          .getOutErr()
          .printErrLn(
              BlazeCommandUtils.expandHelpTopic(
                  getClass().getAnnotation(Command.class).name(),
                  getClass().getAnnotation(Command.class).help(),
                  getClass(),
                  optionList,
                  OptionsParser.HelpVerbosity.LONG,
                  runtime.getProductName()));
      return createFailureResult("no output specified", Code.NO_OUTPUT_SPECIFIED);
    }
    PrintStream out = new PrintStream(env.getReporter().getOutErr().getOutputStream());
    try {
      out.println("Warning: this information is intended for consumption by developers");
      out.println("only, and may change at any time. Script against it at your own risk!");
      out.println();
      Optional<BlazeCommandResult> failure = Optional.empty();

      if (dumpOptions.dumpPackages) {
        env.getPackageManager().dump(out);
        out.println();
      }

      if (dumpOptions.dumpActionCache) {
        if (!dumpActionCache(env, out)) {
          failure =
              Optional.of(
                  createFailureResult("action cache dump failed", Code.ACTION_CACHE_DUMP_FAILED));
        }
        out.println();
      }

      if (dumpOptions.dumpRuleClasses) {
        dumpRuleClasses(runtime, out);
        out.println();
      }

      if (dumpOptions.dumpRules) {
        dumpRuleStats(env.getReporter(), env.getBlazeWorkspace(), env.getSkyframeExecutor(), out);
        out.println();
      }

      if (dumpOptions.starlarkMemory != null) {
        try {
          dumpStarlarkHeap(env.getBlazeWorkspace(), dumpOptions.starlarkMemory, out);
        } catch (IOException e) {
          String message = "Could not dump Starlark memory";
          env.getReporter().error(null, message, e);
          failure = Optional.of(createFailureResult(message, Code.STARLARK_HEAP_DUMP_FAILED));
        }
      }

      MemoizingEvaluator evaluator = env.getSkyframeExecutor().getEvaluator();
      switch (dumpOptions.dumpSkyframe) {
        case OFF:
          break;
        case SUMMARY:
          evaluator.dumpSummary(out);
          break;
        case COUNT:
          evaluator.dumpCount(out);
          break;
        case VALUE:
          evaluator.dumpValues(out, dumpOptions.skyKeyFilter);
          break;
        case DEPS:
          evaluator.dumpDeps(out, dumpOptions.skyKeyFilter);
          break;
        case RDEPS:
          evaluator.dumpRdeps(out, dumpOptions.skyKeyFilter);
          break;
      }

      return failure.orElse(BlazeCommandResult.success());
    } catch (InterruptedException e) {
      env.getReporter().error(null, "Interrupted", e);
      return BlazeCommandResult.failureDetail(
          FailureDetail.newBuilder()
              .setInterrupted(
                  FailureDetails.Interrupted.newBuilder()
                      .setCode(FailureDetails.Interrupted.Code.INTERRUPTED))
              .build());
    } finally {
      out.flush();
    }
  }

  private static boolean dumpActionCache(CommandEnvironment env, PrintStream out) {
    Reporter reporter = env.getReporter();
    try {
      env.getBlazeWorkspace().getOrLoadPersistentActionCache(reporter).dump(out);
    } catch (IOException e) {
      reporter.handle(Event.error("Cannot dump action cache: " + e.getMessage()));
      return false;
    }
    return true;
  }

  private static void dumpRuleClasses(BlazeRuntime runtime, PrintStream out) {
    ImmutableMap<String, RuleClass> ruleClassMap = runtime.getRuleClassProvider().getRuleClassMap();
    List<String> ruleClassNames = new ArrayList<>(ruleClassMap.keySet());
    Collections.sort(ruleClassNames);
    for (String name : ruleClassNames) {
      if (name.startsWith("$")) {
        continue;
      }
      RuleClass ruleClass = ruleClassMap.get(name);
      out.print(ruleClass + "(");
      boolean first = true;
      for (Attribute attribute : ruleClass.getAttributes()) {
        if (attribute.isImplicit()) {
          continue;
        }
        if (first) {
          first = false;
        } else {
          out.print(", ");
        }
        out.print(attribute.getName());
      }
      out.println(")");
    }
  }

  private static void dumpRuleStats(
      ExtendedEventHandler eventHandler,
      BlazeWorkspace workspace,
      SkyframeExecutor executor,
      PrintStream out)
      throws InterruptedException {
    List<RuleStat> ruleStats = executor.getRuleStats(eventHandler);
    if (ruleStats.isEmpty()) {
      out.print("No rules in Bazel server, please run a build command first.");
      return;
    }
    List<RuleStat> rules = ruleStats.stream().filter(RuleStat::isRule).collect(toList());
    List<RuleStat> aspects = ruleStats.stream().filter(r -> !r.isRule()).collect(toList());
    Map<String, RuleBytes> ruleBytes = new HashMap<>();
    Map<String, RuleBytes> aspectBytes = new HashMap<>();
    AllocationTracker allocationTracker = workspace.getAllocationTracker();
    if (allocationTracker != null) {
      allocationTracker.getRuleMemoryConsumption(ruleBytes, aspectBytes);
    }
    printRuleStatsOfType(rules, "RULE", out, ruleBytes, allocationTracker != null);
    printRuleStatsOfType(aspects, "ASPECT", out, aspectBytes, allocationTracker != null);
  }

  private static void printRuleStatsOfType(
      List<RuleStat> ruleStats,
      String type,
      PrintStream out,
      Map<String, RuleBytes> ruleToBytes,
      boolean bytesEnabled) {
    if (ruleStats.isEmpty()) {
      return;
    }
    ruleStats.sort(Comparator.comparing(RuleStat::getCount).reversed());
    int longestName =
        ruleStats.stream().map(r -> r.getName().length()).max(Integer::compareTo).get();
    int maxNameWidth = 30;
    int nameColumnWidth = Math.min(longestName, maxNameWidth);
    int numberColumnWidth = 10;
    int bytesColumnWidth = 13;
    int eachColumnWidth = 11;
    printWithPadding(out, type, nameColumnWidth);
    printWithPaddingBefore(out, "COUNT", numberColumnWidth);
    printWithPaddingBefore(out, "ACTIONS", numberColumnWidth);
    if (bytesEnabled) {
      printWithPaddingBefore(out, "BYTES", bytesColumnWidth);
      printWithPaddingBefore(out, "EACH", eachColumnWidth);
    }
    out.println();
    for (RuleStat ruleStat : ruleStats) {
      printWithPadding(
          out, truncateName(ruleStat.getName(), ruleStat.isRule(), maxNameWidth), nameColumnWidth);
      printWithPaddingBefore(out, formatLong(ruleStat.getCount()), numberColumnWidth);
      printWithPaddingBefore(out, formatLong(ruleStat.getActionCount()), numberColumnWidth);
      if (bytesEnabled) {
        RuleBytes ruleBytes = ruleToBytes.get(ruleStat.getKey());
        long bytes = ruleBytes != null ? ruleBytes.getBytes() : 0L;
        printWithPaddingBefore(out, formatLong(bytes), bytesColumnWidth);
        printWithPaddingBefore(out, formatLong(bytes / ruleStat.getCount()), eachColumnWidth);
      }
      out.println();
    }
    out.println();
  }

  private static String truncateName(String name, boolean isRule, int maxNameWidth) {
    // If this is an aspect, we'll chop off everything except the aspect name
    if (!isRule) {
      int dividerIndex = name.lastIndexOf('%');
      if (dividerIndex >= 0) {
        name = name.substring(dividerIndex + 1);
      }
    }
    if (name.length() <= maxNameWidth) {
      return name;
    }
    int starti = name.length() - maxNameWidth + "...".length();
    return "..." + name.substring(starti);
  }

  private static void printWithPadding(PrintStream out, String str, int columnWidth) {
    out.print(str);
    pad(out, columnWidth + 2, str.length());
  }

  private static void printWithPaddingBefore(PrintStream out, String str, int columnWidth) {
    pad(out, columnWidth, str.length());
    out.print(str);
    pad(out, 2, 0);
  }

  private static void pad(PrintStream out, int columnWidth, int consumed) {
    for (int i = 0; i < columnWidth - consumed; ++i) {
      out.print(' ');
    }
  }

  private static String formatLong(long number) {
    return String.format("%,d", number);
  }

  private static void dumpStarlarkHeap(BlazeWorkspace workspace, String path, PrintStream out)
      throws IOException {
    AllocationTracker allocationTracker = workspace.getAllocationTracker();
    if (allocationTracker == null) {
      out.println(
          "Cannot dump Starlark heap without running in memory tracking mode. "
              + "Please refer to the user manual for the dump commnd "
              + "for information how to turn on memory tracking.");
      return;
    }
    out.println("Dumping Starlark heap to: " + path);
    allocationTracker.dumpStarlarkAllocations(path);
  }

  private static BlazeCommandResult createFailureResult(String message, Code detailedCode) {
    return BlazeCommandResult.failureDetail(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setDumpCommand(FailureDetails.DumpCommand.newBuilder().setCode(detailedCode))
            .build());
  }
}
