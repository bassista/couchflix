package com.cb.fts.sample.service;

import com.cb.fts.sample.entities.vo.*;
import com.cb.fts.sample.repositories.MovieRepository;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.facet.SearchFacet;
import com.couchbase.client.java.search.queries.*;
import com.couchbase.client.java.search.result.SearchQueryResult;
import com.couchbase.client.java.search.result.SearchQueryRow;
import com.couchbase.client.java.search.result.facets.FacetResult;
import com.couchbase.client.java.search.result.facets.TermFacetResult;
import com.couchbase.client.java.search.result.facets.TermRange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.cb.fts.sample.service.Query.EntityType.GENRES;
import static com.cb.fts.sample.service.Query.EntityType.PERSON;

@Service
public class MovieServiceImpl implements MovieService {

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private MovieQueryParser movieQueryParser;


    @Override
    public Result searchQuery(String word, String filters) {
        Map<String,List<String>> facets = getFilters(filters);
        return search11(word, facets);
    }

    private Map<String,List<String>> getFilters(String filters){

        if (filters == null || filters.trim().isEmpty()) {
            return new HashMap<>();
        }
        String[] values = filters.split("::");

        Map<String,List<String>> facets = new HashMap<>();
        for(int i=0;i<values.length; i++) {

            String[] test = values[i].split("=");
            if(test.length > 1) {
                facets.put(test[0], Arrays.asList(test[1].split(",")));
            }
        }

        return facets;
    }


    /**
     * Let's implement a simple search
     * ACTION: 1) Search for 'Star Wars' 2) Search for 'Star War'
     * PROBLEM: Star Wars movies will barely show as a result.
     *          Only a single result in the 5th place, show the match in the details
     * @param word
     * @return
     */
    private Result search1(String word){
        String indexName = "movies_all_index";
        QueryStringQuery query = SearchQuery.queryString(word);
        SearchQueryResult result = movieRepository.getCouchbaseOperations().getCouchbaseBucket().query(
                new SearchQuery(indexName, query).highlight().limit(30));
        return  getSearchResults(result);
    }


    /**
     * Let's implement a search with fuzziness
     * EXPLANATION:
     * ACTION: Search for "Star War"
     *         Star Wars now appears as the second result
     *         Show the scores of the movies are very close
     *         Explore the matches
     *         Star wars match in the overview, (Wan and Wars), original_title, title, and even home page
     * PROBLEM: Do we really need to match in all fields? Seems like matching all fields is generating a lot of noise
     * @param word
     * @return
     */
    private Result search2(String word){
        String indexName = "movies_all_index";
        MatchQuery query = SearchQuery.match(word);
        MatchQuery queryFuzzy = SearchQuery.match(word).fuzziness(1);
        DisjunctionQuery ftsQuery = SearchQuery.disjuncts(query, queryFuzzy);

        SearchQueryResult result = movieRepository.getCouchbaseOperations().getCouchbaseBucket().query(
                new SearchQuery(indexName, ftsQuery).highlight().limit(30));

        return getSearchResults(result);
    }


    /**
     * ACTION: Search for "Star War"
     *         Star wars appears in the 12th position now, do we got worse?
     *         The movie called just "Star" gets a high score because the name of
     *                  the movie is a exact match of one of our terms
     *                  (Explain that )
     * PROBLEM: Maybe searching in the title isn't enough.
     * @param words
     * @return
     */
    private Result search3(String words){
        String indexName = "movies_all_index";
        MatchQuery query = SearchQuery.match(words).field("title");
        MatchQuery queryFuzzy = SearchQuery.match(words).field("title").fuzziness(1);

        DisjunctionQuery ftsQuery = SearchQuery.disjuncts(query, queryFuzzy);

        SearchQueryResult result = movieRepository.getCouchbaseOperations().getCouchbaseBucket().query(
                new SearchQuery(indexName, ftsQuery).highlight().limit(30));

        return getSearchResults(result);
    }


