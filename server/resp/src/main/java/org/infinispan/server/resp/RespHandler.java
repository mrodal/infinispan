package org.infinispan.server.resp;

import java.util.List;
import java.util.concurrent.CompletionStage;

import org.infinispan.commons.util.Util;
import org.infinispan.server.resp.logging.Log;
import org.infinispan.util.concurrent.CompletionStages;
import org.infinispan.util.logging.LogFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class RespHandler extends ChannelInboundHandlerAdapter {
   protected final static Log log = LogFactory.getLog(RespHandler.class, Log.class);
   protected final static int MINIMUM_BUFFER_SIZE;
   protected RespRequestHandler requestHandler;

   protected ByteBuf outboundBuffer;
   // Variable to resume auto read when channel can be written to again. Some commands may resume themselves after
   // flush and may not want to also resume on writability changes
   protected boolean resumeAutoReadOnWritability;

   static {
      MINIMUM_BUFFER_SIZE = Integer.parseInt(System.getProperty("infinispan.resp.minimum-buffer-size", "4096"));
   }

   public RespHandler(RespRequestHandler requestHandler) {
      this.requestHandler = requestHandler;
   }

   protected ByteBuf allocateBuffer(ChannelHandlerContext ctx, int size) {
      if (outboundBuffer != null) {
         if (outboundBuffer.writableBytes() > size) {
            return outboundBuffer;
         }
         log.tracef("Writing buffer %s as request is larger than remaining", outboundBuffer);
         ctx.write(outboundBuffer, ctx.voidPromise());
      }
      int allocatedSize = Math.max(size, MINIMUM_BUFFER_SIZE);
      outboundBuffer = ctx.alloc().buffer(allocatedSize, allocatedSize);
      return outboundBuffer;
   }

   private void flushBufferIfNeeded(ChannelHandlerContext ctx, boolean runOnEventLoop) {
      if (outboundBuffer != null) {
         log.tracef("Writing and flushing buffer %s", outboundBuffer);
         if (runOnEventLoop) {
            ctx.channel().eventLoop().execute(() -> {
               ctx.writeAndFlush(outboundBuffer, ctx.voidPromise());
               outboundBuffer = null;
            });
         } else {
            ctx.writeAndFlush(outboundBuffer, ctx.voidPromise());
            outboundBuffer = null;
         }
      }
   }

   @Override
   public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
      ctx.channel().attr(RespRequestHandler.BYTE_BUF_POOL_ATTRIBUTE_KEY)
            .set(size -> allocateBuffer(ctx, size));
      super.channelRegistered(ctx);
   }

   @Override
   public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
      super.channelUnregistered(ctx);
      requestHandler.handleChannelDisconnect(ctx);
   }

   @Override
   public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
      // If we disabled auto read in the middle of a read, that means we are waiting on a pending command to complete
      if (ctx.channel().config().isAutoRead()) {
         flushBufferIfNeeded(ctx, false);
      }
      super.channelReadComplete(ctx);
   }

   @Override
   public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
      if (resumeAutoReadOnWritability && ctx.channel().isWritable()) {
         resumeAutoReadOnWritability = false;
         log.tracef("Re-enabling auto read for channel %s as channel is now writeable", ctx.channel());
         ctx.channel().config().setAutoRead(true);
      }
      super.channelWritabilityChanged(ctx);
   }

   @Override
   public void channelRead(ChannelHandlerContext ctx, Object msg) {
      RespDecoder arg = (RespDecoder) msg;
      handleCommandAndArguments(ctx, arg.getCommand(), arg.getArguments());
   }

   /**
    * Handles the actual command request. This entails passing the command to the request handler and if
    * the request is completed the decoder may parse more commands.
    *
    * @param ctx channel context in use for this command
    * @param command the actual command
    * @param arguments the arguments provided to the command. The list should not be retained as it is reused
    */
   protected void handleCommandAndArguments(ChannelHandlerContext ctx, RespCommand command, List<byte[]> arguments) {
      if (log.isTraceEnabled()) {
         log.tracef("Received command: %s with arguments %s for %s", command, Util.toStr(arguments), ctx.channel());
      }

      CompletionStage<RespRequestHandler> stage = requestHandler.handleRequest(ctx, command, arguments);
      if (CompletionStages.isCompletedSuccessfully(stage)) {
         requestHandler = CompletionStages.join(stage);
         if (outboundBuffer != null && outboundBuffer.readableBytes() > ctx.channel().bytesBeforeUnwritable()) {
            log.tracef("Buffer will cause channel %s to be unwriteable - forcing flush", ctx.channel());
            // Note the flush is done later after this task completes, since we don't want to resume reading yet
            flushBufferIfNeeded(ctx, true);
            ctx.channel().config().setAutoRead(false);
            resumeAutoReadOnWritability = true;
            return;
         }
         return;
      }
      log.tracef("Disabling auto read for channel %s until previous command is complete", ctx.channel());
      // Disable reading any more from socket - until command is complete
      ctx.channel().config().setAutoRead(false);
      stage.whenComplete((handler, t) -> {
         assert ctx.channel().eventLoop().inEventLoop();
         if (t != null) {
            exceptionCaught(ctx, t);
            return;
         }
         // Instate the new handler if there was no exception
         requestHandler = handler;
         flushBufferIfNeeded(ctx, false);
         log.tracef("Re-enabling auto read for channel %s as previous command is complete", ctx.channel());
         ctx.channel().config().setAutoRead(true);
      });
   }

   @Override
   public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      log.unexpectedException(cause);
      ByteBufferUtils.stringToByteBuf("-ERR Server Error Encountered: " + cause.getMessage() + "\\r\\n", requestHandler.allocatorToUse);
      flushBufferIfNeeded(ctx, false);
      ctx.close();
   }
}
