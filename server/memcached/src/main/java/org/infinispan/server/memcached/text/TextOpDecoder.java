package org.infinispan.server.memcached.text;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static org.infinispan.commons.util.concurrent.CompletableFutures.asCompletionException;
import static org.infinispan.server.core.transport.ExtendedByteBuf.buffer;
import static org.infinispan.server.core.transport.ExtendedByteBuf.wrappedBuffer;
import static org.infinispan.server.memcached.MemcachedStats.CAS_BADVAL;
import static org.infinispan.server.memcached.MemcachedStats.CAS_HITS;
import static org.infinispan.server.memcached.MemcachedStats.CAS_MISSES;
import static org.infinispan.server.memcached.MemcachedStats.DECR_HITS;
import static org.infinispan.server.memcached.MemcachedStats.DECR_MISSES;
import static org.infinispan.server.memcached.MemcachedStats.INCR_HITS;
import static org.infinispan.server.memcached.MemcachedStats.INCR_MISSES;
import static org.infinispan.server.memcached.text.TextConstants.CRLF;
import static org.infinispan.server.memcached.text.TextConstants.CRLFBytes;
import static org.infinispan.server.memcached.text.TextConstants.DELETED;
import static org.infinispan.server.memcached.text.TextConstants.END;
import static org.infinispan.server.memcached.text.TextConstants.END_SIZE;
import static org.infinispan.server.memcached.text.TextConstants.EXISTS;
import static org.infinispan.server.memcached.text.TextConstants.MAX_UNSIGNED_LONG;
import static org.infinispan.server.memcached.text.TextConstants.MIN_UNSIGNED;
import static org.infinispan.server.memcached.text.TextConstants.MN;
import static org.infinispan.server.memcached.text.TextConstants.NOT_FOUND;
import static org.infinispan.server.memcached.text.TextConstants.NOT_STORED;
import static org.infinispan.server.memcached.text.TextConstants.OK;
import static org.infinispan.server.memcached.text.TextConstants.SPACE;
import static org.infinispan.server.memcached.text.TextConstants.STORED;
import static org.infinispan.server.memcached.text.TextConstants.TOUCHED;
import static org.infinispan.server.memcached.text.TextConstants.VALUE;
import static org.infinispan.server.memcached.text.TextConstants.VALUE_SIZE;
import static org.infinispan.server.memcached.text.TextConstants.ZERO;

import java.io.StreamCorruptedException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import javax.security.auth.Subject;

import org.infinispan.commons.CacheException;
import org.infinispan.commons.util.Version;
import org.infinispan.commons.util.concurrent.CompletableFutures;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.container.versioning.NumericVersion;
import org.infinispan.context.Flag;
import org.infinispan.metadata.Metadata;
import org.infinispan.server.memcached.MemcachedMetadata;
import org.infinispan.server.memcached.MemcachedServer;

import io.netty.buffer.ByteBuf;

/**
 * @since 15.0
 **/
public abstract class TextOpDecoder extends TextDecoder {

   protected TextOpDecoder(MemcachedServer server, Subject subject) {
      super(server, subject);
   }

   protected void get(TextHeader header, List<byte[]> keys, boolean withVersions) {
      int numberOfKeys = keys.size();
      if (numberOfKeys > 1) {
         CacheEntry<byte[], byte[]>[] entries = new CacheEntry[numberOfKeys];
         CompletionStage<Void> lastStage = doGetMultipleKeys(keys, entries, 0);
         for (int i = 1; i < numberOfKeys; ++i) {
            final int idx = i;
            lastStage = lastStage.thenCompose(unused -> doGetMultipleKeys(keys, entries, idx));
         }
         send(header, lastStage.thenApply(unused -> createMultiGetResponse(entries)));
      } else {
         byte[] key = keys.get(0);
         send(header, cache.getCacheEntryAsync(key).thenApply(entry -> createGetResponse(key, entry, withVersions)));
      }
   }

