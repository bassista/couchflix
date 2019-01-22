package com.cb.fts.sample.service;

import com.cb.fts.sample.entities.Actor;
import com.cb.fts.sample.entities.ActorName;
import com.cb.fts.sample.entities.Movie;
import com.cb.fts.sample.repositories.ActorNameRepository;
import com.cb.fts.sample.repositories.MovieRepository;
import com.couchbase.client.java.document.json.JsonObject;
import com.couchbase.client.java.query.N1qlParams;
import com.couchbase.client.java.query.N1qlQuery;
import com.couchbase.client.java.query.ParameterizedN1qlQuery;
import com.couchbase.client.java.query.consistency.ScanConsistency;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class ActorEntityService {

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private ActorNameRepository actorNameRepository;

    public void generateActorNamesDatabase() {

        int page =0;
        boolean hasMore = true;

        //actorNameRepository.getCouchbaseOperations().getCouchbaseBucket().environment().
        do {
            System.out.println("---current "+page );
            Page<Movie> movies = movieRepository.findAll(PageRequest.of(page, 10, Sort.by("id")));

            if(movies.getSize() == 100) {
                page++;
                hasMore = true;
            } else {
                hasMore = false;
            }


            for(Movie movie: movies.getContent()) {
                if(movie.getCast() != null) {
                    int maxActors = 0;
                    for(Actor actor: movie.getCast()) {
                        if(maxActors>=10){
                            break;
                        }
                        Optional<ActorName> actorName = actorNameRepository.findById(getActorNameId(actor.getName()));

                        if(actorName.isPresent()) {
                            actorName.get().setMoviesCount(actorName.get().getMoviesCount()+1);
                            actorNameRepository.save(actorName.get());
                        } else {
                            String id = getActorNameId(actor.getName());
                            System.out.println("criando="+id);
                            ActorName an = new ActorName();
                            an.setMoviesCount(1);
                            an.setId(id);
                            actorNameRepository.save(an);
                        }

                    }
                }
            }
        } while (hasMore);

    }


    private List<Actor> getListActors() {

        String bucketName = actorNameRepository.getCouchbaseOperations().getCouchbaseBucket().bucketManager().info().name();

        String queryString = "select c.* from movies m unnest m.`cast` c limit 10 offset 0";

        N1qlParams params = N1qlParams.build().consistency(ScanConsistency.NOT_BOUNDED).adhoc(true);
        ParameterizedN1qlQuery query = N1qlQuery.parameterized(queryString, JsonObject.create(), params);
        return actorNameRepository.getCouchbaseOperations().findByN1QLProjection(query, Actor.class);
    }



    private String getActorNameId(String name) {
        String prefix = "actorName-";

        String newName = Normalizer
                .normalize(name.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("[^\\p{ASCII}]", "");

        return prefix+Arrays.asList(newName.split(" "))
                .stream()
                .map(e-> e.trim())
                .filter(e->!e.isEmpty())
                .collect(Collectors.joining("-"));
    }
}
