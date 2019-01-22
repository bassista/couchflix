package com.cb.fts.sample.service;

import com.cb.fts.sample.entities.Movie;
import com.cb.fts.sample.entities.vo.Result;
import com.cb.fts.sample.entities.vo.SearchResult;

import javax.validation.Valid;
import java.util.List;

public interface MovieService {

    Result searchQuery(String query, String genres);
}