   protected void set(TextHeader header, byte[] key, byte[] value, int flags, int expiration, boolean quiet) {
      send(header, cache.withFlags(Flag.IGNORE_RETURN_VALUES)
            .putAsync(key, value, metadata(flags, expiration))
            .thenApply(unused -> createSuccessResponse(TextCommand.set, quiet)));
   }

   protected void delete(TextHeader header, byte[] key, boolean quiet) {
      send(header, cache.removeAsync(key)
            .thenApply(prev -> prev == null ? createNotExistResponse(TextCommand.delete, quiet) : createSuccessResponse(TextCommand.delete, quiet)));
   }

   protected void concat(TextHeader header, byte[] key, byte[] value, int flags, int expiration, boolean quiet, boolean append) {
      send(header, cache.getAsync(key)
            .thenCompose(prev -> {
               if (prev == null) {
                  return completedFuture(quiet ? null : NOT_STORED);
               }
               byte[] concatenated = append ? concat(prev, value) : concat(value, prev);
               return cache.replaceAsync(key, prev, concatenated, metadata(flags, expiration))
                     .thenApply(replaced ->
                           replaced ?
                                 (quiet ? null : STORED) :
                                 (quiet ? null : NOT_STORED));
            }));
   }

   protected void replace(TextHeader header, byte[] key, byte[] value, int flags, int expiration, boolean quiet) {
      send(header, cache.withFlags(Flag.SKIP_LISTENER_NOTIFICATION)
            .getAsync(key)
            .thenCompose(prev -> prev == null ?
                  CompletableFutures.completedNull() :
                  cache.replaceAsync(key, value, metadata(flags, expiration)))
            .thenApply(prev -> prev == null ? createNotExecutedResponse(TextCommand.replace, quiet) : createSuccessResponse(TextCommand.replace, quiet)));
   }

   protected void add(TextHeader header, byte[] key, byte[] value, int flags, int expiration, boolean quiet) {
      send(header, cache.getAsync(key)
            .thenCompose(prev -> prev == null ?
                  cache.putIfAbsentAsync(key, value, metadata(flags, expiration)) :
                  completedFuture(prev))
            .thenApply(prev -> prev == null ? createSuccessResponse(TextCommand.add, quiet) : createNotExecutedResponse(TextCommand.add, quiet)));
   }

   protected void cas(TextHeader header, byte[] key, byte[] value, int flags, int expiration, long cas, boolean quiet) {
      send(header, cache.withFlags(Flag.SKIP_LISTENER_NOTIFICATION)
            .getCacheEntryAsync(key)
            .thenCompose(entry -> {
               if (entry == null) {
                  return completedFuture(createNotExistResponse(TextCommand.cas, quiet));
               }
               NumericVersion streamVersion = new NumericVersion(cas);
               if (!entry.getMetadata().version().equals(streamVersion)) {
                  return completedFuture(createNotExecutedResponse(TextCommand.cas, quiet));
               }
               byte[] prev = entry.getValue();
               return cache.replaceAsync(key, prev, value, metadata(flags, expiration))
                     .thenApply(replaced -> replaced ? createSuccessResponse(TextCommand.cas, quiet) : createNotExecutedResponse(TextCommand.cas, quiet));
            }));
   }

   protected void touch(TextHeader header, byte[] key, int expiration, boolean quiet) {
      send(header, cache.getCacheEntryAsync(key)
            .thenCompose(entry -> {
               if (entry == null) {
                  return completedFuture(createNotExistResponse(TextCommand.touch, quiet));
               }
               return cache.replaceAsync(entry.getKey(), entry.getValue(), touchMetadata(entry, expiration))
                     .thenApply(unused -> createTouchedResponse(quiet));
            }));
   }

