package org.infinispan.multimap.impl;

import org.infinispan.commons.marshall.ProtoStreamTypeIds;
import org.infinispan.commons.util.Util;
import org.infinispan.marshall.protostream.impl.MarshallableUserObject;
import org.infinispan.protostream.annotations.ProtoFactory;
import org.infinispan.protostream.annotations.ProtoField;
import org.infinispan.protostream.annotations.ProtoTypeId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Bucket used to store ListMultimap values.
 *
 * @author Katia Aresti
 * @since 15.0
 */
@ProtoTypeId(ProtoStreamTypeIds.MULTIMAP_LIST_BUCKET)
public class ListBucket<V> {

   final Deque<V> values;

   public ListBucket() {
      this.values = new ArrayDeque<>(0);
   }

   public ListBucket(V value) {
      Deque<V> deque = new ArrayDeque<>(1);
      deque.add(value);
      this.values = deque;
   }

   private ListBucket(Deque<V> values) {
      this.values = values;
   }

   public static <V> ListBucket<V> create(V value) {
      return new ListBucket<>(value);
   }

   @ProtoFactory
   ListBucket(Collection<MarshallableUserObject<V>> wrappedValues) {
      this((Deque<V>) wrappedValues.stream().map(MarshallableUserObject::get)
            .collect(Collectors.toCollection(ArrayDeque::new)));
   }

   @ProtoField(number = 1, collectionImplementation = ArrayList.class)
   Collection<MarshallableUserObject<V>> getWrappedValues() {
      return this.values.stream().map(MarshallableUserObject::new).collect(Collectors.toCollection(ArrayDeque::new));
   }

   public boolean contains(V value) {
      for (V v : values) {
         if (Objects.deepEquals(v, value)) {
            return Boolean.TRUE;
         }
      }
      return Boolean.FALSE;
   }

   public boolean isEmpty() {
      return values.isEmpty();
   }

   public int size() {
      return values.size();
   }

   /**
    * @return a defensive copy of the {@link #values} collection.
    */
   public Deque<V> toDeque() {
      return new ArrayDeque<>(values);
   }

   @Override
   public String toString() {
      return "ListBucket{values=" + Util.toStr(values) + '}';
   }

   public ListBucket<V> offer(V value, boolean first) {
      Deque<V> newBucket = new ArrayDeque<>(values);
      if (first) {
         newBucket.offerFirst(value);
      } else {
         newBucket.offerLast(value);
      }

      return new ListBucket<>(newBucket);
   }

   public Collection<V> sublist(long from, long to) {
      // from and to are + but from is bigger
      // example: from 2 > to 1 -> empty result
      // from and to are - and to is smaller
      // example: from -1 > to -2 -> empty result
      if ((from > 0 && to > 0 && from > to) || (from < 0 && to < 0 && from > to)) {
         return Collections.emptyList();
      }

      // index request
      if (from == to) {
         V element = index(from);
         if (element != null) {
            return Collections.singletonList(element);
         }
         return Collections.emptyList();
      }

      List<V> result = new ArrayList<>();
      long fromIte = from < 0 ? values.size() + from : from;
      long toIte = to < 0 ? values.size() + to : to;

      Iterator<V> ite = values.iterator();
      int offset = 0;
      while (ite.hasNext()) {
         V element = ite.next();
         if (offset < fromIte){
            offset++;
            continue;
         }
         if (offset > toIte){
            break;
         }

         result.add(element);
         offset++;
      }
      return result;
   }

   public class ListBucketResult {
      private final Collection<V> result;
      private final ListBucket<V> bucketValue;
      public ListBucketResult(Collection<V> result, ListBucket<V> bucketValue) {
         this.result = result;
         this.bucketValue = bucketValue;
      }

      public ListBucket<V> bucketValue() {
         return bucketValue;
      }

      public Collection<V> opResult() {
         return result;
      }
   }
   public ListBucketResult poll(boolean first, long count) {
      List<V> polledValues = new ArrayList<>();
      if (count >= values.size()) {
         if (first) {
            polledValues.addAll(values);
         } else {
            Iterator<V> ite = values.descendingIterator();
            while(ite.hasNext()) {
               polledValues.add(ite.next());
            }
         }
         return new ListBucketResult(polledValues, new ListBucket<>());
      }

      Deque<V> valuesCopy = new ArrayDeque<>(values);
      for (int i = 0 ; i < count; i++) {
         if (first) {
            polledValues.add(valuesCopy.pollFirst());
         } else {
            polledValues.add(valuesCopy.pollLast());
         }
      }
      return new ListBucketResult(polledValues, new ListBucket<>(valuesCopy));
   }

   public V index(long index) {
      if (index == 0) {
         return values.element();
      }
      if (index == values.size() - 1) {
         return values.getLast();
      }
      V result = null;
      if (index > 0) {
         if (index >= values.size()) {
            return null;
         }

         Iterator<V> iterator = values.iterator();
         int currentIndex = 0;
         while (currentIndex++ <= index) {
            result = iterator.next();
         }
      } else {
         long currentIndex = Math.abs(index);
         if (currentIndex > values.size()) {
            return null;
         }

         Iterator<V> iterator = values.descendingIterator();
         while (currentIndex-- > 0) {
            result = iterator.next();
         }
      }

      return result;
   }

}
