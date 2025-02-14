package org.infinispan.server.resp.commands;

import io.netty.channel.ChannelHandlerContext;
import org.infinispan.commons.CacheException;
import org.infinispan.server.resp.Resp3Handler;
import org.infinispan.server.resp.RespErrorUtil;
import org.infinispan.server.resp.RespRequestHandler;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

public interface Resp3Command {

   CompletionStage<RespRequestHandler> perform(Resp3Handler handler, ChannelHandlerContext ctx, List<byte[]> arguments);

   default CompletionStage<RespRequestHandler> handleException(Resp3Handler handler, Throwable t) {
      Throwable ex = t;
      if (t instanceof CompletionException) {
         ex = t.getCause();
      }
      if (ex instanceof CacheException) {
         ex = ex.getCause();
      }
      if (ex instanceof ClassCastException) {
         RespErrorUtil.wrongType(handler.allocatorToUse());
         return handler.myStage();
      }

      throw new RuntimeException(t);
   }
}
