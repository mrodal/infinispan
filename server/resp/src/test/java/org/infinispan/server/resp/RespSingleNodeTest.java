package org.infinispan.server.resp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.infinispan.server.resp.test.RespTestingUtil.OK;
import static org.infinispan.server.resp.test.RespTestingUtil.PONG;
import static org.testng.AssertJUnit.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.commons.test.Exceptions;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.server.resp.test.CommonRespTests;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.ArrayOutput;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;

/**
 * Base class for single node tests.
 *
 * @author William Burns
 * @since 14.0
 */
@Test(groups = "functional", testName = "server.resp.RespSingleNodeTest")
public class RespSingleNodeTest extends SingleNodeRespBaseTest {

   @Test
   public void testSetMultipleOptions() throws Exception {
      RedisCommands<String, String> redis = redisConnection.sync();

      // Should return (nil), failed since value does not exist
      SetArgs args = SetArgs.Builder.xx();
      assertThat(redis.set("key", "value", args)).isNull();

      // Should return OK, because value does not exist.
      args = SetArgs.Builder.nx();
      assertThat(redis.set("key", "value", args)).isEqualTo(OK);
      assertThat(redis.get("key")).isEqualTo("value");

      // Should return (nil), because value exists.
      assertThat(redis.set("key", "value3", args)).isNull();
      assertThat(redis.get("key")).isEqualTo("value");

      // Should return OK, value exists
      args = SetArgs.Builder.xx();
      assertThat(redis.set("key", "value2", args)).isEqualTo(OK);
      assertThat(redis.get("key")).isEqualTo("value2");

      // Should insert with TTL. This one returns the value.
      args = SetArgs.Builder.ex(60);
      assertThat(redis.setGet("key", "value3", args)).isEqualTo("value2");

      CacheEntry<Object, Object> entry = cache.getAdvancedCache()
            .withMediaType(MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_OCTET_STREAM)
            .getCacheEntry("key".getBytes(StandardCharsets.UTF_8));
      assertThat(new String((byte[]) entry.getValue())).isEqualTo("value3");
      assertThat(entry.getLifespan()).isEqualTo(60_000);

      // Making sure we won't go that fast.
      Thread.sleep(50);

      // We insert while keeping the TTL.
      args = SetArgs.Builder.keepttl();
      assertThat(redis.set("key", "value4", args)).isEqualTo(OK);
      entry = cache.getAdvancedCache()
            .withMediaType(MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_OCTET_STREAM)
            .getCacheEntry("key".getBytes(StandardCharsets.UTF_8));
      assertThat(new String((byte[]) entry.getValue())).isEqualTo("value4");
      assertThat(entry.getLifespan()).isLessThan(60_000);

      // Conditional operation keeping TTL.
      args = SetArgs.Builder.keepttl().xx();

      assertThat(redis.set("key", "value5", args)).isEqualTo(OK);
      entry = cache.getAdvancedCache()
            .withMediaType(MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_OCTET_STREAM)
            .getCacheEntry("key".getBytes(StandardCharsets.UTF_8));
      assertThat(new String((byte[]) entry.getValue())).isEqualTo("value5");
      assertThat(entry.getLifespan()).isLessThan(60_000);

      // Key exist and keeping TTL, but conditional failing. Should return nil.
      args = SetArgs.Builder.keepttl().nx();
      String res = redis.set("key", "value5", args);
      assertThat(res).isNull();

      // No NPE when keeping TTL, key not exists, and conditional succeed.
      args = SetArgs.Builder.keepttl().nx();
      assertThat(redis.set("randomKey", "value", args)).isEqualTo(OK);

      // No NPE when keeping TTL and key doesn't exist.
      args = SetArgs.Builder.keepttl();
      assertThat(redis.set("otherKey", "value", args)).isEqualTo(OK);

      redis.del("key", "randomKey", "otherKey");
   }