   protected void gat(TextHeader header, int expiration, List<byte[]> keys, boolean withVersions) {
      int numberOfKeys = keys.size();
      if (numberOfKeys > 1) {
         CacheEntry<byte[], byte[]>[] entries = new CacheEntry[numberOfKeys];
         CompletionStage<Void> lastStage = doGatMultipleKeys(keys, entries, expiration, 0);
         for (int i = 1; i < numberOfKeys; ++i) {
            final int idx = i;
            lastStage = lastStage.thenCompose(unused -> doGatMultipleKeys(keys, entries, expiration, idx));
         }
         send(header, lastStage.thenApply(unused -> createMultiGetResponse(entries)));
      } else {
         byte[] key = keys.get(0);
         send(header, cache.getCacheEntryAsync(key).thenCompose(entry -> {
            if (entry == null) {
               return completedFuture(END);
            } else {
               return cache.replaceAsync(entry.getKey(), entry.getValue(), touchMetadata(entry, expiration))
                     .thenApply(unused -> createGetResponse(key, entry, withVersions));
            }
         }));
      }
   }


   protected void md(TextHeader header, byte[] key, List<byte[]> args) {
      throw new UnsupportedOperationException();
   }

   protected void ma(TextHeader header, byte[] key, List<byte[]> args) {
      throw new UnsupportedOperationException();
   }

   protected void me(TextHeader header, byte[] key, List<byte[]> args) {
      throw new UnsupportedOperationException();
   }

   protected void mn(TextHeader header) {
      send(header, CompletableFuture.completedFuture(MN));
   }

   protected void ms(TextHeader header, byte[] key, byte[] value, List<byte[]> args) {
      throw new UnsupportedOperationException();
   }

   protected void mg(TextHeader header, byte[] key, List<byte[]> args) {
      throw new UnsupportedOperationException();
   }

   private Object createMultiGetResponse(CacheEntry<byte[], byte[]>[] entries) {
      ByteBuf[] elements = new ByteBuf[entries.length + 1];
      for (int i = 0; i < entries.length; i++) {
         elements[i] = buildGetResponse(entries[i]);
      }
      elements[entries.length] = wrappedBuffer(END);
      return elements;
   }

   private CompletionStage<Void> doGetMultipleKeys(List<byte[]> keys, CacheEntry<byte[], byte[]>[] entries, int i) {
      try {
         return cache.getCacheEntryAsync(keys.get(i)).thenAccept(entry -> entries[i] = entry);
      } catch (Throwable t) {
         return failedFuture(t);
      }
   }

   private CompletionStage<Void> doGatMultipleKeys(List<byte[]> keys, CacheEntry<byte[], byte[]>[] entries, int expiration, int idx) {
      return cache.getCacheEntryAsync(keys.get(idx))
            .thenCompose(entry -> {
               if (entry == null) {
                  return CompletableFutures.completedNull();
               } else {
                  return cache.replaceAsync(entry.getKey(), entry.getValue(), touchMetadata(entry, expiration)).thenApply(unused -> {
                     entries[idx] = entry;
                     return null;
                  });
               }
            });
   }

   protected void flush_all(TextHeader header, List<byte[]> varargs) {
      boolean noreply = false;
      int delay = 0;
      for (byte[] arg : varargs) {
         String s = new String(arg, StandardCharsets.US_ASCII);
         if ("noreply".equals(s)) {
            noreply = true;
         } else {
            delay = Integer.parseUnsignedInt(s);
         }
      }
      boolean quiet = noreply;
      if (delay == 0) {
         send(header, cache.clearAsync().thenApply(unused -> quiet ? null : OK));
         return;
      }
      server.getScheduler().schedule(cache::clear, toMillis(delay), TimeUnit.MILLISECONDS);
      send(header, CompletableFuture.completedFuture(quiet ? null : OK));
   }

   protected void version(TextHeader header) {
      send(header, CompletableFuture.completedFuture("VERSION " + Version.getVersion() + CRLF));
   }

   protected void quit(TextHeader header) {
      channel.close();
   }

