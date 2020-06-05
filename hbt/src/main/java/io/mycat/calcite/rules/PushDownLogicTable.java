/**
 * Copyright (C) <2019>  <chen junwen>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.calcite.rules;

import com.google.common.collect.ImmutableList;
import io.mycat.BackendTableInfo;
import io.mycat.calcite.CalciteUtls;
import io.mycat.calcite.table.MycatLogicTable;
import io.mycat.calcite.table.MycatPhysicalTable;
import io.mycat.metadata.ShardingTableHandler;
import org.apache.calcite.interpreter.Bindables;
import org.apache.calcite.plan.*;
import org.apache.calcite.prepare.RelOptTableImpl;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.FilterableTable;
import org.apache.calcite.schema.ProjectableFilterableTable;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.tools.RelBuilder;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * @author Junwen Chen
 **/
/*
RexSimplify 简化行表达式
SubstitutionVisitor 物化结合
MaterializedViewSubstitutionVisitor
 */
public class PushDownLogicTable extends RelOptRule {
    public  static  PushDownLogicTable INSTANCE = new PushDownLogicTable();

    public PushDownLogicTable() {
        super(operand(LogicalTableScan.class, none()), "PushDownLogicTable");
    }


    public static boolean canHandle(RelOptTable table) {
        return table.unwrap(ScannableTable.class) != null
                || table.unwrap(FilterableTable.class) != null
                || table.unwrap(ProjectableFilterableTable.class) != null;
    }
    /**
     * @param call todo result set with order，backend
     */
    @Override
    public void onMatch(RelOptRuleCall call) {
        TableScan judgeObject = (TableScan) call.rels[0];
        if(canHandle(judgeObject.getTable())){
            RelNode value = toPhyTable(call.builder(), judgeObject);
            if (value != null) {
                call.transformTo(value);
            }
        }

    }

    public RelNode toPhyTable(RelBuilder builder, TableScan judgeObject) {

        Bindables.BindableTableScan bindableTableScan;
        if (judgeObject instanceof Bindables.BindableTableScan) {
            bindableTableScan = (Bindables.BindableTableScan) judgeObject;
        } else {
            bindableTableScan = Bindables.BindableTableScan.create(judgeObject.getCluster(), judgeObject.getTable());
        }
        RelOptCluster cluster = bindableTableScan.getCluster();//工具类
        RelMetadataQuery metadataQuery = cluster.getMetadataQuery();
        RelOptTable relOptTable = bindableTableScan.getTable();//包装表
        RelOptSchema relOptSchema = bindableTableScan.getTable().getRelOptSchema();//schema信息
        MycatLogicTable logicTable = relOptTable.unwrap(MycatLogicTable.class);

        RelNode value = null;
        if (logicTable != null) {
            switch (logicTable.getTable().getType()) {
                case SHARDING:
                    value = shardingTable(builder, bindableTableScan, cluster, relOptSchema, logicTable);
                    break;
                case GLOBAL:
                    value = global(cluster, bindableTableScan, relOptSchema, logicTable);
            }

        } else {
            value = bindableTableScan;
        }
        return value;
    }

    @NotNull
    private RelNode global(RelOptCluster cluster,
                           Bindables.BindableTableScan bindableTableScan,
                           RelOptSchema relOptSchema,
                           MycatLogicTable logicTable) {
        final HashSet<String> context = new HashSet<>();
        RelNode logicalTableScan;
        MycatPhysicalTable mycatPhysicalTable = logicTable.getMycatGlobalPhysicalTable(context);
        RelOptTable dataNode = RelOptTableImpl.create(
                relOptSchema,
                logicTable.getRowType(cluster.getTypeFactory()),//这里使用logicTable,避免类型不一致
                mycatPhysicalTable,
                ImmutableList.of(mycatPhysicalTable.getBackendTableInfo().getUniqueName()));
        logicalTableScan = LogicalTableScan.create(cluster, dataNode,ImmutableList.of());
        return RelOptUtil.createProject(RelOptUtil.createFilter(logicalTableScan, bindableTableScan.filters), bindableTableScan.projects);
    }

    private RelNode shardingTable(RelBuilder builder, Bindables.BindableTableScan bindableTableScan, RelOptCluster cluster, RelOptSchema relOptSchema, MycatLogicTable logicTable) {
        final HashSet<String> context = new HashSet<>();
        RelNode value;
        ArrayList<RexNode> filters = new ArrayList<>(bindableTableScan.filters == null ? Collections.emptyList() : bindableTableScan.filters);
        List<BackendTableInfo> backendTableInfos = CalciteUtls.getBackendTableInfos((ShardingTableHandler) logicTable.logicTable(), filters);

        ////////////////////////////////////////////////////////////////////////////////////////////////
        //视图优化


        ////////////////////////////////////////////////////////////////////////////////////////////////

        TreeMap<String, List<RelNode>> bindTableGroupMapByTargetName = new TreeMap<>(Comparator.comparing(x -> x));
        for (BackendTableInfo backendTableInfo : backendTableInfos) {
            String targetName = backendTableInfo.getTargetName();
            List<RelNode> queryBackendTasksList = bindTableGroupMapByTargetName.computeIfAbsent(targetName, (s) -> new ArrayList<>());
            queryBackendTasksList.add(getBindableTableScan(bindableTableScan, cluster, relOptSchema, backendTableInfo));
        }

        TreeMap<String, RelNode> relNodeGroup = new TreeMap<>(Comparator.comparing(x -> x));
        context.addAll(relNodeGroup.keySet());
        for (Map.Entry<String, List<RelNode>> entry : bindTableGroupMapByTargetName.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            builder.clear();
            RelNode union = builder.pushAll(entry.getValue()).union(true, entry.getValue().size()).build();
            relNodeGroup.put(entry.getKey(), union);
        }

        if (relNodeGroup.size() == 1) {
            value = relNodeGroup.entrySet().iterator().next().getValue();
        } else {
            if (relNodeGroup.size() < 1) {
                throw new AssertionError();
            }
            value = builder.pushAll(relNodeGroup.values()).union(true,relNodeGroup.values().size()).build();
        }
        return value;
    }

    @NotNull
    private static RelNode getBindableTableScan(Bindables.BindableTableScan bindableTableScan, RelOptCluster cluster, RelOptSchema relOptSchema, BackendTableInfo backendTableInfo) {
        String uniqueName = backendTableInfo.getUniqueName();
        MycatLogicTable unwrap = bindableTableScan.getTable().unwrap(MycatLogicTable.class);
        MycatPhysicalTable mycatPhysicalTable = unwrap.getMycatPhysicalTable(uniqueName);
        RelOptTable dataNode = RelOptTableImpl.create(
                relOptSchema,
                mycatPhysicalTable.getRowType(cluster.getTypeFactory()),
                mycatPhysicalTable,
                ImmutableList.of(uniqueName));
        RelNode logicalTableScan = LogicalTableScan.create(cluster, dataNode,ImmutableList.of());
        return RelOptUtil.createProject(RelOptUtil.createFilter(logicalTableScan, bindableTableScan.filters), bindableTableScan.projects);
    }
}