   public void testConditionalSetOperationWithReturn() {
      RedisCommands<String, String> redis = redisConnection.sync();
      String key = UUID.randomUUID().toString();

      // Should return (nil), failed since value does not exist.
      SetArgs args = SetArgs.Builder.xx();
      assertThat(redis.setGet(key, "something", args)).isNull();
      assertThat(redis.get(key)).isNull();
      // Should return (nil), because value does not exist, but operation succeeded.
      args = SetArgs.Builder.nx();
      String res = redis.setGet(key, "value", args);
      assertThat(res).isNull();
      assertThat(redis.get(key)).isEqualTo("value");

      // Should return the previous because value exists but operation failed.
      assertThat(redis.setGet(key, "value2", args)).isEqualTo("value");
      assertThat(redis.get(key)).isEqualTo("value");

      // Should return previous value but succeeded.
      args = SetArgs.Builder.xx();
      assertThat(redis.setGet(key, "value2", args)).isEqualTo("value");
      assertThat(redis.get(key)).isEqualTo("value2");
   }

   public void testSetMGet() {
      RedisCommands<String, String> redis = redisConnection.sync();
      redis.set("k1", "v1");
      redis.set("k3", "v3");
      redis.set("k4", "v4");

      List<KeyValue<String, String>> expected = new ArrayList<>(4);
      expected.add(KeyValue.just("k1", "v1"));
      expected.add(KeyValue.empty("k2"));
      expected.add(KeyValue.just("k3", "v3"));
      expected.add(KeyValue.just("k4", "v4"));

      List<KeyValue<String, String>> results = redis.mget("k1", "k2", "k3", "k4");
      assertThat(results).containsExactlyElementsOf(expected);
   }

   public void testSetEmptyStringMGet() {
      RedisCommands<String, String> redis = redisConnection.sync();
      redis.set("k1", "");
      redis.set("k3", "value2");

      assertThat(redis.get("k1")).isEmpty();

      List<KeyValue<String, String>> expected = new ArrayList<>(3);
      expected.add(KeyValue.just("k1", ""));
      expected.add(KeyValue.empty("k2"));
      expected.add(KeyValue.just("k3", "value2"));

      List<KeyValue<String, String>> results = redis.mget("k1", "k2", "k3");
      assertThat(results).containsExactlyElementsOf(expected);
   }

   public void testMSetMGet() {
      RedisCommands<String, String> redis = redisConnection.sync();
      Map<String, String> values = new HashMap<>();
      values.put("k1", "v1");
      values.put("k3", "v3");
      values.put("k4", "v4");
      redis.mset(values);

      List<KeyValue<String, String>> expected = new ArrayList<>(4);
      expected.add(KeyValue.just("k1", "v1"));
      expected.add(KeyValue.empty("k2"));
      expected.add(KeyValue.just("k3", "v3"));
      expected.add(KeyValue.just("k4", "v4"));

      List<KeyValue<String, String>> results = redis.mget("k1", "k2", "k3", "k4");
      assertThat(results).containsExactlyElementsOf(expected);
   }

   public void testSetGetDelete() {
      RedisCommands<String, String> redis = redisConnection.sync();
      redis.set("k1", "v1");
      String v = redis.get("k1");
      assertThat(v).isEqualTo("v1");

      redis.del("k1");

      assertThat(redis.get("k1")).isNull();
      assertThat(redis.get("something")).isNull();
   }

   public void testSetGetBigValue() {
      RedisCommands<String, String> redis = redisConnection.sync();
      StringBuilder sb = new StringBuilder();
      String charsToChoose = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890";

      for (int i = 0; i < 10_000; ++i) {
         sb.append(charsToChoose.charAt(ThreadLocalRandom.current().nextInt(charsToChoose.length())));
      }
      String actualString = sb.toString();
      redis.set("k1", actualString);
      assertThat(redis.get("k1")).isEqualTo(actualString);
   }

   public void testPingNoArg() {
      RedisCommands<String, String> redis = redisConnection.sync();
      assertThat(redis.ping()).isEqualTo(PONG);
   }

   public void testEcho() {
      RedisCommands<String, String> redis = redisConnection.sync();
      String stringToSend = "HI THERE!";
      assertThat(redis.echo(stringToSend)).isEqualTo(stringToSend);
   }

