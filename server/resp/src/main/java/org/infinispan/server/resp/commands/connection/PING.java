package org.infinispan.server.resp.commands.connection;

import io.netty.channel.ChannelHandlerContext;
import org.infinispan.server.resp.ByteBufferUtils;
import org.infinispan.server.resp.commands.PubSubResp3Command;
import org.infinispan.server.resp.commands.Resp3Command;
import org.infinispan.server.resp.Resp3Handler;
import org.infinispan.server.resp.RespCommand;
import org.infinispan.server.resp.RespRequestHandler;
import org.infinispan.server.resp.SubscriberHandler;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * @link https://redis.io/commands/ping/
 * @since 14.0
 */
public class PING extends RespCommand implements Resp3Command, PubSubResp3Command {
   public static final String NAME = "PING";

   public PING() {
      super(NAME, -2, 0, 0, 0);
   }

   @Override
   public CompletionStage<RespRequestHandler> perform(Resp3Handler handler, ChannelHandlerContext ctx,
                                                                List<byte[]> arguments) {
      if (arguments.size() == 0) {
         ByteBufferUtils.stringToByteBuf("$4\r\nPONG\r\n", handler.allocatorToUse());
         return handler.myStage();
      }
      return handler.delegate(ctx, this, arguments);
   }

   @Override
   public CompletionStage<RespRequestHandler> perform(SubscriberHandler handler, ChannelHandlerContext ctx,
                                                                List<byte[]> arguments) {
      handler.resp3Handler().handleRequest(ctx, this, arguments);
      return handler.myStage();
   }
}