   protected void incr(TextHeader header, byte[] key, byte[] delta, boolean quiet, boolean isIncrement) {
      send(header, cache.getAsync(key)
            .thenCompose(prev -> {
               if (prev == null) {
                  if (statsEnabled) {
                     if (isIncrement) {
                        INCR_MISSES.incrementAndGet(statistics);
                     } else {
                        DECR_MISSES.incrementAndGet(statistics);
                     }
                  }
                  return completedFuture(quiet ? null : NOT_FOUND);
               }
               BigInteger prevCounter = new BigInteger(new String(prev));
               BigInteger d = validateDelta(new String(delta, StandardCharsets.US_ASCII));
               BigInteger candidateCounter;
               if (isIncrement) {
                  candidateCounter = prevCounter.add(d);
                  candidateCounter = candidateCounter.compareTo(MAX_UNSIGNED_LONG) > 0 ? MIN_UNSIGNED : candidateCounter;
               } else {
                  candidateCounter = prevCounter.subtract(d);
                  candidateCounter = candidateCounter.compareTo(MIN_UNSIGNED) < 0 ? MIN_UNSIGNED : candidateCounter;
               }
               String counterString = candidateCounter.toString();
               return cache.replaceAsync(key, prev, counterString.getBytes(), metadata(0, 0))
                     .thenApply(replaced -> {
                        if (!replaced) {
                           // If there's a concurrent modification on this key, the spec does not say what to do, so treat it as exceptional
                           throw asCompletionException(new CacheException("Value modified since we retrieved from the cache, old value was " + prevCounter));
                        }
                        if (statsEnabled) {
                           if (isIncrement) {
                              INCR_HITS.incrementAndGet(statistics);
                           } else {
                              DECR_HITS.incrementAndGet(statistics);
                           }
                        }
                        return quiet ? null : counterString + CRLF;
                     });
            }));
   }

   protected void stats(TextHeader header, List<byte[]> names) {
      send(header, server.getBlockingManager().supplyBlocking(() -> {
         Map<String, String> stats = statsMap();
         ByteBuf buf = channel.alloc().buffer();
         if (names.isEmpty()) {
            for (Map.Entry<String, String> stat : stats.entrySet()) {
               stat(buf, stat.getKey(), stat.getValue());
            }
         } else {
            for (byte[] name : names) {
               String stat = new String(name, StandardCharsets.US_ASCII);
               String value = stats.get(stat);
               if (value == null) {
                  buf.clear();
                  buf.writeCharSequence("CLIENT_ERROR\r\n", StandardCharsets.US_ASCII);
                  return buf;
               } else {
                  stat(buf, stat, value);
               }
            }
         }
         buf.writeBytes(END);
         return buf;
      }, "memcached-stats"));
   }

   private static void stat(ByteBuf buf, String name, String value) {
      if (value != null) {
         buf.writeCharSequence("STAT ", StandardCharsets.US_ASCII);
         buf.writeCharSequence(name, StandardCharsets.US_ASCII);
         buf.writeByte(' ');
         buf.writeCharSequence(value, StandardCharsets.US_ASCII);
         buf.writeBytes(CRLFBytes);
      }
   }

   private CompletionStage<Void> doGetMultipleKeys(byte[] key, Map<byte[], CacheEntry<byte[], byte[]>> responses) {
      try {
         return cache.getCacheEntryAsync(key)
               .thenAccept(entry -> {
                  if (entry != null) {
                     responses.put(key, entry);
                  }
               });
      } catch (Throwable t) {
         return failedFuture(t);
      }
   }

   private Object createSuccessResponse(TextCommand cmd, boolean quiet) {
      if (statsEnabled && cmd == TextCommand.cas) {
         CAS_HITS.incrementAndGet(statistics);
      }
      if (quiet) {
         return null;
      }
      return cmd == TextCommand.delete ? DELETED : STORED;
   }

   Object createNotExecutedResponse(TextCommand cmd, boolean quiet) {
      if (statsEnabled && cmd == TextCommand.cas) {
         CAS_BADVAL.incrementAndGet(statistics);
      }
      if (quiet) {
         return null;
      }
      return cmd == TextCommand.cas ? EXISTS : NOT_STORED;
   }

   Object createNotExistResponse(TextCommand cmd, boolean quiet) {
      if (statsEnabled && cmd == TextCommand.cas) {
         CAS_MISSES.incrementAndGet(statistics);
      }
      return quiet ? null : NOT_FOUND;
   }

