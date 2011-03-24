/*
 * JBoss, Home of Professional Open Source
 * Copyright 2009, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.infinispan.query.blackbox;

import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.Query;
import org.infinispan.Cache;
import org.infinispan.config.Configuration;
import org.infinispan.interceptors.base.CommandInterceptor;
import org.infinispan.query.CacheQuery;
import org.infinispan.query.QueryFactory;
import org.infinispan.query.backend.QueryInterceptor;
import org.infinispan.query.test.Person;
import org.infinispan.test.MultipleCacheManagersTest;
import org.infinispan.test.TestingUtil;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;
import org.testng.annotations.Test;

import java.util.List;

import static org.infinispan.config.Configuration.CacheMode.REPL_SYNC;
import static org.infinispan.query.helper.TestQueryHelperFactory.*;

/**
 * @author Navin Surtani
 */
@Test(groups = "functional")
public class ClusteredCacheTest extends MultipleCacheManagersTest {

   private static final Log log = LogFactory.getLog(Person.class);
   
   Cache<String, Person> cache1, cache2;
   Person person1;
   Person person2;
   Person person3;
   Person person4;
   QueryParser queryParser;
   Query luceneQuery;
   CacheQuery cacheQuery;
   final String key1 = "Navin";
   final String key2 = "BigGoat";
   final String key3 = "MiniGoat";

   public ClusteredCacheTest() {
      cleanup = CleanupPhase.AFTER_METHOD;
   }

   protected void enhanceConfig(Configuration c) {
      // meant to be overridden
   }

   @Override
   protected void createCacheManagers() throws Throwable {
      Configuration cacheCfg = getDefaultClusteredConfig(REPL_SYNC);
      cacheCfg.fluent()
         .indexing()
         .indexLocalOnly(false)
         .addProperty("hibernate.search.default.directory_provider", "ram");
      enhanceConfig(cacheCfg);
      List<Cache<String, Person>> caches = createClusteredCaches(2, /*"query-cache",*/ cacheCfg);
      cache1 = caches.get(0);
      cache2 = caches.get(1);
   }

   private void prepareTestData() {
      person1 = new Person();
      person1.setName("Navin Surtani");
      person1.setBlurb("Likes playing WoW");

      person2 = new Person();
      person2.setName("BigGoat");
      person2.setBlurb("Eats grass");

      person3 = new Person();
      person3.setName("MiniGoat");
      person3.setBlurb("Eats cheese");

      //Put the 3 created objects in the cache1.

      cache1.put(key1, person1);
      cache1.put(key2, person2);
      cache1.put(key3, person3);
   }

   public void testSimple() throws ParseException {
      prepareTestData();
      cacheQuery = createCacheQuery(cache2, "blurb", "playing");

      List<Object> found = cacheQuery.list();

      assert found.size() == 1;

      if (found.get(0) == null) {
         log.warn("found.get(0) is null");
         Person p1 = cache2.get(key1);
         if (p1 == null) {
            log.warn("Person p1 is null in sc2 and cannot actually see the data of person1 in sc1");
         } else {
            log.trace("p1 name is  " + p1.getName());

         }
      }

      assert found.get(0).equals(person1);
   }

   private void assertQueryInterceptorPresent(Cache<?, ?> c) {
      CommandInterceptor i = TestingUtil.findInterceptor(c, QueryInterceptor.class);
      assert i != null : "Expected to find a QueryInterceptor, only found " + c.getAdvancedCache().getInterceptorChain();
   }

   public void testModified() throws ParseException {
      prepareTestData();
      assertQueryInterceptorPresent(cache2);

      queryParser = createQueryParser("blurb");
      luceneQuery = queryParser.parse("playing");
      cacheQuery = new QueryFactory(cache2).getQuery(luceneQuery);

      List<Object> found = cacheQuery.list();

      assert found.size() == 1 : "Expected list of size 1, was of size " + found.size();
      assert found.get(0).equals(person1);

      person1.setBlurb("Likes pizza");
      cache1.put("Navin", person1);


      queryParser = createQueryParser("blurb");
      luceneQuery = queryParser.parse("pizza");
      cacheQuery = new QueryFactory(cache2).getQuery(luceneQuery);

      found = cacheQuery.list();

      assert found.size() == 1;
      assert found.get(0).equals(person1);
   }

   public void testAdded() throws ParseException {
      prepareTestData();
      queryParser = createQueryParser("blurb");

      luceneQuery = queryParser.parse("eats");
      cacheQuery = new QueryFactory(cache2).getQuery(luceneQuery);
      List<Object> found = cacheQuery.list();


      assert found.size() == 2 : "Size of list should be 2";
      assert found.contains(person2);
      assert found.contains(person3);
      assert !found.contains(person4) : "This should not contain object person4";

      person4 = new Person();
      person4.setName("Mighty Goat");
      person4.setBlurb("Also eats grass");

      cache1.put("mighty", person4);

      luceneQuery = queryParser.parse("eats");
      cacheQuery = new QueryFactory(cache2).getQuery(luceneQuery);
      found = cacheQuery.list();

      assert found.size() == 3 : "Size of list should be 3";
      assert found.contains(person2);
      assert found.contains(person3);
      assert found.contains(person4) : "This should now contain object person4";
   }

   public void testRemoved() throws ParseException {
      prepareTestData();
      queryParser = createQueryParser("blurb");
      luceneQuery = queryParser.parse("eats");
      cacheQuery = new QueryFactory(cache2).getQuery(luceneQuery);
      List<Object> found = cacheQuery.list();

      assert found.size() == 2;
      assert found.contains(person2);
      assert found.contains(person3) : "This should still contain object person3";

      cache1.remove(key3);

      queryParser = createQueryParser("blurb");
      luceneQuery = queryParser.parse("eats");
      cacheQuery = new QueryFactory(cache2).getQuery(luceneQuery);
      found = cacheQuery.list();
   }

   public void testGetResultSize() throws ParseException {
      prepareTestData();
      queryParser = createQueryParser("blurb");
      luceneQuery = queryParser.parse("playing");
      cacheQuery = new QueryFactory(cache2).getQuery(luceneQuery);
      List<Object> found = cacheQuery.list();

      assert found.size() == 1;
   }

   public void testClear() throws ParseException {
      prepareTestData();
      queryParser = createQueryParser("blurb");
      luceneQuery = queryParser.parse("eats");
      cacheQuery = new QueryFactory(cache1).getQuery(luceneQuery);

      Query[] queries = new Query[2];
      queries[0] = luceneQuery;

      luceneQuery = queryParser.parse("playing");
      queries[1] = luceneQuery;

      luceneQuery = luceneQuery.combine(queries);

      cacheQuery = new QueryFactory(cache1).getQuery(luceneQuery);
      assert cacheQuery.getResultSize() == 3;

      // run the same query on cache 2
      cacheQuery = new QueryFactory(cache2).getQuery(luceneQuery);
      assert cacheQuery.getResultSize() == 3;

      cache1.clear();
      cacheQuery = new QueryFactory(cache1).getQuery(luceneQuery);
      assert cacheQuery.getResultSize() == 0;

      // run the same query on cache 2
      cacheQuery = new QueryFactory(cache2).getQuery(luceneQuery);
      assert cacheQuery.getResultSize() == 0;
   }

}