   private BlockingQueue<String> addPubSubListener(RedisPubSubCommands<String, String> connection) {
      BlockingQueue<String> handOffQueue = new LinkedBlockingQueue<>();

      connection.getStatefulConnection().addListener(new RedisPubSubAdapter<String, String>() {
         @Override
         public void message(String channel, String message) {
            log.tracef("Received message on channel %s of %s", channel, message);
            handOffQueue.add("message-" + channel + "-" + message);
         }

         @Override
         public void subscribed(String channel, long count) {
            log.tracef("Subscribed to %s with %s", channel, count);
            handOffQueue.add("subscribed-" + channel + "-" + count);
         }

         @Override
         public void unsubscribed(String channel, long count) {
            log.tracef("Unsubscribed to %s with %s", channel, count);
            handOffQueue.add("unsubscribed-" + channel + "-" + count);
         }
      });

      return handOffQueue;
   }

   @DataProvider(name = "booleans")
   Object[][] booleans() {
      // Reset disabled for now as the client isn't sending a reset command to the
      // server
      return new Object[][] { { true }, { false } };
   }

   @Test(dataProvider = "booleans")
   public void testPubSubUnsubscribe(boolean quit) throws InterruptedException {
      int listenersBefore = cache.getAdvancedCache().getListeners().size();

      RedisPubSubCommands<String, String> connection = createPubSubConnection();
      BlockingQueue<String> handOffQueue = addPubSubListener(connection);

      // Subscribe to some channels
      connection.subscribe("channel2", "test");
      String value = handOffQueue.poll(10, TimeUnit.SECONDS);
      assertThat(value).isEqualTo("subscribed-channel2-0");
      value = handOffQueue.poll(10, TimeUnit.SECONDS);
      assertThat(value).isEqualTo("subscribed-test-0");

      // 2 listeners, one for each sub above
      assertThat(cache.getAdvancedCache().getListeners()).hasSize(listenersBefore + 2);
      // Unsubscribe to all channels
      if (quit) {
         // Originally wanted to use reset or quit, but they don't do what we expect from
         // lettuce
         connection.getStatefulConnection().close();

         // Have to use eventually as they are removed asynchronously
         eventually(() -> cache.getAdvancedCache().getListeners().size() == listenersBefore);
         assertThat(cache.getAdvancedCache().getListeners()).hasSize(listenersBefore);

         assertThat(handOffQueue).isEmpty();
      } else {
         connection.unsubscribe();

         // Unsubscribed channels can be in different orders
         for (int i = 0; i < 2; ++i) {
            value = handOffQueue.poll(10, TimeUnit.SECONDS);
            assertThat(value).withFailMessage("Didn't receive any notifications").isNotNull();
            if (!value.equals("unsubscribed-channel2-0") && !value.equals("unsubscribed-test-0")) {
               fail("Notification doesn't match expected, was: " + value);
            }
         }

         assertThat(cache.getAdvancedCache().getListeners()).hasSize(listenersBefore);
         assertThat(connection.ping()).isEqualTo(PONG);
      }
   }

   public void testPubSub() throws InterruptedException {
      RedisPubSubCommands<String, String> connection = createPubSubConnection();
      BlockingQueue<String> handOffQueue = addPubSubListener(connection);
      // Subscribe to some channels
      connection.subscribe("channel2", "test");
      String value = handOffQueue.poll(10, TimeUnit.SECONDS);
      assertThat(value).isEqualTo("subscribed-channel2-0");
      value = handOffQueue.poll(10, TimeUnit.SECONDS);
      assertThat(value).isEqualTo("subscribed-test-0");

      // Send a message to confirm it is properly listening
      RedisCommands<String, String> redis = redisConnection.sync();
      redis.publish("channel2", "boomshakayaka");
      value = handOffQueue.poll(10, TimeUnit.SECONDS);
      assertThat(value).isEqualTo("message-channel2-boomshakayaka");

      connection.subscribe("channel");
      value = handOffQueue.poll(10, TimeUnit.SECONDS);
      assertThat(value).isEqualTo("subscribed-channel-0");

      connection.unsubscribe("channel2");
      connection.unsubscribe("doesn't-exist");
      connection.unsubscribe("channel", "test");

      for (String channel : new String[] { "channel2", "doesn't-exist", "channel", "test" }) {
         value = handOffQueue.poll(10, TimeUnit.SECONDS);
         assertThat(value).isEqualTo("unsubscribed-" + channel + "-0");
      }
   }

