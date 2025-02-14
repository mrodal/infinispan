package org.infinispan.server.resp;

import static org.infinispan.server.resp.RespConstants.CRLF;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.function.BiConsumer;

import org.infinispan.server.resp.response.LCSResponse;
import org.infinispan.server.resp.response.SetResponse;

/**
 * Utility class with Consumers
 *
 * @since 15.0
 */
public final class Consumers {

   private Consumers() {

   }

   static final byte[] OK = "+OK\r\n".getBytes(StandardCharsets.US_ASCII);
   static final byte[] MATCHES = "matches".getBytes(StandardCharsets.US_ASCII);

   public static final BiConsumer<Object, ByteBufPool> OK_BICONSUMER = (ignore, alloc) -> alloc.acquire(OK.length)
         .writeBytes(OK);

   public static final BiConsumer<Long, ByteBufPool> LONG_BICONSUMER = Resp3Handler::handleLongResult;

   public static final BiConsumer<Double, ByteBufPool> DOUBLE_BICONSUMER = Resp3Handler::handleDoubleResult;

   public static final BiConsumer<byte[], ByteBufPool> GET_BICONSUMER = (innerValueBytes, alloc) -> {
      if (innerValueBytes != null) {
         ByteBufferUtils.bytesToResult(innerValueBytes, alloc);
      } else {
         ByteBufferUtils.stringToByteBuf("$-1\r\n", alloc);
      }
   };

   public static final BiConsumer<Collection<byte[]>, ByteBufPool> GET_ARRAY_BICONSUMER = (innerValueBytes, alloc) -> {
      if (innerValueBytes != null) {
         ByteBufferUtils.bytesToResult(innerValueBytes, alloc);
      } else {
         ByteBufferUtils.stringToByteBuf("$-1\r\n", alloc);
      }
   };

   public static final BiConsumer<byte[], ByteBufPool> DELETE_BICONSUMER = (prev, alloc) ->
         ByteBufferUtils.stringToByteBuf(":" + (prev == null ? "0" : "1") + CRLF, alloc);

   public static final BiConsumer<SetResponse, ByteBufPool> SET_BICONSUMER = (res, alloc) -> {
      // The set operation has three return options, with a precedence:
      //
      // 1. Previous value or `nil`: when `GET` flag present;
      // 2. `OK`: when set operation succeeded
      // 3. `nil`: when set operation failed, e.g., tried using XX or NX.
      if (res.isReturnValue()) {
         GET_BICONSUMER.accept(res.value(), alloc);
         return;
      }

      if (res.isSuccess()) {
         OK_BICONSUMER.accept(res, alloc);
         return;
      }

      GET_BICONSUMER.accept(null, alloc);
   };
   public static final BiConsumer<LCSResponse, ByteBufPool> LCS_BICONSUMER = (res, alloc) -> {
      // If lcs present, return a bulk_string
      if (res.lcs != null) {
         Resp3Handler.handleBulkResult(res.lcs, alloc);
         return;
      }
      // If idx is null then it's a justLen command, return a long
      if (res.idx == null) {
         Resp3Handler.handleLongResult(Long.valueOf(res.len), alloc);
         return;
      }
      handleIdxArray(res, alloc);
   };

   private static void handleIdxArray(LCSResponse res, ByteBufPool alloc) {
      // return idx. it's a 4 items array
      Resp3Handler.handleArrayPrefix(4, alloc);
      Resp3Handler.handleBulkResult("matches", alloc);
      Resp3Handler.handleArrayPrefix(res.idx.size(), alloc);
      for (var match : res.idx) {
         // 2 positions + optional length
         var size = match.length > 4 ? 3 : 2;
         Resp3Handler.handleArrayPrefix(size, alloc);
         Resp3Handler.handleArrayPrefix(2, alloc);
         Resp3Handler.handleLongResult(match[0], alloc);
         Resp3Handler.handleLongResult(match[1], alloc);
         Resp3Handler.handleArrayPrefix(2, alloc);
         Resp3Handler.handleLongResult(match[2], alloc);
         Resp3Handler.handleLongResult(match[3], alloc);
         if (size == 3) {
            Resp3Handler.handleLongResult(match[4], alloc);
         }
      }
      Resp3Handler.handleBulkResult("len", alloc);
      Resp3Handler.handleLongResult((long) res.len, alloc);
   }
}
