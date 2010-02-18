/*
 * Copyright 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.javascript.rhino.Node;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Optimizes the order of compiler passes.
*
 */
class PhaseOptimizer implements CompilerPass {

  // This ordering is computed offline by running with compute_phase_ordering.
  @VisibleForTesting
  static final List<String> OPTIMAL_ORDER = ImmutableList.of(
     "removeUnreachableCode",
     "removeUnusedVars",
     "foldConstants",
     "deadAssignmentsElimination",
     "inlineVariables",
     "inlineFunctions",
     "removeUnusedPrototypeProperties",
     "minimizeExitPoints");

  static final int MAX_LOOPS = 100;
  static final String OPTIMIZE_LOOP_ERROR =
      "Fixed point loop exceeded the maximum number of iterations.";

  private static final Logger logger =
      Logger.getLogger(PhaseOptimizer.class.getName());

  private List<CompilerPass> passes = Lists.newArrayList();

  private final AbstractCompiler compiler;
  private final PerformanceTracker tracker;
  private final CodeChangeHandler.RecentChange recentChange =
      new CodeChangeHandler.RecentChange();
  private boolean loopMutex = false;
  private Tracer currentTracer = null;
  private String currentPassName = null;
  private PassFactory sanityCheck = null;

  // The following static properties are only used for computing optimal
  // phase orderings. They should not be touched by normal compiler runs.
  private static boolean randomizeLoops = false;
  private static List<List<String>> loopsRun = Lists.newArrayList();

  PhaseOptimizer(AbstractCompiler compiler, PerformanceTracker tracker) {
    this.compiler = compiler;
    this.tracker = tracker;
    compiler.addChangeHandler(recentChange);
  }

  /**
   * Randomizes loops. This should only be used when computing optimal phase
   * orderings.
   */
  static void randomizeLoops() {
    randomizeLoops = true;
  }

  /**
   * Get the phase ordering of loops during this run.
   * Returns an empty list when the loops are not randomized.
   */
  static List<List<String>> getLoopsRun() {
    return loopsRun;
  }

  /**
   * Clears the phase ordering of loops during this run.
   */
  static void clearLoopsRun() {
    loopsRun.clear();
  }

  /**
   * Add the passes generated by the given factories to the compile sequence.
   *
   * Automatically pulls multi-run passes into fixed point loops. If there
   * are 2 or more multi-run passes in a row, they will run together in
   * the same fixed point loop. If A and B are in the same fixed point loop,
   * the loop will continue to run both A and B until both are finished
   * making changes.
   *
   * Other than that, the PhaseOptimizer is free to tweak the order and
   * frequency of multi-run passes in a fixed-point loop.
   */
  void consume(List<PassFactory> factories) {
    Loop currentLoop = new LoopInternal();
    boolean isCurrentLoopPopulated = false;
    for (PassFactory factory : factories) {
      if (factory.isOneTimePass()) {
        if (isCurrentLoopPopulated) {
          passes.add(currentLoop);

          currentLoop = new LoopInternal();
          isCurrentLoopPopulated = false;
        }
        addOneTimePass(factory);
      } else {
        currentLoop.addLoopedPass(factory);
        isCurrentLoopPopulated = true;
      }
    }

    if (isCurrentLoopPopulated) {
      passes.add(currentLoop);
    }
  }

  /**
   * Add the pass generated by the given factory to the compile sequence.
   * This pass will be run once.
   */
  void addOneTimePass(PassFactory factory) {
    passes.add(new PassFactoryDelegate(compiler, factory));
  }

  /**
   * Add a loop to the compile sequence. This loop will continue running
   * until the AST stops changing.
   * @return The loop structure. Pass suppliers should be added to the loop.
   */
  Loop addFixedPointLoop() {
    Loop loop = new LoopInternal();
    passes.add(loop);
    return loop;
  }

  /**
   * Adds a sanity checker to be run after every pass. Intended for development.
   */
  void setSanityCheck(PassFactory sanityCheck) {
    this.sanityCheck = sanityCheck;
  }

  /**
   * Run all the passes in the optimizer.
   */
  public void process(Node externs, Node root) {
    for (CompilerPass pass : passes) {
      pass.process(externs, root);
      if (hasHaltingErrors()) {
        return;
      }
    }
  }

  /**
   * Marks the beginning of a pass.
   */
  private void startPass(String passName) {
    Preconditions.checkState(currentTracer == null && currentPassName == null);
    currentPassName = passName;
    currentTracer = newTracer(passName);
  }

  /**
   * Marks the end of a pass.
   */
  private void endPass(Node externs, Node root) {
    Preconditions.checkState(currentTracer != null && currentPassName != null);
    stopTracer(currentTracer, currentPassName);
    String passToCheck = currentPassName;
    currentPassName = null;
    currentTracer = null;

    try {
      maybeSanityCheck(externs, root);
    } catch (Exception e) {
      // TODO(johnlenz): Remove this once the normalization checks report
      // errors instead of exceptions.
      throw new RuntimeException("Sanity check failed for " + passToCheck, e);
    }
  }