   public void testCommand() {
      RedisCommands<String, String> redis = redisConnection.sync();

      List<Object> commands = redis.command();
      assertThat(commands.size()).isEqualTo(RespCommand.all().size());
   }

   public void testAuth() {
      RedisCommands<String, String> redis = redisConnection.sync();
      Exceptions.expectException(RedisCommandExecutionException.class, ".*but no password is set",
            () -> redis.auth("user", "pass"));
   }

   public void testNotImplementedCommand() {
      RedisCommands<String, String> redis = redisConnection.sync();
      Exceptions.expectException(RedisCommandExecutionException.class, "ERR unknown command",
            () -> redis.sadd("not-supported", "should error"));
   }

   protected RedisPubSubCommands<String, String> createPubSubConnection() {
      return client.connectPubSub().sync();
   }

   public void testPipeline() throws ExecutionException, InterruptedException, TimeoutException {
      CommonRespTests.testPipeline(redisConnection);
   }

   @Test
   public void testUpperLowercase() {
      RedisCommands<String, String> redis = redisConnection.sync();
      CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8).addValue("Hello");
      String response = redis.dispatch(new SimpleCommand("ECHO"), new StatusOutput<>(StringCodec.UTF8), args);
      assertEquals("Hello", response);
      response = redis.dispatch(new SimpleCommand("echo"), new StatusOutput<>(StringCodec.UTF8), args);
      assertEquals("Hello", response);
   }

   @Test
   public void testInfo() {
      RedisCommands<String, String> redis = redisConnection.sync();
      String info = redis.info();
      assertThat(info).contains("# Server");
      assertThat(info).contains("# Client");
      assertThat(info).contains("# Modules");
      assertThat(info).contains("# Persistence");
      assertThat(info).contains("# Keyspace");
      info = redis.info("server");
      assertThat(info).contains("# Server");
      assertThat(info).doesNotContain("# Client");
   }

   @Test
   public void testModule() {
      RedisCommands<String, String> redis = redisConnection.sync();
      CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8).addValue("LIST");
      List<Object> response = redis.dispatch(new SimpleCommand("MODULE"), new ArrayOutput<>(StringCodec.UTF8), args);
      assertEquals(0, response.size());
   }

   @Test
   public void testDbSize() {
      RedisCommands<String, String> redis = redisConnection.sync();
      Long size = redis.dbsize();
      assertThat(size).isEqualTo(cache.size());
      redis.set("dbsize-key", "dbsize-value");
      assertThat(redis.dbsize()).isEqualTo(size + 1);
   }

   public static class SimpleCommand implements ProtocolKeyword {
      private final String name;

      public SimpleCommand(String name) {
         this.name = name;
      }

      @Override
      public byte[] getBytes() {
         return name.getBytes(StandardCharsets.UTF_8);
      }

      @Override
      public String name() {
         return name;
      }
   }

   @Test
   public void testExists() {
      // Test on 10 entries
      RedisCommands<String, String> redis = redisConnection.sync();
      IntStream.range(0, 10).map(i -> 2 * i).forEach(i -> redis.set("key" + i, "value " + i));
      // Check 20 keys, 10 exist
      String[] keys = IntStream.range(0, 21).boxed().map(v -> "key" + v).toArray(String[]::new);
      assertThat(redis.exists(keys)).isEqualTo(10);
   }

   @Test
   public void testExistsMisc() {
      RedisCommands<String, String> redis = redisConnection.sync();
      redis.set("key1", "value1");
      redis.set("key2", "value2");
      redis.set("key3", "value3");
      assertThat(redis.exists("key1")).isEqualTo(1);
      assertThat(redis.exists("key1", "key2", "key3")).isEqualTo(3);
      assertThat(redis.exists("nonexistent-key")).isEqualTo(0);
      assertThat(redis.exists("key1", "nonexistent-key", "key2")).isEqualTo(2);
      assertThat(redis.exists("key1", "nonexistent-key", "key1")).isEqualTo(2);
      assertThat(redis.exists("nonexistent-key", "nonexistent-key", "key1")).isEqualTo(1);
   }
}
