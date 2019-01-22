package com.cb.fts.sample.repositories;

import com.cb.fts.sample.entities.Movie;
import org.springframework.data.couchbase.core.query.N1qlPrimaryIndexed;
import org.springframework.data.couchbase.core.query.ViewIndexed;
import org.springframework.data.couchbase.repository.CouchbasePagingAndSortingRepository;

@N1qlPrimaryIndexed
@ViewIndexed(designDoc = "movie")
public interface MovieRepository extends CouchbasePagingAndSortingRepository<Movie, String> {
}