    /**
     * ACTION: 1) Search for "Star War"
     *              Star wars doesn't even appear as a result
     * PROBLEM: Maybe the problems is that the two words should be matched together
     * @param words
     * @return
     */
    private Result search4(String words){
        String indexName = "movies_all_index";
        DisjunctionQuery titleQuery = getDisjunction(words, "title");
        DisjunctionQuery originalQuery = getDisjunction(words, "original_title");
        DisjunctionQuery overviewQuery = getDisjunction(words, "overview");
        DisjunctionQuery ftsQuery = SearchQuery.disjuncts(titleQuery, originalQuery, overviewQuery);

        SearchQueryResult result = movieRepository.getCouchbaseOperations().getCouchbaseBucket().query(
                new SearchQuery(indexName, ftsQuery).highlight().limit(30));

        return getSearchResults(result);
    }

    /**
     * ACTION: 1) Create new Index called "movies_shingle"
     *             1.1) Custom filters -> create new Shingle: min=2, max=2, check "Include original token"
     *             1.2) Analyzers -> create new analyzer called "Custom_Analyzer":
     *                              include the following token filters:
     *                                      stop_en,
     *                                      to_lower
     *                                      shingle
     *         2) Search for Star War again, star wars movies will be returned
     * PROBLEM: The results still does not look right, the first results are not exactly the most famous movies
     * @param words
     * @return
     */
    private Result search5(String words){
        String indexName = "movies_shingle";
        DisjunctionQuery titleQuery = getDisjunction(words, "title");
        DisjunctionQuery originalQuery = getDisjunction(words, "original_title");
        DisjunctionQuery overviewQuery = getDisjunction(words, "overview");
        DisjunctionQuery ftsQuery = SearchQuery.disjuncts(titleQuery, originalQuery, overviewQuery);

        SearchQueryResult result = movieRepository.getCouchbaseOperations().getCouchbaseBucket().query(
                new SearchQuery(indexName, ftsQuery).highlight().limit(30));

        return getSearchResults(result);
    }


    /**
     * Index year
     *
     * ACTION:  add release_year (number) to the movies_shingle index
     *          In the code we have enabled "explain"
     *
     * RESULT:  Nothing changed, although the scores are different
     *          Star Wars - is being penalized on 0.87
     *          Rogue one is boosted 1.10
     * PROBLEM: Movie "Star Wars Robot Chicken" key::42979 -> vote_count: 58,  "popularity": 7.994542,
     *          Plastic Galaxy: The Story of Star Wars Toys  key::253150 -> 9,  "popularity": 1.168756
     *          Why those movies are ranking higher than Episode 1, 2 and 3?
     *          We need to think about ranking factors
     *
     * QUESTION: We need to thing about ranking factors (then go to slides)
     *
     * @return
     */
    private Result search6(String words){
        String indexName = "movies_shingle";
        DisjunctionQuery titleQuery = getDisjunction(words, "title", 1.4);
        DisjunctionQuery originalQuery = getDisjunction(words, "original_title", 1.15);
        DisjunctionQuery overviewQuery = getDisjunction(words, "overview");
        DisjunctionQuery ftsQuery = SearchQuery.disjuncts(titleQuery, originalQuery, overviewQuery);

        DisjunctionQuery yearInterval = boostReleaseYearQuery();
        ConjunctionQuery conjunctionQuery = SearchQuery.conjuncts(ftsQuery, yearInterval);

        SearchQueryResult result = movieRepository.getCouchbaseOperations().getCouchbaseBucket().query(
                new SearchQuery(indexName, conjunctionQuery).highlight().explain(true).limit(30));

        return getSearchResults(result);
    }


    /**
     * search for "fat eddie murphy"
     * @param words
     * @return
     */
    private Result search7(String words){
        String indexName = "movies_shingle";
        DisjunctionQuery titleQuery = getDisjunction(words, "title", 1.4);
        DisjunctionQuery originalQuery = getDisjunction(words, "original_title", 1.05);
        DisjunctionQuery overviewQuery = getDisjunction(words, "overview");
        DisjunctionQuery ftsQuery = SearchQuery.disjuncts(titleQuery,
                originalQuery,
                getActorsDisjunction(words),
                getCrewDisjunction(words),
                overviewQuery);

        ConjunctionQuery conjunctionQuery = SearchQuery.conjuncts(ftsQuery,
                boostReleaseYearQuery(),
                boostRuntime(),
                boostPopularity());

        SearchQueryResult result = movieRepository.getCouchbaseOperations().getCouchbaseBucket().query(
                new SearchQuery(indexName, conjunctionQuery).highlight().limit(30));

        return getSearchResults(result);
    }


