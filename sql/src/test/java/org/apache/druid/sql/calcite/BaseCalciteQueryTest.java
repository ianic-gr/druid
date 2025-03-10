/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.sql.calcite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.druid.annotations.UsedByJUnitParamsRunner;
import org.apache.druid.common.config.NullHandling;
import org.apache.druid.hll.VersionOneHyperLogLogCollector;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.java.util.common.io.Closer;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.math.expr.ExprMacroTable;
import org.apache.druid.query.DataSource;
import org.apache.druid.query.Druids;
import org.apache.druid.query.JoinDataSource;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryContexts;
import org.apache.druid.query.QueryDataSource;
import org.apache.druid.query.QueryRunnerFactoryConglomerate;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.post.ExpressionPostAggregator;
import org.apache.druid.query.dimension.DimensionSpec;
import org.apache.druid.query.extraction.CascadeExtractionFn;
import org.apache.druid.query.extraction.ExtractionFn;
import org.apache.druid.query.filter.AndDimFilter;
import org.apache.druid.query.filter.BoundDimFilter;
import org.apache.druid.query.filter.DimFilter;
import org.apache.druid.query.filter.ExpressionDimFilter;
import org.apache.druid.query.filter.InDimFilter;
import org.apache.druid.query.filter.NotDimFilter;
import org.apache.druid.query.filter.OrDimFilter;
import org.apache.druid.query.filter.SelectorDimFilter;
import org.apache.druid.query.groupby.GroupByQuery;
import org.apache.druid.query.groupby.having.DimFilterHavingSpec;
import org.apache.druid.query.ordering.StringComparator;
import org.apache.druid.query.ordering.StringComparators;
import org.apache.druid.query.scan.ScanQuery;
import org.apache.druid.query.spec.MultipleIntervalSegmentSpec;
import org.apache.druid.query.spec.QuerySegmentSpec;
import org.apache.druid.query.timeseries.TimeseriesQuery;
import org.apache.druid.query.topn.TopNQueryConfig;
import org.apache.druid.segment.column.ColumnHolder;
import org.apache.druid.segment.column.ValueType;
import org.apache.druid.segment.join.JoinType;
import org.apache.druid.segment.virtual.ExpressionVirtualColumn;
import org.apache.druid.server.QueryStackTests;
import org.apache.druid.server.security.AuthenticationResult;
import org.apache.druid.server.security.AuthorizerMapper;
import org.apache.druid.server.security.ForbiddenException;
import org.apache.druid.server.security.Resource;
import org.apache.druid.sql.SqlLifecycle;
import org.apache.druid.sql.SqlLifecycleFactory;
import org.apache.druid.sql.calcite.expression.DruidExpression;
import org.apache.druid.sql.calcite.planner.Calcites;
import org.apache.druid.sql.calcite.planner.DruidOperatorTable;
import org.apache.druid.sql.calcite.planner.PlannerConfig;
import org.apache.druid.sql.calcite.planner.PlannerContext;
import org.apache.druid.sql.calcite.planner.PlannerFactory;
import org.apache.druid.sql.calcite.schema.DruidSchemaCatalog;
import org.apache.druid.sql.calcite.util.CalciteTestBase;
import org.apache.druid.sql.calcite.util.CalciteTests;
import org.apache.druid.sql.calcite.util.QueryLogHook;
import org.apache.druid.sql.calcite.util.SpecificSegmentsQuerySegmentWalker;
import org.apache.druid.sql.calcite.view.InProcessViewManager;
import org.apache.druid.sql.http.SqlParameter;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Interval;
import org.joda.time.chrono.ISOChronology;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * A base class for SQL query testing. It sets up query execution environment, provides useful helper methods,
 * and populates data using {@link CalciteTests#createMockWalker}.
 */
public class BaseCalciteQueryTest extends CalciteTestBase
{
  public static String NULL_STRING;
  public static Float NULL_FLOAT;
  public static Long NULL_LONG;
  public static final String HLLC_STRING = VersionOneHyperLogLogCollector.class.getName();

  final boolean useDefault = NullHandling.replaceWithDefault();

  @BeforeClass
  public static void setupNullValues()
  {
    NULL_STRING = NullHandling.defaultStringValue();
    NULL_FLOAT = NullHandling.defaultFloatValue();
    NULL_LONG = NullHandling.defaultLongValue();
  }

  public static final Logger log = new Logger(BaseCalciteQueryTest.class);

  public static final PlannerConfig PLANNER_CONFIG_DEFAULT = new PlannerConfig();
  public static final PlannerConfig PLANNER_CONFIG_DEFAULT_NO_COMPLEX_SERDE = new PlannerConfig()
  {
    @Override
    public boolean shouldSerializeComplexValues()
    {
      return false;
    }
  };
  public static final PlannerConfig PLANNER_CONFIG_REQUIRE_TIME_CONDITION = new PlannerConfig()
  {
    @Override
    public boolean isRequireTimeCondition()
    {
      return true;
    }
  };
  public static final PlannerConfig PLANNER_CONFIG_NO_TOPN = new PlannerConfig()
  {
    @Override
    public int getMaxTopNLimit()
    {
      return 0;
    }
  };
  public static final PlannerConfig PLANNER_CONFIG_NO_HLL = new PlannerConfig()
  {
    @Override
    public boolean isUseApproximateCountDistinct()
    {
      return false;
    }
  };
  public static final PlannerConfig PLANNER_CONFIG_LOS_ANGELES = new PlannerConfig()
  {
    @Override
    public DateTimeZone getSqlTimeZone()
    {
      return DateTimes.inferTzFromString("America/Los_Angeles");
    }
  };

  public static final PlannerConfig PLANNER_CONFIG_AUTHORIZE_SYS_TABLES = new PlannerConfig()
  {
    @Override
    public boolean isAuthorizeSystemTablesDirectly()
    {
      return true;
    }
  };

  public static final String DUMMY_SQL_ID = "dummy";
  public static final String LOS_ANGELES = "America/Los_Angeles";

  private static final ImmutableMap.Builder<String, Object> DEFAULT_QUERY_CONTEXT_BUILDER =
      ImmutableMap.<String, Object>builder()
                  .put(PlannerContext.CTX_SQL_QUERY_ID, DUMMY_SQL_ID)
                  .put(PlannerContext.CTX_SQL_CURRENT_TIMESTAMP, "2000-01-01T00:00:00Z")
                  .put(QueryContexts.DEFAULT_TIMEOUT_KEY, QueryContexts.DEFAULT_TIMEOUT_MILLIS)
                  .put(QueryContexts.MAX_SCATTER_GATHER_BYTES_KEY, Long.MAX_VALUE);
  public static final Map<String, Object> QUERY_CONTEXT_DEFAULT = DEFAULT_QUERY_CONTEXT_BUILDER.build();

  public static final Map<String, Object> QUERY_CONTEXT_NO_STRINGIFY_ARRAY =
      DEFAULT_QUERY_CONTEXT_BUILDER.put(PlannerContext.CTX_SQL_STRINGIFY_ARRAYS, false)
                                   .build();

  public static final Map<String, Object> QUERY_CONTEXT_DONT_SKIP_EMPTY_BUCKETS = ImmutableMap.of(
      PlannerContext.CTX_SQL_QUERY_ID, DUMMY_SQL_ID,
      PlannerContext.CTX_SQL_CURRENT_TIMESTAMP, "2000-01-01T00:00:00Z",
      TimeseriesQuery.SKIP_EMPTY_BUCKETS, false,
      QueryContexts.DEFAULT_TIMEOUT_KEY, QueryContexts.DEFAULT_TIMEOUT_MILLIS,
      QueryContexts.MAX_SCATTER_GATHER_BYTES_KEY, Long.MAX_VALUE
  );

  public static final Map<String, Object> QUERY_CONTEXT_DO_SKIP_EMPTY_BUCKETS = ImmutableMap.of(
      PlannerContext.CTX_SQL_QUERY_ID, DUMMY_SQL_ID,
      PlannerContext.CTX_SQL_CURRENT_TIMESTAMP, "2000-01-01T00:00:00Z",
      TimeseriesQuery.SKIP_EMPTY_BUCKETS, true,
      QueryContexts.DEFAULT_TIMEOUT_KEY, QueryContexts.DEFAULT_TIMEOUT_MILLIS,
      QueryContexts.MAX_SCATTER_GATHER_BYTES_KEY, Long.MAX_VALUE
  );

  public static final Map<String, Object> QUERY_CONTEXT_NO_TOPN = ImmutableMap.of(
      PlannerContext.CTX_SQL_QUERY_ID, DUMMY_SQL_ID,
      PlannerContext.CTX_SQL_CURRENT_TIMESTAMP, "2000-01-01T00:00:00Z",
      PlannerConfig.CTX_KEY_USE_APPROXIMATE_TOPN, "false",
      QueryContexts.DEFAULT_TIMEOUT_KEY, QueryContexts.DEFAULT_TIMEOUT_MILLIS,
      QueryContexts.MAX_SCATTER_GATHER_BYTES_KEY, Long.MAX_VALUE
  );

  public static final Map<String, Object> QUERY_CONTEXT_LOS_ANGELES = ImmutableMap.of(
      PlannerContext.CTX_SQL_QUERY_ID, DUMMY_SQL_ID,
      PlannerContext.CTX_SQL_CURRENT_TIMESTAMP, "2000-01-01T00:00:00Z",
      PlannerContext.CTX_SQL_TIME_ZONE, LOS_ANGELES,
      QueryContexts.DEFAULT_TIMEOUT_KEY, QueryContexts.DEFAULT_TIMEOUT_MILLIS,
      QueryContexts.MAX_SCATTER_GATHER_BYTES_KEY, Long.MAX_VALUE
  );

  // Matches QUERY_CONTEXT_DEFAULT
  public static final Map<String, Object> TIMESERIES_CONTEXT_BY_GRAN = ImmutableMap.of(
      PlannerContext.CTX_SQL_QUERY_ID, DUMMY_SQL_ID,
      PlannerContext.CTX_SQL_CURRENT_TIMESTAMP, "2000-01-01T00:00:00Z",
      TimeseriesQuery.SKIP_EMPTY_BUCKETS, true,
      QueryContexts.DEFAULT_TIMEOUT_KEY, QueryContexts.DEFAULT_TIMEOUT_MILLIS,
      QueryContexts.MAX_SCATTER_GATHER_BYTES_KEY, Long.MAX_VALUE
  );

  // Add additional context to the given context map for when the
  // timeseries query has timestamp_floor expression on the timestamp dimension
  public static Map<String, Object> getTimeseriesContextWithFloorTime(
      Map<String, Object> context,
      String timestampResultField
  )
  {
    return ImmutableMap.<String, Object>builder()
                       .putAll(context)
                       .put(TimeseriesQuery.CTX_TIMESTAMP_RESULT_FIELD, timestampResultField)
                       .build();
  }

  // Matches QUERY_CONTEXT_LOS_ANGELES
  public static final Map<String, Object> TIMESERIES_CONTEXT_LOS_ANGELES = new HashMap<>();

  public static final Map<String, Object> OUTER_LIMIT_CONTEXT = new HashMap<>(QUERY_CONTEXT_DEFAULT);

  public static QueryRunnerFactoryConglomerate conglomerate;
  public static Closer resourceCloser;
  public static int minTopNThreshold = TopNQueryConfig.DEFAULT_MIN_TOPN_THRESHOLD;

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  public boolean cannotVectorize = false;
  public boolean skipVectorize = false;

  public SpecificSegmentsQuerySegmentWalker walker = null;
  public QueryLogHook queryLogHook;

  static {
    TIMESERIES_CONTEXT_LOS_ANGELES.put(PlannerContext.CTX_SQL_QUERY_ID, DUMMY_SQL_ID);
    TIMESERIES_CONTEXT_LOS_ANGELES.put(PlannerContext.CTX_SQL_CURRENT_TIMESTAMP, "2000-01-01T00:00:00Z");
    TIMESERIES_CONTEXT_LOS_ANGELES.put(PlannerContext.CTX_SQL_TIME_ZONE, LOS_ANGELES);
    TIMESERIES_CONTEXT_LOS_ANGELES.put(TimeseriesQuery.SKIP_EMPTY_BUCKETS, true);
    TIMESERIES_CONTEXT_LOS_ANGELES.put(QueryContexts.DEFAULT_TIMEOUT_KEY, QueryContexts.DEFAULT_TIMEOUT_MILLIS);
    TIMESERIES_CONTEXT_LOS_ANGELES.put(QueryContexts.MAX_SCATTER_GATHER_BYTES_KEY, Long.MAX_VALUE);

    OUTER_LIMIT_CONTEXT.put(PlannerContext.CTX_SQL_OUTER_LIMIT, 2);
  }

  // Generate timestamps for expected results
  public static long timestamp(final String timeString)
  {
    return Calcites.jodaToCalciteTimestamp(DateTimes.of(timeString), DateTimeZone.UTC);
  }

  // Generate timestamps for expected results
  public static long timestamp(final String timeString, final String timeZoneString)
  {
    final DateTimeZone timeZone = DateTimes.inferTzFromString(timeZoneString);
    return Calcites.jodaToCalciteTimestamp(new DateTime(timeString, timeZone), timeZone);
  }

  // Generate day numbers for expected results
  public static int day(final String dayString)
  {
    return (int) (Intervals.utc(timestamp("1970"), timestamp(dayString)).toDurationMillis() / (86400L * 1000L));
  }

  public static QuerySegmentSpec querySegmentSpec(final Interval... intervals)
  {
    return new MultipleIntervalSegmentSpec(Arrays.asList(intervals));
  }

  public static AndDimFilter and(DimFilter... filters)
  {
    return new AndDimFilter(Arrays.asList(filters));
  }

  public static OrDimFilter or(DimFilter... filters)
  {
    return new OrDimFilter(Arrays.asList(filters));
  }

  public static NotDimFilter not(DimFilter filter)
  {
    return new NotDimFilter(filter);
  }

  public static InDimFilter in(String dimension, List<String> values, ExtractionFn extractionFn)
  {
    return new InDimFilter(dimension, values, extractionFn);
  }

  public static SelectorDimFilter selector(final String fieldName, final String value, final ExtractionFn extractionFn)
  {
    return new SelectorDimFilter(fieldName, value, extractionFn);
  }

  public static ExpressionDimFilter expressionFilter(final String expression)
  {
    return new ExpressionDimFilter(expression, CalciteTests.createExprMacroTable());
  }

  public static DimFilter numericSelector(
      final String fieldName,
      final String value,
      final ExtractionFn extractionFn
  )
  {
    // We use Bound filters for numeric equality to achieve "10.0" = "10"
    return bound(fieldName, value, value, false, false, extractionFn, StringComparators.NUMERIC);
  }

  public static BoundDimFilter bound(
      final String fieldName,
      final String lower,
      final String upper,
      final boolean lowerStrict,
      final boolean upperStrict,
      final ExtractionFn extractionFn,
      final StringComparator comparator
  )
  {
    return new BoundDimFilter(fieldName, lower, upper, lowerStrict, upperStrict, null, extractionFn, comparator);
  }

  public static BoundDimFilter timeBound(final Object intervalObj)
  {
    final Interval interval = new Interval(intervalObj, ISOChronology.getInstanceUTC());
    return new BoundDimFilter(
        ColumnHolder.TIME_COLUMN_NAME,
        String.valueOf(interval.getStartMillis()),
        String.valueOf(interval.getEndMillis()),
        false,
        true,
        null,
        null,
        StringComparators.NUMERIC
    );
  }

  public static CascadeExtractionFn cascade(final ExtractionFn... fns)
  {
    return new CascadeExtractionFn(fns);
  }

  public static List<DimensionSpec> dimensions(final DimensionSpec... dimensionSpecs)
  {
    return Arrays.asList(dimensionSpecs);
  }

  public static List<AggregatorFactory> aggregators(final AggregatorFactory... aggregators)
  {
    return Arrays.asList(aggregators);
  }

  public static DimFilterHavingSpec having(final DimFilter filter)
  {
    return new DimFilterHavingSpec(filter, true);
  }

  public static ExpressionVirtualColumn expressionVirtualColumn(
      final String name,
      final String expression,
      final ValueType outputType
  )
  {
    return new ExpressionVirtualColumn(name, expression, outputType, CalciteTests.createExprMacroTable());
  }

  public static JoinDataSource join(
      DataSource left,
      DataSource right,
      String rightPrefix,
      String condition,
      JoinType joinType,
      DimFilter filter
  )
  {
    return JoinDataSource.create(
        left,
        right,
        rightPrefix,
        condition,
        joinType,
        filter,
        CalciteTests.createExprMacroTable()
    );
  }

  public static JoinDataSource join(
      DataSource left,
      DataSource right,
      String rightPrefix,
      String condition,
      JoinType joinType
  )
  {
    return join(left, right, rightPrefix, condition, joinType, null);
  }

  public static String equalsCondition(DruidExpression left, DruidExpression right)
  {
    return StringUtils.format("(%s == %s)", left.getExpression(), right.getExpression());
  }

  public static ExpressionPostAggregator expressionPostAgg(final String name, final String expression)
  {
    return new ExpressionPostAggregator(name, expression, null, CalciteTests.createExprMacroTable());
  }

  public static Druids.ScanQueryBuilder newScanQueryBuilder()
  {
    return new Druids.ScanQueryBuilder().resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                                        .legacy(false);
  }

  @BeforeClass
  public static void setUpClass()
  {
    resourceCloser = Closer.create();
    conglomerate = QueryStackTests.createQueryRunnerFactoryConglomerate(resourceCloser, () -> minTopNThreshold);
  }

  @AfterClass
  public static void tearDownClass() throws IOException
  {
    resourceCloser.close();
  }

  @Rule
  public QueryLogHook getQueryLogHook()
  {
    return queryLogHook = QueryLogHook.create();
  }

  @Before
  public void setUp() throws Exception
  {
    walker = createQuerySegmentWalker();
  }

  @After
  public void tearDown() throws Exception
  {
    walker.close();
    walker = null;
  }

  public SpecificSegmentsQuerySegmentWalker createQuerySegmentWalker() throws IOException
  {
    return CalciteTests.createMockWalker(
        conglomerate,
        temporaryFolder.newFolder()
    );
  }

  public DruidOperatorTable createOperatorTable()
  {
    return CalciteTests.createOperatorTable();
  }

  public ExprMacroTable createMacroTable()
  {
    return CalciteTests.createExprMacroTable();
  }

  public void assertQueryIsUnplannable(final String sql)
  {
    assertQueryIsUnplannable(PLANNER_CONFIG_DEFAULT, sql);
  }

  public void assertQueryIsUnplannable(final PlannerConfig plannerConfig, final String sql)
  {
    Exception e = null;
    try {
      testQuery(plannerConfig, sql, CalciteTests.REGULAR_USER_AUTH_RESULT, ImmutableList.of(), ImmutableList.of());
    }
    catch (Exception e1) {
      e = e1;
    }

    if (!(e instanceof RelOptPlanner.CannotPlanException)) {
      log.error(e, "Expected CannotPlanException for query: %s", sql);
      Assert.fail(sql);
    }
  }

  /**
   * Provided for tests that wish to check multiple queries instead of relying on ExpectedException.
   */
  public void assertQueryIsForbidden(final String sql, final AuthenticationResult authenticationResult)
  {
    assertQueryIsForbidden(PLANNER_CONFIG_DEFAULT, sql, authenticationResult);
  }

  public void assertQueryIsForbidden(
      final PlannerConfig plannerConfig,
      final String sql,
      final AuthenticationResult authenticationResult
  )
  {
    Exception e = null;
    try {
      testQuery(plannerConfig, sql, authenticationResult, ImmutableList.of(), ImmutableList.of());
    }
    catch (Exception e1) {
      e = e1;
    }

    if (!(e instanceof ForbiddenException)) {
      log.error(e, "Expected ForbiddenException for query: %s with authResult: %s", sql, authenticationResult);
      Assert.fail(sql);
    }
  }

  public void testQuery(
      final String sql,
      final List<Query> expectedQueries,
      final List<Object[]> expectedResults
  ) throws Exception
  {
    testQuery(
        PLANNER_CONFIG_DEFAULT,
        QUERY_CONTEXT_DEFAULT,
        DEFAULT_PARAMETERS,
        sql,
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        expectedQueries,
        expectedResults
    );
  }

  public void testQuery(
      final String sql,
      final Map<String, Object> context,
      final List<Query> expectedQueries,
      final List<Object[]> expectedResults
  ) throws Exception
  {
    testQuery(
        PLANNER_CONFIG_DEFAULT,
        context,
        DEFAULT_PARAMETERS,
        sql,
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        expectedQueries,
        expectedResults
    );
  }

  public void testQuery(
      final String sql,
      final List<Query> expectedQueries,
      final List<Object[]> expectedResults,
      final List<SqlParameter> parameters
  ) throws Exception
  {
    testQuery(
        PLANNER_CONFIG_DEFAULT,
        QUERY_CONTEXT_DEFAULT,
        parameters,
        sql,
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        expectedQueries,
        expectedResults
    );
  }

  public void testQuery(
      final PlannerConfig plannerConfig,
      final String sql,
      final AuthenticationResult authenticationResult,
      final List<Query> expectedQueries,
      final List<Object[]> expectedResults
  ) throws Exception
  {
    testQuery(
        plannerConfig,
        QUERY_CONTEXT_DEFAULT,
        DEFAULT_PARAMETERS,
        sql,
        authenticationResult,
        expectedQueries,
        expectedResults
    );
  }

  public void testQuery(
      final PlannerConfig plannerConfig,
      final Map<String, Object> queryContext,
      final String sql,
      final AuthenticationResult authenticationResult,
      final List<Query> expectedQueries,
      final List<Object[]> expectedResults
  ) throws Exception
  {
    log.info("SQL: %s", sql);
    queryLogHook.clearRecordedQueries();
    final List<Object[]> plannerResults =
        getResults(plannerConfig, queryContext, DEFAULT_PARAMETERS, sql, authenticationResult);
    verifyResults(sql, expectedQueries, expectedResults, plannerResults);
  }

  /**
   * Override not just the outer query context, but also the contexts of all subqueries.
   */
  private <T> Query<T> recursivelyOverrideContext(final Query<T> query, final Map<String, Object> context)
  {
    return query.withDataSource(recursivelyOverrideContext(query.getDataSource(), context))
                .withOverriddenContext(context);
  }

  /**
   * Override the contexts of all subqueries of a particular datasource.
   */
  private DataSource recursivelyOverrideContext(final DataSource dataSource, final Map<String, Object> context)
  {
    if (dataSource instanceof QueryDataSource) {
      final Query subquery = ((QueryDataSource) dataSource).getQuery();
      return new QueryDataSource(recursivelyOverrideContext(subquery, context));
    } else {
      return dataSource.withChildren(
          dataSource.getChildren()
                    .stream()
                    .map(ds -> recursivelyOverrideContext(ds, context))
                    .collect(Collectors.toList())
      );
    }
  }

  public void testQuery(
      final PlannerConfig plannerConfig,
      final Map<String, Object> queryContext,
      final List<SqlParameter> parameters,
      final String sql,
      final AuthenticationResult authenticationResult,
      final List<Query> expectedQueries,
      final List<Object[]> expectedResults
  ) throws Exception
  {
    log.info("SQL: %s", sql);

    final List<String> vectorizeValues = new ArrayList<>();

    vectorizeValues.add("false");

    if (!skipVectorize) {
      vectorizeValues.add("force");
    }

    for (final String vectorize : vectorizeValues) {
      queryLogHook.clearRecordedQueries();

      final Map<String, Object> theQueryContext = new HashMap<>(queryContext);
      theQueryContext.put(QueryContexts.VECTORIZE_KEY, vectorize);
      theQueryContext.put(QueryContexts.VECTORIZE_VIRTUAL_COLUMNS_KEY, vectorize);

      if (!"false".equals(vectorize)) {
        theQueryContext.put(QueryContexts.VECTOR_SIZE_KEY, 2); // Small vector size to ensure we use more than one.
      }

      final List<Query> theQueries = new ArrayList<>();
      for (Query query : expectedQueries) {
        theQueries.add(recursivelyOverrideContext(query, theQueryContext));
      }

      if (cannotVectorize && "force".equals(vectorize)) {
        expectedException.expect(RuntimeException.class);
        expectedException.expectMessage("Cannot vectorize");
      }

      final List<Object[]> plannerResults = getResults(plannerConfig, theQueryContext, parameters, sql, authenticationResult);
      verifyResults(sql, theQueries, expectedResults, plannerResults);
    }
  }

  public List<Object[]> getResults(
      final PlannerConfig plannerConfig,
      final Map<String, Object> queryContext,
      final List<SqlParameter> parameters,
      final String sql,
      final AuthenticationResult authenticationResult
  ) throws Exception
  {
    return getResults(
        plannerConfig,
        queryContext,
        parameters,
        sql,
        authenticationResult,
        createOperatorTable(),
        createMacroTable(),
        CalciteTests.TEST_AUTHORIZER_MAPPER,
        CalciteTests.getJsonMapper()
    );
  }

  public List<Object[]> getResults(
      final PlannerConfig plannerConfig,
      final Map<String, Object> queryContext,
      final List<SqlParameter> parameters,
      final String sql,
      final AuthenticationResult authenticationResult,
      final DruidOperatorTable operatorTable,
      final ExprMacroTable macroTable,
      final AuthorizerMapper authorizerMapper,
      final ObjectMapper objectMapper
  ) throws Exception
  {
    final SqlLifecycleFactory sqlLifecycleFactory = getSqlLifecycleFactory(
        plannerConfig,
        operatorTable,
        macroTable,
        authorizerMapper,
        objectMapper
    );

    return sqlLifecycleFactory.factorize().runSimple(sql, queryContext, parameters, authenticationResult).toList();
  }

  public void verifyResults(
      final String sql,
      final List<Query> expectedQueries,
      final List<Object[]> expectedResults,
      final List<Object[]> results
  )
  {
    for (int i = 0; i < results.size(); i++) {
      log.info("row #%d: %s", i, Arrays.toString(results.get(i)));
    }

    Assert.assertEquals(StringUtils.format("result count: %s", sql), expectedResults.size(), results.size());
    assertResultsEquals(sql, expectedResults, results);

    verifyQueries(sql, expectedQueries);
  }

  private void verifyQueries(
      final String sql,
      @Nullable final List<Query> expectedQueries
  )
  {
    if (expectedQueries != null) {
      final List<Query> recordedQueries = queryLogHook.getRecordedQueries();

      Assert.assertEquals(
          StringUtils.format("query count: %s", sql),
          expectedQueries.size(),
          recordedQueries.size()
      );
      for (int i = 0; i < expectedQueries.size(); i++) {
        Assert.assertEquals(
            StringUtils.format("query #%d: %s", i + 1, sql),
            expectedQueries.get(i),
            recordedQueries.get(i)
        );
      }
    }
  }

  public void assertResultsEquals(String sql, List<Object[]> expectedResults, List<Object[]> results)
  {
    for (int i = 0; i < results.size(); i++) {
      Assert.assertArrayEquals(
          StringUtils.format("result #%d: %s", i + 1, sql),
          expectedResults.get(i),
          results.get(i)
      );
    }
  }

  public void testQueryThrows(
      final String sql,
      final Map<String, Object> queryContext,
      final List<Query> expectedQueries,
      final Consumer<ExpectedException> expectedExceptionInitializer
  ) throws Exception
  {
    testQueryThrows(
        PLANNER_CONFIG_DEFAULT,
        queryContext,
        DEFAULT_PARAMETERS,
        sql,
        CalciteTests.REGULAR_USER_AUTH_RESULT,
        expectedQueries,
        expectedExceptionInitializer
    );
  }

  public void testQueryThrows(
      final PlannerConfig plannerConfig,
      final Map<String, Object> queryContext,
      final List<SqlParameter> parameters,
      final String sql,
      final AuthenticationResult authenticationResult,
      final List<Query> expectedQueries,
      final Consumer<ExpectedException> expectedExceptionInitializer
  ) throws Exception
  {
    log.info("SQL: %s", sql);

    final List<String> vectorizeValues = new ArrayList<>();

    vectorizeValues.add("false");

    if (!skipVectorize) {
      vectorizeValues.add("force");
    }

    for (final String vectorize : vectorizeValues) {
      queryLogHook.clearRecordedQueries();

      final Map<String, Object> theQueryContext = new HashMap<>(queryContext);
      theQueryContext.put(QueryContexts.VECTORIZE_KEY, vectorize);
      theQueryContext.put(QueryContexts.VECTORIZE_VIRTUAL_COLUMNS_KEY, vectorize);

      if (!"false".equals(vectorize)) {
        theQueryContext.put(QueryContexts.VECTOR_SIZE_KEY, 2); // Small vector size to ensure we use more than one.
      }

      final List<Query> theQueries = new ArrayList<>();
      for (Query query : expectedQueries) {
        theQueries.add(recursivelyOverrideContext(query, theQueryContext));
      }

      if (cannotVectorize && "force".equals(vectorize)) {
        expectedException.expect(RuntimeException.class);
        expectedException.expectMessage("Cannot vectorize");
      } else {
        expectedExceptionInitializer.accept(expectedException);
      }

      // this should validate expectedException
      getResults(plannerConfig, theQueryContext, parameters, sql, authenticationResult);

      verifyQueries(sql, theQueries);
    }
  }

  public Set<Resource> analyzeResources(
      PlannerConfig plannerConfig,
      String sql,
      AuthenticationResult authenticationResult
  )
  {
    SqlLifecycleFactory lifecycleFactory = getSqlLifecycleFactory(
        plannerConfig,
        createOperatorTable(),
        createMacroTable(),
        CalciteTests.TEST_AUTHORIZER_MAPPER,
        CalciteTests.getJsonMapper()
    );

    SqlLifecycle lifecycle = lifecycleFactory.factorize();
    lifecycle.initialize(sql, ImmutableMap.of());
    return lifecycle.runAnalyzeResources(authenticationResult).getResources();
  }

  public SqlLifecycleFactory getSqlLifecycleFactory(
      PlannerConfig plannerConfig,
      DruidOperatorTable operatorTable,
      ExprMacroTable macroTable,
      AuthorizerMapper authorizerMapper,
      ObjectMapper objectMapper
  )
  {
    final InProcessViewManager viewManager =
        new InProcessViewManager(CalciteTests.TEST_AUTHENTICATOR_ESCALATOR, CalciteTests.DRUID_VIEW_MACRO_FACTORY);
    DruidSchemaCatalog rootSchema = CalciteTests.createMockRootSchema(
        conglomerate,
        walker,
        plannerConfig,
        viewManager,
        authorizerMapper
    );

    final PlannerFactory plannerFactory = new PlannerFactory(
        rootSchema,
        CalciteTests.createMockQueryLifecycleFactory(walker, conglomerate),
        operatorTable,
        macroTable,
        plannerConfig,
        authorizerMapper,
        objectMapper,
        CalciteTests.DRUID_SCHEMA_NAME
    );
    final SqlLifecycleFactory sqlLifecycleFactory = CalciteTests.createSqlLifecycleFactory(plannerFactory);

    viewManager.createView(
        plannerFactory,
        "aview",
        "SELECT SUBSTRING(dim1, 1, 1) AS dim1_firstchar FROM foo WHERE dim2 = 'a'"
    );

    viewManager.createView(
        plannerFactory,
        "bview",
        "SELECT COUNT(*) FROM druid.foo\n"
        + "WHERE __time >= CURRENT_TIMESTAMP + INTERVAL '1' DAY AND __time < TIMESTAMP '2002-01-01 00:00:00'"
    );

    viewManager.createView(
        plannerFactory,
        "cview",
        "SELECT SUBSTRING(bar.dim1, 1, 1) AS dim1_firstchar, bar.dim2 as dim2, dnf.l2 as l2\n"
        + "FROM (SELECT * from foo WHERE dim2 = 'a') as bar INNER JOIN druid.numfoo dnf ON bar.dim2 = dnf.dim2"
    );

    viewManager.createView(
        plannerFactory,
        "dview",
        "SELECT SUBSTRING(dim1, 1, 1) AS numfoo FROM foo WHERE dim2 = 'a'"
    );

    viewManager.createView(
        plannerFactory,
        "forbiddenView",
        "SELECT __time, SUBSTRING(dim1, 1, 1) AS dim1_firstchar, dim2 FROM foo WHERE dim2 = 'a'"
    );

    viewManager.createView(
        plannerFactory,
        "restrictedView",
        "SELECT __time, dim1, dim2, m1 FROM druid.forbiddenDatasource WHERE dim2 = 'a'"
    );

    viewManager.createView(
        plannerFactory,
        "invalidView",
        "SELECT __time, dim1, dim2, m1 FROM druid.invalidDatasource WHERE dim2 = 'a'"
    );
    return sqlLifecycleFactory;
  }

  protected void cannotVectorize()
  {
    cannotVectorize = true;
  }

  protected void skipVectorize()
  {
    skipVectorize = true;
  }

  protected static boolean isRewriteJoinToFilter(final Map<String, Object> queryContext)
  {
    return (boolean) queryContext.getOrDefault(
        QueryContexts.REWRITE_JOIN_TO_FILTER_ENABLE_KEY,
        QueryContexts.DEFAULT_ENABLE_REWRITE_JOIN_TO_FILTER
    );
  }

  /**
   * This is a provider of query contexts that should be used by join tests.
   * It tests various configs that can be passed to join queries. All the configs provided by this provider should
   * have the join query engine return the same results.
   */
  public static class QueryContextForJoinProvider
  {
    @UsedByJUnitParamsRunner
    public static Object[] provideQueryContexts()
    {
      return new Object[]{
          // default behavior
          QUERY_CONTEXT_DEFAULT,
          // all rewrites enabled
          new ImmutableMap.Builder<String, Object>()
              .putAll(QUERY_CONTEXT_DEFAULT)
              .put(QueryContexts.JOIN_FILTER_REWRITE_VALUE_COLUMN_FILTERS_ENABLE_KEY, true)
              .put(QueryContexts.JOIN_FILTER_REWRITE_ENABLE_KEY, true)
              .put(QueryContexts.REWRITE_JOIN_TO_FILTER_ENABLE_KEY, true)
              .build(),
          // filter-on-value-column rewrites disabled, everything else enabled
          new ImmutableMap.Builder<String, Object>()
              .putAll(QUERY_CONTEXT_DEFAULT)
              .put(QueryContexts.JOIN_FILTER_REWRITE_VALUE_COLUMN_FILTERS_ENABLE_KEY, false)
              .put(QueryContexts.JOIN_FILTER_REWRITE_ENABLE_KEY, true)
              .put(QueryContexts.REWRITE_JOIN_TO_FILTER_ENABLE_KEY, true)
              .build(),
          // filter rewrites fully disabled, join-to-filter enabled
          new ImmutableMap.Builder<String, Object>()
              .putAll(QUERY_CONTEXT_DEFAULT)
              .put(QueryContexts.JOIN_FILTER_REWRITE_VALUE_COLUMN_FILTERS_ENABLE_KEY, false)
              .put(QueryContexts.JOIN_FILTER_REWRITE_ENABLE_KEY, false)
              .put(QueryContexts.REWRITE_JOIN_TO_FILTER_ENABLE_KEY, true)
              .build(),
          // filter rewrites disabled, but value column filters still set to true (it should be ignored and this should
          // behave the same as the previous context)
          new ImmutableMap.Builder<String, Object>()
              .putAll(QUERY_CONTEXT_DEFAULT)
              .put(QueryContexts.JOIN_FILTER_REWRITE_VALUE_COLUMN_FILTERS_ENABLE_KEY, true)
              .put(QueryContexts.JOIN_FILTER_REWRITE_ENABLE_KEY, false)
              .put(QueryContexts.REWRITE_JOIN_TO_FILTER_ENABLE_KEY, true)
              .build(),
          // filter rewrites fully enabled, join-to-filter disabled
          new ImmutableMap.Builder<String, Object>()
              .putAll(QUERY_CONTEXT_DEFAULT)
              .put(QueryContexts.JOIN_FILTER_REWRITE_VALUE_COLUMN_FILTERS_ENABLE_KEY, true)
              .put(QueryContexts.JOIN_FILTER_REWRITE_ENABLE_KEY, true)
              .put(QueryContexts.REWRITE_JOIN_TO_FILTER_ENABLE_KEY, false)
              .build(),
          // all rewrites disabled
          new ImmutableMap.Builder<String, Object>()
              .putAll(QUERY_CONTEXT_DEFAULT)
              .put(QueryContexts.JOIN_FILTER_REWRITE_VALUE_COLUMN_FILTERS_ENABLE_KEY, false)
              .put(QueryContexts.JOIN_FILTER_REWRITE_ENABLE_KEY, false)
              .put(QueryContexts.REWRITE_JOIN_TO_FILTER_ENABLE_KEY, false)
              .build(),
          };
    }

    public static Map<String, Object> withOverrides(Map<String, Object> originalContext, Map<String, Object> overrides)
    {
      Map<String, Object> contextWithOverrides = new HashMap<>(originalContext);
      contextWithOverrides.putAll(overrides);
      return contextWithOverrides;
    }
  }

  protected Map<String, Object> withLeftDirectAccessEnabled(Map<String, Object> context)
  {
    // since context is usually immutable in tests, make a copy
    HashMap<String, Object> newContext = new HashMap<>(context);
    newContext.put(QueryContexts.SQL_JOIN_LEFT_SCAN_DIRECT, true);
    return newContext;
  }

  /**
   * Reset the walker and conglomerate with required number of merge buffers. Default value is 2.
   */
  protected void requireMergeBuffers(int numMergeBuffers) throws IOException
  {
    conglomerate = QueryStackTests.createQueryRunnerFactoryConglomerate(
        resourceCloser,
        QueryStackTests.getProcessingConfig(true, numMergeBuffers)
    );
    walker = CalciteTests.createMockWalker(conglomerate, temporaryFolder.newFolder());
  }

  protected Map<String, Object> withTimestampResultContext(
      Map<String, Object> input,
      String timestampResultField,
      int timestampResultFieldIndex,
      Granularity granularity
  )
  {
    Map<String, Object> output = new HashMap<>(input);
    output.put(GroupByQuery.CTX_TIMESTAMP_RESULT_FIELD, timestampResultField);
    output.put(GroupByQuery.CTX_TIMESTAMP_RESULT_FIELD_GRANULARITY, granularity);
    output.put(GroupByQuery.CTX_TIMESTAMP_RESULT_FIELD_INDEX, timestampResultFieldIndex);
    return output;
  }
}