  /**
   * Runs the sanity check if it is available.
   */
  void maybeSanityCheck(Node externs, Node root) {
    if (sanityCheck != null) {
      sanityCheck.create(compiler).process(externs, root);
    }
  }

  private boolean hasHaltingErrors() {
    return compiler.hasHaltingErrors();
  }

  /**
   * Returns a new tracer for the given pass name.
   */
  private Tracer newTracer(String passName) {
    String comment = passName +
        (recentChange.hasCodeChanged() ? " on recently changed AST" : "");
    if (tracker != null) {
      tracker.recordPassStart(passName);
    }
    return new Tracer("JSCompiler", comment);
  }

  private void stopTracer(Tracer t, String passName) {
    long result = t.stop();
    if (tracker != null) {
      tracker.recordPassStop(passName, result);
    }
  }

  /**
   * A single compiler pass.
   */
  private abstract class NamedPass implements CompilerPass {
    private final String name;

    NamedPass(String name) {
      this.name = name;
    }

    public void process(Node externs, Node root) {
      logger.info(name);
      startPass(name);
      processInternal(externs, root);
      endPass(externs, root);
    }

    abstract void processInternal(Node externs, Node root);
  }

  /**
   * Delegates to a PassFactory for processing.
   */
  private class PassFactoryDelegate extends NamedPass {
    private final AbstractCompiler myCompiler;
    private final PassFactory factory;

    private PassFactoryDelegate(
        AbstractCompiler myCompiler, PassFactory factory) {
      super(factory.getName());
      this.myCompiler = myCompiler;
      this.factory = factory;
    }

    @Override
    void processInternal(Node externs, Node root) {
      factory.create(myCompiler).process(externs, root);
    }
  }

  /**
   * Runs a set of compiler passes until they reach a fixed point.
   */
  static abstract class Loop implements CompilerPass {
    abstract void addLoopedPass(PassFactory factory);
  }

  /**
   * Runs a set of compiler passes until they reach a fixed point.
   *
   * Notice that this is a non-static class, because it includes the closure
   * of PhaseOptimizer.
   */
  private class LoopInternal extends Loop {
    private final List<NamedPass> myPasses = Lists.newArrayList();
    private final Set<String> myNames = Sets.newHashSet();

    @Override
    void addLoopedPass(PassFactory factory) {
      String name = factory.getName();
      Preconditions.checkArgument(
          !myNames.contains(name),
          "Already a pass with name '" + name + "' in this loop");
      myNames.add(factory.getName());
      myPasses.add(new PassFactoryDelegate(compiler, factory));
    }

    /**
     * Gets the pass names, in order.
     */
    private List<String> getPassOrder() {
      List<String> order = Lists.newArrayList();
      for (NamedPass pass : myPasses) {
        order.add(pass.name);
      }
      return order;
    }

    public void process(Node externs, Node root) {
      Preconditions.checkState(!loopMutex, "Nested loops are forbidden");
      loopMutex = true;
      if (randomizeLoops) {
        randomizePasses();
      } else {
        optimizePasses();
      }

      try {
        // TODO(nicksantos): Use a smarter algorithm that dynamically adjusts
        // the order that passes are run in.
        int count = 0;
        out: do {
          if (count++ > MAX_LOOPS) {
            compiler.throwInternalError(OPTIMIZE_LOOP_ERROR, null);
          }

          recentChange.reset();  // reset before this round of optimizations

          for (CompilerPass pass : myPasses) {
            pass.process(externs, root);
            if (hasHaltingErrors()) {
              break out;
            }
          }

        } while (recentChange.hasCodeChanged() && !hasHaltingErrors());

        if (randomizeLoops) {
          loopsRun.add(getPassOrder());
        }
      } finally {
        loopMutex = false;
      }
    }

    /** Re-arrange the passes in a random order. */
    private void randomizePasses() {
      List<NamedPass> mixedupPasses = Lists.newArrayList();
      Random random = new Random();
      while (myPasses.size() > 0) {
        mixedupPasses.add(
            myPasses.remove(random.nextInt(myPasses.size())));
      }
      myPasses.addAll(mixedupPasses);
    }

    /** Re-arrange the passes in an optimal order. */
    private void optimizePasses() {
      // It's important that this ordering is deterministic, so that
      // multiple compiles with the same input produce exactly the same
      // results.
      //
      // To do this, grab any passes we recognize, and move them to the end
      // in an "optimal" order.
      List<NamedPass> optimalPasses = Lists.newArrayList();
      for (String passName : OPTIMAL_ORDER) {
        for (NamedPass pass : myPasses) {
          if (pass.name.equals(passName)) {
            optimalPasses.add(pass);
            break;
          }
        }
      }

      myPasses.removeAll(optimalPasses);
      myPasses.addAll(optimalPasses);
    }
  }
}