    private Result search8(String words){
        String indexName = "movies_shingle";
        DisjunctionQuery titleQuery = getDisjunction(words, "title", 1.4);
        DisjunctionQuery originalQuery = getDisjunction(words, "original_title", 1.15);
        DisjunctionQuery overviewQuery = getDisjunction(words, "overview");
        DisjunctionQuery ftsQuery = SearchQuery.disjuncts(titleQuery,
                originalQuery,
                getActorsDisjunction(words),
                getCrewDisjunction(words),
                overviewQuery);

        ConjunctionQuery conjunctionQuery = SearchQuery.conjuncts(ftsQuery,
                boostReleaseYearQuery(),
                boostRuntime(),
                boostWeightedRating(),
                boostPopularity());


        SearchQueryResult result = movieRepository.getCouchbaseOperations().getCouchbaseBucket().query(
                new SearchQuery(indexName, conjunctionQuery).highlight().limit(30));

        return getSearchResults(result);
    }


    private Result search9(String words){
        String indexName = "movies_shingle";
        DisjunctionQuery titleQuery = getDisjunction(words, "title", 1.4);
        DisjunctionQuery originalQuery = getDisjunction(words, "original_title", 1.15);
        DisjunctionQuery overviewQuery = getDisjunction(words, "overview");


        DisjunctionQuery ftsQuery = SearchQuery.disjuncts(titleQuery,
                originalQuery,
                getActorsDisjunction(words),
                getCrewDisjunction(words),
                overviewQuery);

        ConjunctionQuery conjunctionQuery = SearchQuery.conjuncts(ftsQuery,
                boostReleaseYearQuery(),
                boostRuntime(),
                boostPromoted(),
                boostWeightedRating(),
                boostPopularity());


        SearchQueryResult result = movieRepository.getCouchbaseOperations().getCouchbaseBucket().query(
                new SearchQuery(indexName, conjunctionQuery).highlight().limit(30));

        return getSearchResults(result);
    }

    private DisjunctionQuery boostPromoted() {
        BooleanFieldQuery promotedQuery = SearchQuery.booleanField(true).field("promoted").boost(1.5);
        BooleanFieldQuery notPromotedQuery = SearchQuery.booleanField(false).field("promoted").boost(1);
        return SearchQuery.disjuncts(promotedQuery, notPromotedQuery);
    }


    /**
     * Adding facets
     * @param words
     * @return
     */
    private Result search10(String words, Map<String, List<String>> facets){
        String indexName = "movies_shingle";
        DisjunctionQuery titleQuery = getDisjunction(words, "title", 1.4);
        DisjunctionQuery originalQuery = getDisjunction(words, "original_title", 1.15);
        DisjunctionQuery collection = getDisjunction(words, "collection.name", 1.1);
        DisjunctionQuery overviewQuery = getDisjunction(words, "overview");

        DisjunctionQuery ftsQuery = SearchQuery.disjuncts(titleQuery,
                originalQuery,
                collection,
                getActorsDisjunction(words),
                getCrewDisjunction(words),
                overviewQuery);

        ConjunctionQuery conjunctionQuery = SearchQuery.conjuncts(ftsQuery,
                boostReleaseYearQuery(),
                boostRuntime(),
                boostPromoted(),
                boostWeightedRating(),
                boostPopularity());

        if(!facets.isEmpty()) {
            conjunctionQuery = addFacetFilters(conjunctionQuery, facets);
        }

        SearchQuery query =  new SearchQuery(indexName, conjunctionQuery).highlight().limit(20);
        query.addFacet("genres",  SearchFacet.term("genres.name", 10));
        SearchQueryResult result = movieRepository.getCouchbaseOperations().getCouchbaseBucket().query(query);
        return getSearchResults(result);
    }

//    /**
//     * Bruce willis
//     * @param words
//     * @return
//     */
//    private Result search10(String words, Map<String,List<String>> facets){
//
//        boolean hasPerson = false;//MovieQueryParser.containsPerson(words);
//
//        double penalty = 0;
//        if(hasPerson){
//            penalty = 0.4;
//            System.out.println("the query is about a person : "+words );
//        }
//
//        String indexName = "movies_shingle";
//        DisjunctionQuery titleQuery = getDisjunction(words, "title", 1.4 -penalty);
//        DisjunctionQuery overviewQuery = getDisjunction(words, "overview", 1);
//
//        DisjunctionQuery ftsQuery = null;
//        if(!hasPerson) {
//            DisjunctionQuery originalQuery = getDisjunction(words, "original_title", 1.15);
//            ftsQuery = SearchQuery.disjuncts(titleQuery, originalQuery,
//                    getActorsDisjunction(words, hasPerson),
//                    getCrewDisjunction(words, hasPerson),
//                    overviewQuery);
//        } else {
//            ftsQuery = SearchQuery.disjuncts(titleQuery,
//                    getActorsDisjunction(words, hasPerson),
//                    getCrewDisjunction(words, hasPerson),
//                    overviewQuery);
//        }
//
//        ConjunctionQuery conjunctionQuery = SearchQuery.conjuncts(ftsQuery,
//                boostReleaseYearQuery(),
//                boostRuntime(),
//                boostWeightedRating(),
//                boostPopularity());
//
//        SearchQueryResult result = movieRepository.getCouchbaseOperations().getCouchbaseBucket().query(
//                new SearchQuery(indexName, conjunctionQuery).highlight().limit(30));
//
//        return getSearchResults(result);
//    }



