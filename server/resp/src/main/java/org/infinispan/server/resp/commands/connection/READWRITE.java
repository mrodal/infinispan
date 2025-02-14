package org.infinispan.server.resp.commands.connection;

import io.netty.channel.ChannelHandlerContext;
import org.infinispan.server.resp.commands.Resp3Command;
import org.infinispan.server.resp.Consumers;
import org.infinispan.server.resp.Resp3Handler;
import org.infinispan.server.resp.RespCommand;
import org.infinispan.server.resp.RespRequestHandler;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * @link https://redis.io/commands/readwrite/
 * @since 14.0
 */
public class READWRITE extends RespCommand implements Resp3Command {

   public READWRITE() {
      super(1, 0, 0, 0);
   }

   @Override
   public CompletionStage<RespRequestHandler> perform(Resp3Handler handler,
                                                      ChannelHandlerContext ctx,
                                                      List<byte[]> arguments) {
      // We are always in read write allowing read from backups
      Consumers.OK_BICONSUMER.accept(null, handler.allocatorToUse());
      return handler.myStage();
   }
}
