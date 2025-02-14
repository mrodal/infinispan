package org.infinispan.server.resp.commands.list;

import io.netty.channel.ChannelHandlerContext;
import org.infinispan.multimap.impl.EmbeddedMultimapListCache;
import org.infinispan.server.resp.Consumers;
import org.infinispan.server.resp.Resp3Handler;
import org.infinispan.server.resp.RespCommand;
import org.infinispan.server.resp.RespErrorUtil;
import org.infinispan.server.resp.RespRequestHandler;
import org.infinispan.server.resp.commands.Resp3Command;
import org.infinispan.util.concurrent.CompletionStages;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * @link https://redis.io/commands/llen/
 *
 * Returns the length of the list stored at key. If key does not exist,
 * it is interpreted as an empty list and 0 is returned.
 * When the value at key is not a list, an error is returned.
 *
 * @since 15.0
 */
public class LLEN extends RespCommand implements Resp3Command {
   public LLEN() {
      super(2, 1, 1, 1);
   }

   @Override
   public CompletionStage<RespRequestHandler> perform(Resp3Handler handler,
                                                      ChannelHandlerContext ctx,
                                                      List<byte[]> arguments) {

      if (arguments.size() < 1) {
         // ERROR
         RespErrorUtil.wrongArgumentNumber(this, handler.allocatorToUse());
         return handler.myStage();
      }

      return indexAndReturn(handler, ctx, arguments);
   }

   protected CompletionStage<RespRequestHandler> indexAndReturn(Resp3Handler handler,
                                                              ChannelHandlerContext ctx,
                                                              List<byte[]> arguments) {
      byte[] key = arguments.get(0);
      EmbeddedMultimapListCache<byte[], byte[]> listMultimap = handler.getListMultimap();

      return CompletionStages.handleAndCompose(listMultimap.size(key) ,(size, t) -> {
         if (t != null) {
           return handleException(handler, t);
         }

         return handler.stageToReturn(CompletableFuture.completedFuture(size), ctx, Consumers.LONG_BICONSUMER);
      });
   }
}