    private Result search11(String words, Map<String, List<String>> facets){
        String indexName = "movies_shingle";

        Query query = movieQueryParser.parse(words);

        DisjunctionQuery ftsQuery = new DisjunctionQuery();
        if(query.getWords().trim().length() >0) {
            ftsQuery.or(getDisjunction(query.getWords(), "title", 1.4));
            ftsQuery.or( getDisjunction(query.getWords(), "original_title", 1.15));
            ftsQuery.or(getDisjunction(query.getWords(), "collection.name", 1.1));
            ftsQuery.or( getDisjunction(query.getWords(), "overview"));
        }

        DisjunctionQuery actors = getActorsDisjunction(words, query);
        if(actors!= null) {
            ftsQuery.or(actors);
        }

//        DisjunctionQuery crew = ftsQuery.or(getCrewDisjunction(words, query));
//        if(crew!= null) {
//            ftsQuery.or(crew);
//        }

       // System.out.println("!!!!!!!!!!childQueries!!!!!!!"+ftsQuery.childQueries().size());

        ConjunctionQuery conjunctionQuery = SearchQuery.conjuncts(ftsQuery,
                boostReleaseYearQuery(),
                boostRuntime(),
                boostPromoted(),
                boostWeightedRating(),
                boostPopularity());

        if(query.getEntities().containsKey(GENRES)) {
            addFilters(conjunctionQuery, "genres.name", query.getEntities().get(GENRES));
        }

        if(!facets.isEmpty()) {
            conjunctionQuery = addFacetFilters(conjunctionQuery, facets);
        }


        System.out.println("===================================================");
        System.out.println(query);
        System.out.println("===================================================");

        SearchQuery searchQuery =  new SearchQuery(indexName, conjunctionQuery).highlight().limit(50);
        searchQuery.addFacet("genres",  SearchFacet.term("genres.name", 10));
        SearchQueryResult result = movieRepository.getCouchbaseOperations().getCouchbaseBucket().query(searchQuery);
        return getSearchResults(result);
    }


    private ConjunctionQuery addFacetFilters(ConjunctionQuery conjunctionQuery , Map<String, List<String>> facets) {


        for(Map.Entry<String, List<String>> entry: facets.entrySet()) {
            if("genres".equals(entry.getKey())) {
                addFilters(conjunctionQuery, "genres.name", entry.getValue());
            } else if("collection".equals(entry.getKey())) {
                addFilters(conjunctionQuery, "collection.name", entry.getValue());
            } else if("year".equals(entry.getKey())) {
                addFilters(conjunctionQuery, "release_year", entry.getValue());
            }
        }
        return conjunctionQuery;
    }

