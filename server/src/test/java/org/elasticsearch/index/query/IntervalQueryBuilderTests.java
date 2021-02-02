/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.query;

import org.apache.lucene.index.Term;
import org.apache.lucene.queries.intervals.IntervalQuery;
import org.apache.lucene.queries.intervals.Intervals;
import org.apache.lucene.queries.intervals.IntervalsSource;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.test.AbstractQueryTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

public class IntervalQueryBuilderTests extends AbstractQueryTestCase<IntervalQueryBuilder> {

    @Override
    protected IntervalQueryBuilder doCreateTestQueryBuilder() {
        return new IntervalQueryBuilder(TEXT_FIELD_NAME, createRandomSource(0, true));
    }

    private static final String[] filters = new String[]{
        "containing", "contained_by", "not_containing", "not_contained_by",
        "overlapping", "not_overlapping", "before", "after"
    };

    private static final String MASKED_FIELD = "masked_field";
    private static final String NO_POSITIONS_FIELD = "no_positions_field";
    private static final String PREFIXED_FIELD = "prefixed_field";

    @Override
    protected void initializeAdditionalMappings(MapperService mapperService) throws IOException {
        XContentBuilder mapping = jsonBuilder().startObject().startObject("_doc").startObject("properties")
            .startObject(MASKED_FIELD)
            .field("type", "text")
            .endObject()
            .startObject(NO_POSITIONS_FIELD)
            .field("type", "text")
            .field("index_options", "freqs")
            .endObject()
            .startObject(PREFIXED_FIELD)
            .field("type", "text")
            .startObject("index_prefixes").endObject()
            .endObject()
            .endObject().endObject().endObject();

        mapperService.merge("_doc",
            new CompressedXContent(Strings.toString(mapping)), MapperService.MergeReason.MAPPING_UPDATE);
    }

    private static IntervalsSourceProvider createRandomSource(int depth, boolean useScripts) {
        if (depth > 2) {
            return createRandomMatch(depth + 1, useScripts);
        }
        switch (randomInt(20)) {
            case 0:
            case 1:
                return createRandomDisjunction(depth, useScripts);
            case 2:
            case 3:
                return createRandomCombine(depth, useScripts);
            default:
                return createRandomMatch(depth + 1, useScripts);
        }
    }

    static IntervalsSourceProvider.Disjunction createRandomDisjunction(int depth, boolean useScripts) {
        int orCount = randomInt(4) + 1;
        List<IntervalsSourceProvider> orSources = createRandomSourceList(depth, useScripts, orCount);
        return new IntervalsSourceProvider.Disjunction(orSources, createRandomFilter(depth + 1, useScripts));
    }

    static IntervalsSourceProvider.Combine createRandomCombine(int depth, boolean useScripts) {
        int count = randomInt(5) + 1;
        List<IntervalsSourceProvider> subSources = createRandomSourceList(depth, useScripts, count);
        boolean ordered = randomBoolean();
        int maxGaps = randomInt(5) - 1;
        IntervalsSourceProvider.IntervalFilter filter = createRandomFilter(depth + 1, useScripts);
        return new IntervalsSourceProvider.Combine(subSources, ordered, maxGaps, filter);
    }

