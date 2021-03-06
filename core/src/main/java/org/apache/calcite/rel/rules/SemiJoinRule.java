/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.rel.rules;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinInfo;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.runtime.PredicateImpl;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.RelBuilderFactory;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.ImmutableIntList;

import com.google.common.base.Predicate;
import com.google.common.collect.Lists;

import java.util.List;

/**
 * Planner rule that creates a {@code SemiJoinRule} from a
 * {@link org.apache.calcite.rel.core.Join} on top of a
 * {@link org.apache.calcite.rel.logical.LogicalAggregate}.
 */
public class SemiJoinRule extends RelOptRule {
  private static final Predicate<Join> IS_LEFT_OR_INNER =
      new PredicateImpl<Join>() {
        public boolean test(Join input) {
          switch (input.getJoinType()) {
          case LEFT:
          case INNER:
            return true;
          default:
            return false;
          }
        }
      };

  public static final SemiJoinRule INSTANCE =
      new SemiJoinRule(Project.class, Join.class, Aggregate.class,
          RelFactories.LOGICAL_BUILDER, "SemiJoinRule");

  /** Creates a SemiJoinRule. */
  public SemiJoinRule(Class<Project> projectClass, Class<Join> joinClass,
      Class<Aggregate> aggregateClass, RelBuilderFactory relBuilderFactory,
      String description) {
    super(
        operand(projectClass,
            some(
                operand(joinClass, null, IS_LEFT_OR_INNER,
                    some(operand(RelNode.class, any()),
                        operand(aggregateClass, any()))))),
        relBuilderFactory, description);
  }

  @Override public void onMatch(RelOptRuleCall call) {
    final Project project = call.rel(0);
    final Join join = call.rel(1);
    final RelNode left = call.rel(2);
    final Aggregate aggregate = call.rel(3);
    final RelOptCluster cluster = join.getCluster();
    final RexBuilder rexBuilder = cluster.getRexBuilder();
    final ImmutableBitSet bits =
        RelOptUtil.InputFinder.bits(project.getProjects(), null);
    final ImmutableBitSet rightBits =
        ImmutableBitSet.range(left.getRowType().getFieldCount(),
            join.getRowType().getFieldCount());
    if (bits.intersects(rightBits)) {
      return;
    }
    final JoinInfo joinInfo = join.analyzeCondition();
    if (!joinInfo.rightSet().equals(
        ImmutableBitSet.range(aggregate.getGroupCount()))) {
      // Rule requires that aggregate key to be the same as the join key.
      // By the way, neither a super-set nor a sub-set would work.
      return;
    }
    if (!joinInfo.isEqui()) {
      return;
    }
    final RelBuilder relBuilder = call.builder();
    relBuilder.push(left);
    switch (join.getJoinType()) {
    case INNER:
      final List<Integer> newRightKeyBuilder = Lists.newArrayList();
      final List<Integer> aggregateKeys = aggregate.getGroupSet().asList();
      for (int key : joinInfo.rightKeys) {
        newRightKeyBuilder.add(aggregateKeys.get(key));
      }
      final ImmutableIntList newRightKeys = ImmutableIntList.copyOf(newRightKeyBuilder);
      relBuilder.push(aggregate.getInput());
      final RexNode newCondition =
          RelOptUtil.createEquiJoinCondition(relBuilder.peek(2, 0),
              joinInfo.leftKeys, relBuilder.peek(2, 1), newRightKeys,
              rexBuilder);
      relBuilder.semiJoin(newCondition);
      break;

    case LEFT:
      // The right-hand side produces no more than 1 row (because of the
      // Aggregate) and no fewer than 1 row (because of LEFT), and therefore
      // we can eliminate the semi-join.
      break;

    default:
      throw new AssertionError(join.getJoinType());
    }
    relBuilder.project(project.getProjects(), project.getRowType().getFieldNames());
    call.transformTo(relBuilder.build());
  }
}

// End SemiJoinRule.java
