package org.infinispan.rest.search.reindex;

import static org.infinispan.rest.assertion.ResponseAssertion.assertThat;
import static org.infinispan.util.concurrent.CompletionStages.join;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

import org.infinispan.Cache;
import org.infinispan.client.rest.RestCacheClient;
import org.infinispan.client.rest.RestClient;
import org.infinispan.client.rest.RestEntity;
import org.infinispan.client.rest.RestResponse;
import org.infinispan.client.rest.configuration.RestClientConfigurationBuilder;
import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.commons.dataconversion.internal.Json;
import org.infinispan.commons.test.TestResourceTracker;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.IndexStorage;
import org.infinispan.configuration.cache.IndexingMode;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.query.model.Game;
import org.infinispan.query.remote.client.ProtobufMetadataManagerConstants;
import org.infinispan.rest.helper.RestServerHelper;
import org.infinispan.test.SingleCacheManagerTest;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.testng.annotations.Test;

@Test(groups = "unit", testName = "rest.search.reindex.LocalRestIndexerRemoveTest")
public class LocalRestIndexerRemoveTest extends SingleCacheManagerTest {

   private static final String CACHE_NAME = "types";

   private static final int ENTRIES = 5_000;

   private RestServerHelper restServer;
   private RestClient restClient;

   @Override
   protected EmbeddedCacheManager createCacheManager() throws Exception {
      EmbeddedCacheManager cacheManager = TestCacheManagerFactory.createCacheManager();

      // Register proto schema
      Cache<String, String> metadataCache = cacheManager.getCache(ProtobufMetadataManagerConstants.PROTOBUF_METADATA_CACHE_NAME);
      metadataCache.putIfAbsent(Game.GameSchema.INSTANCE.getProtoFileName(), Game.GameSchema.INSTANCE.getProtoFile());
      assertFalse(metadataCache.containsKey(ProtobufMetadataManagerConstants.ERRORS_KEY_SUFFIX));

      ConfigurationBuilder config = new ConfigurationBuilder();
      config
            .encoding()
            .mediaType(MediaType.APPLICATION_PROTOSTREAM_TYPE)
            .indexing()
            .enable()
            .indexingMode(IndexingMode.MANUAL)
            .storage(IndexStorage.LOCAL_HEAP)
            .addIndexedEntity("Game");
      cacheManager.createCache(CACHE_NAME, config.build());

      restServer = new RestServerHelper(cacheManager);
      restServer.start(TestResourceTracker.getCurrentTestShortName());
      restClient = RestClient.forConfiguration(new RestClientConfigurationBuilder().addServer()
            .host(restServer.getHost()).port(restServer.getPort())
            .build());

      return cacheManager;
   }

   @Test
   public void test() {
      RestCacheClient cacheClient = restClient.cache(CACHE_NAME);
      List<CompletionStage<RestResponse>> responses = new ArrayList<>(ENTRIES);

      for (int i = 0; i < ENTRIES; i++) {
         Json game = Json.object()
               .set("_type", "Game")
               .set("name", "Game n." + i)
               .set("description", "This is the game #" + i);

         responses.add(cacheClient.put("game-" + i, RestEntity.create(MediaType.APPLICATION_JSON, game.toString())));
      }
      for (CompletionStage<RestResponse> response : responses) {
         assertThat(response).isOk();
      }

      assertThat(cacheClient.reindex()).isOk();
      assertEquals(ENTRIES, count(cacheClient));

      assertThat(cacheClient.clearIndex()).isOk();
      assertEquals(0, count(cacheClient));
   }

   @Override
   protected void teardown() {
      try {
         restClient.close();
      } catch (IOException ex) {
         // ignore it
      } finally {
         try {
            restServer.stop();
         } finally {
            super.teardown();
         }
      }
   }

   private int count(RestCacheClient cacheClient) {
      RestResponse response = join(cacheClient.searchStats());
      Json stat = Json.read(response.getBody());
      Json indexGame = stat.at("index").at("types").at("Game");
      return indexGame.at("count").asInteger();
   }
}