   private static Object createGetResponse(byte[] k, CacheEntry<byte[], byte[]> entry, boolean requiresVersions) {
      if (entry == null) {
         return END;
      }
      return requiresVersions ? buildSingleGetWithVersionResponse(entry) : buildSingleGetResponse(entry);
   }

   private static Object createTouchedResponse(boolean quiet) {
      return quiet ? null : TOUCHED;
   }

   private static ByteBuf buildSingleGetResponse(CacheEntry<byte[], byte[]> entry) {
      ByteBuf buf = buildGetHeaderBegin(entry, END_SIZE);
      writeGetHeaderData(entry.getValue(), buf);
      return writeGetHeaderEnd(buf);
   }

   private static ByteBuf buildGetResponse(CacheEntry<byte[], byte[]> entry) {
      if (entry == null) {
         return null;
      } else {
         ByteBuf buf = buildGetHeaderBegin(entry, 0);
         return writeGetHeaderData(entry.getValue(), buf);
      }
   }

   private static ByteBuf buildGetHeaderBegin(CacheEntry<byte[], byte[]> entry,
                                              int extraSpace) {
      byte[] key = entry.getKey();
      byte[] data = entry.getValue();
      byte[] dataSize = String.valueOf(data.length).getBytes();

      byte[] flags;
      Metadata metadata = entry.getMetadata();
      if (metadata instanceof MemcachedMetadata) {
         long metaFlags = ((MemcachedMetadata) metadata).flags;
         flags = String.valueOf(metaFlags).getBytes();
      } else {
         flags = ZERO;
      }

      int flagsSize = flags.length;
      ByteBuf buf = buffer(VALUE_SIZE + key.length + data.length + flagsSize + dataSize.length + 6 + extraSpace);
      buf.writeBytes(VALUE);
      buf.writeBytes(key);
      buf.writeByte(SPACE);
      buf.writeBytes(flags);
      buf.writeByte(SPACE);
      buf.writeBytes(dataSize);
      return buf;
   }

   private static ByteBuf buildSingleGetWithVersionResponse(CacheEntry<byte[], byte[]> entry) {
      byte[] v = entry.getValue();
      // TODO: Would be nice for EntryVersion to allow retrieving the version itself...
      byte[] version = String.valueOf(((NumericVersion) entry.getMetadata().version()).getVersion()).getBytes();
      ByteBuf buf = buildGetHeaderBegin(entry, version.length + 1 + END_SIZE);
      buf.writeByte(SPACE); // 1
      buf.writeBytes(version); // version.length
      writeGetHeaderData(v, buf);
      return writeGetHeaderEnd(buf);
   }

   private static ByteBuf writeGetHeaderData(byte[] data, ByteBuf buf) {
      buf.writeBytes(CRLFBytes);
      buf.writeBytes(data);
      buf.writeBytes(CRLFBytes);
      return buf;
   }

   private static ByteBuf writeGetHeaderEnd(ByteBuf buf) {
      buf.writeBytes(END);
      return buf;
   }

   static byte[] concat(byte[] a, byte[] b) {
      byte[] data = new byte[a.length + b.length];
      System.arraycopy(a, 0, data, 0, a.length);
      System.arraycopy(b, 0, data, a.length, b.length);
      return data;
   }

   private BigInteger validateDelta(String delta) {
      BigInteger bigIntDelta = new BigInteger(delta);
      if (bigIntDelta.compareTo(MAX_UNSIGNED_LONG) > 0)
         throw asCompletionException(new StreamCorruptedException("Increment or decrement delta sent (" + delta + ") exceeds unsigned limit ("
               + MAX_UNSIGNED_LONG + ")"));
      else if (bigIntDelta.compareTo(MIN_UNSIGNED) < 0)
         throw asCompletionException(new StreamCorruptedException("Increment or decrement delta cannot be negative: " + delta));
      return bigIntDelta;
   }
}
