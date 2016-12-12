package org.infinispan.container.offheap;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertTrue;
import static org.testng.AssertJUnit.fail;

import java.io.IOException;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.infinispan.Cache;
import org.infinispan.commons.marshall.Marshaller;
import org.infinispan.commons.marshall.WrappedByteArray;
import org.infinispan.commons.marshall.WrappedBytes;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.container.StorageType;
import org.infinispan.eviction.EvictionType;
import org.infinispan.filter.KeyFilter;
import org.testng.annotations.Test;

/**
 */
@Test(groups = "functional", testName = "container.offheap.OffHeapBoundedSingleNodeTest")
public class OffHeapBoundedSingleNodeTest extends OffHeapSingleNodeTest {

   protected static final int COUNT = 100;

   @Override
   protected void createCacheManagers() throws Throwable {
      ConfigurationBuilder dcc = getDefaultClusteredCacheConfig(CacheMode.LOCAL, true);
      dcc.memory().storageType(StorageType.OFF_HEAP).size(COUNT).evictionType(EvictionType.COUNT);
      // Only start up the 1 cache
      addClusterEnabledCacheManager(dcc);
   }

   public void testMoreWriteThanSize() {
      Cache<String, String> cache = cache(0);

      for (int i = 0; i < COUNT + 5; ++i) {
         cache.put("key" + i, "value" + i);
      }

      assertEquals(COUNT, cache.size());
   }

   public void testMultiThreaded() throws ExecutionException, InterruptedException, TimeoutException {
      Cache<String, String> cache = cache(0);

      AtomicInteger offset = new AtomicInteger();
      AtomicBoolean collision = new AtomicBoolean();
      int threadCount = 5;
      Future[] futures = new Future[threadCount];

      for (int i = 0; i < threadCount; ++i) {
         futures[i] = fork(() -> {
            boolean collide = collision.get();
            // We could have overrides, that is fine
            collision.set(!collide);
            int value = collide ? offset.get() : offset.incrementAndGet();
            for (int j = 0; j < COUNT * 10; ++j) {
               String key = "key" + value + "-" + j;
               cache.put(key, "value" + value + "-" + j);
            }
         });
      }

      for (Future future : futures) {
         future.get(10, TimeUnit.SECONDS);
      }

      int cacheSize = cache.size();
      if (cacheSize > COUNT) {
         log.fatal("Entries were: " + cache.entrySet().stream().map(Object::toString).collect(Collectors.joining(",")));
      }
      assertTrue("Cache size was " + cacheSize, cacheSize <= COUNT);
   }
}
