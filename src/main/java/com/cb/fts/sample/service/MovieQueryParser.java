package com.cb.fts.sample.service;

import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.cb.fts.sample.entities.ActorName;
import com.cb.fts.sample.repositories.ActorNameRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.text.WordUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A probabilistic parser is potentially a better option here
 */

@Component
public class MovieQueryParser {

    @Autowired
    private ActorNameRepository actorNameRepository;

    private static final List<String> categories = Arrays.asList("comedy", "crime", "drama"
            , "science fiction", "romance", "horror", "thriller", "action"
            , "adventure", "fantasy", "mystery", "animation", "family", "foreign"
            , "documentary", "music", "history", "western", "tv movie");

    public Query parse(String words) {
        Query query = new Query(words);

        extractCategories(query);
        extractEntities(query);
        return query;
    }


    private void extractEntities(Query query) {

        List<String> shingles = getShingles(query.getWords());

        for(String shingle: shingles) {
            Optional<ActorName> actorName = actorNameRepository.findById(getActorNameId(shingle));
            if(actorName.isPresent()) {
                query.addEntity(Query.EntityType.PERSON, shingle);
            }

        }

    }

//    private static void extractEntities(Query query) {
//
//        HttpClient httpclient = HttpClients.createDefault();
//
//        try {
//            URIBuilder builder = new URIBuilder("https://francecentral.api.cognitive.microsoft.com/text/analytics/v2.1-preview/entities");
//
//            URI uri = builder.build();
//            HttpPost request = new HttpPost(uri);
//            request.setHeader("Content-Type", "application/json");
//            request.setHeader("Ocp-Apim-Subscription-Key", "");
//
//
//            System.out.println(">>>>>>>>>>>>>>>>"+WordUtils.capitalize( query.getWords()));
//            String body = "{\"documents\": [{\"language\": \"en\",\"id\": \"1\",\"text\": \"" + WordUtils.capitalize( query.getWords())  + "\"}]}";
//            // Request body
//            StringEntity reqEntity = new StringEntity(body);
//            request.setEntity(reqEntity);
//
//            HttpResponse response = httpclient.execute(request);
//            HttpEntity entity = response.getEntity();
//
//            ObjectMapper mapper = new ObjectMapper();
//            JsonNode rootNode = mapper.readTree(EntityUtils.toString(entity));
//
//            JsonNode documents = rootNode.get("documents");
//
//            if (documents.size() > 0 && !documents.get(0).has("entities")) {
//                return;
//            } else {
//                JsonNode entities = documents.get(0).get("entities");
//                for (int i = 0; i < entities.size(); i++) {
//
//                    if(entities.get(i).get("type").toString().contains("Person") || entities.get(i).get("type").toString().contains("Organization") ) {
//                        String name = entities.get(i).get("name").toString().replaceAll("\"", "");
//                        Query.EntityType type = Query.EntityType.valueOf(entities.get(i).get("type").toString().toUpperCase().replaceAll("\"", ""));
//                        query.addEntity(type, name);
//                    }
//                }
//
//            }
//        } catch (Exception e) {
//            System.out.println(e.getMessage());
//        }
//
//    }

    private static void extractCategories(Query query) {

        for(String category: categories) {
            if(query.getWords().toLowerCase().matches("(?s).*\\b"+category.toLowerCase()+"\\b.*")) {
                query.addEntity(Query.EntityType.GENRES, category );
            }
        }
    }


    private static List<String> getShingles(String words) {
        String[] w = words.split(" ");


        if(w.length == 0 || w.length == 1 ) {
            return new ArrayList<>();

        } else if(w.length == 2) {
            return  Arrays.asList(w[0]+ " " + w[1]);
        } else {
            List<String> shingles = new ArrayList<>();

            for (int i = 0; i < w.length - 1; i++) {

                shingles.add(w[i] +" "+w[i+1]);
            }

            return shingles;
        }
    }


    private static String getActorNameId(String name) {
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