    private void addFilters(ConjunctionQuery conjunctionQuery, String fieldName, List<String> values) {
        if(values.size() == 1) {
            conjunctionQuery.and(SearchQuery.term(values.get(0)).field(fieldName));
        } if(values.size() > 1) {
            DisjunctionQuery disjunctionQuery = SearchQuery.disjuncts();
            for(String genre : values) {
                disjunctionQuery.or(SearchQuery.term(genre).field(fieldName));
            }
            conjunctionQuery.and(disjunctionQuery);
        }
    }


    private DisjunctionQuery getActorsDisjunction(String words) {
            DisjunctionQuery castQuery = getDisjunction(words, "cast.name", 1.15);
            MatchQuery character = SearchQuery.match(words).field("cast.character");
            return SearchQuery.disjuncts(castQuery, character);

    }

    private DisjunctionQuery getCrewDisjunction(String words) {
            DisjunctionQuery nameQuery = getDisjunction(words, "crew.name", 1.15);
            MatchQuery jobQuery = SearchQuery.match(words).boost(1.1).field("crew.job");
        return SearchQuery.disjuncts(nameQuery, jobQuery);

    }


    private DisjunctionQuery getActorsDisjunction(String words, Query query) {

        if(query!= null && query.getEntities().keySet().contains(PERSON)) {
            List<String> names = query.getEntities().get(PERSON);
            if(names.size() ==1) {
                return getDisjunction(names.get(0), "castAdjusted.name", 1.5 );
            } else {
                DisjunctionQuery dq = new DisjunctionQuery();
                for(String name: names){
                    dq.or(getDisjunction(name, "castAdjusted.name", 1.5 ));
                }
                return dq;
            }
        } else {
            if (query.getWords().trim().isEmpty()) {
                return null;
            }
            DisjunctionQuery castQuery = getDisjunction(words, "castAdjusted.name", 1.15);
            MatchQuery character = SearchQuery.match(words).field("castAdjusted.character");
            return SearchQuery.disjuncts(castQuery, character);
        }
    }

    private DisjunctionQuery getCrewDisjunction(String words, Query query) {

        if(query!= null && query.getEntities().keySet().contains(PERSON)) {
            List<String> names = query.getEntities().get(PERSON);
            if(names.size() ==1) {
                return getDisjunction(words, "crew.name", 1.4);
            } else {
                DisjunctionQuery dq = new DisjunctionQuery();
                for(String name: names){
                    dq.or(getDisjunction(name, "crew.name", 1.4));
                }
                return dq;
            }
        } else {
            if (query.getWords().trim().isEmpty()) {
                return null;
            }
            DisjunctionQuery nameQuery = getDisjunction(words, "crew.name", 1.15);
            MatchQuery jobQuery = SearchQuery.match(words).boost(1.1).field("crew.job");
            return SearchQuery.disjuncts(nameQuery, jobQuery);
        }
    }

    private DisjunctionQuery boostPopularity() {
        NumericRangeQuery rangeQuery = SearchQuery.numericRange().field("popularity").boost(1.25);
        rangeQuery.max(1000);
        rangeQuery.min(40);

        NumericRangeQuery rangeQuery2 = SearchQuery.numericRange().field("popularity").boost(1.20);
        rangeQuery2.max(39.9999);
        rangeQuery2.min(30);

        NumericRangeQuery rangeQuery3 = SearchQuery.numericRange().field("popularity").boost(1.10);
        rangeQuery3.max(29.9999);
        rangeQuery3.min(10);

        NumericRangeQuery rangeQuery4 = SearchQuery.numericRange().field("popularity").boost(0.90);
        rangeQuery4.max(9.9999);
        rangeQuery4.min(4);

        NumericRangeQuery rangeQuery5 = SearchQuery.numericRange().field("popularity").boost(0.80);
        rangeQuery5.max(3.9999);
        rangeQuery5.min(0);

        DisjunctionQuery yearDisjunction = SearchQuery.disjuncts(rangeQuery, rangeQuery2, rangeQuery3, rangeQuery4, rangeQuery5 );
        return yearDisjunction;
    }

