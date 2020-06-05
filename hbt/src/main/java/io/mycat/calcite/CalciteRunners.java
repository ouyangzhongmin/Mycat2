package io.mycat.calcite;

import com.google.common.collect.ImmutableList;
import io.mycat.MycatConnection;
import io.mycat.api.collector.RowBaseIterator;
import io.mycat.calcite.prepare.MycatCalcitePlanner;
import io.mycat.calcite.resultset.EnumeratorRowIterator;
import io.mycat.calcite.resultset.MyCatResultSetEnumerator;
import io.mycat.calcite.rules.PushDownLogicTable;
import io.mycat.calcite.rules.UnionRule;
import io.mycat.calcite.table.MycatSQLTableScan;
import io.mycat.calcite.table.SingeTargetSQLTable;
import io.mycat.calcite.table.StreamUnionTable;
import io.mycat.datasource.jdbc.JdbcRuntime;
import io.mycat.hbt.TextConvertor;
import io.mycat.upondb.MycatDBContext;
import lombok.SneakyThrows;
import org.apache.calcite.interpreter.Interpreters;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.prepare.RelOptTableImpl;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelShuttleImpl;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.logical.LogicalUnion;
import org.apache.calcite.runtime.ArrayBindable;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.tools.RelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class CalciteRunners {
    private final static Logger LOGGER = LoggerFactory.getLogger(CalciteRunners.class);

    @SneakyThrows
    public static RelNode complie(MycatCalcitePlanner planner, String sql, boolean forUpdate) {
        SqlNode sqlNode = planner.parse(sql);
        SqlNode validate = planner.validate(sqlNode);
        RelNode relNode = planner.convert(validate);
        return complie(planner, relNode, forUpdate);
    }

    public static RelNode complie(MycatCalcitePlanner planner, RelNode relNode, boolean forUpdate) {
        try {
            relNode = planner.eliminateLogicTable(relNode);
            StringWriter stringWriter = new StringWriter();
//        final RelWriterImpl pw =
//                new RelWriterImpl(new PrintWriter(stringWriter));
//        relNode.explain(pw);
//        LOGGER.debug(stringWriter.toString());
            relNode = planner.pullUpUnion(relNode);
            relNode = planner.pushDownBySQL(relNode, forUpdate);
            return relNode;
        }catch (Throwable e){
            LOGGER.error("",e);
        }
        return null;
    }


    @SneakyThrows
    public static RowBaseIterator run(MycatCalciteDataContext calciteDataContext, RelNode relNode) {
        Map<String, List<SingeTargetSQLTable>> map = new HashMap<>();
        HepProgramBuilder hepProgramBuilder = new HepProgramBuilder();
        hepProgramBuilder.addMatchLimit(64);

        hepProgramBuilder.addRuleInstance(new UnionRule());
        final HepPlanner planner2 = new HepPlanner(hepProgramBuilder.build());
        planner2.setRoot(relNode);
        relNode = planner2.findBestExp();
//        System.out.println(  TextConvertor.dumpResultSet(relNode));
        relNode.accept(new RelShuttleImpl() {
            @Override
            public RelNode visit(TableScan scan) {
                SingeTargetSQLTable unwrap = scan.getTable().unwrap(SingeTargetSQLTable.class);
                if (unwrap != null && !unwrap.existsEnumerable()) {
                    List<SingeTargetSQLTable> tables = map.computeIfAbsent(unwrap.getTargetName(), s -> new ArrayList<>(2));
                    tables.add(unwrap);
                }
                return super.visit(scan);
            }
        });
//        UnionResolver unionResolver = new UnionResolver();
//        while (unionResolver.change) {
//            unionResolver.change = false;
//            relNode = relNode.accept(unionResolver);
//        }
//      System.out.println(  TextConvertor.dumpResultSet(relNode));
        fork(calciteDataContext, map);
        ArrayBindable bindable1 = Interpreters.bindable(relNode);
        Enumerable<Object[]> bind = bindable1.bind(calciteDataContext);

        Enumerator<Object[]> enumerator = bind.enumerator();
        return new EnumeratorRowIterator(CalciteConvertors.getMycatRowMetaData(relNode.getRowType()), enumerator);
    }

    private static void fork(MycatCalciteDataContext calciteDataContext, Map<String, List<SingeTargetSQLTable>> map) throws IllegalAccessException {


        MycatDBContext uponDBContext = calciteDataContext.getUponDBContext();
        AtomicBoolean cancelFlag = uponDBContext.cancelFlag();
        if (uponDBContext.isInTransaction()) {
            for (Map.Entry<String, List<SingeTargetSQLTable>> entry : map.entrySet()) {
                String datasource = entry.getKey();
                List<SingeTargetSQLTable> list = entry.getValue();
                SingeTargetSQLTable table = list.get(0);
                if (table.existsEnumerable()) {
                    continue;
                }
                MycatConnection connection = uponDBContext.getConnection(datasource);
                if (list.size() > 1) {
                    throw new IllegalAccessException("该执行计划重复拉取同一个数据源的数据");
                }
                Future<RowBaseIterator> submit = JdbcRuntime.INSTANCE.getFetchDataExecutorService()
                        .submit(() -> connection.executeQuery(table.getMetaData(), table.getSql()));
                table.setEnumerable(new AbstractEnumerable<Object[]>() {
                    @Override
                    @SneakyThrows
                    public Enumerator<Object[]> enumerator() {
                        return new MyCatResultSetEnumerator(cancelFlag, submit.get(1, TimeUnit.MINUTES));
                    }
                });
            }
        } else {
            Iterator<String> iterator = map.entrySet().stream()
                    .flatMap(i -> i.getValue().stream())
                    .filter(i -> !i.existsEnumerable())
                    .map(i -> i.getTargetName()).iterator();
            Map<String, Deque<MycatConnection>> nameMap = JdbcRuntime.INSTANCE.getConnection(iterator);
            for (Map.Entry<String, List<SingeTargetSQLTable>> entry : map.entrySet()) {
                List<SingeTargetSQLTable> value = entry.getValue();
                for (SingeTargetSQLTable v : value) {
                    MycatConnection connection = nameMap.get(v.getTargetName()).remove();
                    uponDBContext.addCloseResource(connection);
                    Future<RowBaseIterator> submit = JdbcRuntime.INSTANCE.getFetchDataExecutorService()
                            .submit(() -> connection.executeQuery(v.getMetaData(), v.getSql()));
                    AbstractEnumerable enumerable = new AbstractEnumerable<Object[]>() {
                        @Override
                        @SneakyThrows
                        public Enumerator<Object[]> enumerator() {
                            LOGGER.info("------!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                            return new MyCatResultSetEnumerator(cancelFlag, submit.get());
                        }
                    };
                    v.setEnumerable(enumerable);
                }
            }
        }
    }
}