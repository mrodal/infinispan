package org.infinispan.multimap.impl;

import org.infinispan.AdvancedCache;
import org.infinispan.Cache;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.container.impl.InternalEntryFactory;
import org.infinispan.functional.FunctionalMap;
import org.infinispan.functional.impl.FunctionalMapImpl;
import org.infinispan.functional.impl.ReadWriteMapImpl;
import org.infinispan.multimap.impl.function.IndexFunction;
import org.infinispan.multimap.impl.function.OfferFunction;
import org.infinispan.multimap.impl.function.PollFunction;
import org.infinispan.multimap.impl.function.SubListFunction;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static java.util.Objects.requireNonNull;

/**
 * Multimap with Linked List Implementation methods
 *
 * @author  Katia Aresti
 * @since 15.0
 */
public class EmbeddedMultimapListCache<K, V> {
   public static final String ERR_KEY_CAN_T_BE_NULL = "key can't be null";
   public static final String ERR_VALUE_CAN_T_BE_NULL = "value can't be null";
   protected final FunctionalMap.ReadWriteMap<K, ListBucket<V>> readWriteMap;
   protected final AdvancedCache<K, ListBucket<V>> cache;
   protected final InternalEntryFactory entryFactory;

   public EmbeddedMultimapListCache(Cache<K, ListBucket<V>> cache) {
      this.cache = cache.getAdvancedCache();
      FunctionalMapImpl<K, ListBucket<V>> functionalMap = FunctionalMapImpl.create(this.cache);
      this.readWriteMap = ReadWriteMapImpl.create(functionalMap);
      this.entryFactory = this.cache.getComponentRegistry().getInternalEntryFactory().running();
   }

   /**
    * Get the value as a collection
    *
    * @param key, the name of the list
    * @return the collection with values if such exist, or an empty collection if the key is not present
    */
   public CompletionStage<Collection<V>> get(K key) {
      requireNonNull(key, ERR_KEY_CAN_T_BE_NULL);
      return getEntry(key).thenApply(entry -> {
         if (entry != null) {
            return entry.getValue();
         }
         return List.of();
      });
   }

   private CompletionStage<CacheEntry<K, Collection<V>>> getEntry(K key) {
      requireNonNull(key, ERR_KEY_CAN_T_BE_NULL);
      return cache.getAdvancedCache().getCacheEntryAsync(key)
            .thenApply(entry -> {
               if (entry == null)
                  return null;

               return entryFactory.create(entry.getKey(),(entry.getValue().toDeque()) , entry.getMetadata());
            });
   }

   /**
    * Inserts the specified element at the front of the specified list.
    *
    * @param key, the name of the list
    * @param value, the element to be inserted
    * @return {@link CompletionStage} containing a {@link Void}
    */
   public CompletionStage<Void> offerFirst(K key, V value) {
      requireNonNull(key, ERR_KEY_CAN_T_BE_NULL);
      requireNonNull(value, ERR_VALUE_CAN_T_BE_NULL);
      return readWriteMap.eval(key, new OfferFunction<>(value, true));
   }

   /**
    * Inserts the specified element at the end of the specified list.
    *
    * @param key, the name of the list
    * @param value, the element to be inserted
    * @return {@link CompletionStage} containing a {@link Void}
    */
   public CompletionStage<Void> offerLast(K key, V value) {
      requireNonNull(key, ERR_KEY_CAN_T_BE_NULL);
      requireNonNull(value, ERR_VALUE_CAN_T_BE_NULL);
      return readWriteMap.eval(key, new OfferFunction<>(value, false));
   }

   /**
    * Returns true if the list associated with the key exists.
    *
    * @param key, the name of the list
    * @return {@link CompletionStage} containing a {@link Boolean}
    */
   public CompletionStage<Boolean> containsKey(K key) {
      requireNonNull(key, ERR_KEY_CAN_T_BE_NULL);
      return cache.containsKeyAsync(key);
   }

   /**
    * Returns the number of elements in the list.
    * If the entry does not exit, returns size 0.
    *
    * @param key, the name of the list
    * @return {@link CompletionStage} containing a {@link Long}
    */
   public CompletionStage<Long> size(K key) {
      requireNonNull(key, ERR_KEY_CAN_T_BE_NULL);
      return cache.getAsync(key).thenApply(b -> b == null ? 0 : (long) b.size());
   }

   /**
    * Returns the element at the given index. Index is zero-based.
    * 0 means fist element. Negative index counts index from the tail. For example -1 is the last element.
    *
    * @param key, the name of the list
    * @param index, the position of the element.
    * @return The existing value. Returns null if the key does not exist or the index is out of bounds.
    */
   public CompletionStage<V> index(K key, long index) {
      requireNonNull(key, "key can't be null");
      return readWriteMap.eval(key, new IndexFunction<>(index));
   }

   /**
    * Retrieves a sub list of elements, starting from 0.
    * Negative indexes point positions counting from the tail of the list.
    * For example 0 is the first element, 1 is the second element, -1 the last element.
    *
    * @param key, the name of the list
    * @param from, the starting offset
    * @param to, the final offset
    * @return The subList. Returns null if the key does not exist.
    */
   public CompletionStage<Collection<V>> subList(K key, long from, long to) {
      requireNonNull(key, "key can't be null");
      return readWriteMap.eval(key, new SubListFunction<>(from, to));
   }

   /**
    * Removes the given count of elements from the head of the list.
    *
    * @param key, the name of the list
    * @param count, the number of elements. Must be positive.
    * @return {@link CompletionStage} containing a {@link Collection<V>} of values removed,
    * or null if the key does not exit
    */
   public CompletionStage<Collection<V>> pollFirst(K key, long count) {
      return poll(key, count, true);
   }

   /**
    * Removes the given count of elements from the tail of the list.
    *
    * @param key, the name of the list
    * @param count, the number of elements. Must be positive.
    * @return {@link CompletionStage} containing a {@link Collection<V>} of values removed,
    * or null if the key does not exit
    */
   public CompletionStage<Collection<V>> pollLast(K key, long count) {
      return poll(key, count, false);
   }

   private CompletableFuture<Collection<V>> poll(K key, long count, boolean first) {
      requireNonNull(key, "key can't be null");
      requirePositive(count, "count can't be negative");
      return readWriteMap.eval(key, new PollFunction<>(first, count));
   }

   private static void requirePositive(long count, String message) {
      if (count < 0) {
         throw new IllegalArgumentException(message);
      }
   }

   public CompletionStage<Void> set(K key, V value, int index) {
      throw new UnsupportedOperationException();
   }
}