    private DisjunctionQuery boostReleaseYearQuery() {

        LocalDateTime now = LocalDateTime.now();
        //movies older than which are up to 3 years old get a boost of 10%
        NumericRangeQuery rangeQuery = SearchQuery.numericRange().field("release_year").boost(1.35);
        rangeQuery.max(now.getYear());
        rangeQuery.min(now.getYear()-4);

        NumericRangeQuery penalizationQuery = SearchQuery.numericRange().field("release_year").boost(1.15);
        penalizationQuery.max(now.getYear()-5);
        penalizationQuery.min(now.getYear()-10);

        NumericRangeQuery penalization1Query = SearchQuery.numericRange().field("release_year").boost(1);
        penalization1Query.max(now.getYear()-9);
        penalization1Query.min(now.getYear()-15);

        NumericRangeQuery penalization2Query = SearchQuery.numericRange().field("release_year").boost(0.92);
        penalization2Query.max(now.getYear()-16);
        penalization2Query.min(now.getYear()-25);

        //movies which are from 8 to 18 years old, nothing will be penalized in 10%
        NumericRangeQuery penalization3Query = SearchQuery.numericRange().field("release_year").boost(0.85);
        penalization3Query.max(now.getYear()-25);
        penalization3Query.min(0);

        DisjunctionQuery yearDisjunction = SearchQuery.disjuncts(rangeQuery, penalizationQuery, penalization1Query, penalization2Query, penalization3Query );

        return yearDisjunction;
    }

    private DisjunctionQuery boostRuntime() {

        NumericRangeQuery runtime1 = SearchQuery.numericRange().field("runtime").boost(1.25);
        runtime1.max(5000);
        runtime1.min(360);

        NumericRangeQuery runtime2 = SearchQuery.numericRange().field("runtime").boost(1.17);
        runtime2.max(359);
        runtime2.min(100);

        NumericRangeQuery runtime3 = SearchQuery.numericRange().field("runtime").boost(0.90);
        runtime3.max(99);
        runtime3.min(40);

        NumericRangeQuery runtime4 = SearchQuery.numericRange().field("runtime").boost(0.75);
        runtime4.max(39);
        runtime4.min(0);

        DisjunctionQuery runtimeDisjunction = SearchQuery.disjuncts(runtime1, runtime2, runtime3, runtime4 );

        return runtimeDisjunction;
    }

    private DisjunctionQuery boostWeightedRating() {

        NumericRangeQuery weightedRating1 = SearchQuery.numericRange().field("weightedRating").boost(1.25);
        weightedRating1.max(10);
        weightedRating1.min(7);

        NumericRangeQuery weightedRating2 = SearchQuery.numericRange().field("weightedRating").boost(1.10);
        weightedRating2.max(6.9999);
        weightedRating2.min(5);

        NumericRangeQuery weightedRating3 = SearchQuery.numericRange().field("weightedRating").boost(1);
        weightedRating3.max(4.999);
        weightedRating3.min(3);

        NumericRangeQuery weightedRating4 = SearchQuery.numericRange().field("weightedRating").boost(0.75);
        weightedRating4.max(2.999);
        weightedRating4.min(0);


        DisjunctionQuery runtimeDisjunction = SearchQuery.disjuncts(weightedRating1, weightedRating2, weightedRating3, weightedRating4 );

        return runtimeDisjunction;
    }

//    private List<SearchResult> search5(String words){
//        String indexName = "movies_all_index";
//        DisjunctionQuery titleQuery = getDisjunction(words, "title", 1.4);
//        DisjunctionQuery originalQuery = getDisjunction(words, "original_title", 1.3);
//        DisjunctionQuery overviewQuery = getDisjunction(words, "overview");
//        DisjunctionQuery ftsQuery = SearchQuery.disjuncts(titleQuery, originalQuery, originalQuery, overviewQuery);
//
//        SearchQueryResult result = movieRepository.getCouchbaseOperations().getCouchbaseBucket().query(
//                new SearchQuery(indexName, ftsQuery).highlight().limit(30));
//
//        return getSearchResults(result);
//    }


    private DisjunctionQuery getDisjunction(String words, String field) {
        MatchQuery query = SearchQuery.match(words).field(field);
        MatchQuery queryFuzzy = SearchQuery.match(words).field(field).fuzziness(1);
        return SearchQuery.disjuncts(query, queryFuzzy);
    }