    static List<IntervalsSourceProvider> createRandomSourceList(int depth, boolean useScripts, int count) {
        List<IntervalsSourceProvider> subSources = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            subSources.add(createRandomSource(depth + 1, useScripts));
        }
        return subSources;
    }

    private static IntervalsSourceProvider.IntervalFilter createRandomFilter(int depth, boolean useScripts) {
        if (depth < 3 && randomInt(20) > 18) {
            return createRandomNonNullFilter(depth, useScripts);
        }
        return null;
    }

    static IntervalsSourceProvider.IntervalFilter createRandomNonNullFilter(int depth, boolean useScripts) {
        if (useScripts == false || randomBoolean()) {
            return new IntervalsSourceProvider.IntervalFilter(createRandomSource(depth + 1, false), randomFrom(filters));
        }
        return new IntervalsSourceProvider.IntervalFilter(
            new Script(ScriptType.INLINE, "mockscript", "1", Collections.emptyMap()));
    }

    static IntervalsSourceProvider.Match createRandomMatch(int depth, boolean useScripts) {
        String useField = rarely() ? MASKED_FIELD : null;
        int wordCount = randomInt(4) + 1;
        List<String> words = new ArrayList<>();
        for (int i = 0; i < wordCount; i++) {
            words.add(randomRealisticUnicodeOfLengthBetween(4, 20));
        }
        String text = String.join(" ", words);
        boolean mOrdered = randomBoolean();
        int maxMGaps = randomInt(5) - 1;
        String analyzer = randomFrom("simple", "keyword", "whitespace");
        return new IntervalsSourceProvider.Match(text, maxMGaps, mOrdered, analyzer, createRandomFilter(depth + 1, useScripts), useField);
    }

    @Override
    public void testCacheability() throws IOException {
        IntervalQueryBuilder queryBuilder = new IntervalQueryBuilder(TEXT_FIELD_NAME, createRandomSource(0, false));
        SearchExecutionContext context = createSearchExecutionContext();
        QueryBuilder rewriteQuery = rewriteQuery(queryBuilder, new SearchExecutionContext(context));
        assertNotNull(rewriteQuery.toQuery(context));
        assertTrue("query should be cacheable: " + queryBuilder.toString(), context.isCacheable());

        IntervalsSourceProvider.IntervalFilter scriptFilter = new IntervalsSourceProvider.IntervalFilter(
            new Script(ScriptType.INLINE, "mockscript", "1", Collections.emptyMap())
        );
        IntervalsSourceProvider source = new IntervalsSourceProvider.Match("text", 0, true, "simple", scriptFilter, null);
        queryBuilder = new IntervalQueryBuilder(TEXT_FIELD_NAME, source);
        rewriteQuery = rewriteQuery(queryBuilder, new SearchExecutionContext(context));
        assertNotNull(rewriteQuery.toQuery(context));
        assertFalse("query with scripts should not be cacheable: " + queryBuilder.toString(), context.isCacheable());
    }

    @Override
    protected void doAssertLuceneQuery(IntervalQueryBuilder queryBuilder, Query query, SearchExecutionContext context) throws IOException {
        assertThat(query, instanceOf(IntervalQuery.class));
    }

    @Override
    public IntervalQueryBuilder mutateInstance(IntervalQueryBuilder instance) throws IOException {
        if (randomBoolean()) {
            return super.mutateInstance(instance); // just change name/boost
        }
        if (randomBoolean()) {
            return new IntervalQueryBuilder(KEYWORD_FIELD_NAME, instance.getSourceProvider());
        }
        return new IntervalQueryBuilder(TEXT_FIELD_NAME, createRandomSource(0, true));
    }

    public void testMatchInterval() throws IOException {

        String json = "{ \"intervals\" : " +
            "{ \"" + TEXT_FIELD_NAME + "\" : { \"match\" : { \"query\" : \"Hello world\" } } } }";

        IntervalQueryBuilder builder = (IntervalQueryBuilder) parseQuery(json);
        Query expected = new IntervalQuery(TEXT_FIELD_NAME,
            Intervals.unordered(Intervals.term("hello"), Intervals.term("world")));

        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

        json = "{ \"intervals\" : " +
            "{ \"" + TEXT_FIELD_NAME + "\" : { " +
            "       \"match\" : { " +
            "           \"query\" : \"Hello world\"," +
            "           \"max_gaps\" : 40 } } } }";

        builder = (IntervalQueryBuilder) parseQuery(json);
        expected = new IntervalQuery(TEXT_FIELD_NAME,
            Intervals.maxgaps(40, Intervals.unordered(Intervals.term("hello"), Intervals.term("world"))));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

        json = "{ \"intervals\" : " +
            "{ \"" + TEXT_FIELD_NAME + "\" : { " +
            "       \"match\" : { " +
            "           \"query\" : \"Hello world\"," +
            "           \"ordered\" : true }," +
            "       \"boost\" : 2 } } }";

        builder = (IntervalQueryBuilder) parseQuery(json);
        expected = new BoostQuery(new IntervalQuery(TEXT_FIELD_NAME,
            Intervals.ordered(Intervals.term("hello"), Intervals.term("world"))), 2);
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

        json = "{ \"intervals\" : " +
            "{ \"" + TEXT_FIELD_NAME + "\" : { " +
            "       \"match\" : { " +
            "           \"query\" : \"Hello world\"," +
            "           \"max_gaps\" : 10," +
            "           \"analyzer\" : \"whitespace\"," +
            "           \"ordered\" : true } } } }";

        builder = (IntervalQueryBuilder) parseQuery(json);
        expected = new IntervalQuery(TEXT_FIELD_NAME,
            Intervals.maxgaps(10, Intervals.ordered(Intervals.term("Hello"), Intervals.term("world"))));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

        json = "{ \"intervals\" : " +
            "{ \"" + TEXT_FIELD_NAME + "\" : { " +
            "       \"match\" : { " +
            "           \"query\" : \"Hello world\"," +
            "           \"max_gaps\" : 10," +
            "           \"analyzer\" : \"whitespace\"," +
            "           \"use_field\" : \"" + MASKED_FIELD + "\"," +
            "           \"ordered\" : true } } } }";

        builder = (IntervalQueryBuilder) parseQuery(json);
        expected = new IntervalQuery(TEXT_FIELD_NAME,
            Intervals.fixField(MASKED_FIELD,
                                Intervals.maxgaps(10, Intervals.ordered(Intervals.term("Hello"), Intervals.term("world")))));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

        json = "{ \"intervals\" : " +
            "{ \"" + TEXT_FIELD_NAME + "\" : { " +
            "       \"match\" : { " +
            "           \"query\" : \"Hello world\"," +
            "           \"max_gaps\" : 10," +
            "           \"analyzer\" : \"whitespace\"," +
            "           \"ordered\" : true," +
            "           \"filter\" : {" +
            "               \"containing\" : {" +
            "                   \"match\" : { \"query\" : \"blah\" } } } } } } }";

        builder = (IntervalQueryBuilder) parseQuery(json);
        expected = new IntervalQuery(TEXT_FIELD_NAME,
            Intervals.containing(Intervals.maxgaps(10, Intervals.ordered(Intervals.term("Hello"), Intervals.term("world"))),
                                 Intervals.term("blah")));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));
    }

    public void testOrInterval() throws IOException {

        String json = "{ \"intervals\" : { \"" + TEXT_FIELD_NAME + "\": {" +
            "       \"any_of\" : { " +
            "           \"intervals\" : [" +
            "               { \"match\" : { \"query\" : \"one\" } }," +
            "               { \"match\" : { \"query\" : \"two\" } } ] } } } }";
        IntervalQueryBuilder builder = (IntervalQueryBuilder) parseQuery(json);
        Query expected = new IntervalQuery(TEXT_FIELD_NAME,
            Intervals.or(Intervals.term("one"), Intervals.term("two")));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

        json = "{ \"intervals\" : { \"" + TEXT_FIELD_NAME + "\": {" +
            "       \"any_of\" : { " +
            "           \"intervals\" : [" +
            "               { \"match\" : { \"query\" : \"one\" } }," +
            "               { \"match\" : { \"query\" : \"two\" } } ]," +
            "           \"filter\" : {" +
            "               \"not_containing\" : { \"match\" : { \"query\" : \"three\" } } } } } } }";
        builder = (IntervalQueryBuilder) parseQuery(json);
        expected = new IntervalQuery(TEXT_FIELD_NAME,
            Intervals.notContaining(
                Intervals.or(Intervals.term("one"), Intervals.term("two")),
                Intervals.term("three")));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));
    }

    public void testCombineInterval() throws IOException {

        String json = "{ \"intervals\" : { \"" + TEXT_FIELD_NAME + "\": {" +
            "       \"all_of\" : {" +
            "           \"ordered\" : true," +
            "           \"intervals\" : [" +
            "               { \"match\" : { \"query\" : \"one\" } }," +
            "               { \"all_of\" : { " +
            "                   \"ordered\" : false," +
            "                   \"intervals\" : [" +
            "                       { \"match\" : { \"query\" : \"two\" } }," +
            "                       { \"match\" : { \"query\" : \"three\" } } ] } } ]," +
            "           \"max_gaps\" : 30," +
            "           \"filter\" : { " +
            "               \"contained_by\" : { " +
            "                   \"match\" : { " +
            "                       \"query\" : \"SENTENCE\"," +
            "                       \"analyzer\" : \"keyword\" } } } }," +
            "       \"boost\" : 1.5 } } }";
        IntervalQueryBuilder builder = (IntervalQueryBuilder) parseQuery(json);
        Query expected = new BoostQuery(new IntervalQuery(TEXT_FIELD_NAME,
            Intervals.containedBy(
                    Intervals.maxgaps(30, Intervals.ordered(
                        Intervals.term("one"),
                        Intervals.unordered(Intervals.term("two"), Intervals.term("three")))),
                    Intervals.term("SENTENCE"))), 1.5f);
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

    }

    public void testCombineDisjunctionInterval() throws IOException {
        String json = "{ \"intervals\" : " +
            "{ \"" + TEXT_FIELD_NAME + "\": { " +
            "       \"all_of\" : {" +
            "           \"ordered\" : true," +
            "           \"intervals\" : [" +
            "               { \"match\" : { \"query\" : \"atmosphere\" } }," +
            "               { \"any_of\" : {" +
            "                   \"intervals\" : [" +
            "                       { \"match\" : { \"query\" : \"cold\" } }," +
            "                       { \"match\" : { \"query\" : \"outside\" } } ] } } ]," +
            "           \"max_gaps\" : 30," +
            "           \"filter\" : { " +
            "               \"not_contained_by\" : { " +
            "                   \"match\" : { \"query\" : \"freeze\" } } } } } } }";

        IntervalQueryBuilder builder = (IntervalQueryBuilder) parseQuery(json);
        Query expected = new IntervalQuery(TEXT_FIELD_NAME,
            Intervals.notContainedBy(
                Intervals.maxgaps(30, Intervals.ordered(
                    Intervals.term("atmosphere"),
                    Intervals.or(Intervals.term("cold"), Intervals.term("outside"))
                )),
                Intervals.term("freeze")));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));
    }

    public void testNonIndexedFields() throws IOException {
        IntervalsSourceProvider provider = new IntervalsSourceProvider.Match("test", 0, true, null, null, null);
        IntervalQueryBuilder b = new IntervalQueryBuilder("no_such_field", provider);
        assertThat(b.toQuery(createSearchExecutionContext()), equalTo(new MatchNoDocsQuery()));

        Exception e = expectThrows(IllegalArgumentException.class, () -> {
            IntervalQueryBuilder builder = new IntervalQueryBuilder(INT_FIELD_NAME, provider);
            builder.doToQuery(createSearchExecutionContext());
        });
        assertThat(e.getMessage(), equalTo("Can only use interval queries on text fields - not on ["
            + INT_FIELD_NAME + "] which is of type [integer]"));

        e = expectThrows(IllegalArgumentException.class, () -> {
            IntervalQueryBuilder builder = new IntervalQueryBuilder(NO_POSITIONS_FIELD, provider);
            builder.doToQuery(createSearchExecutionContext());
        });
        assertThat(e.getMessage(), equalTo("Cannot create intervals over field ["
            + NO_POSITIONS_FIELD + "] with no positions indexed"));

        String json = "{ \"intervals\" : " +
            "{ \"" + TEXT_FIELD_NAME + "\" : { " +
            "       \"match\" : { " +
            "           \"query\" : \"Hello world\"," +
            "           \"max_gaps\" : 10," +
            "           \"analyzer\" : \"whitespace\"," +
            "           \"use_field\" : \"" + NO_POSITIONS_FIELD + "\"," +
            "           \"ordered\" : true } } } }";

        e = expectThrows(IllegalArgumentException.class, () -> {
            IntervalQueryBuilder builder = (IntervalQueryBuilder) parseQuery(json);
            builder.doToQuery(createSearchExecutionContext());
        });
        assertThat(e.getMessage(), equalTo("Cannot create intervals over field ["
            + NO_POSITIONS_FIELD + "] with no positions indexed"));
    }

    public void testMultipleProviders() {
        String json = "{ \"intervals\" : { \"" + TEXT_FIELD_NAME + "\": { " +
            "\"boost\" : 1," +
            "\"match\" : { \"query\" : \"term1\" }," +
            "\"all_of\" : { \"intervals\" : [ { \"query\" : \"term2\" } ] } }";

        ParsingException e = expectThrows(ParsingException.class, () -> {
            parseQuery(json);
        });
        assertThat(e.getMessage(), equalTo("Only one interval rule can be specified, found [match] and [all_of]"));
    }

    public void testScriptFilter() throws IOException {
        IntervalFilterScript.Factory factory = () -> new IntervalFilterScript() {
            @Override
            public boolean execute(Interval interval) {
                return interval.getStart() > 3;
            }
        };
        SearchExecutionContext context = new SearchExecutionContext(createSearchExecutionContext()) {
            @Override
            @SuppressWarnings("unchecked")
            public <FactoryType> FactoryType compile(Script script, ScriptContext<FactoryType> context) {
                assertEquals(IntervalFilterScript.CONTEXT, context);
                assertEquals(new Script("interval.start > 3"), script);
                return (FactoryType)factory;
            }
        };

        String json = "{ \"intervals\" : { \"" + TEXT_FIELD_NAME + "\": { " +
            "\"match\" : { " +
            "   \"query\" : \"term1\"," +
            "   \"filter\" : { " +
            "       \"script\" : { " +
            "            \"source\" : \"interval.start > 3\" } } } } } }";

        IntervalQueryBuilder builder = (IntervalQueryBuilder) parseQuery(json);
        Query q = builder.toQuery(context);

        IntervalQuery expected = new IntervalQuery(TEXT_FIELD_NAME,
            new IntervalsSourceProvider.ScriptFilterSource(Intervals.term("term1"), "interval.start > 3", null));
        assertEquals(expected, q);
    }

    public void testPrefixes() throws IOException {

        String json = "{ \"intervals\" : { \"" + TEXT_FIELD_NAME + "\": { " +
            "\"prefix\" : { \"prefix\" : \"term\" } } } }";
        IntervalQueryBuilder builder = (IntervalQueryBuilder) parseQuery(json);
        Query expected = new IntervalQuery(TEXT_FIELD_NAME, Intervals.prefix(new BytesRef("term")));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

        String no_positions_json = "{ \"intervals\" : { \"" + NO_POSITIONS_FIELD + "\": { " +
            "\"prefix\" : { \"prefix\" : \"term\" } } } }";
        expectThrows(IllegalArgumentException.class, () -> {
            IntervalQueryBuilder builder1 = (IntervalQueryBuilder) parseQuery(no_positions_json);
            builder1.toQuery(createSearchExecutionContext());
            });

        String no_positions_fixed_field_json = "{ \"intervals\" : { \"" + TEXT_FIELD_NAME + "\": { " +
            "\"prefix\" : { \"prefix\" : \"term\", \"use_field\" : \"" + NO_POSITIONS_FIELD + "\" } } } }";
        expectThrows(IllegalArgumentException.class, () -> {
            IntervalQueryBuilder builder1 = (IntervalQueryBuilder) parseQuery(no_positions_fixed_field_json);
            builder1.toQuery(createSearchExecutionContext());
        });

        String prefix_json = "{ \"intervals\" : { \"" + PREFIXED_FIELD + "\": { " +
            "\"prefix\" : { \"prefix\" : \"term\" } } } }";
        builder = (IntervalQueryBuilder) parseQuery(prefix_json);
        expected = new IntervalQuery(PREFIXED_FIELD, Intervals.fixField(PREFIXED_FIELD + "._index_prefix", Intervals.term("term")));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

        String short_prefix_json = "{ \"intervals\" : { \"" + PREFIXED_FIELD + "\": { " +
            "\"prefix\" : { \"prefix\" : \"t\" } } } }";
        builder = (IntervalQueryBuilder) parseQuery(short_prefix_json);
        expected = new IntervalQuery(PREFIXED_FIELD, Intervals.or(
            Intervals.fixField(PREFIXED_FIELD + "._index_prefix", Intervals.wildcard(new BytesRef("t?"))),
            Intervals.term("t")));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

        String fix_field_prefix_json =  "{ \"intervals\" : { \"" + TEXT_FIELD_NAME + "\": { " +
            "\"prefix\" : { \"prefix\" : \"term\", \"use_field\" : \"" + PREFIXED_FIELD + "\" } } } }";
        builder = (IntervalQueryBuilder) parseQuery(fix_field_prefix_json);
        // This looks weird, but it's fine, because the innermost fixField wins
        expected = new IntervalQuery(TEXT_FIELD_NAME,
            Intervals.fixField(PREFIXED_FIELD, Intervals.fixField(PREFIXED_FIELD + "._index_prefix", Intervals.term("term"))));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

        String keyword_json = "{ \"intervals\" : { \"" + PREFIXED_FIELD + "\": { " +
            "\"prefix\" : { \"prefix\" : \"Term\", \"analyzer\" : \"keyword\" } } } }";
        builder = (IntervalQueryBuilder) parseQuery(keyword_json);
        expected = new IntervalQuery(PREFIXED_FIELD, Intervals.fixField(PREFIXED_FIELD + "._index_prefix", Intervals.term("Term")));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

        String keyword_fix_field_json = "{ \"intervals\" : { \"" + TEXT_FIELD_NAME + "\": { " +
            "\"prefix\" : { \"prefix\" : \"Term\", \"analyzer\" : \"keyword\", \"use_field\" : \"" + PREFIXED_FIELD + "\" } } } }";
        builder = (IntervalQueryBuilder) parseQuery(keyword_fix_field_json);
        expected = new IntervalQuery(TEXT_FIELD_NAME,
            Intervals.fixField(PREFIXED_FIELD, Intervals.fixField(PREFIXED_FIELD + "._index_prefix", Intervals.term("Term"))));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));
    }

    public void testWildcard() throws IOException {

        String json = "{ \"intervals\" : { \"" + TEXT_FIELD_NAME + "\": { " +
            "\"wildcard\" : { \"pattern\" : \"Te?m\" } } } }";

        IntervalQueryBuilder builder = (IntervalQueryBuilder) parseQuery(json);
        Query expected = new IntervalQuery(TEXT_FIELD_NAME, Intervals.wildcard(new BytesRef("te?m")));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

        String no_positions_json = "{ \"intervals\" : { \"" + NO_POSITIONS_FIELD + "\": { " +
            "\"wildcard\" : { \"pattern\" : \"term\" } } } }";
        expectThrows(IllegalArgumentException.class, () -> {
            IntervalQueryBuilder builder1 = (IntervalQueryBuilder) parseQuery(no_positions_json);
            builder1.toQuery(createSearchExecutionContext());
        });

        String keyword_json = "{ \"intervals\" : { \"" + TEXT_FIELD_NAME + "\": { " +
            "\"wildcard\" : { \"pattern\" : \"Te?m\", \"analyzer\" : \"keyword\" } } } }";

        builder = (IntervalQueryBuilder) parseQuery(keyword_json);
        expected = new IntervalQuery(TEXT_FIELD_NAME, Intervals.wildcard(new BytesRef("Te?m")));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

        String fixed_field_json = "{ \"intervals\" : { \"" + TEXT_FIELD_NAME + "\": { " +
            "\"wildcard\" : { \"pattern\" : \"Te?m\", \"use_field\" : \"masked_field\" } } } }";

        builder = (IntervalQueryBuilder) parseQuery(fixed_field_json);
        expected = new IntervalQuery(TEXT_FIELD_NAME, Intervals.fixField(MASKED_FIELD, Intervals.wildcard(new BytesRef("te?m"))));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

        String fixed_field_json_no_positions = "{ \"intervals\" : { \"" + TEXT_FIELD_NAME + "\": { " +
            "\"wildcard\" : { \"pattern\" : \"Te?m\", \"use_field\" : \"" + NO_POSITIONS_FIELD + "\" } } } }";
        expectThrows(IllegalArgumentException.class, () -> {
            IntervalQueryBuilder builder1 = (IntervalQueryBuilder) parseQuery(fixed_field_json_no_positions);
            builder1.toQuery(createSearchExecutionContext());
        });

        String fixed_field_analyzer_json = "{ \"intervals\" : { \"" + TEXT_FIELD_NAME + "\": { " +
            "\"wildcard\" : { \"pattern\" : \"Te?m\", \"use_field\" : \"masked_field\", \"analyzer\" : \"keyword\" } } } }";

        builder = (IntervalQueryBuilder) parseQuery(fixed_field_analyzer_json);
        expected = new IntervalQuery(TEXT_FIELD_NAME, Intervals.fixField(MASKED_FIELD,
            Intervals.wildcard(new BytesRef("Te?m"))));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));
    }

    private static IntervalsSource buildFuzzySource(String term, String label, int prefixLength, boolean transpositions, int editDistance) {
        FuzzyQuery fq = new FuzzyQuery(new Term("field", term), editDistance, prefixLength, 128, transpositions);
        return Intervals.multiterm(fq.getAutomata(), label);
    }

    public void testFuzzy() throws IOException {

        String json = "{ \"intervals\" : { \"" + TEXT_FIELD_NAME + "\": { " +
            "\"fuzzy\" : { \"term\" : \"Term\" } } } }";
        IntervalQueryBuilder builder = (IntervalQueryBuilder) parseQuery(json);

        Query expected = new IntervalQuery(TEXT_FIELD_NAME,
            buildFuzzySource("term", "Term", FuzzyQueryBuilder.DEFAULT_PREFIX_LENGTH, true, Fuzziness.AUTO.asDistance("term")));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

        String json_with_prefix = "{ \"intervals\" : { \"" + TEXT_FIELD_NAME + "\": { " +
            "\"fuzzy\" : { \"term\" : \"Term\", \"prefix_length\" : 2 } } } }";
        builder = (IntervalQueryBuilder) parseQuery(json_with_prefix);
        expected = new IntervalQuery(TEXT_FIELD_NAME,
            buildFuzzySource("term", "Term", 2, true, Fuzziness.AUTO.asDistance("term")));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

        String json_with_fuzziness = "{ \"intervals\" : { \"" + TEXT_FIELD_NAME + "\": { " +
            "\"fuzzy\" : { \"term\" : \"Term\", \"prefix_length\" : 2, \"fuzziness\" : \"1\" } } } }";
        builder = (IntervalQueryBuilder) parseQuery(json_with_fuzziness);
        expected = new IntervalQuery(TEXT_FIELD_NAME,
            buildFuzzySource("term", "Term", 2, true, Fuzziness.ONE.asDistance("term")));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

        String json_no_transpositions = "{ \"intervals\" : { \"" + TEXT_FIELD_NAME + "\": { " +
            "\"fuzzy\" : { \"term\" : \"Term\", \"prefix_length\" : 2, \"transpositions\" : false } } } }";
        builder = (IntervalQueryBuilder) parseQuery(json_no_transpositions);
        expected = new IntervalQuery(TEXT_FIELD_NAME,
            buildFuzzySource("term", "Term", 2, false, Fuzziness.AUTO.asDistance("term")));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

        String json_with_analyzer = "{ \"intervals\" : { \"" + TEXT_FIELD_NAME + "\": { " +
            "\"fuzzy\" : { \"term\" : \"Term\", \"prefix_length\" : 2, \"analyzer\" : \"keyword\" } } } }";
        builder = (IntervalQueryBuilder) parseQuery(json_with_analyzer);
        expected = new IntervalQuery(TEXT_FIELD_NAME,
            buildFuzzySource("Term", "Term", 2, true, Fuzziness.AUTO.asDistance("term")));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

        String json_with_fixfield = "{ \"intervals\" : { \"" + TEXT_FIELD_NAME + "\": { " +
            "\"fuzzy\" : { \"term\" : \"Term\", \"prefix_length\" : 2, \"fuzziness\" : \"1\", " +
            "\"use_field\" : \"" + MASKED_FIELD + "\" } } } }";
        builder = (IntervalQueryBuilder) parseQuery(json_with_fixfield);
        expected = new IntervalQuery(TEXT_FIELD_NAME, Intervals.fixField(MASKED_FIELD,
            buildFuzzySource("term", "Term", 2, true, Fuzziness.ONE.asDistance("term"))));
        assertEquals(expected, builder.toQuery(createSearchExecutionContext()));

    }

}