    private DisjunctionQuery getDisjunction(String words, String field, double boost) {
        MatchQuery query = SearchQuery.match(words).boost(boost).field(field);
        MatchQuery queryFuzzy = SearchQuery.match(words).boost(boost).field(field).fuzziness(1);
        return SearchQuery.disjuncts(query, queryFuzzy);
    }




    private Result getSearchResults(SearchQueryResult result){

        Result rt = new Result();
        List<SearchResult> movies = new ArrayList<>();
        if (result != null && result.errors().isEmpty()) {
            Iterator<SearchQueryRow> resultIterator = result.iterator();
            int counter = 1;
            while (resultIterator.hasNext()) {
                SearchQueryRow row = resultIterator.next();
                movies.add( new SearchResult(movieRepository.findById(row.id()).get(), new QueryStats(counter, row)));
                counter++;
            }
        }

        rt.setResults(movies);

        if( result.facets()!= null & result.facets().size() >0) {
            List<Facet> facets = new ArrayList<>();
            for( Map.Entry<String, FacetResult> entry: result.facets().entrySet()) {

                List<TermRange> termRanges = ((TermFacetResult) entry.getValue()).terms();
                List<FacetItem> items =  termRanges.stream().map(e->new FacetItem(e)).collect(Collectors.toList());
                facets.add(new Facet(entry.getKey(), items));
            }

            rt.setFacets(facets);
        }
        return rt;
    }

    public void printTime(StopWatch stopWatch){

        System.out.println("=============TIME SPENT====================");
        System.out.println(stopWatch.getTotalTimeSeconds() +" sec");
        System.out.println("===========================================");
    }


    private static void printResult(String label, SearchQueryResult resultObject) {
        System.out.println();
        System.out.println("= = = = = = = = = = = = = = = = = = = = = = =");
        System.out.println("= = = = = = = = = = = = = = = = = = = = = = =");
        System.out.println();
        System.out.println(label);
        System.out.println();

        for (SearchQueryRow row : resultObject) {
            System.out.println(row);
        }
    }



    //enrichment - augment the movie

    //finger in the wind -- bosting
    //never have empty results
    //word2vec
    //personalization - learn to rank model











    /*
    "   - only index the fields you need to search on;
        - related, don't use dynamic indexing unless you really need it
          (""dynamic"": false);
        - disable include_in_all as it makes another copy
          (""include_in_all"": false);
        - don't use stored fields if you're not using
          the highlighting or faceting features
          (""store"": false) (""store_dynamic"": false);
        - if you're indexing id or numeric fields where
          you don't need to range search (e.g., price > 100)
          and instead you're only doing exact value matching
          (e.g., company_id = 12001),
          then don't use field type ""number""
          and instead use field type ""text"" and the ""keyword"" analyzer;

        and, also see the FTS Tips document..."
     */


    //https://hub.internal.couchbase.com/confluence/pages/viewpage.action?spaceKey=CBFTS&title=Couchbase+Full+Text+Search

    //COMPArISON
    //https://docs.google.com/document/d/171-Z9uy1BsZFMkPGvVb0FlODbNnvrgdZvAbgU26xZ2k/edit#
    //https://github.com/o19s/elasticsearch-learning-to-rank
    //https://hub.internal.couchbase.com/confluence/pages/viewpage.action?spaceKey=CBFTS&title=Couchbase+Full+Text+Search


    //Customer satisfaction and retention – Factors that aid customer retention including seller feedback and order defect rate (ODR). Make customers happy and they’ll keep coming back. The more positive seller feedback and good reviews you get, the more likely it is that you’ll win the sale.

//
//    It’s a pretty simple process at its core:
//
//    First, they pull the relevant results from their massive “catalog” of product listings.
//            Then, they sort those results into an order that is “most relevant” to the user.
//            Now, some of you SEOs out there might be thinking, “Wait a second… Isn’t relevancy Google’s turf? I thought Amazon only cared about conversions! What’s all this focus on relevance doing here?”
//
//    The answer is simple: Relevance doesn’t mean the same thing to Amazon that it does to Google. Read this statement from A9 carefully to see if you can catch the difference:
//
//Time on page and bounce rate

    //confidence on ratings???????????????????
    //https://medium.freecodecamp.org/whose-reviews-should-you-trust-imdb-rotten-tomatoes-metacritic-or-fandango-7d1010c6cf19
}
